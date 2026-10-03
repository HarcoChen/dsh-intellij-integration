package top.harcochen.dsh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebugSessionListener;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.breakpoints.XBreakpoint;
import com.intellij.xdebugger.breakpoints.XBreakpointProperties;
import com.intellij.xdebugger.breakpoints.XBreakpointType;
import com.intellij.xdebugger.breakpoints.XLineBreakpoint;
import com.intellij.xdebugger.breakpoints.XLineBreakpointType;
import com.intellij.xdebugger.frame.XExecutionStack;
import com.intellij.xdebugger.frame.XSuspendContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Only public, product-neutral XDebugger operations and existing project run configurations. */
final class DshDebugTools implements AutoCloseable {
    private final Project project;
    private final DshDebugContextController context;
    private final Map<XDebugSession, String> ids = new IdentityHashMap<>();
    private final Map<XExecutionStack, Integer> threads = new IdentityHashMap<>();
    private int nextThreadId = 1;
    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(
                    task -> {
                        Thread thread = new Thread(task, "dsh-debug-wait");
                        thread.setDaemon(true);
                        return thread;
                    });
    private final Set<CompletableFuture<?>> pending =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    DshDebugTools(Project project) {
        this.project = project;
        context =
                new DshDebugContextController(project, ignored -> {}, ignored -> {}, ignored -> {});
    }

    static JsonArray definitions() {
        JsonArray tools = new JsonArray();
        tools.add(
                tool(
                        "debug_start",
                        "Start an existing project run configuration; never creates configurations.",
                        """
                        {
                          "configurationName": {
                            "type": "string"
                          },
                          "noDebug": {
                            "type": "boolean"
                          }
                        }
                        """,
                        null));
        tools.add(
                tool(
                        "debug_breakpoint",
                        "Add/remove/list source breakpoints. Generic verification state is unavailable.",
                        """
                        {
                          "action": {
                            "type": "string",
                            "enum": [
                              "add",
                              "remove",
                              "list"
                            ]
                          },
                          "file": {
                            "type": "string"
                          },
                          "line": {
                            "type": "integer",
                            "minimum": 1
                          },
                          "condition": {
                            "type": "string"
                          },
                          "enabled": {
                            "type": "boolean"
                          },
                          "logExpression": {
                            "type": "string"
                          }
                        }
                        """,
                        "action"));
        tools.add(
                tool(
                        "debug_control",
                        "Continue, pause, step, list paused threads or wait. Line-level stepping only.",
                        """
                        {
                          "action": {
                            "type": "string",
                            "enum": [
                              "continue",
                              "pause",
                              "next",
                              "stepIn",
                              "stepOut",
                              "threads",
                              "wait"
                            ]
                          },
                          "sessionId": {
                            "type": "string"
                          },
                          "threadId": {
                            "type": "integer",
                            "minimum": 1
                          },
                          "timeoutMs": {
                            "type": "integer",
                            "minimum": 250,
                            "maximum": 45000
                          }
                        }
                        """,
                        "action"));
        tools.add(
                tool(
                        "debug_context",
                        "Read bounded/redacted stack, frame variables, source and diagnostics.",
                        """
                        {
                          "sessionId": {
                            "type": "string"
                          },
                          "threadId": {
                            "type": "integer",
                            "minimum": 1
                          },
                          "frameId": {
                            "type": "integer",
                            "minimum": 0
                          }
                        }
                        """,
                        null));
        return tools;
    }

    private static JsonObject tool(
            String name, String description, String properties, String required) {
        JsonObject tool = new JsonObject();
        tool.addProperty("name", name);
        tool.addProperty("description", description);
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("additionalProperties", false);
        schema.add("properties", JsonParser.parseString(properties));
        if (required != null) {
            JsonArray fields = new JsonArray();
            fields.add(required);
            schema.add("required", fields);
        }
        tool.add("inputSchema", schema);
        return tool;
    }

