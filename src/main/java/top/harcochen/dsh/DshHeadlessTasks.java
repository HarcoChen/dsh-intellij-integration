package top.harcochen.dsh;

import com.google.gson.JsonObject;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.KillableProcessHandler;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Project-local one-shot CLI tasks, independently owned from the Web Runtime. */
public final class DshHeadlessTasks implements Disposable {
    record Request(
            String prompt,
            String directory,
            String permission,
            String sessionId,
            int timeoutMinutes) {
        Request {
            if (prompt == null
                    || prompt.isBlank()
                    || prompt.getBytes(StandardCharsets.UTF_8).length > 256 * 1024)
                throw new IllegalArgumentException(
                        DshBundle.message("dsh.headless.prompt.invalid"));
            if (directory == null || !Path.of(directory).isAbsolute())
                throw new IllegalArgumentException(
                        DshBundle.message("dsh.headless.directory.invalid"));
            permission = permission == null ? "" : permission.strip();
            sessionId = sessionId == null ? "" : sessionId;
            if (permission.length() > 128
                    || permission.indexOf('\0') >= 0
                    || sessionId.length() > 512
                    || sessionId.indexOf('\0') >= 0)
                throw new IllegalArgumentException(
                        DshBundle.message("dsh.headless.options.invalid"));
            if (timeoutMinutes < 1 || timeoutMinutes > 1440)
                throw new IllegalArgumentException(
                        DshBundle.message("dsh.headless.timeout.invalid"));
        }
    }

    record Row(String id, String title, String state, String sessionId, long revision) {}

    record View(
            Row row,
            Request request,
            String answer,
            String events,
            String diagnostics,
            String error,
            Integer exitCode,
            boolean truncated,
            boolean active) {}

    private final Project project;
    private final Map<String, Run> runs = new LinkedHashMap<>();
    private final ExecutorService work =
            Executors.newCachedThreadPool(
                    runnable -> {
                        Thread thread = new Thread(runnable, "dsh-headless");
                        thread.setDaemon(true);
                        return thread;
                    });
    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(
                    runnable -> {
                        Thread thread = new Thread(runnable, "dsh-headless-timeouts");
                        thread.setDaemon(true);
                        return thread;
                    });
    private volatile boolean disposed;

    public DshHeadlessTasks(Project project) {
        this.project = project;
    }

    public static DshHeadlessTasks getInstance(Project project) {
        return project.getService(DshHeadlessTasks.class);
    }

    public static void open(Project project) {
        DshHeadlessTaskDialog.open(project);
    }

    synchronized String start(Request request) {
        if (disposed || project.isDisposed())
            throw new IllegalStateException(DshBundle.message("dsh.headless.closed"));
        if (runs.values().stream().filter(Run::active).count() >= 4)
            throw new IllegalStateException(DshBundle.message("dsh.headless.limit"));
        if (!request.sessionId().isBlank()
                && runs.values().stream().anyMatch(run -> run.usesSession(request.sessionId())))
            throw new IllegalStateException(DshBundle.message("dsh.headless.session.active"));
        if (!Files.isDirectory(Path.of(request.directory())))
            throw new IllegalArgumentException(DshBundle.message("dsh.headless.directory.invalid"));
        while (runs.size() >= 20) {
            String completed =
                    runs.entrySet().stream()
                            .filter(entry -> !entry.getValue().active())
                            .map(Map.Entry::getKey)
                            .findFirst()
                            .orElse(null);
            if (completed == null) break;
            runs.remove(completed);
        }
        Run run = new Run(request);
        runs.put(run.id, run);
        work.execute(run::launch);
        return run.id;
    }

    synchronized List<Row> rows() {
        List<Row> result = new ArrayList<>();
        for (Run run : runs.values()) result.add(run.row());
        java.util.Collections.reverse(result);
        return List.copyOf(result);
    }

    synchronized View view(String id, long knownRevision) {
        Run run = runs.get(id);
        return run == null || run.row().revision() == knownRevision ? null : run.view();
    }

    synchronized void cancel(String id) {
        Run run = runs.get(id);
        if (run != null) run.cancel(false);
    }

