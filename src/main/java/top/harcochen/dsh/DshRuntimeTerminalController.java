package top.harcochen.dsh;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefJSQuery;
import java.awt.Dimension;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javax.swing.JComponent;
import top.harcochen.dsh.remote.DshRemoteService;
import top.harcochen.dsh.remote.DshRemoteState;
import top.harcochen.dsh.remote.DshRuntimeTerminal;

/** Runtime-owned PTYs, independent of IDE shell sessions and Agent Jobs. */
final class DshRuntimeTerminalController implements AutoCloseable {
    private final Project project;
    private final DshRemoteService remote;
    private final DshRuntimeService runtime;
    private final ExecutorService operations;
    private final Consumer<String> notify;
    private final Map<String, TerminalDialog> dialogs = new LinkedHashMap<>();
    private final Map<String, JsonObject> pendingCloses = new LinkedHashMap<>();
    private final Consumer<DshRemoteState.Snapshot> listener = this::changed;
    private volatile boolean disposed;
    private boolean opening;

    DshRuntimeTerminalController(
            Project project,
            DshRemoteService remote,
            DshRuntimeService runtime,
            ExecutorService operations,
            Consumer<String> notify) {
        this.project = project;
        this.remote = remote;
        this.runtime = runtime;
        this.operations = operations;
        this.notify = notify;
        remote.addListener(listener);
    }

    synchronized void open(String session) {
        if (session == null || opening || disposed) return;
        opening = true;
        String endpoint = runtime.getUrl();
        operations.execute(
                () -> {
                    try {
                        JsonObject args = new JsonObject();
                        args.addProperty("agentId", session);
                        JsonObject limits =
                                DshRuntimeTerminal.environment(
                                        remote.callFeature("terminal/environment", args));
                        JsonObject listArgs = new JsonObject();
                        listArgs.addProperty("sessionId", session);
                        JsonElement raw = remote.callFeature("terminal/list", listArgs);
                        if (!raw.isJsonArray() || raw.getAsJsonArray().size() > 1000)
                            throw new IllegalStateException("Invalid terminal list");
                        List<JsonObject> terminals = new ArrayList<>();
                        for (JsonElement value : raw.getAsJsonArray())
                            terminals.add(DshRuntimeTerminal.info(value));
                        ApplicationManager.getApplication()
                                .invokeLater(() -> choose(endpoint, session, limits, terminals));
                    } catch (Exception error) {
                        notify.accept(
                                DshBundle.message(
                                        "dsh.terminal.unavailable", DshJson.message(error)));
                    } finally {
                        synchronized (this) {
                            opening = false;
                        }
                    }
                });
    }

    private void choose(
            String endpoint, String session, JsonObject limits, List<JsonObject> terminals) {
        if (!current(endpoint) || disposed) return;
        List<String> labels = new ArrayList<>();
        labels.add(DshBundle.message("dsh.terminal.create"));
        for (JsonObject info : terminals)
            labels.add(
                    DshJson.string(info, "title")
                            + " · "
                            + DshJson.string(info, "state")
                            + " · "
                            + DshJson.string(info, "cwd"));
        int selected =
                DshChoiceDialog.show(
                        project,
                        DshBundle.message("dsh.terminal.choose"),
                        DshBundle.message("dsh.terminal.title"),
                        Messages.getQuestionIcon(),
                        labels.toArray(new String[0]),
                        labels.get(0));
        if (selected < 0 || !current(endpoint)) return;
        if (selected > 0) {
            show(endpoint, session, limits, terminals.get(selected - 1));
            return;
        }
        operations.execute(
                () -> {
                    try {
                        assertCurrent(endpoint);
                        JsonObject request = new JsonObject();
                        request.addProperty("id", java.util.UUID.randomUUID().toString());
                        request.addProperty(
                                "cols", Math.min(100, limits.get("maxCols").getAsInt()));
                        request.addProperty("rows", Math.min(30, limits.get("maxRows").getAsInt()));
                        JsonObject args = new JsonObject();
                        args.addProperty("agentId", session);
                        args.add("request", request);
                        JsonObject info =
                                DshRuntimeTerminal.info(
                                        remote.callFeature("terminal/create", args));
                        ApplicationManager.getApplication()
                                .invokeLater(() -> show(endpoint, session, limits, info));
                    } catch (Exception error) {
                        notify.accept(DshJson.message(error));
                    }
                });
    }

    private synchronized void show(
            String endpoint, String session, JsonObject limits, JsonObject info) {
        if (disposed || !current(endpoint)) return;
        String key = endpoint + "\n" + session + "\n" + DshJson.string(info, "id");
        TerminalDialog existing = dialogs.get(key);
        if (existing != null) {
            existing.toFront();
            existing.connect();
            return;
        }
        if (!DshBridge.isAvailable()) {
            notify.accept(DshBundle.message("dsh.jcef.not.available"));
            return;
        }
        TerminalDialog dialog = new TerminalDialog(endpoint, session, limits, info, key);
        dialogs.put(key, dialog);
        dialog.show();
    }