    CompletableFuture<String> execute(String name, JsonObject args) {
        JsonObject schema = null;
        for (JsonElement value : definitions())
            if (name.equals(DshJson.string(value.getAsJsonObject(), "name")))
                schema = value.getAsJsonObject().getAsJsonObject("inputSchema");
        if (schema == null)
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Unknown debugger tool"));
        JsonObject properties = schema.getAsJsonObject("properties");
        for (var entry : args.entrySet()) {
            if (!properties.has(entry.getKey()))
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException(
                                "Unsupported debugger argument: " + entry.getKey()));
            JsonObject field = properties.getAsJsonObject(entry.getKey());
            String type = DshJson.string(field, "type");
            JsonElement value = entry.getValue();
            boolean valid =
                    value.isJsonPrimitive()
                            && switch (type) {
                                case "string" ->
                                        value.getAsJsonPrimitive().isString()
                                                && value.getAsString().length() <= 8192;
                                case "boolean" -> value.getAsJsonPrimitive().isBoolean();
                                case "integer" ->
                                        value.getAsJsonPrimitive().isNumber()
                                                && Double.isFinite(value.getAsDouble())
                                                && value.getAsDouble()
                                                        == Math.floor(value.getAsDouble())
                                                && value.getAsDouble() >= 0
                                                && value.getAsDouble() <= 1_000_000;
                                default -> false;
                            };
            if (!valid)
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException(
                                "Invalid debugger argument: " + entry.getKey()));
        }
        CompletableFuture<String> result =
                this.<CompletableFuture<String>>onEdt(
                                () ->
                                        switch (name) {
                                            case "debug_start" -> start(args);
                                            case "debug_breakpoint" ->
                                                    CompletableFuture.completedFuture(
                                                            breakpoint(args));
                                            case "debug_control" -> control(args);
                                            case "debug_context" -> readContext(args);
                                            default ->
                                                    CompletableFuture.failedFuture(
                                                            new IllegalArgumentException(
                                                                    "Unknown debugger tool"));
                                        })
                        .thenCompose(value -> value)
                        .orTimeout(49, TimeUnit.SECONDS);
        pending.add(result);
        result.whenComplete((value, error) -> pending.remove(result));
        return result;
    }

    private <T> CompletableFuture<T> onEdt(Supplier<T> task) {
        CompletableFuture<T> result = new CompletableFuture<>();
        ApplicationManager.getApplication()
                .invokeLater(
                        () -> {
                            if (result.isDone()) return;
                            if (closed || project.isDisposed()) {
                                result.completeExceptionally(
                                        new IllegalStateException("Debugger bridge closed"));
                                return;
                            }
                            try {
                                result.complete(task.get());
                            } catch (Exception error) {
                                result.completeExceptionally(error);
                            }
                        });
        pending.add(result);
        result.whenComplete((value, error) -> pending.remove(result));
        return result.orTimeout(5, TimeUnit.SECONDS);
    }

    private CompletableFuture<String> start(JsonObject args) {
        List<RunnerAndConfigurationSettings> configurations =
                RunManager.getInstance(project).getAllSettings();
        String wanted = DshJson.strictString(args, "configurationName");
        List<RunnerAndConfigurationSettings> matches =
                configurations.stream()
                        .filter(value -> wanted == null || wanted.equals(value.getName()))
                        .toList();
        if (matches.size() != 1)
            throw new IllegalArgumentException(
                    "Choose one existing run configuration: "
                            + configurations.stream()
                                    .map(RunnerAndConfigurationSettings::getName)
                                    .toList());
        RunnerAndConfigurationSettings settings = matches.get(0);
        boolean noDebug = DshJson.bool(args, "noDebug", false);
        try {
            var executor =
                    noDebug
                            ? DefaultRunExecutor.getRunExecutorInstance()
                            : DefaultDebugExecutor.getDebugExecutorInstance();
            var builder = ExecutionEnvironmentBuilder.create(executor, settings);
            CompletableFuture<String> result = new CompletableFuture<>();
            var environment =
                    builder.build(
                            descriptor -> {
                                if (closed) {
                                    result.completeExceptionally(
                                            new IllegalStateException("Debugger bridge closed"));
                                    return;
                                }
                                XDebugSession started =
                                        noDebug
                                                ? null
                                                : XDebuggerManager.getInstance(project)
                                                        .getDebugSession(
                                                                descriptor.getExecutionConsole());
                                if (started == null)
                                    result.complete(
                                            "Started existing configuration "
                                                    + settings.getName()
                                                    + (noDebug
                                                            ? " without debugging."
                                                            : "; debugger session is still initializing."));
                                else
                                    result.complete(
                                            "Started debug session "
                                                    + settings.getName()
                                                    + " (sessionId "
                                                    + id(started)
                                                    + ").");
                            });
            var events = project.getMessageBus().connect();
            events.subscribe(
                    com.intellij.execution.ExecutionManager.EXECUTION_TOPIC,
                    new com.intellij.execution.ExecutionListener() {
                        private boolean matches(
                                com.intellij.execution.runners.ExecutionEnvironment started) {
                            return started.getRunProfile() == settings.getConfiguration()
                                    && started.getExecutor().getId().equals(executor.getId());
                        }

                        @Override
                        public void processNotStarted(
                                String executorId,
                                com.intellij.execution.runners.ExecutionEnvironment started) {
                            if (matches(started))
                                result.completeExceptionally(
                                        new IllegalStateException(
                                                "IDE declined the existing run configuration"));
                        }

                        @Override
                        public void processNotStarted(
                                String executorId,
                                com.intellij.execution.runners.ExecutionEnvironment started,
                                Throwable error) {
                            if (matches(started))
                                result.completeExceptionally(
                                        error == null
                                                ? new IllegalStateException(
                                                        "IDE declined the existing run configuration")
                                                : error);
                        }

                        @Override
                        public void processStarted(
                                String executorId,
                                com.intellij.execution.runners.ExecutionEnvironment started,
                                com.intellij.execution.process.ProcessHandler handler) {
                            if (!matches(started)) return;
                            onEdt(
                                            () -> {
                                                XDebugSession[] live =
                                                        XDebuggerManager.getInstance(project)
                                                                .getDebugSessions();
                                                for (XDebugSession session : live)
                                                    if (session.getRunProfile()
                                                            == settings.getConfiguration())
                                                        return "Started debug session "
                                                                + settings.getName()
                                                                + " (sessionId "
                                                                + id(session)
                                                                + ").";
                                                return "Started existing configuration "
                                                        + settings.getName()
                                                        + "; query the debugger for its pause.";
                                            })
                                    .whenComplete(
                                            (value, error) -> {
                                                if (error == null) result.complete(value);
                                                else result.completeExceptionally(error);
                                            });
                        }
                    });
            pending.add(result);
            result.whenComplete(
                    (value, error) -> {
                        events.disconnect();
                        pending.remove(result);
                    });
            com.intellij.execution.ProgramRunnerUtil.executeConfiguration(environment, false, true);
            return result.orTimeout(30, TimeUnit.SECONDS);
        } catch (com.intellij.execution.ExecutionException error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private String id(XDebugSession session) {
        ids.keySet()
                .removeIf(
                        known ->
                                java.util.Arrays.stream(
                                                XDebuggerManager.getInstance(project)
                                                        .getDebugSessions())
                                        .noneMatch(live -> live == known));
        return ids.computeIfAbsent(session, ignored -> UUID.randomUUID().toString());
    }

    private XDebugSession session(JsonObject args) {
        XDebugSession[] live = XDebuggerManager.getInstance(project).getDebugSessions();
        String requested = DshJson.strictString(args, "sessionId");
        if (requested != null)
            for (XDebugSession session : live)
                if (requested.equals(id(session)) || requested.equals(session.getSessionName()))
                    return session;
        if (requested == null && live.length == 1) return live[0];
        throw new IllegalArgumentException(
                "Choose a live sessionId: "
                        + java.util.Arrays.stream(live)
                                .map(value -> value.getSessionName() + "=" + id(value))
                                .toList());
    }

    private String breakpoint(JsonObject args) {
        String action = DshJson.strictString(args, "action");
        var manager = XDebuggerManager.getInstance(project).getBreakpointManager();
        if ("list".equals(action)) {
            JsonArray rows = new JsonArray();
            for (XBreakpoint<?> value : manager.getAllBreakpoints())
                if (value instanceof XLineBreakpoint<?> breakpoint && rows.size() < 60) {
                    JsonObject row = new JsonObject();
                    row.addProperty("file", breakpoint.getFileUrl());
                    row.addProperty("line", breakpoint.getLine() + 1);
                    row.addProperty("enabled", breakpoint.isEnabled());
                    row.addProperty("verified", "unavailable in public XDebugger API");
                    if (breakpoint.getConditionExpression() != null)
                        row.addProperty(
                                "condition", breakpoint.getConditionExpression().getExpression());
                    rows.add(row);
                }
            return rows.toString();
        }
        if (!Set.of("add", "remove").contains(action == null ? "" : action))
            throw new IllegalArgumentException("action must be add/remove/list");
        String fileName = DshJson.strictString(args, "file");
        int line = DshJson.integer(args, "line", 0) - 1;
        if (fileName == null || line < 0 || project.getBasePath() == null)
            throw new IllegalArgumentException("file and positive 1-based line required");
        Path root = Path.of(project.getBasePath()).toAbsolutePath().normalize();
        Path path = Path.of(fileName);
        if (!path.isAbsolute()) path = root.resolve(path);
        path = path.normalize();
        try {
            if (!path.toRealPath().startsWith(root.toRealPath()))
                throw new IllegalArgumentException("Breakpoint file is outside this project");
        } catch (java.io.IOException missing) {
            throw new IllegalArgumentException("Breakpoint file unavailable");
        }
        VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
        if (file == null) throw new IllegalArgumentException("Breakpoint file unavailable");
        boolean valid =
                ReadAction.compute(
                        () -> {
                            var document = FileDocumentManager.getInstance().getDocument(file);
                            return document != null && line < document.getLineCount();
                        });
        if (!valid) throw new IllegalArgumentException("Breakpoint line is outside this file");
        return WriteAction.compute(
                () -> {
                    List<XLineBreakpoint<?>> existing = new ArrayList<>();
                    for (XBreakpoint<?> candidate : manager.getAllBreakpoints())
                        if (candidate instanceof XLineBreakpoint<?> value
                                && value.getFileUrl().equals(file.getUrl())
                                && value.getLine() == line) existing.add(value);
                    if ("remove".equals(action)) {
                        existing.forEach(manager::removeBreakpoint);
                        return "Removed " + existing.size() + " source breakpoint(s).";
                    }
                    XLineBreakpoint<?> breakpoint;
                    if (!existing.isEmpty()) breakpoint = existing.get(0);
                    else {
                        List<XLineBreakpointType<?>> types = new ArrayList<>();
                        for (XBreakpointType<?, ?> value :
                                XBreakpointType.EXTENSION_POINT_NAME.getExtensionList())
                            if (value instanceof XLineBreakpointType<?> type
                                    && type.canPutAt(file, line, project)) types.add(type);
                        types.sort(
                                java.util.Comparator.comparingInt(
                                                (XLineBreakpointType<?> type) -> type.getPriority())
                                        .reversed());
                        if (types.isEmpty())
                            throw new IllegalArgumentException(
                                    "Installed debugger cannot place a source breakpoint here");
                        breakpoint = addLine(types.get(0), file, line);
                    }
                    breakpoint.setEnabled(DshJson.bool(args, "enabled", true));
                    if (args.has("condition"))
                        breakpoint.setCondition(DshJson.strictString(args, "condition"));
                    if (args.has("logExpression")) {
                        breakpoint.setLogExpression(DshJson.strictString(args, "logExpression"));
                        breakpoint.setSuspendPolicy(
                                com.intellij.xdebugger.breakpoints.SuspendPolicy.NONE);
                    }
                    return "Source breakpoint configured at "
                            + file.getPath()
                            + ":"
                            + (line + 1)
                            + ". Verification status is debugger-specific.";
                });
    }

    private <P extends XBreakpointProperties> XLineBreakpoint<P> addLine(
            XLineBreakpointType<P> type, VirtualFile file, int line) {
        return XDebuggerManager.getInstance(project)
                .getBreakpointManager()
                .addLineBreakpoint(
                        type, file.getUrl(), line, type.createBreakpointProperties(file, line));
    }

    private CompletableFuture<String> control(JsonObject args) {
        XDebugSession session = session(args);
        String action = DshJson.strictString(args, "action");
        if ("wait".equals(action))
            return waitForPause(session, DshJson.integer(args, "timeoutMs", 10000));
        if ("threads".equals(action))
            return executionStacks(session)
                    .thenApply(
                            stacks -> {
                                StringBuilder result = new StringBuilder();
                                for (XExecutionStack stack : stacks)
                                    result.append("threadId ")
                                            .append(threadId(stack))
                                            .append(": ")
                                            .append(stack.getDisplayName())
                                            .append('\n');
                                return result.length() == 0
                                        ? "No paused execution stacks. The generic API exposes threads while suspended."
                                        : result.toString();
                            });
        return selectThread(session, args)
                .thenCompose(
                        ignored ->
                                onEdt(
                                        () -> {
                                            if (!session.getDebugProcess()
                                                    .checkCanPerformCommands())
                                                throw new IllegalStateException(
                                                        "Debugger is not ready for control");
                                            if (!"continue".equals(action)
                                                    && !"pause".equals(action)
                                                    && !session.isSuspended())
                                                throw new IllegalStateException(
                                                        "Pause before stepping");
                                            switch (action == null ? "" : action) {
                                                case "continue" -> session.resume();
                                                case "pause" -> session.pause();
                                                case "next" -> session.stepOver(false);
                                                case "stepIn" -> session.stepInto();
                                                case "stepOut" -> session.stepOut();
                                                default ->
                                                        throw new IllegalArgumentException(
                                                                "Unknown debugger action");
                                            }
                                            return "Requested "
                                                    + action
                                                    + " in "
                                                    + session.getSessionName()
                                                    + ". Use wait/context to observe the result.";
                                        }));
    }

    private synchronized int threadId(XExecutionStack stack) {
        if (threads.size() > 256) threads.clear();
        return threads.computeIfAbsent(stack, ignored -> nextThreadId++);
    }

    private CompletableFuture<List<XExecutionStack>> executionStacks(XDebugSession session) {
        XSuspendContext suspended = session.getSuspendContext();
        if (suspended == null) return CompletableFuture.completedFuture(List.of());
        CompletableFuture<List<XExecutionStack>> result = new CompletableFuture<>();
        List<XExecutionStack> stacks = new ArrayList<>();
        suspended.computeExecutionStacks(
                new XSuspendContext.XExecutionStackContainer() {
                    @Override
                    public void addExecutionStack(
                            List<? extends XExecutionStack> items, boolean last) {
                        synchronized (stacks) {
                            stacks.addAll(
                                    items.subList(
                                            0,
                                            Math.min(
                                                    items.size(),
                                                    Math.max(0, 60 - stacks.size()))));
                            if (last) result.complete(List.copyOf(stacks));
                        }
                    }

                    @Override
                    public void errorOccurred(String message) {
                        result.completeExceptionally(new IllegalStateException(message));
                    }

                    @Override
                    public boolean isObsolete() {
                        return closed
                                || session.getSuspendContext() != suspended
                                || result.isDone();
                    }
                });
        return result.orTimeout(5, TimeUnit.SECONDS);
    }

    private CompletableFuture<Void> selectThread(XDebugSession session, JsonObject args) {
        if (!args.has("threadId")) return CompletableFuture.completedFuture(null);
        int requested = DshJson.integer(args, "threadId", -1);
        return executionStacks(session)
                .thenCompose(
                        stacks ->
                                onEdt(
                                        () -> {
                                            for (XExecutionStack stack : stacks)
                                                if (threadId(stack) == requested) {
                                                    if (stack.getTopFrame() == null)
                                                        throw new IllegalArgumentException(
                                                                "Thread has no available top frame");
                                                    session.setCurrentStackFrame(
                                                            stack, stack.getTopFrame(), true);
                                                    return null;
                                                }
                                            throw new IllegalArgumentException(
                                                    "Thread identity expired; list paused threads again");
                                        }));
    }

    private CompletableFuture<String> readContext(JsonObject args) {
        XDebugSession session = session(args);
        return selectThread(session, args)
                .thenCompose(
                        ignored -> {
                            if (!args.has("frameId")) return context.capture(session);
                            int frameId = DshJson.integer(args, "frameId", -1);
                            return onEdt(() -> selectFrame(session, frameId))
                                    .thenCompose(value -> value)
                                    .thenCompose(value -> context.capture(session));
                        });
    }

    private CompletableFuture<Void> selectFrame(XDebugSession session, int index) {
        if (index < 0 || index >= 10 || !session.isSuspended())
            throw new IllegalArgumentException("frameId must be 0..9 in the paused stack");
        XExecutionStack stack = session.getSuspendContext().getActiveExecutionStack();
        CompletableFuture<Void> result = new CompletableFuture<>();
        stack.computeStackFrames(
                0,
                new XExecutionStack.XStackFrameContainer() {
                    private int offset;

                    @Override
                    public void addStackFrames(
                            List<? extends com.intellij.xdebugger.frame.XStackFrame> frames,
                            boolean last) {
                        if (index >= offset && index < offset + frames.size()) {
                            var frame = frames.get(index - offset);
                            onEdt(
                                            () -> {
                                                session.setCurrentStackFrame(stack, frame, true);
                                                return null;
                                            })
                                    .whenComplete(
                                            (v, e) -> {
                                                if (e == null) result.complete(null);
                                                else result.completeExceptionally(e);
                                            });
                        } else if (last)
                            result.completeExceptionally(
                                    new IllegalArgumentException("Frame no longer available"));
                        offset += frames.size();
                    }

                    @Override
                    public void errorOccurred(String error) {
                        result.completeExceptionally(new IllegalStateException(error));
                    }

                    @Override
                    public boolean isObsolete() {
                        return closed || !session.isSuspended() || result.isDone();
                    }
                });
        return result.orTimeout(5, TimeUnit.SECONDS);
    }

    private CompletableFuture<String> waitForPause(XDebugSession session, int timeout) {
        if (session.isSuspended())
            return CompletableFuture.completedFuture("Session already paused. Read debug_context.");
        CompletableFuture<String> result = new CompletableFuture<>();
        XDebugSessionListener listener =
                new XDebugSessionListener() {
                    @Override
                    public void sessionPaused() {
                        result.complete("Session paused. Read debug_context.");
                    }

                    @Override
                    public void sessionStopped() {
                        result.complete("Debug session terminated before pausing.");
                    }
                };
        session.addSessionListener(listener);
        var expiry =
                timer.schedule(
                        () ->
                                result.complete(
                                        "No pause within timeout; target may still be running."),
                        Math.max(250, Math.min(45000, timeout)),
                        TimeUnit.MILLISECONDS);
        result.whenComplete(
                (value, error) -> {
                    expiry.cancel(false);
                    ApplicationManager.getApplication()
                            .invokeLater(() -> session.removeSessionListener(listener));
                });
        pending.add(result);
        result.whenComplete((value, error) -> pending.remove(result));
        if (session.isSuspended()) result.complete("Session paused. Read debug_context.");
        return result;
    }

    @Override
    public void close() {
        closed = true;
        timer.shutdownNow();
        for (var future : pending)
            future.completeExceptionally(new IllegalStateException("Debugger bridge closed"));
        pending.clear();
    }
}