    boolean closed() {
        return disposed;
    }

    private final class Run {
        private final String id = UUID.randomUUID().toString();
        private final Request request;
        private final String title;
        private final DshHeadlessOutput output = new DshHeadlessOutput();
        private final DshHeadlessOutput.Tail answer = new DshHeadlessOutput.Tail();
        private final DshHeadlessOutput.Tail events = new DshHeadlessOutput.Tail();
        private final DshHeadlessOutput.Tail diagnostics = new DshHeadlessOutput.Tail();
        private final CompletableFuture<Void> finished = new CompletableFuture<>();
        private KillableProcessHandler handler;
        private ScheduledFuture<?> deadline;
        private ScheduledFuture<?> forceStop;
        private String state = "preparing";
        private String sessionId = "";
        private String error;
        private Integer exitCode;
        private long revision;
        private boolean cancelled;
        private boolean timedOut;
        private boolean finalSeen;
        private boolean finalTruncated;

        Run(Request request) {
            this.request = request;
            String first = request.prompt().strip().lines().findFirst().orElse("");
            this.title = first.substring(0, Math.min(first.length(), 90));
        }

        synchronized boolean active() {
            return !finished.isDone();
        }

        synchronized boolean usesSession(String identity) {
            return active() && (identity.equals(sessionId) || identity.equals(request.sessionId()));
        }

        synchronized Row row() {
            return new Row(id, title, state, sessionId, revision);
        }

        synchronized View view() {
            return new View(
                    row(),
                    request,
                    answer.text(),
                    events.text(),
                    diagnostics.text(),
                    error,
                    exitCode,
                    answer.truncated()
                            || events.truncated()
                            || diagnostics.truncated()
                            || output.oversized()
                            || output.diagnosticTruncated()
                            || finalTruncated,
                    active());
        }

        void launch() {
            try {
                DshRuntimeService runtime = DshRuntimeService.getInstance(project);
                Map<String, String> environment = runtime.headlessEnvironment();
                ExecutionException unavailable = null;
                for (List<String> candidate : runtime.headlessCommands()) {
                    synchronized (this) {
                        if (cancelled || disposed) {
                            settle(null);
                            return;
                        }
                    }
                    List<String> command = new ArrayList<>(candidate);
                    if (!request.permission().isEmpty())
                        command.addAll(List.of("--permission-mode", request.permission()));
                    if (!request.sessionId().isBlank())
                        command.addAll(List.of("--session-id", request.sessionId()));
                    GeneralCommandLine invocation =
                            new GeneralCommandLine(command)
                                    .withWorkDirectory(request.directory())
                                    .withCharset(StandardCharsets.UTF_8)
                                    .withParentEnvironmentType(
                                            GeneralCommandLine.ParentEnvironmentType.NONE);
                    Process process;
                    try {
                        ProcessBuilder builder = invocation.toProcessBuilder();
                        builder.environment().clear();
                        builder.environment().putAll(environment);
                        builder.environment().put("NO_COLOR", "1");
                        process = builder.start();
                    } catch (java.io.IOException | ExecutionException failure) {
                        unavailable =
                                new ExecutionException(
                                        DshBundle.message("dsh.headless.launch.failed"), failure);
                        continue;
                    }
                    KillableProcessHandler created =
                            new KillableProcessHandler(
                                    process, "DSH headless", StandardCharsets.UTF_8);
                    created.setShouldDestroyProcessRecursively(true);
                    created.addProcessListener(
                            new ProcessAdapter() {
                                @Override
                                public void onTextAvailable(ProcessEvent event, Key key) {
                                    receive(event.getText(), key);
                                }

                                @Override
                                public void processTerminated(ProcessEvent event) {
                                    settle(event.getExitCode());
                                }
                            });
                    synchronized (this) {
                        handler = created;
                        state = cancelled ? "cancelling" : "running";
                        revision++;
                    }
                    created.startNotify();
                    synchronized (this) {
                        if (finished.isDone()) return;
                        if (cancelled || disposed) {
                            cancel(false);
                            return;
                        }
                        deadline =
                                timer.schedule(
                                        () -> cancel(true),
                                        request.timeoutMinutes(),
                                        TimeUnit.MINUTES);
                    }
                    try (OutputStream input = process.getOutputStream()) {
                        input.write(request.prompt().getBytes(StandardCharsets.UTF_8));
                        input.flush();
                    } catch (java.io.IOException writeFailure) {
                        synchronized (this) {
                            if (!finished.isDone() && !cancelled) {
                                if (error == null)
                                    error = DshBundle.message("dsh.headless.input.failed");
                                stop();
                            }
                        }
                    }
                    return;
                }
                throw unavailable == null
                        ? new IllegalStateException(
                                DshBundle.message("dsh.runtime.desktop.required"))
                        : unavailable;
            } catch (Exception failure) {
                synchronized (this) {
                    error = DshJson.message(failure);
                    revision++;
                }
                settle(null);
            }
        }