    private void changed(DshRemoteState.Snapshot snapshot) {
        ApplicationManager.getApplication()
                .invokeLater(
                        () -> {
                            List<TerminalDialog> active;
                            synchronized (this) {
                                active = new ArrayList<>(dialogs.values());
                            }
                            for (TerminalDialog dialog : active) {
                                if (!DshRemoteService.PHASE_CONNECTED.equals(snapshot.phase)
                                        || !remote.access()
                                                .allows(
                                                        "terminal/attach",
                                                        "session",
                                                        dialog.session)
                                        || !current(dialog.endpoint)) dialog.detach();
                                else if (dialog.generation != snapshot.generation
                                        || dialog.attachment == null) dialog.connect();
                            }
                            if (DshRemoteService.PHASE_CONNECTED.equals(snapshot.phase))
                                operations.execute(this::flushCloses);
                        });
    }

    private synchronized void flushCloses() {
        for (var entry : new ArrayList<>(pendingCloses.entrySet())) {
            JsonObject close = entry.getValue();
            if (!current(DshJson.string(close, "endpoint"))) continue;
            try {
                JsonObject args = close.deepCopy();
                args.remove("endpoint");
                remote.callFeature("terminal/close", args);
                pendingCloses.remove(entry.getKey());
            } catch (RuntimeException failure) {
                /* Retry against this exact Runtime after reconnect. */
            }
        }
    }

    private boolean current(String endpoint) {
        return endpoint != null && endpoint.equals(runtime.getUrl());
    }

    private void assertCurrent(String endpoint) {
        if (!current(endpoint))
            throw new IllegalStateException(DshBundle.message("dsh.runtime.changed"));
    }

    private final class TerminalDialog extends DialogWrapper {
        private final String endpoint;
        private final String session;
        private final String id;
        private final String key;
        private final JsonObject limits;
        private final JBCefBrowser browser = new JBCefBrowser();
        private final JBCefJSQuery query = JBCefJSQuery.create((JBCefBrowserBase) browser);
        private final ExecutorService input =
                Executors.newSingleThreadExecutor(
                        r -> {
                            Thread thread = new Thread(r, "dsh-terminal-input");
                            thread.setDaemon(true);
                            return thread;
                        });
        private DshRuntimeTerminal attachment;
        private boolean ready;
        private volatile boolean closed;
        private volatile long displayEpoch;
        private final java.util.ArrayDeque<JsonObject> output = new java.util.ArrayDeque<>();
        private int pendingOutputBytes;
        private boolean outputPosted;
        private long generation = -1;
        private int cols = 100;
        private int rows = 30;

        TerminalDialog(
                String endpoint, String session, JsonObject limits, JsonObject info, String key) {
            super(project, false);
            this.endpoint = endpoint;
            this.session = session;
            this.limits = limits;
            this.id = DshJson.string(info, "id");
            this.key = key;
            setTitle(
                    DshBundle.message("dsh.terminal.title")
                            + " · "
                            + DshJson.string(info, "title"));
            setModal(false);
            query.addHandler(
                    raw -> {
                        if (raw.length() > 2 * 1024 * 1024) return null;
                        try {
                            JsonElement value = JsonParser.parseString(raw);
                            if (value.isJsonObject())
                                ApplicationManager.getApplication()
                                        .invokeLater(() -> receive(value.getAsJsonObject()));
                        } catch (RuntimeException ignored) {
                            /* Ignore malformed browser messages. */
                        }
                        return null;
                    });
            Disposer.register(getDisposable(), browser);
            Disposer.register(getDisposable(), query);
            init();
            String script = resource("terminal.js");
            String html =
                    "<!doctype html><html><head><meta charset=\"UTF-8\"><style>"
                            + resource("terminal.css")
                            + "</style></head><body><div id=\"status\">DSH Runtime</div>"
                            + "<div id=\"terminal\"></div><script>window.dshTerminalPost=function(message){"
                            + query.inject("JSON.stringify(message)")
                            + "};</script><script>"
                            + script
                            + "</script></body></html>";
            browser.loadHTML(html);
        }

        @Override
        protected JComponent createCenterPanel() {
            browser.getComponent().setPreferredSize(new Dimension(900, 560));
            return browser.getComponent();
        }

        @Override
        protected javax.swing.Action[] createActions() {
            return new javax.swing.Action[] {getCancelAction()};
        }

        private void receive(JsonObject action) {
            if (closed || !current(endpoint)) return;
            String type = DshJson.string(action, "type");
            if ("ready".equals(type)) {
                ready = true;
                connect();
            } else if ("rendered".equals(type)) {
                long epoch = DshJson.longValue(action.get("epoch"), -1);
                int bytes = DshJson.integer(action, "bytes", -1);
                synchronized (this) {
                    if (epoch == displayEpoch && bytes >= 0 && bytes <= pendingOutputBytes)
                        pendingOutputBytes -= bytes;
                }
            } else if ("input".equals(type) && attachment != null)
                attachment.write(DshJson.strictString(action, "data"));
            else if ("resize".equals(type)) {
                cols = DshJson.integer(action, "cols", 100);
                rows = DshJson.integer(action, "rows", 30);
                if (attachment != null) attachment.resize(cols, rows);
            }
        }

