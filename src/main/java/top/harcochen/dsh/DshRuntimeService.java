package top.harcochen.dsh;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.EnvironmentUtil;
import org.jetbrains.annotations.NotNull;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Owns the local dsh web process and the connection URL used by the chat panel.
 *
 * The lifecycle mirrors {@code DshRuntime} in dsh-ide: a configured server is
 * reused, an existing localhost port is probed before spawning, and the child
 * process is terminated when the project closes. All blocking process and HTTP
 * work runs off the Swing event dispatch thread.
 */
public final class DshRuntimeService implements Disposable {
    private static final Logger LOG = Logger.getInstance(DshRuntimeService.class);
    private static final Pattern URL_PATTERN = Pattern.compile(
            "https?://(?:127\\.0\\.0\\.1|localhost|0\\.0\\.0\\.0|\\[::1\\]):[0-9]{1,5}",
            Pattern.CASE_INSENSITIVE);
    private static final int DEFAULT_PORT = 3080;
    /** The npm package the Runtime ships as; see {@link #pinRuntimeVersion}. */
    private static final String RUNTIME_PACKAGE = "@deepseek-ai/dsh";

    private final Project project;
    private final ExecutorService executor;
    private final CopyOnWriteArrayList<Consumer<RuntimeStatus>> listeners = new CopyOnWriteArrayList<>();
    private final StringBuilder output = new StringBuilder();
    private final Object lifecycleLock = new Object();
    /** Machine-wide start lock, shared with the VS Code extension. */
    private final DshRuntimeLock runtimeLock = new DshRuntimeLock();
    private volatile Process process;
    private volatile String baseUrl;
    private volatile RuntimeStatus status = new RuntimeStatus(RuntimeState.STOPPED, null, null);
    private volatile CompletableFuture<String> startFuture;
    private volatile boolean stopping;
    private final DshRpcClient client;

