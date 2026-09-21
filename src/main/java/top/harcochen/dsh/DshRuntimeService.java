package top.harcochen.dsh;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.EnvironmentUtil;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.jetbrains.annotations.NotNull;
import top.harcochen.dsh.remote.DshRemoteAuth;
import top.harcochen.dsh.remote.DshRemoteUnaryClient;

/**
 * Owns the local dsh web process and the connection URL used by the Remote layer.
 *
 * <p>The lifecycle mirrors {@code DshRuntime} in dsh-ide: a configured server is reused, an
 * existing localhost port is probed before spawning, and the child process is terminated when the
 * project closes. All blocking process and HTTP work runs off the Swing event dispatch thread.
 * Protocol work (unary RPC, streams, authentication state) lives in {@code
 * top.harcochen.dsh.remote}; this service only manages the process, the launch/base URLs, the
 * credential injection, the shared start lock, and the reachability probe.
 */
public final class DshRuntimeService implements Disposable {
    private static final Logger LOG = Logger.getInstance(DshRuntimeService.class);
    private static final int DEFAULT_PORT = 3080;
    private static final String MANAGED_LAUNCHER = "__dsh_managed_runtime__";

    /** The npm package the Runtime ships as; see {@link #pinRuntimeVersion}. */
    private static final String RUNTIME_PACKAGE = "@deepseek-ai/dsh";

    private final Project project;
    private final ExecutorService executor;
    private final CopyOnWriteArrayList<Consumer<RuntimeStatus>> listeners =
            new CopyOnWriteArrayList<>();
    private final StringBuilder output = new StringBuilder();
    private final Object lifecycleLock = new Object();

    /** Machine-wide start lock, shared with the VS Code extension. */
    private final DshRuntimeLock runtimeLock = new DshRuntimeLock();

    /** Authority-bound session cookie exchange for the RC Remote API. */
    private final DshRemoteAuth auth = new DshRemoteAuth(this::getUrl, this::getLaunchUrl);

    private volatile Process process;
    private volatile String baseUrl;
    private volatile String launchUrl;
    private volatile RuntimeStatus status = new RuntimeStatus(RuntimeState.STOPPED, null, null);
    private volatile CompletableFuture<String> startFuture;
    private volatile boolean stopping;
    private volatile boolean stopRequested;
    private volatile String launchedVersion;
    private volatile Process probingProcess;
    private volatile Process managedHelper;
    private volatile com.google.gson.JsonObject recoveryStatus;

    public DshRuntimeService(@NotNull Project project) {
        this.project = project;
        this.executor =
                Executors.newCachedThreadPool(
                        runnable -> {
                            Thread thread = new Thread(runnable, "dsh-intellij-runtime");
                            thread.setDaemon(true);
                            return thread;
                        });
    }

    public static DshRuntimeService getInstance(@NotNull Project project) {
        return project.getService(DshRuntimeService.class);
    }

    public RuntimeStatus getStatus() {
        return status;
    }

    public String getUrl() {
        return baseUrl;
    }

    /** URL suitable for opening in a browser; unlike API calls it retains the launch token. */
    public String getBrowserUrl() {
        return launchUrl != null ? launchUrl : baseUrl;
    }

    /** The launch URL backing the one-time token exchange; for diagnostics only. */
    String getLaunchUrl() {
        return launchUrl;
    }

    /** The shared authority-bound authentication state. */
    public DshRemoteAuth auth() {
        return auth;
    }

    /** Reachability probe for the RC Remote API (no side effects; authenticates first). */
    boolean isRemoteHealthy(String url) {
        if (DshRuntimeEndpoint.normalizeUrl(url) == null) return false;
        DshRemoteUnaryClient probe =
                new DshRemoteUnaryClient(
                        () -> url,
                        () -> DshSettingsState.getInstance(project).requestTimeoutMs,
                        auth,
                        null);
        return probe.probe();
    }

    public String getLogs() {
        synchronized (output) {
            return output.toString();
        }
    }

    public void addStatusListener(Consumer<RuntimeStatus> listener) {
        listeners.add(listener);
    }

    public void removeStatusListener(Consumer<RuntimeStatus> listener) {
        listeners.remove(listener);
    }

    public CompletableFuture<String> startAsync() {
        synchronized (lifecycleLock) {
            if (startFuture != null && !startFuture.isDone()) return startFuture;
            if (status.state == RuntimeState.RUNNING && baseUrl != null) {
                return CompletableFuture.completedFuture(baseUrl);
            }
            if (managedHelper != null && managedHelper.isAlive())
                return CompletableFuture.failedFuture(
                        new IllegalStateException(
                                "DSH Runtime is recovering; cancel recovery or wait for completion."));
            stopping = false;
            stopRequested = false;
            setStatus(
                    new RuntimeStatus(
                            RuntimeState.STARTING,
                            baseUrl,
                            DshBundle.message("dsh.runtime.starting")));
            startFuture =
                    CompletableFuture.supplyAsync(this::startBlocking, executor)
                            .whenComplete(
                                    (url, error) -> {
                                        synchronized (lifecycleLock) {
                                            startFuture = null;
                                        }
                                        if (error != null && !stopRequested) {
                                            Throwable cause =
                                                    error instanceof CompletionException
                                                                    && error.getCause() != null
                                                            ? error.getCause()
                                                            : error;
                                            setStatus(
                                                    new RuntimeStatus(
                                                            RuntimeState.ERROR,
                                                            baseUrl,
                                                            cause.getMessage()));
                                        }
                                    });
            return startFuture;
        }
    }

    public CompletableFuture<Void> stopAsync() {
        synchronized (lifecycleLock) {
            stopping = true;
            stopRequested = true;
            CompletableFuture<String> startup = startFuture;
            return CompletableFuture.runAsync(this::stopBlocking, executor)
                    .thenCompose(
                            ignored ->
                                    startup == null
                                            ? CompletableFuture.completedFuture(null)
                                            : startup.handle((url, error) -> null))
                    .thenRunAsync(this::stopBlocking, executor);
        }
    }

    public CompletableFuture<String> restartAsync() {
        return stopAsync().thenCompose(ignored -> startAsync());
    }