        private void connect() {
            if (!ready
                    || closed
                    || !current(endpoint)
                    || !DshRemoteService.PHASE_CONNECTED.equals(remote.phase())) return;
            detach();
            generation = remote.snapshot().generation;
            long expectedGeneration = generation;
            long epoch = displayEpoch;
            DshRuntimeTerminal next =
                    new DshRuntimeTerminal(
                            remote,
                            input,
                            () ->
                                    !closed
                                            && current(endpoint)
                                            && remote.snapshot().generation == expectedGeneration
                                            && epoch == displayEpoch
                                            && remote.access()
                                                    .allows("terminal/attach", "session", session),
                            frame -> {
                                post(frame, epoch);
                                if ("snapshot".equals(DshJson.string(frame, "type")))
                                    ApplicationManager.getApplication()
                                            .invokeLater(
                                                    () -> {
                                                        if (epoch == displayEpoch
                                                                && attachment != null)
                                                            attachment.resize(cols, rows);
                                                    });
                            },
                            session,
                            id,
                            limits);
            attachment = next;
            next.start();
        }

        private void detach() {
            synchronized (this) {
                displayEpoch++;
                output.clear();
                pendingOutputBytes = 0;
            }
            if (attachment != null) attachment.close();
            attachment = null;
            JsonObject frame = new JsonObject();
            frame.addProperty("type", "state");
            frame.addProperty("writable", false);
            frame.addProperty("message", DshBundle.message("dsh.terminal.disconnected"));
            post(frame);
        }

        private void post(JsonObject frame) {
            post(frame, displayEpoch);
        }

        private synchronized void post(JsonObject frame, long epoch) {
            if (closed || epoch != displayEpoch) return;
            String data = DshJson.strictString(frame, "data");
            int bytes = data == null ? 0 : data.getBytes(StandardCharsets.UTF_8).length;
            int maximum =
                    Math.min(
                            16 * 1024 * 1024,
                            DshJson.integer(limits, "maxBufferedBytes", 2 * 1024 * 1024));
            if ((long) pendingOutputBytes + bytes > maximum || output.size() >= 4096) {
                output.clear();
                pendingOutputBytes = 0;
                throw new IllegalStateException(DshBundle.message("dsh.terminal.output.limit"));
            }
            JsonObject view = frame.deepCopy();
            view.addProperty("viewEpoch", epoch);
            output.addLast(view);
            pendingOutputBytes += bytes;
            if (outputPosted) return;
            outputPosted = true;
            ApplicationManager.getApplication().invokeLater(this::drainOutput);
        }

        private void drainOutput() {
            com.google.gson.JsonArray frames = new com.google.gson.JsonArray();
            long epoch;
            synchronized (this) {
                outputPosted = false;
                if (closed) {
                    output.clear();
                    return;
                }
                epoch = displayEpoch;
                while (!output.isEmpty()) frames.add(output.removeFirst());
            }
            if (epoch != displayEpoch) return;
            browser.getCefBrowser()
                    .executeJavaScript(
                            "if(window.dshTerminalReceive){for(const frame of "
                                    + frames.toString().replace("<", "\\u003c")
                                    + ")window.dshTerminalReceive(frame);}",
                            browser.getCefBrowser().getURL(),
                            0);
        }

        @Override
        protected void dispose() {
            closed = true;
            if (attachment != null) attachment.close();
            input.shutdownNow();
            synchronized (DshRuntimeTerminalController.this) {
                dialogs.remove(key);
                JsonObject close = new JsonObject();
                close.addProperty("endpoint", endpoint);
                close.addProperty("agentId", session);
                close.addProperty("id", id);
                pendingCloses.put(key, close);
            }
            if (!disposed) operations.execute(DshRuntimeTerminalController.this::flushCloses);
            super.dispose();
        }
    }

    private static String resource(String name) {
        try (var input =
                DshRuntimeTerminalController.class.getResourceAsStream("/webview/" + name)) {
            if (input == null) throw new IOException("Missing terminal resource " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    @Override
    public void close() {
        disposed = true;
        remote.removeListener(listener);
        ApplicationManager.getApplication()
                .invokeLater(
                        () -> {
                            List<TerminalDialog> active;
                            synchronized (this) {
                                active = new ArrayList<>(dialogs.values());
                            }
                            for (TerminalDialog dialog : active)
                                dialog.close(DialogWrapper.CANCEL_EXIT_CODE);
                            com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService()
                                    .execute(this::flushCloses);
                        });
    }
}