    public DshRuntimeService(@NotNull Project project) {
        this.project = project;
        this.executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "dsh-intellij-runtime");
            thread.setDaemon(true);
            return thread;
        });
        this.client = new DshRpcClient(() -> baseUrl,
                () -> DshSettingsState.getInstance(this.project).requestTimeoutMs);
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

    public DshRpcClient getClient() {
        return client;
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
            stopping = false;
            setStatus(new RuntimeStatus(RuntimeState.STARTING, baseUrl, DshBundle.message("dsh.runtime.starting")));
            startFuture = CompletableFuture.supplyAsync(this::startBlocking, executor)
                    .whenComplete((url, error) -> {
                        synchronized (lifecycleLock) {
                            startFuture = null;
                        }
                        if (error != null && !stopping) {
                            Throwable cause = error instanceof CompletionException && error.getCause() != null
                                    ? error.getCause() : error;
                            setStatus(new RuntimeStatus(RuntimeState.ERROR, baseUrl, cause.getMessage()));
                        }
                    });
            return startFuture;
        }
    }

    public CompletableFuture<Void> stopAsync() {
        return CompletableFuture.runAsync(this::stopBlocking, executor);
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
            setStatus(new RuntimeStatus(RuntimeState.RUNNING, baseUrl, DshBundle.message("dsh.runtime.settings.changed")));
        }
    }

    private String startBlocking() {
        DshSettingsState settings = DshSettingsState.getInstance(project);
        String configuredUrl = DshRpcClient.normalizeUrl(settings.serverUrl);
        if (configuredUrl != null && client.isWebHealthy(configuredUrl)) {
            baseUrl = configuredUrl;
            setStatus(new RuntimeStatus(RuntimeState.RUNNING, configuredUrl, DshBundle.message("dsh.runtime.connected.configured")));
            return configuredUrl;
        }

        int configuredPort = settings.serverPort;
        String existing = findExistingRuntime(configuredPort);
        if (existing != null) {
            baseUrl = existing;
            setStatus(new RuntimeStatus(RuntimeState.RUNNING, existing, DshBundle.message("dsh.runtime.connected.existing")));
            return existing;
        }

        // The start lock is shared with the VS Code extension, so at most one
        // editor on this machine spawns a Runtime. Losing the race is the
        // normal path when both start together: wait for the winner's URL
        // rather than racing it to a second Runtime.
        if (!runtimeLock.acquire()) {
            String peer = awaitPeerRuntime(configuredPort, settings.startupTimeoutMs);
            if (peer != null) {
                baseUrl = peer;
                setStatus(new RuntimeStatus(RuntimeState.RUNNING, peer, DshBundle.message("dsh.runtime.connected.peer")));
                return peer;
            }
            throw new IllegalStateException(
                    DshBundle.message("dsh.runtime.peer.start.failed"));
        }

        int outputOffset = outputLength();
        Process child;
        String detected;
        try {
            child = launch(settings);
            process = child;
            streamOutput(child);
            try {
                detected = waitForServer(child, settings, outputOffset);
            } catch (RuntimeException error) {
                process = null;
                if (child.isAlive()) child.destroyForcibly();
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
        runtimeLock.publishUrl(detected);
        baseUrl = detected;
        setStatus(new RuntimeStatus(RuntimeState.RUNNING, detected, DshBundle.message("dsh.runtime.running")));
        return detected;
    }

    /**
     * A Runtime already serving this machine: the URL the shared lock
     * advertises first, then the conventional ports. The lock is what finds a
     * Runtime listening on an ephemeral port, which no port probe can.
     */
    private String findExistingRuntime(int configuredPort) {
        String advertised = runtimeLock.readAdvertisedUrl();
        if (advertised != null && client.isHarnessHealthy(advertised)) return advertised;
        List<Integer> ports = new ArrayList<>();
        if (configuredPort > 0) ports.add(configuredPort);
        if (configuredPort != DEFAULT_PORT) ports.add(DEFAULT_PORT);
        for (Integer port : ports) {
            String candidate = "http://127.0.0.1:" + port;
            if (client.isHarnessHealthy(candidate)) return candidate;
        }
        return null;
    }

    /** Wait for the editor that won the start lock to advertise its Runtime. */
    private String awaitPeerRuntime(int configuredPort, int startupTimeoutMs) {
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(Math.max(1_000, startupTimeoutMs));
        while (System.nanoTime() < deadline && !stopping) {
            String url = findExistingRuntime(configuredPort);
            if (url != null) return url;
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
        Map<String, String> environment = executionEnvironment();
        Throwable last = null;
        for (List<String> command : candidates) {
            try {
                List<String> resolvedCommand = prepareCommand(command, environment);
                appendLog("$ " + redactCommand(resolvedCommand));
                ProcessBuilder builder = new ProcessBuilder(resolvedCommand);
                String basePath = project.getBasePath();
                if (basePath != null) builder.directory(Path.of(basePath).toFile());
                builder.redirectErrorStream(true);
                builder.environment().putAll(environment);
                if (settings.npmRegistry != null && !settings.npmRegistry.isBlank()) {
                    if (isNodePackageManager(command.get(0))) {
                        builder.environment().putIfAbsent("npm_config_registry", settings.npmRegistry.trim());
                    }
                }
                String apiKeyName = settings.apiKeyEnv == null ? "" : settings.apiKeyEnv.trim();
                if (!apiKeyName.matches("[A-Za-z_][A-Za-z0-9_]*")) apiKeyName = "";
                String apiKey = apiKeyName.isBlank() ? null : DshCredentials.read(project);
                if (apiKey != null && !apiKey.isBlank()) {
                    builder.environment().put(apiKeyName, apiKey);
                }
                Process child = builder.start();
                appendLog(DshBundle.message("dsh.runtime.log.started.pid", child.pid()));
                return child;
            } catch (IOException error) {
                last = error;
                appendLog(DshBundle.message("dsh.runtime.log.unable.to.start", command.get(0), error.getMessage()));
            }
        }
        String message = DshBundle.message("dsh.runtime.launch.failed");
        if (last != null && last.getMessage() != null) message += "\n" + last.getMessage();
        throw new IllegalStateException(message, last);
    }

    private List<List<String>> launcherCandidates(DshSettingsState settings) {
        String command = settings.command == null ? "dsh" : settings.command.trim();
        if (command.isEmpty()) command = "dsh";
        String runtimeVersion = settings.runtimeVersion == null || settings.runtimeVersion.isBlank()
                ? "latest" : settings.runtimeVersion.trim();
        List<String> configuredArgs = pinRuntimeVersion(splitArguments(settings.commandArgs), runtimeVersion);
        if (!hasPort(configuredArgs)) {
            configuredArgs = new ArrayList<>(configuredArgs);
            configuredArgs.add("--port");
            configuredArgs.add(Integer.toString(Math.max(0, settings.serverPort)));
        }
        List<List<String>> candidates = new ArrayList<>();
        List<String> configured = new ArrayList<>();
        configured.add(command);
        configured.addAll(configuredArgs);
        candidates.add(configured);

        boolean packageManager = isNodePackageManager(command);
        if (settings.installWhenMissing && !packageManager) {
            List<String> fallback = new ArrayList<>();
            fallback.add(platformCommand("npx"));
            fallback.add("--yes");
            fallback.add(RUNTIME_PACKAGE + "@" + runtimeVersion);
            fallback.add("web");
            fallback.add("--no-open");
            if (!hasPort(fallback)) {
                fallback.add("--port");
                fallback.add(Integer.toString(Math.max(0, settings.serverPort)));
            }
            candidates.add(fallback);
        }
        if (settings.installWhenMissing && executableBaseName(command).equals("pnpm")) {
            List<String> npx = new ArrayList<>();
            npx.add(platformCommand("npx"));
            npx.add("--yes");
            if (!configuredArgs.isEmpty() && "dlx".equals(configuredArgs.get(0))) {
                npx.addAll(configuredArgs.subList(1, configuredArgs.size()));
            } else {
                npx.add(RUNTIME_PACKAGE + "@" + runtimeVersion);
                npx.add("web");
                npx.add("--no-open");
            }
            if (!hasPort(npx)) {
                npx.add("--port");
                npx.add(Integer.toString(Math.max(0, settings.serverPort)));
            }
            candidates.add(npx);
        }
        return candidates;
    }

    private void streamOutput(Process child) {
        executor.execute(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    appendLog(line);
                    LOG.info("[dsh] " + line);
                }
            } catch (IOException error) {
                if (!stopping) appendLog(DshBundle.message("dsh.runtime.log.output.ended", error.getMessage()));
            }
        });
        executor.execute(() -> {
            try {
                int exitCode = child.waitFor();
                if (!stopping && status.state == RuntimeState.RUNNING) {
                    setStatus(new RuntimeStatus(RuntimeState.ERROR, null, DshBundle.message("dsh.runtime.exited.with.code", exitCode)));
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private String waitForServer(Process child, DshSettingsState settings, int outputOffset) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1_000, settings.startupTimeoutMs));
        while (System.nanoTime() < deadline) {
            if (stopping) throw new IllegalStateException(DshBundle.message("dsh.runtime.startup.cancelled"));
            if (!child.isAlive()) {
                String tail = tailLogs(40);
                throw new IllegalStateException(DshBundle.message("dsh.runtime.exited.before.ready") + (tail.isBlank() ? "" : "\n\n" + tail));
            }
            String outputUrl = findUrl(logsSince(outputOffset));
            List<String> candidates = new ArrayList<>();
            if (outputUrl != null) candidates.add(outputUrl);
            if (settings.serverPort > 0) candidates.add("http://127.0.0.1:" + settings.serverPort);
            for (String candidate : candidates) {
                if (client.isWebHealthy(candidate)) return DshRpcClient.normalizeUrl(candidate);
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(DshBundle.message("dsh.runtime.startup.interrupted"), error);
            }
        }
        String tail = tailLogs(40);
        throw new IllegalStateException(DshBundle.message("dsh.runtime.startup.timeout") + (tail.isBlank() ? "" : "\n\n" + tail));
    }

    private void stopBlocking() {
        synchronized (lifecycleLock) {
            stopping = true;
        }
        Process child = process;
        process = null;
        baseUrl = null;
        // Released before the child is reaped: a peer polling the lock should
        // stop seeing a URL the moment this Runtime is on its way down.
        runtimeLock.release();
        if (child != null && child.isAlive()) {
            child.destroy();
            try {
                if (!child.waitFor(2, TimeUnit.SECONDS)) child.destroyForcibly();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                child.destroyForcibly();
            }
        }
        setStatus(new RuntimeStatus(RuntimeState.STOPPED, null, DshBundle.message("dsh.runtime.stopped")));
    }

    public String diagnoseEnvironment() {
        DshSettingsState settings = DshSettingsState.getInstance(project);
        StringBuilder report = new StringBuilder();
        report.append(DshBundle.message("dsh.diagnose.title")).append("\n");
        report.append("Generated: ").append(Instant.now()).append('\n');
        report.append("IDE: IntelliJ Platform product\n");
        report.append("Project: ").append(project.getBasePath() == null ? "<none>" : project.getBasePath()).append('\n');
        report.append("Configured command: ").append(settings.command).append(' ').append(settings.commandArgs).append('\n');
        Map<String, String> environment = executionEnvironment();
        String configuredExecutable = settings.command == null || settings.command.isBlank()
                ? "dsh" : settings.command.trim();
        report.append("Launch executable: ").append(configuredExecutable).append('\n');
        report.append("Runtime PATH: ").append(environmentValue(environment, "PATH", "<none>")).append('\n');
        report.append("Configured server URL: ").append(settings.serverUrl == null || settings.serverUrl.isBlank() ? "<none>" : settings.serverUrl).append('\n');
        report.append("Configured server port: ").append(settings.serverPort == 0 ? "automatic" : settings.serverPort).append('\n');
        report.append("Runtime status: ").append(status.state).append('\n');
        report.append("Runtime URL: ").append(baseUrl == null ? "<none>" : baseUrl).append('\n');
        report.append("API key environment variable: ").append(settings.apiKeyEnv).append('\n');
        String key = settings.apiKeyEnv == null ? "" : settings.apiKeyEnv.trim();
        report.append("API key environment variable present: ").append(!key.isEmpty() && System.getenv(key) != null ? "yes" : "no").append('\n');
        if (baseUrl != null) report.append("Runtime health: ").append(client.isHealthy(baseUrl) ? "healthy" : "unreachable").append('\n');
        // The shared lock is the first thing to look at whenever two editors
        // each spawned their own Runtime: a path mismatch is the usual cause.
        report.append("Shared runtime lock: ").append(runtimeLock.getPath()).append('\n');
        report.append("Shared runtime lock held by this IDE: ").append(runtimeLock.isHeld() ? "yes" : "no").append('\n');
        String advertised = runtimeLock.readAdvertisedUrl();
        report.append("Shared runtime lock advertises: ").append(advertised == null ? "<nothing>" : advertised).append('\n');
        return report.toString();
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
            output.append(line).append('\n');
            if (output.length() > 250_000) output.delete(0, output.length() - 200_000);
        }
    }

    private int outputLength() {
        synchronized (output) {
            return output.length();
        }
    }

    private String logsSince(int offset) {
        synchronized (output) {
            return output.substring(Math.max(0, Math.min(offset, output.length())));
        }
    }

    private String tailLogs(int lines) {
        synchronized (output) {
            String[] values = output.toString().split("\\R");
            int from = Math.max(0, values.length - lines);
            return String.join("\n", Arrays.copyOfRange(values, from, values.length)).trim();
        }
    }

    private static String findUrl(String value) {
        Matcher matcher = URL_PATTERN.matcher(value == null ? "" : value);
        return matcher.find() ? DshRpcClient.normalizeUrl(matcher.group()) : null;
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
            LOG.warn("Unable to read the IDE shell environment; using the process environment", error);
        }
        return environment;
    }

    /** Use the native command interpreter for package-manager launchers. */
    private static List<String> prepareCommand(List<String> command, Map<String, String> environment) {
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

    private static String environmentValue(Map<String, String> environment, String key, String fallback) {
        String exact = environment.get(key);
        if (exact != null) return exact;
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) return entry.getValue();
        }
        return fallback;
    }

    private static String platformCommand(String executable) {
        return isWindows() ? executable + ".cmd" : executable;
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
                    || value.matches("(?i)(api[-_]?key|token|secret|password|auth|credential)=.*")) {
                int equals = value.indexOf('=');
                result.append(equals < 0 ? "<redacted>" : value.substring(0, equals + 1) + "<redacted>");
            } else {
                result.append(value);
            }
        }
        return result.toString();
    }

    @Override
    public void dispose() {
        try {
            stopBlocking();
        } finally {
            executor.shutdownNow();
        }
    }

    public enum RuntimeState { STOPPED, STARTING, RUNNING, ERROR }

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