    /** Called by the settings page after applying a new command or URL. */
    public void settingsChanged() {
        // A changed server URL should not silently discard an active process.
        // The next explicit Start/Restart uses the new settings; the panel is
        // refreshed so its status and error message remain truthful.
        if (status.state == RuntimeState.RUNNING && baseUrl != null) {
            setStatus(
                    new RuntimeStatus(
                            RuntimeState.RUNNING,
                            baseUrl,
                            DshBundle.message("dsh.runtime.settings.changed")));
        }
    }

    private String startBlocking() {
        DshSettingsState settings = DshSettingsState.getInstance(project);
        DshRuntimeEndpoint configuredEndpoint = DshRuntimeEndpoint.parse(settings.serverUrl, false);
        String configuredUrl =
                configuredEndpoint == null
                        ? DshRuntimeEndpoint.normalizeUrl(settings.serverUrl)
                        : configuredEndpoint.baseUrl;
        if (configuredUrl != null) {
            setRuntimeEndpoint(
                    configuredEndpoint == null
                            ? DshRuntimeEndpoint.ofBase(configuredUrl)
                            : configuredEndpoint);
            if (isRemoteHealthy(configuredUrl)) {
                setStatus(
                        new RuntimeStatus(
                                RuntimeState.RUNNING,
                                configuredUrl,
                                DshBundle.message("dsh.runtime.connected.configured")));
                return configuredUrl;
            }
        }

        int configuredPort = settings.serverPort;
        DshRuntimeEndpoint existing = findExistingRuntime(configuredPort);
        if (existing != null) {
            setRuntimeEndpoint(existing);
            setStatus(
                    new RuntimeStatus(
                            RuntimeState.RUNNING,
                            existing.baseUrl,
                            DshBundle.message("dsh.runtime.connected.existing")));
            return existing.baseUrl;
        }

        // The start lock is shared with the VS Code extension, so at most one
        // editor on this machine spawns a Runtime. Losing the race is the
        // normal path when both start together: wait for the winner's URL
        // rather than racing it to a second Runtime.
        boolean acquired = runtimeLock.acquire();
        if (!acquired && recoverOrphan()) acquired = runtimeLock.acquire();
        if (!acquired) {
            DshRuntimeEndpoint peer = awaitPeerRuntime(configuredPort, settings.startupTimeoutMs);
            if (peer != null) {
                setRuntimeEndpoint(peer);
                setStatus(
                        new RuntimeStatus(
                                RuntimeState.RUNNING,
                                peer.baseUrl,
                                DshBundle.message("dsh.runtime.connected.peer")));
                return peer.baseUrl;
            }
            throw new IllegalStateException(DshBundle.message("dsh.runtime.peer.start.failed"));
        }

        Process child;
        DshRuntimeEndpoint detected;
        try {
            child = launch(settings);
            process = child;
            streamOutput(child);
            try {
                detected = waitForServer(child, settings);
            } catch (RuntimeException | Error error) {
                try {
                    stopBlocking();
                } catch (RuntimeException cleanup) {
                    error.addSuppressed(cleanup);
                }
                throw error;
            }
        } catch (RuntimeException | Error error) {
            // Never leave the lock behind on a failed start: a peer would wait
            // out its whole startup timeout for a Runtime that will never come.
            runtimeLock.release();
            throw error;
        }
        // Publish only after the server answers, so the advertised URL is
        // always one a peer can attach to immediately.
        runtimeLock.publishUrl(detected.baseUrl, detected.launchUrl);
        setRuntimeEndpoint(detected);
        setStatus(
                new RuntimeStatus(
                        RuntimeState.RUNNING,
                        detected.baseUrl,
                        DshBundle.message("dsh.runtime.running")));
        return detected.baseUrl;
    }

    /**
     * Only the shared lock can advertise a compatible, editor-owned Runtime for automatic reuse.
     */
    private DshRuntimeEndpoint findExistingRuntime(int configuredPort) {
        // Probing has to publish each candidate first: the probe authenticates, and the
        // launch-token exchange reads the endpoint fields. So the endpoint is dirty for the whole
        // probe, and a run that finds nothing must put back what it found on entry -- otherwise
        // baseUrl is left pointing at the last dead candidate and every later RPC goes there.
        String entryBase = baseUrl;
        String entryLaunch = launchUrl;
        boolean settled = false;
        try {
            DshRuntimeEndpoint advertised = runtimeLock.readAdvertisedEndpoint();
            if (advertised != null) {
                setRuntimeEndpoint(advertised);
                if (isRemoteHealthy(advertised.baseUrl)) {
                    settled = true;
                    return advertised;
                }
            }
            // Unversioned ports cannot establish compatibility or cross-editor ownership.
            return null;

        } finally {
            if (!settled) restoreRuntimeEndpoint(entryBase, entryLaunch);
        }
    }

