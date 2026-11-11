package top.harcochen.dsh;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import top.harcochen.dsh.remote.DshRemoteService;
import top.harcochen.dsh.remote.DshRemoteState;

/** Optional Forge reads and worktree requests through the Host's sandbox-aware API. */
final class DshForgeController {
    private final Project project;
    private final DshRemoteService remote;
    private final ExecutorService operations;
    private final Consumer<String> notify;

    DshForgeController(
            Project project,
            DshRemoteService remote,
            ExecutorService operations,
            Consumer<String> notify) {
        this.project = project;
        this.remote = remote;
        this.operations = operations;
        this.notify = notify;
    }

    void open(String session) {
        if (session == null) {
            notify.accept(DshBundle.message("dsh.review.no.session"));
            return;
        }
        ApplicationManager.getApplication()
                .invokeLater(
                        () -> {
                            if (!project.isDisposed()) new Panel(session).show();
                        });
    }

    private final class Panel extends DialogWrapper {
        private final String session;
        private final long generation;
        private final JTextArea overview = new JTextArea();
        private final DefaultListModel<String> labels = new DefaultListModel<>();
        private final JList<String> requests = new JList<>(labels);
        private final List<JsonObject> rows = new ArrayList<>();
        private AutoCloseable stream;
        private volatile boolean closed;
        private volatile JsonObject latest;
        private final Consumer<DshRemoteState.Snapshot> listener =
                ignored -> {
                    if (!current())
                        ApplicationManager.getApplication()
                                .invokeLater(
                                        () -> {
                                            if (!closed) close(CANCEL_EXIT_CODE);
                                        });
                };

        Panel(String session) {
            super(project, false);
            this.session = session;
            this.generation = remote.snapshot().generation;
            setTitle(DshBundle.message("dsh.forge.title"));
            setModal(false);
            overview.setEditable(false);
            overview.setLineWrap(true);
            overview.setWrapStyleWord(true);
            overview.setText(DshBundle.message("dsh.review.loading"));
            init();
            remote.addListener(listener);
            stream =
                    remote.watchFeature(
                            "forgePanel/follow",
                            this::args,
                            frame ->
                                    ApplicationManager.getApplication()
                                            .invokeLater(() -> render(frame)),
                            failure ->
                                    ApplicationManager.getApplication()
                                            .invokeLater(
                                                    () -> {
                                                        if (!closed)
                                                            overview.setText(
                                                                    DshBundle.message(
                                                                                    "dsh.forge.unavailable")
                                                                            + (failure == null
                                                                                    ? ""
                                                                                    : "\n"
                                                                                            + DshJson
                                                                                                    .stringOr(
                                                                                                            failure,
                                                                                                            "message",
                                                                                                            "")));
                                                    }));
        }

        private JsonObject args() {
            JsonObject args = new JsonObject();
            args.addProperty("sessionId", session);
            return args;
        }

        private boolean current() {
            return !closed
                    && !project.isDisposed()
                    && remote.snapshot().generation == generation
                    && remote.access().allows("forgePanel/follow", "session", session);
        }

        private void assertCurrent() {
            if (!current())
                throw new IllegalStateException(DshBundle.message("dsh.runtime.changed"));
        }

        private JsonElement call(String method, JsonObject args) {
            assertCurrent();
            return remote.callFeature("forgePanel/" + method, args);
        }

        @Override
        protected JComponent createCenterPanel() {
            JPanel content = new JPanel(new BorderLayout(8, 8));
            content.add(new JScrollPane(overview), BorderLayout.CENTER);
            JScrollPane list = new JScrollPane(requests);
            list.setPreferredSize(new Dimension(800, 130));
            content.add(list, BorderLayout.SOUTH);
            JPanel buttons = new JPanel(new GridLayout(0, 3, 6, 6));
            button(buttons, "refresh", () -> background(() -> call("refresh", args())));
            button(buttons, "local", () -> chooseDiff(false));
            button(buttons, "pull.diff", () -> chooseDiff(true));
            button(buttons, "checks", () -> readRequest("changeRequestChecks"));
            button(buttons, "reviews", () -> readRequest("changeRequestReview"));
            button(buttons, "track", this::track);
            button(buttons, "worktree", this::requestWorktree);
            button(buttons, "cancel.worktree", () -> confirmMutation("cancelWorktree"));
            button(buttons, "retry.worktree", () -> confirmMutation("retryWorktree"));
            content.add(buttons, BorderLayout.NORTH);
            content.setPreferredSize(new Dimension(900, 600));
            return content;
        }