        private synchronized void receive(String text, Key key) {
            if (finished.isDone()) return;
            if (ProcessOutputTypes.STDOUT.equals(key))
                output.accept(text, this::event, diagnostics::append);
            else if (ProcessOutputTypes.STDERR.equals(key)) diagnostics.append(text);
            revision++;
        }

        private void event(JsonObject event) {
            events.append(event + "\n");
            String type = DshJson.string(event, "type");
            if ("session".equals(type) || "final".equals(type)) {
                String identity = DshJson.strictString(event, "sessionId");
                if (identity == null
                        || identity.isBlank()
                        || identity.length() > 512
                        || (!sessionId.isEmpty() && !sessionId.equals(identity))
                        || (!request.sessionId().isBlank()
                                && !request.sessionId().equals(identity))) {
                    error = DshBundle.message("dsh.headless.session.mismatch");
                    stop();
                    return;
                }
                sessionId = identity;
            }
            if ("text".equals(type)) {
                String text = DshJson.strictString(event, "text");
                if (text != null) answer.append(text);
            } else if ("final".equals(type)) {
                String text = DshJson.strictString(event, "text");
                if (text == null) return;
                finalSeen = true;
                finalTruncated = text.length() > DshHeadlessOutput.MAX_CHARS;
                answer.replace(text);
            } else if ("error".equals(type)) {
                String message = DshJson.stringOr(event, "message", "Headless task failed");
                error = message.substring(0, Math.min(message.length(), 8192));
            }
        }

        synchronized void cancel(boolean timeout) {
            if (finished.isDone()) return;
            cancelled = true;
            timedOut |= timeout;
            stop();
        }

        private void stop() {
            state = "cancelling";
            revision++;
            KillableProcessHandler owned = handler;
            if (owned == null || owned.isProcessTerminated()) return;
            work.execute(owned::destroyProcess);
            if (forceStop == null)
                forceStop =
                        timer.schedule(
                                () -> {
                                    if (!owned.isProcessTerminated() && owned.canKillProcess())
                                        owned.killProcess();
                                },
                                3,
                                TimeUnit.SECONDS);
        }

        private synchronized void settle(Integer code) {
            if (finished.isDone()) return;
            output.finish(this::event, diagnostics::append);
            exitCode = code;
            if (deadline != null) deadline.cancel(false);
            if (forceStop != null) forceStop.cancel(false);
            if (timedOut) state = "timed-out";
            else if (cancelled || disposed) state = "cancelled";
            else if (code != null && code == 0 && finalSeen && error == null) state = "completed";
            else {
                state = "failed";
                if (error == null)
                    error =
                            output.oversized()
                                    ? DshBundle.message("dsh.headless.record.large")
                                    : DshBundle.message(
                                            "dsh.headless.no.final", code == null ? "?" : code);
            }
            revision++;
            finished.complete(null);
        }
    }

    @Override
    public void dispose() {
        disposed = true;
        List<Run> owned;
        synchronized (this) {
            owned = List.copyOf(runs.values());
        }
        for (Run run : owned) run.cancel(false);
        CompletableFuture.allOf(
                        owned.stream().map(run -> run.finished).toArray(CompletableFuture[]::new))
                .whenComplete(
                        (value, failure) -> {
                            timer.shutdown();
                            work.shutdown();
                        });
    }
}