    /** Wait for the editor that won the start lock to advertise its Runtime. */
    private DshRuntimeEndpoint awaitPeerRuntime(int configuredPort, int startupTimeoutMs) {
        long deadline =
                System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(Math.max(1_000, startupTimeoutMs));
        while (System.nanoTime() < deadline && !stopping) {
            DshRuntimeEndpoint endpoint = findExistingRuntime(configuredPort);
            if (endpoint != null) return endpoint;
            try {
                Thread.sleep(250);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private Process launch(DshSettingsState settings) {
        List<List<String>> candidates = launcherCandidates(settings);
        String overlay = null;
        if (settings.enableCompaction) {
            String patch = writeCompactionPatch();
            overlay = patch;
            if (patch != null) {
                for (List<String> candidate : candidates) {
                    insertWebLauncherPatch(candidate, patch);
                }
                appendLog("[dsh] compaction command enabled with patch: " + patch);
            }
        }
        Map<String, String> environment = executionEnvironment();
        if (settings.npmRegistry != null && !settings.npmRegistry.isBlank())
            environment.putIfAbsent("npm_config_registry", settings.npmRegistry.trim());
        Throwable last = null;
        for (List<String> command : candidates) {
            try {
                List<String> candidateCommand = command;
                if (MANAGED_LAUNCHER.equals(command.get(0))) {
                    Path managed =
                            DshManagedRuntime.ensure(settings.runtimeVersion, this::appendLog);
                    if (managed == null) {
                        throw new IOException(DshBundle.message("dsh.runtime.managed.unavailable"));
                    }
                    candidateCommand = new ArrayList<>(command);
                    candidateCommand.set(0, managed.toString());
                }
                List<String> resolvedCommand = new ArrayList<>(candidateCommand);
                resolvedCommand.set(0, resolveExecutable(candidateCommand.get(0), environment));
                if (isWindows()) resolvedCommand = prepareCommand(resolvedCommand, environment);
                String version = probeVersion(candidateCommand, environment);
                if (!DshRuntimeVersion.compatible(version)
                        && !isNodePackageManager(candidateCommand.get(0)))
                    version = offerLocalUpgrade(candidateCommand, version, settings);
                if (!DshRuntimeVersion.compatible(version)) {
                    throw new IOException(
                            "DSH Runtime "
                                    + (version == null ? "version unknown" : version)
                                    + " is incompatible; requires >= "
                                    + DshRuntimeVersion.MINIMUM);
                }
                launchedVersion = version;
                resolvedCommand = new ArrayList<>(resolvedCommand);
                for (int index = 1; index < resolvedCommand.size(); index++) {
                    if (resolvedCommand.get(index).equals(RUNTIME_PACKAGE)
                            || resolvedCommand.get(index).startsWith(RUNTIME_PACKAGE + "@"))
                        resolvedCommand.set(index, RUNTIME_PACKAGE + "@" + version);
                }
                if (stopping) throw new IOException("DSH startup cancelled");
                appendLog("$ " + redactCommand(resolvedCommand));
                ProcessBuilder builder = new ProcessBuilder(resolvedCommand);
                String basePath = project.getBasePath();
                if (basePath != null) builder.directory(Path.of(basePath).toFile());
                builder.redirectErrorStream(true);
                builder.environment().putAll(environment);
                if (settings.npmRegistry != null && !settings.npmRegistry.isBlank()) {
                    if (isNodePackageManager(candidateCommand.get(0))) {
                        builder.environment()
                                .putIfAbsent("npm_config_registry", settings.npmRegistry.trim());
                    }
                }
                String apiKeyName = settings.apiKeyEnv == null ? "" : settings.apiKeyEnv.trim();
                if (!apiKeyName.matches("[A-Za-z_][A-Za-z0-9_]*")) apiKeyName = "";
                String apiKey = apiKeyName.isBlank() ? null : DshCredentials.read(project);
                if (apiKey != null && !apiKey.isBlank()) {
                    builder.environment().put(apiKeyName, apiKey);
                }
                List<String> original = List.copyOf(resolvedCommand);
                builder.command(
                        prepareCommand(
                                List.of(
                                        platformCommand("node"),
                                        DshRuntimeHelper.executable().toString()),
                                environment));
                Process child = builder.start();
                managedHelper = child;
                DshRuntimeHelper.send(
                        child,
                        DshRuntimeHelper.configuration(
                                original,
                                basePath == null ? System.getProperty("user.dir") : basePath,
                                launchedVersion,
                                settings,
                                overlay));
                runtimeLock.publishProcess(
                        child, launchedVersion, isNodePackageManager(candidateCommand.get(0)));
                appendLog(DshBundle.message("dsh.runtime.log.started.pid", child.pid()));
                return child;
            } catch (IOException error) {
                last = error;
                appendLog(
                        DshBundle.message(
                                "dsh.runtime.log.unable.to.start",
                                command.get(0),
                                error.getMessage()));
            }
        }
        String message = DshBundle.message("dsh.runtime.launch.failed");
        if (last != null && last.getMessage() != null) message += "\n" + last.getMessage();
        throw new IllegalStateException(message, last);
    }

    private List<List<String>> launcherCandidates(DshSettingsState settings) {
        String command =
                settings.command == null || settings.command.isBlank()
                        ? "auto"
                        : settings.command.trim();
        String version =
                settings.runtimeVersion == null || settings.runtimeVersion.isBlank()
                        ? DshRuntimeVersion.DEFAULT
                        : settings.runtimeVersion.trim();
        List<String> args = pinRuntimeVersion(splitArguments(settings.commandArgs), version);
        List<List<String>> result = new ArrayList<>();
        boolean auto = "auto".equals(command);
        if ("managed".equalsIgnoreCase(command)) {
            Path managed = DshManagedRuntime.ensure(version, this::appendLog);
            if (managed == null) {
                throw new IllegalStateException(
                        DshBundle.message("dsh.runtime.managed.unavailable"));
            }
            result.add(
                    launchCommand(
                            managed.toString(),
                            args.isEmpty() ? List.of("web", "--no-open") : args,
                            settings.serverPort));
            return result;
        }
        if (auto) {
            int packageIndex = -1;
            for (int i = 0; i < args.size(); i++)
                if (args.get(i).startsWith(RUNTIME_PACKAGE)) packageIndex = i;
            if (packageIndex < 0)
                result.add(
                        launchCommand(
                                platformCommand("dsh"),
                                args.isEmpty() ? List.of("web", "--no-open") : args,
                                settings.serverPort));
            if (settings.installWhenMissing) {
                List<String> app =
                        packageIndex < 0 ? args : args.subList(packageIndex + 1, args.size());
                if (app.isEmpty()) app = List.of("web", "--no-open");
                String spec =
                        packageIndex < 0 ? RUNTIME_PACKAGE + "@" + version : args.get(packageIndex);
                List<String> pnpm = new ArrayList<>(List.of("dlx", spec));
                pnpm.addAll(app);
                List<String> npx = new ArrayList<>(List.of("--yes", spec));
                npx.addAll(app);
                result.add(launchCommand(platformCommand("pnpm"), pnpm, settings.serverPort));
                result.add(launchCommand(platformCommand("npx"), npx, settings.serverPort));
            }
            if (settings.installWhenMissing && settings.useManagedRuntime) {
                List<String> app =
                        packageIndex < 0
                                ? (args.isEmpty() ? List.of("web", "--no-open") : args)
                                : new ArrayList<>(args.subList(packageIndex + 1, args.size()));
                if (app.isEmpty()) app = List.of("web", "--no-open");
                result.add(launchCommand(MANAGED_LAUNCHER, app, settings.serverPort));
            }
        } else {
            if (args.isEmpty()) {
                args =
                        isNodePackageManager(command)
                                ? new ArrayList<>(
                                        List.of(
                                                executableBaseName(command).equals("pnpm")
                                                        ? "dlx"
                                                        : "--yes",
                                                RUNTIME_PACKAGE + "@" + version,
                                                "web",
                                                "--no-open"))
                                : List.of("web", "--no-open");
            }
            result.add(launchCommand(command, args, settings.serverPort));
            if (settings.installWhenMissing && executableBaseName(command).equals("dsh")) {
                for (String manager : List.of("pnpm", "npx")) {
                    List<String> alternative =
                            new ArrayList<>(
                                    List.of(
                                            manager.equals("pnpm") ? "dlx" : "--yes",
                                            RUNTIME_PACKAGE + "@" + version));
                    alternative.addAll(args);
                    result.add(
                            launchCommand(
                                    platformCommand(manager), alternative, settings.serverPort));
                }
            }
            if (settings.installWhenMissing
                    && executableBaseName(command).equals("pnpm")
                    && args.get(0).equals("dlx")) {
                List<String> alternative = new ArrayList<>(List.of("--yes"));
                alternative.addAll(args.subList(1, args.size()));
                result.add(launchCommand(platformCommand("npx"), alternative, settings.serverPort));
            }
        }
        return result;
    }

    private static List<String> launchCommand(String command, List<String> args, int port) {
        List<String> result = new ArrayList<>();
        result.add(command);
        result.addAll(args);
        if (!hasPort(result)) {
            result.add("--port");
            result.add(Integer.toString(Math.max(0, port)));
        }
        return result;
    }

    private com.google.gson.JsonObject helperOperation(com.google.gson.JsonObject config)
            throws IOException {
        Map<String, String> environment = executionEnvironment();
        ProcessBuilder builder =
                new ProcessBuilder(
                                prepareCommand(
                                        List.of(
                                                platformCommand("node"),
                                                DshRuntimeHelper.executable().toString()),
                                        environment))
                        .redirectErrorStream(true);
        builder.environment().putAll(environment);
        String cwd =
                project.getBasePath() == null
                        ? System.getProperty("user.dir")
                        : project.getBasePath();
        config.addProperty("storage", DshRuntimeHelper.storage(cwd).toString());
        Process helper = builder.start();
        probingProcess = helper;
        try {
            DshRuntimeHelper.send(helper, config);
            com.google.gson.JsonObject result = new com.google.gson.JsonObject();
            try (BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    helper.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("DSH_INTELLIJ_HELPER ")) {
                        appendLog(line);
                        continue;
                    }
                    var message =
                            com.google.gson.JsonParser.parseString(
                                            line.substring("DSH_INTELLIJ_HELPER ".length()))
                                    .getAsJsonObject();
                    var value = message.getAsJsonObject("value");
                    String event = DshJson.string(message, "event");
                    if ("prompt".equals(event)) {
                        List<String> options = new ArrayList<>();
                        for (var option : value.getAsJsonArray("options"))
                            options.add(option.getAsString());
                        java.util.concurrent.atomic.AtomicInteger selected =
                                new java.util.concurrent.atomic.AtomicInteger(-1);
                        com.intellij.openapi.application.ApplicationManager.getApplication()
                                .invokeAndWait(
                                        () -> {
                                            if (!stopping && !project.isDisposed())
                                                selected.set(
                                                        com.intellij.openapi.ui.Messages.showDialog(
                                                                project,
                                                                DshJson.stringOr(
                                                                                value, "message",
                                                                                "")
                                                                        + "\n\n"
                                                                        + DshJson.stringOr(
                                                                                value, "detail",
                                                                                ""),
                                                                "DSH Runtime",
                                                                options.toArray(new String[0]),
                                                                0,
                                                                com.intellij.openapi.ui.Messages
                                                                        .getWarningIcon()));
                                        });
                        com.google.gson.JsonObject reply = new com.google.gson.JsonObject();
                        reply.addProperty("type", "prompt-result");
                        reply.add("id", value.get("id"));
                        if (selected.get() >= 0 && selected.get() < options.size())
                            reply.addProperty("value", options.get(selected.get()));
                        DshRuntimeHelper.send(helper, reply);
                    } else if ("clipboard".equals(event)) {
                        com.intellij.openapi.ide.CopyPasteManager.getInstance()
                                .setContents(
                                        new java.awt.datatransfer.StringSelection(
                                                DshJson.stringOr(value, "text", "")));
                    } else if ("error".equals(event))
                        throw new IOException(DshJson.string(value, "message"));
                    else if (event != null && event.endsWith("-result")) result = value;
                }
            }
            if (!helper.waitFor(5, TimeUnit.SECONDS) || helper.exitValue() != 0)
                throw new IOException("DSH Runtime operation failed");
            return result;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException(error);
        } finally {
            if (helper.isAlive()) helper.destroy();
            if (probingProcess == helper) probingProcess = null;
        }
    }

    private String offerLocalUpgrade(List<String> command, String actual, DshSettingsState settings)
            throws IOException {
        com.google.gson.JsonObject config = new com.google.gson.JsonObject();
        config.addProperty("operation", "upgrade");
        Map<String, String> environment = executionEnvironment();
        config.addProperty("command", resolveExecutable(command.get(0), environment));
        config.addProperty("npm", resolveExecutable(platformCommand("npm"), environment));
        config.addProperty("actual", actual);
        config.addProperty("target", DshRuntimeVersion.DEFAULT);
        config.addProperty("registry", settings.npmRegistry);
        return DshJson.string(helperOperation(config), "version");
    }

    private boolean recoverOrphan() {
        for (Path path :
                List.of(
                        runtimeLock.getPath(),
                        runtimeLock.getPath().resolveSibling("dsh-vscode-runtime.lock"))) {
            if (!java.nio.file.Files.exists(path)) continue;
            com.google.gson.JsonObject config = new com.google.gson.JsonObject();
            config.addProperty("operation", "orphan");
            config.addProperty("lockPath", path.toString());
            config.addProperty("sharedLockPath", runtimeLock.getPath().toString());
            try {
                if (DshJson.bool(helperOperation(config), "stopped", false)) return true;
            } catch (IOException error) {
                appendLog(error.getMessage());
            }
        }
        return false;
    }

    private String probeVersion(List<String> command, Map<String, String> environment)
            throws IOException {
        int packageIndex = -1;
        for (int i = 1; i < command.size(); i++)
            if (command.get(i).startsWith(RUNTIME_PACKAGE)) packageIndex = i;
        List<String> probe =
                new ArrayList<>(command.subList(0, packageIndex < 0 ? 1 : packageIndex + 1));
        probe.add("--version");
        ProcessBuilder builder =
                new ProcessBuilder(prepareCommand(probe, environment)).redirectErrorStream(true);
        builder.environment().putAll(environment);
        Process child = builder.start();
        probingProcess = child;
        CompletableFuture<String> output =
                CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return new String(
                                        child.getInputStream().readNBytes(65536),
                                        StandardCharsets.UTF_8);
                            } catch (IOException error) {
                                return "";
                            }
                        },
                        executor);
        try {
            if (!child.waitFor(90, TimeUnit.SECONDS)) {
                child.descendants().forEach(ProcessHandle::destroyForcibly);
                child.destroyForcibly();
                throw new IOException("DSH version probe timed out");
            }
            if (stopping) throw new IOException("DSH startup cancelled");
            return child.exitValue() == 0 ? DshRuntimeVersion.fromOutput(output.join()) : null;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            child.destroyForcibly();
            throw new IOException(error);
        } finally {
            if (probingProcess == child) probingProcess = null;
        }
    }

    public com.google.gson.JsonObject recoveryStatus() {
        return recoveryStatus == null ? null : recoveryStatus.deepCopy();
    }

    private boolean observeHelper(Process child, String line) {
        String prefix = "DSH_INTELLIJ_HELPER ";
        if (!line.startsWith(prefix)) return false;
        if (child != managedHelper || stopping) return true;
        try {
            var message =
                    com.google.gson.JsonParser.parseString(line.substring(prefix.length()))
                            .getAsJsonObject();
            var value = message.getAsJsonObject("value");
            switch (DshJson.stringOr(message, "event", "")) {
                case "process" ->
                        runtimeLock.publishHelperProcess(
                                DshJson.longValue(value.get("pid"), -1),
                                DshJson.string(value, "version"),
                                value.has("group")
                                        ? DshJson.longValue(value.get("group"), -1)
                                        : null);
                case "recovery" -> {
                    recoveryStatus = value.deepCopy();
                    String phase = DshJson.string(value, "phase");
                    boolean terminal =
                            "recovered".equals(phase)
                                    || "unrecoverable".equals(phase)
                                    || "cancelled".equals(phase);
                    setStatus(
                            new RuntimeStatus(
                                    terminal
                                            ? "recovered".equals(phase)
                                                    ? RuntimeState.RUNNING
                                                    : RuntimeState.ERROR
                                            : RuntimeState.RECOVERING,
                                    baseUrl,
                                    DshJson.string(value, "summary")));
                }
                case "ready" -> {
                    DshRuntimeEndpoint endpoint =
                            DshRuntimeEndpoint.parse(DshJson.string(value, "launchUrl"), true);
                    if (endpoint != null) {
                        setRuntimeEndpoint(endpoint);
                        runtimeLock.publishUrl(endpoint.baseUrl, endpoint.launchUrl);
                        setStatus(
                                new RuntimeStatus(
                                        RuntimeState.RUNNING,
                                        baseUrl,
                                        DshBundle.message("dsh.runtime.running")));
                    }
                }
                case "error", "action-error" ->
                        setStatus(
                                new RuntimeStatus(
                                        RuntimeState.ERROR,
                                        baseUrl,
                                        DshJson.string(value, "message")));
                default -> {}
            }
        } catch (RuntimeException malformed) {
            appendLog("Invalid recovery helper status");
        }
        return true;
    }

    public void cancelRecovery() {
        Process helper = managedHelper;
        if (helper == null || !helper.isAlive()) return;
        com.google.gson.JsonObject message = new com.google.gson.JsonObject();
        message.addProperty("type", "cancel");
        try {
            DshRuntimeHelper.send(helper, message);
        } catch (IOException error) {
            appendLog(error.getMessage());
        }
    }

    public CompletableFuture<String> recoveryAction(String operation) {
        if ("restore".equals(operation))
            return stopAsync().thenCompose(ignored -> recoveryOperation(operation));
        return recoveryOperation(operation);
    }

    private CompletableFuture<String> recoveryOperation(String operation) {
        return CompletableFuture.supplyAsync(
                () -> {
                    if (!List.of("restore", "export").contains(operation))
                        throw new IllegalArgumentException("Unknown recovery operation");
                    try {
                        ProcessBuilder builder =
                                new ProcessBuilder(
                                                prepareCommand(
                                                        List.of(
                                                                platformCommand("node"),
                                                                DshRuntimeHelper.executable()
                                                                        .toString()),
                                                        executionEnvironment()))
                                        .redirectErrorStream(true);
                        builder.environment().putAll(executionEnvironment());
                        Process helper = builder.start();
                        com.google.gson.JsonObject config = new com.google.gson.JsonObject();
                        config.addProperty("operation", operation);
                        config.addProperty(
                                "storage",
                                DshRuntimeHelper.storage(
                                                project.getBasePath() == null
                                                        ? System.getProperty("user.dir")
                                                        : project.getBasePath())
                                        .toString());
                        DshRuntimeHelper.send(helper, config);
                        CompletableFuture<String> response =
                                CompletableFuture.supplyAsync(
                                        () -> {
                                            try {
                                                return new String(
                                                        helper.getInputStream().readNBytes(65536),
                                                        StandardCharsets.UTF_8);
                                            } catch (IOException error) {
                                                throw new CompletionException(error);
                                            }
                                        },
                                        executor);
                        if (!helper.waitFor(30, TimeUnit.SECONDS)) {
                            helper.destroy();
                            throw new IOException("Recovery action timed out");
                        }
                        String result = response.join();
                        if (helper.exitValue() != 0)
                            throw new IOException(redactRuntimeOutput(result));
                        if ("restore".equals(operation)) recoveryStatus = null;
                        return result;
                    } catch (IOException | InterruptedException error) {
                        throw new CompletionException(error);
                    }
                },
                executor);
    }

    private void streamOutput(Process child) {
        executor.execute(
                () -> {
                    try (BufferedReader reader =
                            new BufferedReader(
                                    new InputStreamReader(
                                            child.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (observeHelper(child, line)) continue;
                            observeRuntimeEndpoint(line);
                            appendLog(line);
                            LOG.info("[dsh] " + redactRuntimeOutput(line));
                        }
                    } catch (IOException error) {
                        if (!stopping)
                            appendLog(
                                    DshBundle.message(
                                            "dsh.runtime.log.output.ended", error.getMessage()));
                    }
                });
        executor.execute(
                () -> {
                    try {
                        int exitCode = child.waitFor();
                        if (!stopping
                                && process == child
                                && (status.state == RuntimeState.RUNNING
                                        || status.state == RuntimeState.RECOVERING)) {
                            setStatus(
                                    new RuntimeStatus(
                                            RuntimeState.ERROR,
                                            null,
                                            DshBundle.message(
                                                    "dsh.runtime.exited.with.code", exitCode)));
                        }
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    }
                });
    }

    private DshRuntimeEndpoint waitForServer(Process child, DshSettingsState settings) {
        long deadline =
                System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(
                                Math.max(
                                        settings.recoveryEnabled ? 240_000 : 1_000,
                                        settings.startupTimeoutMs));
        while (System.nanoTime() < deadline) {
            if (stopping)
                throw new IllegalStateException(DshBundle.message("dsh.runtime.startup.cancelled"));
            if (!child.isAlive()) {
                String tail = tailLogs(40);
                throw new IllegalStateException(
                        DshBundle.message("dsh.runtime.exited.before.ready")
                                + (tail.isBlank() ? "" : "\n\n" + tail));
            }
            DshRuntimeEndpoint endpoint = currentEndpoint();
            // The helper authenticates and verifies its own launch before emitting ready.
            // A pre-existing listener on the configured port is not evidence of this launch.
            if (status.state == RuntimeState.RUNNING && endpoint != null) return endpoint;
            try {
                Thread.sleep(250);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        DshBundle.message("dsh.runtime.startup.interrupted"), error);
            }
        }
        String tail = tailLogs(40);
        throw new IllegalStateException(
                DshBundle.message("dsh.runtime.startup.timeout")
                        + (tail.isBlank() ? "" : "\n\n" + tail));
    }

    private void stopBlocking() {
        stopping = true;
        Process probe = probingProcess;
        if (probe != null) {
            probe.descendants().forEach(ProcessHandle::destroy);
            probe.destroy();
        }
        Process child = process == null ? managedHelper : process;
        if (child != null) {
            List<ProcessHandle> descendants = child.descendants().toList();
            try {
                if (child == managedHelper && child.isAlive()) {
                    com.google.gson.JsonObject stop = new com.google.gson.JsonObject();
                    stop.addProperty("type", "stop");
                    DshRuntimeHelper.send(child, stop);
                    child.getOutputStream().close();
                } else child.destroy();
                if (!child.waitFor(12, TimeUnit.SECONDS)) {
                    descendants.forEach(ProcessHandle::destroy);
                    child.destroy();
                    if (!child.waitFor(3, TimeUnit.SECONDS)) {
                        descendants.stream()
                                .filter(ProcessHandle::isAlive)
                                .forEach(ProcessHandle::destroyForcibly);
                        child.destroyForcibly();
                        child.waitFor(2, TimeUnit.SECONDS);
                    }
                }
            } catch (IOException error) {
                child.destroy();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new CompletionException(error);
            }
            if (child.isAlive() || !runtimeLock.runtimeExited())
                throw new IllegalStateException(
                        "DSH Runtime shutdown could not be verified; shared lock retained.");
        }
        process = null;
        managedHelper = null;
        clearRuntimeEndpoint();
        runtimeLock.release();
        setStatus(
                new RuntimeStatus(
                        RuntimeState.STOPPED, null, DshBundle.message("dsh.runtime.stopped")));
    }

    public String diagnoseEnvironment() {
        DshSettingsState settings = DshSettingsState.getInstance(project);
        StringBuilder report = new StringBuilder();
        report.append(DshBundle.message("dsh.diagnose.title")).append("\n");
        report.append("Generated: ").append(Instant.now()).append('\n');
        report.append("IDE: IntelliJ Platform product\n");
        report.append("Project: ")
                .append(project.getBasePath() == null ? "<none>" : project.getBasePath())
                .append('\n');
        report.append("Configured command: ")
                .append(settings.command)
                .append(' ')
                .append(settings.commandArgs)
                .append('\n');
        Map<String, String> environment = executionEnvironment();
        String configuredExecutable =
                settings.command == null || settings.command.isBlank()
                        ? "dsh"
                        : settings.command.trim();
        report.append("Launch executable: ").append(configuredExecutable).append('\n');
        report.append("Runtime PATH: ")
                .append(environmentValue(environment, "PATH", "<none>"))
                .append('\n');
        report.append("Configured server URL: ")
                .append(
                        settings.serverUrl == null || settings.serverUrl.isBlank()
                                ? "<none>"
                                : redactRuntimeOutput(settings.serverUrl))
                .append('\n');
        report.append("Configured server port: ")
                .append(settings.serverPort == 0 ? "automatic" : settings.serverPort)
                .append('\n');
        report.append("Runtime status: ").append(status.state).append('\n');
        report.append("Runtime URL: ").append(baseUrl == null ? "<none>" : baseUrl).append('\n');
        report.append("Runtime launch token: ")
                .append(launchUrl == null ? "not advertised" : "advertised")
                .append('\n');
        report.append("Runtime auth cookie: ")
                .append(auth.isEstablished() ? "established" : "not established")
                .append('\n');
        report.append("API key environment variable: ").append(settings.apiKeyEnv).append('\n');
        String key = settings.apiKeyEnv == null ? "" : settings.apiKeyEnv.trim();
        report.append("API key environment variable present: ")
                .append(!key.isEmpty() && System.getenv(key) != null ? "yes" : "no")
                .append('\n');
        if (baseUrl != null)
            report.append("Remote health: ")
                    .append(isRemoteHealthy(baseUrl) ? "healthy" : "unreachable")
                    .append('\n');
        // The shared lock is the first thing to look at whenever two editors
        // each spawned their own Runtime: a path mismatch is the usual cause.
        report.append("Shared runtime lock: ").append(runtimeLock.getPath()).append('\n');
        report.append("Shared runtime lock held by this IDE: ")
                .append(runtimeLock.isHeld() ? "yes" : "no")
                .append('\n');
        String advertised = runtimeLock.readAdvertisedUrl();
        report.append("Shared runtime lock advertises: ")
                .append(advertised == null ? "<nothing>" : advertised)
                .append('\n');
        return report.toString();
    }

    private DshRuntimeEndpoint currentEndpoint() {
        String currentBase = baseUrl;
        if (currentBase == null || currentBase.isBlank()) return null;
        String currentLaunch = launchUrl;
        if (currentLaunch == null || currentLaunch.isBlank()) {
            return DshRuntimeEndpoint.ofBase(currentBase);
        }
        DshRuntimeEndpoint endpoint = DshRuntimeEndpoint.parse(currentLaunch, false);
        return endpoint == null ? DshRuntimeEndpoint.ofBase(currentBase) : endpoint;
    }

    private void clearRuntimeEndpoint() {
        baseUrl = null;
        launchUrl = null;
        auth.invalidate();
    }

    /**
     * Put back an endpoint captured before a probe. Unlike {@link #setRuntimeEndpoint} this writes
     * both fields verbatim, so a launch token taken from the snapshot is restored rather than
     * inferred, and a snapshot of "no endpoint" restores as no endpoint.
     */
    private void restoreRuntimeEndpoint(String previousBase, String previousLaunch) {
        if (previousBase == null || previousBase.isBlank()) {
            clearRuntimeEndpoint();
            return;
        }
        boolean changed =
                !Objects.equals(baseUrl, previousBase)
                        || !Objects.equals(launchUrl, previousLaunch);
        baseUrl = previousBase;
        launchUrl = previousLaunch;
        if (changed) {
            auth.invalidate();
        }
    }

    /** Observe the raw child output before redacting it for the visible Runtime log. */
    private void observeRuntimeEndpoint(String text) {
        DshRuntimeEndpoint discovered = DshRuntimeEndpoint.extract(text);
        if (discovered == null) return;
        DshRuntimeEndpoint previous = currentEndpoint();
        if (previous != null
                && previous.baseUrl.equals(discovered.baseUrl)
                && Objects.equals(previous.launchUrl, discovered.launchUrl)) return;
        setRuntimeEndpoint(discovered);
        if (runtimeLock.isHeld()) runtimeLock.publishUrl(discovered.baseUrl, discovered.launchUrl);
    }

    /** Replace the active endpoint and invalidate a cookie bound to the old endpoint. */
    private void setRuntimeEndpoint(DshRuntimeEndpoint endpoint) {
        if (endpoint == null) return;
        String previousBase = baseUrl;
        String previousLaunch = launchUrl;
        String nextLaunch =
                endpoint.launchUrl != null
                        ? endpoint.launchUrl
                        : Objects.equals(previousBase, endpoint.baseUrl) ? previousLaunch : null;
        baseUrl = endpoint.baseUrl;
        launchUrl = nextLaunch;
        if (!Objects.equals(previousBase, baseUrl) || !Objects.equals(previousLaunch, launchUrl)) {
            auth.invalidate();
        }
    }

    private static String redactRuntimeOutput(String value) {
        return value == null ? "" : value.replaceAll("([?&]token=)[A-Za-z0-9_-]+", "$1<redacted>");
    }

    private void setStatus(RuntimeStatus next) {
        status = next;
        for (Consumer<RuntimeStatus> listener : listeners) {
            try {
                listener.accept(next);
            } catch (RuntimeException error) {
                LOG.warn("DSH status listener failed", error);
            }
        }
    }

    private void appendLog(String line) {
        synchronized (output) {
            output.append(redactRuntimeOutput(line)).append('\n');
            if (output.length() > 250_000) output.delete(0, output.length() - 200_000);
        }
    }

    private String tailLogs(int lines) {
        synchronized (output) {
            String[] values = output.toString().split("\\R");
            int from = Math.max(0, values.length - lines);
            return String.join("\n", Arrays.copyOfRange(values, from, values.length)).trim();
        }
    }

    /**
     * Pin a bare {@code @deepseek-ai/dsh} package spec to the configured runtime version.
     *
     * <p>{@code pnpm dlx @deepseek-ai/dsh} resolves the {@code latest} dist-tag on every launch, so
     * an upstream release reaches users the moment it is published -- including one whose RPC
     * surface this plugin has not adapted to yet. The version setting already existed but only
     * reached the npx fallbacks, never the configured command, which is the path the defaults
     * actually take.
     *
     * <p>Only a bare package name is rewritten. An explicit version or tag, a scoped alias, a
     * tarball URL, or a local checkout path is left exactly as written, so pointing the plugin at a
     * working tree or deliberately tracking {@code @latest} still works.
     */
    private static List<String> pinRuntimeVersion(List<String> args, String runtimeVersion) {
        if (runtimeVersion.isBlank() || "latest".equals(runtimeVersion)) return args;
        List<String> result = new ArrayList<>(args.size());
        for (String arg : args) {
            result.add(RUNTIME_PACKAGE.equals(arg) ? arg + "@" + runtimeVersion : arg);
        }
        return result;
    }

    /**
     * Write the launcher patch that re-enables the compaction command for this process, matching
     * dsh-ide's enableCompaction. Returns null when the patch cannot be written; the launch then
     * proceeds without it.
     */
    private String writeCompactionPatch() {
        Path patch =
                Path.of(
                        System.getProperty("java.io.tmpdir"),
                        "dsh-intellij-" + ProcessHandle.current().pid() + "-compaction.patch.yml");
        try {
            java.nio.file.Files.writeString(
                    patch,
                    "- id: compaction-basic\n  disabled: false\n\n- id: command-compact\n  disabled: false\n",
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                    java.nio.file.StandardOpenOption.WRITE);
            return patch.toString();
        } catch (IOException error) {
            LOG.warn("Unable to write the compaction launcher patch", error);
            return null;
        }
    }

    /** Index of the Web-app token in one launch command, or -1 for non-web profiles. */
    private static int webProfileIndex(List<String> args) {
        for (int index = 0; index < args.size(); index++) {
            String argument = args.get(index);
            if (argument.equals("web")
                    || argument.equals("--profile=web")
                    || (argument.equals("--profile")
                            && index + 1 < args.size()
                            && args.get(index + 1).equals("web"))) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Insert a DSH launcher flag before the first Web-app argument. DSH stops parsing its own flags
     * at the first unknown token, so app flags such as {@code --no-open} must not precede a later
     * launcher-level {@code --patch}.
     */
    private static void insertWebLauncherPatch(List<String> args, String patchPath) {
        int profileIndex = webProfileIndex(args);
        if (profileIndex < 0) {
            args.add("--patch");
            args.add(patchPath);
            return;
        }
        int insertionIndex = profileIndex + 1;
        while (insertionIndex < args.size()) {
            String argument = args.get(insertionIndex);
            if (argument.equals("--patch")) {
                insertionIndex += 2;
            } else if (argument.startsWith("--patch=")
                    || argument.equals("--dump-config")
                    || argument.equals("--dump-default-config")) {
                insertionIndex += 1;
            } else {
                break;
            }
        }
        args.add(insertionIndex, "--patch");
        args.add(insertionIndex + 1, patchPath);
    }

    private static boolean hasPort(List<String> args) {
        for (String arg : args) {
            if (arg.equals("--port") || arg.equals("-p") || arg.startsWith("--port=")) return true;
        }
        return false;
    }

    /** Returns the shell environment discovered by the IntelliJ Platform. */
    private static Map<String, String> executionEnvironment() {
        Map<String, String> environment = new HashMap<>(System.getenv());
        try {
            environment.putAll(EnvironmentUtil.getEnvironmentMap());
        } catch (RuntimeException error) {
            LOG.warn(
                    "Unable to read the IDE shell environment; using the process environment",
                    error);
        }
        return environment;
    }

    /** Use the native command interpreter for package-manager launchers. */
    private static List<String> prepareCommand(
            List<String> command, Map<String, String> environment) {
        if (command.isEmpty()) return command;
        String executable = command.get(0).toLowerCase(Locale.ROOT);
        if (isWindows() && (executable.endsWith(".cmd") || executable.endsWith(".bat"))) {
            List<String> wrapped = new ArrayList<>();
            wrapped.add(environmentValue(environment, "ComSpec", "cmd.exe"));
            wrapped.add("/d");
            wrapped.add("/c");
            wrapped.addAll(command);
            return wrapped;
        }
        if (!isWindows()) {
            List<String> wrapped = new ArrayList<>();
            wrapped.add("/usr/bin/env");
            wrapped.addAll(command);
            return wrapped;
        }
        return command;
    }

    private static String resolveExecutable(String command, Map<String, String> environment) {
        if (command.contains("/") || command.contains("\\"))
            return Path.of(command).toAbsolutePath().toString();
        for (String entry :
                environmentValue(environment, "PATH", "").split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) continue;
            Path candidate = Path.of(entry, command);
            if (java.nio.file.Files.isRegularFile(candidate)
                    && (isWindows() || java.nio.file.Files.isExecutable(candidate)))
                return candidate.toAbsolutePath().toString();
        }
        return command;
    }

    private static boolean isNodePackageManager(String executable) {
        String name = executableBaseName(executable);
        return name.equals("npm") || name.equals("npx") || name.equals("pnpm");
    }

    private static String executableBaseName(String executable) {
        int slash = Math.max(executable.lastIndexOf('/'), executable.lastIndexOf('\\'));
        String name = executable.substring(slash + 1).toLowerCase(Locale.ROOT);
        if (name.endsWith(".cmd") || name.endsWith(".bat") || name.endsWith(".exe")) {
            name = name.substring(0, name.lastIndexOf('.'));
        }
        return name;
    }

    private static String environmentValue(
            Map<String, String> environment, String key, String fallback) {
        String exact = environment.get(key);
        if (exact != null) return exact;
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) return entry.getValue();
        }
        return fallback;
    }

    private static String platformCommand(String executable) {
        return isWindows()
                ? executable + ("node".equals(executable) ? ".exe" : ".cmd")
                : executable;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** Shell-like tokenization without invoking a shell (and without shell injection). */
    public static List<String> splitArguments(String value) {
        if (value == null || value.isBlank()) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        char quote = 0;
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (escaped) {
                token.append(current);
                escaped = false;
            } else if (current == '\\' && quote != '\'') {
                escaped = true;
            } else if (quote != 0) {
                if (current == quote) quote = 0;
                else token.append(current);
            } else if (current == '\'' || current == '"') {
                quote = current;
            } else if (Character.isWhitespace(current)) {
                if (!token.isEmpty()) {
                    result.add(token.toString());
                    token.setLength(0);
                }
            } else {
                token.append(current);
            }
        }
        if (escaped) token.append('\\');
        if (quote != 0) throw new IllegalArgumentException("Unclosed quote in command arguments");
        if (!token.isEmpty()) result.add(token.toString());
        return result;
    }

    private static String redactCommand(List<String> command) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < command.size(); index++) {
            if (index > 0) result.append(' ');
            String value = command.get(index);
            String previous = index == 0 ? "" : command.get(index - 1).toLowerCase(Locale.ROOT);
            if (previous.matches(".*(token|secret|password|api[-_]?key|auth|credential).*")
                    || value.matches(
                            "(?i)(api[-_]?key|token|secret|password|auth|credential)=.*")) {
                int equals = value.indexOf('=');
                result.append(
                        equals < 0 ? "<redacted>" : value.substring(0, equals + 1) + "<redacted>");
            } else {
                result.append(value);
            }
        }
        return result.toString();
    }

    @Override
    public void dispose() {
        stopRequested = true;
        try {
            stopBlocking();
        } finally {
            executor.shutdownNow();
        }
    }

    public enum RuntimeState {
        STOPPED,
        STARTING,
        RECOVERING,
        RUNNING,
        ERROR
    }

    public static final class RuntimeStatus {
        public final RuntimeState state;
        public final String url;
        public final String message;

        public RuntimeStatus(RuntimeState state, String url, String message) {
            this.state = Objects.requireNonNull(state);
            this.url = url;
            this.message = message;
        }
    }
}