        private void button(JPanel panel, String key, Runnable action) {
            JButton button = new JButton(DshBundle.message("dsh.forge." + key));
            button.addActionListener(ignored -> action.run());
            panel.add(button);
        }

        @Override
        protected javax.swing.Action[] createActions() {
            return new javax.swing.Action[] {getCancelAction()};
        }

        private void render(JsonObject state) {
            if (!current()) return;
            if (DshJson.strictString(state, "directory") == null
                    || !state.has("changeRequests")
                    || !state.get("changeRequests").isJsonArray()
                    || state.getAsJsonArray("changeRequests").size() > 1000) {
                overview.setText(DshBundle.message("dsh.forge.unavailable"));
                return;
            }
            latest = state.deepCopy();
            int selected = requests.getSelectedIndex();
            labels.clear();
            rows.clear();
            StringBuilder summary = new StringBuilder(DshJson.string(state, "directory"));
            summary.append("\n\n").append(DshBundle.message("dsh.forge.checkout")).append('\n');
            summary.append(checkoutSummary(state.get("checkout")));
            summary.append("\n\n").append(DshBundle.message("dsh.forge.worktree")).append('\n');
            summary.append(worktreeSummary(state.get("worktree")));
            for (JsonElement raw : state.getAsJsonArray("changeRequests")) {
                if (!raw.isJsonObject()) continue;
                JsonObject row = raw.getAsJsonObject();
                if (DshJson.strictString(row, "url") == null) continue;
                rows.add(row.deepCopy());
                JsonObject snapshot =
                        row.has("snapshot") && row.get("snapshot").isJsonObject()
                                ? row.getAsJsonObject("snapshot")
                                : new JsonObject();
                labels.addElement(
                        DshJson.stringOr(snapshot, "title", DshJson.string(row, "url"))
                                + " · "
                                + DshJson.stringOr(snapshot, "state", "pending"));
                summary.append("\n\n")
                        .append(DshJson.string(row, "url"))
                        .append('\n')
                        .append(DshJson.stringOr(snapshot, "title", ""))
                        .append("\n")
                        .append(DshJson.stringOr(snapshot, "state", ""))
                        .append(" · ")
                        .append(DshJson.stringOr(snapshot, "headBranch", ""))
                        .append(" → ")
                        .append(DshJson.stringOr(snapshot, "baseBranch", ""))
                        .append("\n")
                        .append(DshBundle.message("dsh.forge.checks"))
                        .append(" · ")
                        .append(DshJson.stringOr(snapshot, "checksRollup", ""))
                        .append("\n")
                        .append(DshBundle.message("dsh.forge.reviews"))
                        .append(" · ")
                        .append(DshJson.stringOr(snapshot, "reviewDecision", ""));
            }
            overview.setText(summary.substring(0, Math.min(summary.length(), 150000)));
            overview.setCaretPosition(0);
            if (!rows.isEmpty())
                requests.setSelectedIndex(Math.max(0, Math.min(selected, rows.size() - 1)));
        }

        private JsonObject selectedRequest() {
            int index = requests.getSelectedIndex();
            if (index < 0 || index >= rows.size()) {
                notify.accept(DshBundle.message("dsh.forge.select"));
                return null;
            }
            return rows.get(index).deepCopy();
        }

        private void readRequest(String method) {
            JsonObject row = selectedRequest();
            if (row == null) return;
            JsonObject snapshot =
                    row.has("snapshot") && row.get("snapshot").isJsonObject()
                            ? row.getAsJsonObject("snapshot")
                            : null;
            if (snapshot == null || DshJson.strictString(snapshot, "headSha") == null) return;
            JsonObject args = args();
            args.addProperty("url", DshJson.string(row, "url"));
            args.addProperty("headSha", DshJson.string(snapshot, "headSha"));
            if (method.equals("changeRequestReview")) args.addProperty("fresh", true);
            background(() -> showDetail(describe(call(method, args))));
        }

        private void chooseDiff(boolean pull) {
            JsonObject args = args();
            String method;
            if (pull) {
                JsonObject row = selectedRequest();
                if (row == null) return;
                args.addProperty("url", DshJson.string(row, "url"));
                args.add("commits", JsonNull.INSTANCE);
                method = "changeRequestDiff";
            } else {
                String[] kinds = {"uncommitted", "unstaged", "staged", "branch"};
                int choice =
                        DshChoiceDialog.show(
                                project,
                                DshBundle.message("dsh.forge.local"),
                                DshBundle.message("dsh.forge.title"),
                                Messages.getQuestionIcon(),
                                kinds,
                                kinds[0]);
                if (choice < 0) return;
                JsonObject scope = new JsonObject();
                scope.addProperty("kind", kinds[choice]);
                if (choice == 3) {
                    scope.add("base", JsonNull.INSTANCE);
                    scope.add("commits", JsonNull.INSTANCE);
                }
                args.add("scope", scope);
                method = "localDiff";
            }
            background(
                    () -> {
                        JsonElement value = call(method, args);
                        if (!value.isJsonObject()
                                || !value.getAsJsonObject().has("files")
                                || !value.getAsJsonObject().get("files").isJsonArray())
                            throw new IllegalStateException("Invalid Forge diff");
                        StringBuilder patch = new StringBuilder();
                        for (JsonElement raw : value.getAsJsonObject().getAsJsonArray("files")) {
                            if (!raw.isJsonObject()) continue;
                            JsonObject file = raw.getAsJsonObject();
                            patch.append("diff -- ")
                                    .append(DshJson.string(file, "path"))
                                    .append('\n');
                            if (DshJson.bool(file, "binary", false)
                                    || DshJson.bool(file, "oversized", false)) {
                                patch.append(DshBundle.message("dsh.files.binary")).append('\n');
                                continue;
                            }
                            if (!file.has("hunks") || !file.get("hunks").isJsonArray()) continue;
                            for (JsonElement rawHunk : file.getAsJsonArray("hunks")) {
                                JsonObject hunk = rawHunk.getAsJsonObject();
                                patch.append("@@ -")
                                        .append(hunk.get("oldStart"))
                                        .append(',')
                                        .append(hunk.get("oldLines"))
                                        .append(" +")
                                        .append(hunk.get("newStart"))
                                        .append(',')
                                        .append(hunk.get("newLines"))
                                        .append(" @@\n");
                                for (JsonElement line : hunk.getAsJsonArray("lines"))
                                    patch.append(line.getAsString()).append('\n');
                            }
                            if (patch.length() > 8 * 1024 * 1024)
                                throw new IllegalStateException(
                                        DshBundle.message("dsh.files.too.large"));
                        }
                        showDetail(patch.toString());
                    });
        }

        private void track() {
            String url =
                    Messages.showInputDialog(
                            project,
                            DshBundle.message("dsh.forge.track"),
                            DshBundle.message("dsh.forge.title"),
                            Messages.getQuestionIcon());
            if (url == null || !DshWebviewActionSanitizer.isSafeExternalUrl(url)) return;
            JsonObject args = args();
            args.addProperty("url", url.strip());
            background(() -> call("track", args));
        }

        private void requestWorktree() {
            JsonObject state = latest;
            if (state == null
                    || !state.has("worktree")
                    || !state.get("worktree").isJsonObject()
                    || !DshJson.bool(state.getAsJsonObject("worktree"), "editable", false)) {
                notify.accept(DshBundle.message("dsh.forge.worktree.locked"));
                return;
            }
            String base =
                    Messages.showInputDialog(
                            project,
                            DshBundle.message("dsh.forge.base"),
                            DshBundle.message("dsh.forge.worktree"),
                            Messages.getQuestionIcon());
            if (base == null) return;
            if (Messages.showYesNoDialog(
                            project,
                            DshBundle.message("dsh.forge.worktree.confirm"),
                            DshBundle.message("dsh.forge.worktree"),
                            Messages.getQuestionIcon())
                    != Messages.YES) return;
            JsonObject args = args();
            args.add(
                    "from",
                    base.isBlank()
                            ? JsonNull.INSTANCE
                            : new com.google.gson.JsonPrimitive(base.strip()));
            background(() -> call("requestWorktree", args));
        }

        private void confirmMutation(String method) {
            if (Messages.showYesNoDialog(
                            project,
                            DshBundle.message("dsh.forge.worktree.confirm"),
                            DshBundle.message("dsh.forge.worktree"),
                            Messages.getQuestionIcon())
                    == Messages.YES) background(() -> call(method, args()));
        }

        private void background(Runnable task) {
            operations.execute(
                    () -> {
                        try {
                            assertCurrent();
                            task.run();
                        } catch (Exception error) {
                            notify.accept(DshJson.message(error));
                        }
                    });
        }

        private void showDetail(String text) {
            ApplicationManager.getApplication()
                    .invokeLater(
                            () -> {
                                if (!current()) return;
                                DialogWrapper detail =
                                        new DialogWrapper(project, false) {
                                            {
                                                setTitle(DshBundle.message("dsh.forge.title"));
                                                setModal(false);
                                                init();
                                            }

                                            @Override
                                            protected JComponent createCenterPanel() {
                                                JTextArea area = new JTextArea(text);
                                                area.setEditable(false);
                                                JScrollPane scroll = new JScrollPane(area);
                                                scroll.setPreferredSize(new Dimension(850, 600));
                                                return scroll;
                                            }

                                            @Override
                                            protected javax.swing.Action[] createActions() {
                                                return new javax.swing.Action[] {getCancelAction()};
                                            }
                                        };
                                com.intellij.openapi.util.Disposer.register(
                                        getDisposable(), () -> detail.close(CANCEL_EXIT_CODE));
                                detail.show();
                            });
        }

        @Override
        protected void dispose() {
            closed = true;
            remote.removeListener(listener);
            if (stream != null)
                try {
                    stream.close();
                } catch (Exception ignored) {
                    /* released */
                }
            super.dispose();
        }
    }

    private static String checkoutSummary(JsonElement raw) {
        if (raw == null || !raw.isJsonObject()) return "";
        JsonObject state = raw.getAsJsonObject();
        if (!state.has("checkout") || !state.get("checkout").isJsonObject())
            return DshJson.stringOr(state, "message", DshJson.stringOr(state, "state", ""));
        JsonObject checkout = state.getAsJsonObject("checkout");
        return DshJson.stringOr(checkout, "root", "")
                + "\n"
                + DshJson.stringOr(checkout, "branch", "HEAD")
                + " · "
                + DshJson.stringOr(checkout, "commit", "");
    }

    private static String worktreeSummary(JsonElement raw) {
        if (raw == null || !raw.isJsonObject()) return "";
        JsonObject state = raw.getAsJsonObject();
        StringBuilder text = new StringBuilder();
        JsonElement created = state.get("created");
        if (created != null && created.isJsonObject()) {
            JsonObject value = created.getAsJsonObject();
            text.append(DshJson.stringOr(value, "path", ""))
                    .append("\n")
                    .append(DshJson.stringOr(value, "branch", ""));
        }
        JsonElement request = state.get("request");
        if (request != null && request.isJsonObject())
            text.append("\n")
                    .append(DshBundle.message("dsh.forge.request.pending"))
                    .append(" · ")
                    .append(DshJson.stringOr(request.getAsJsonObject(), "from", "HEAD"));
        JsonElement failure = state.get("failure");
        if (failure != null && failure.isJsonObject())
            text.append("\n").append(DshJson.stringOr(failure.getAsJsonObject(), "message", ""));
        String blocker = DshJson.strictString(state, "blocker");
        if (blocker != null) text.append("\n").append(blocker);
        if (text.isEmpty()) text.append(DshBundle.message("dsh.forge.no.worktree"));
        return text.toString().strip();
    }

    /** Read-only structured facts; bounded depth and text avoid unbounded native rendering. */
    private static String describe(JsonElement value) {
        StringBuilder text = new StringBuilder();
        describe(value, text, 0);
        return text.toString();
    }

    private static void describe(JsonElement value, StringBuilder text, int depth) {
        if (value == null || value.isJsonNull() || text.length() > 150000 || depth > 8) return;
        if (value.isJsonPrimitive()) text.append(value.getAsString());
        else if (value.isJsonArray())
            for (JsonElement item : value.getAsJsonArray()) {
                describe(item, text, depth + 1);
                text.append('\n');
            }
        else
            for (var entry : value.getAsJsonObject().entrySet()) {
                text.append("  ".repeat(depth)).append(entry.getKey()).append(": ");
                describe(entry.getValue(), text, depth + 1);
                text.append('\n');
            }
    }
}
