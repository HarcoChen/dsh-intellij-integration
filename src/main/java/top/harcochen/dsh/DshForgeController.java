package top.harcochen.dsh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
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
        private boolean mutating;
        private final List<JButton> writeButtons = new ArrayList<>();
        private final Set<String> uncertain = new java.util.HashSet<>();
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
            button(buttons, "write", this::writeActions);
            button(
                    buttons,
                    "open.host",
                    () -> {
                        JsonObject row = selectedRequest();
                        if (row != null
                                && DshWebviewActionSanitizer.isSafeExternalUrl(
                                        DshJson.string(row, "url")))
                            BrowserUtil.browse(DshJson.string(row, "url"));
                    });
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
            if (key.equals("write")) writeButtons.add(button);
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

        private void writeActions() {
            JsonObject row = selectedRequest();
            if (row == null || mutating) return;
            String[] keys = {
                "submit.review",
                "inline.comment",
                "thread.action",
                "ready",
                "merge",
                "rerun",
                "request.reviewer",
                "update.branch",
                "pending.review"
            };
            String[] labels =
                    Arrays.stream(keys)
                            .map(key -> DshBundle.message("dsh.forge." + key))
                            .toArray(String[]::new);
            int selected =
                    DshChoiceDialog.show(
                            project,
                            DshJson.string(row, "url"),
                            DshBundle.message("dsh.forge.write"),
                            Messages.getQuestionIcon(),
                            labels,
                            labels[0]);
            if (selected < 0) return;
            JsonObject snapshot = requestSnapshot(row);
            if (snapshot == null) return;
            switch (selected) {
                case 0 -> submitReview(row, snapshot);
                case 1 -> inlineComment(row, snapshot);
                case 2 -> threadActions(row, snapshot);
                case 3 ->
                        mutate(
                                row,
                                "markReady",
                                requestArgs(row),
                                DshBundle.message("dsh.forge.ready"));
                case 4 -> merge(row, snapshot);
                case 5 -> {
                    if (!DshJson.bool(snapshot, "canRerunChecks", false)) {
                        unavailableAction();
                        return;
                    }
                    JsonObject request = new JsonObject();
                    request.addProperty("headSha", DshJson.string(snapshot, "headSha"));
                    JsonObject args = requestArgs(row);
                    args.add("request", request);
                    mutate(row, "rerunChecks", args, DshBundle.message("dsh.forge.rerun"));
                }
                case 6 -> requestReviewer(row, snapshot);
                case 7 -> {
                    List<String> methods =
                            methods(snapshot, "branchUpdates", Set.of("merge", "rebase"));
                    String method = chooseMethod("dsh.forge.update.branch", methods);
                    if (method == null) return;
                    JsonObject args = requestArgs(row);
                    args.addProperty("method", method);
                    args.addProperty("headSha", DshJson.string(snapshot, "headSha"));
                    mutate(
                            row,
                            "updateBranch",
                            args,
                            DshBundle.message("dsh.forge.update.branch") + " · " + method);
                }
                case 8 -> pendingReviewActions(row, snapshot);
                default -> throw new IllegalStateException("Unknown Forge action");
            }
        }

        private JsonObject requestSnapshot(JsonObject row) {
            JsonElement value = row.get("snapshot");
            if (value == null
                    || !value.isJsonObject()
                    || !DshJson.stringOr(value.getAsJsonObject(), "headSha", "")
                            .matches("(?:[0-9a-f]{40}|[0-9a-f]{64})")) {
                notify.accept(DshBundle.message("dsh.forge.refresh.first"));
                return null;
            }
            return value.getAsJsonObject();
        }

        private JsonObject requestArgs(JsonObject row) {
            JsonObject args = args();
            args.addProperty("url", DshJson.string(row, "url"));
            return args;
        }

        private void assertRequest(JsonObject row) {
            assertCurrent();
            JsonObject expected = requestSnapshot(row);
            JsonObject state = latest;
            if (expected != null && state != null)
                for (JsonElement value : state.getAsJsonArray("changeRequests")) {
                    if (!value.isJsonObject()) continue;
                    JsonObject live = value.getAsJsonObject();
                    if (!DshJson.string(row, "url").equals(DshJson.string(live, "url"))) continue;
                    JsonElement snapshot = live.get("snapshot");
                    if (snapshot != null
                            && snapshot.isJsonObject()
                            && DshJson.string(expected, "headSha")
                                    .equals(DshJson.string(snapshot.getAsJsonObject(), "headSha")))
                        return;
                }
            throw new IllegalStateException(DshBundle.message("dsh.forge.stale"));
        }

        private void submitReview(JsonObject row, JsonObject snapshot) {
            String[] events = {"comment", "approve", "request-changes"};
            String[] labels =
                    Arrays.stream(events)
                            .map(event -> DshBundle.message("dsh.forge.review." + event))
                            .toArray(String[]::new);
            int selected =
                    DshChoiceDialog.show(
                            project,
                            DshJson.string(row, "url"),
                            DshBundle.message("dsh.forge.submit.review"),
                            Messages.getQuestionIcon(),
                            labels,
                            labels[0]);
            if (selected < 0) return;
            String body = reviewBody("dsh.forge.review.body");
            if (body == null || (body.isBlank() && selected != 1)) return;
            JsonObject request = new JsonObject();
            request.addProperty("kind", "review");
            request.addProperty("event", events[selected]);
            request.addProperty("headSha", DshJson.string(snapshot, "headSha"));
            request.addProperty("body", body);
            request.add("comments", new JsonArray());
            JsonObject args = requestArgs(row);
            args.add("request", request);
            mutate(row, "submitReview", args, labels[selected] + "\n\n" + body);
        }

        private void inlineComment(JsonObject row, JsonObject snapshot) {
            JsonObject args = requestArgs(row);
            args.add("commits", JsonNull.INSTANCE);
            readForWrite(
                    row,
                    "changeRequestDiff",
                    args,
                    diff -> {
                        if (!DshJson.string(snapshot, "headSha")
                                .equals(DshJson.string(diff, "headSha"))) {
                            notify.accept(DshBundle.message("dsh.forge.stale"));
                            return;
                        }
                        List<String> paths = new ArrayList<>();
                        if (diff.has("files") && diff.get("files").isJsonArray())
                            for (JsonElement raw : diff.getAsJsonArray("files")) {
                                if (!raw.isJsonObject()) continue;
                                JsonObject file = raw.getAsJsonObject();
                                if (!DshJson.bool(file, "binary", false)
                                        && !DshJson.bool(file, "oversized", false)
                                        && DshJson.strictString(file, "path") != null)
                                    paths.add(DshJson.string(file, "path"));
                            }
                        if (paths.isEmpty()) {
                            unavailableAction();
                            return;
                        }
                        int file =
                                DshChoiceDialog.show(
                                        project,
                                        DshBundle.message("dsh.forge.inline.comment"),
                                        DshBundle.message("dsh.forge.title"),
                                        Messages.getQuestionIcon(),
                                        paths.toArray(String[]::new),
                                        paths.get(0));
                        if (file < 0) return;
                        String[] sides = {
                            DshBundle.message("dsh.diff.proposed"),
                            DshBundle.message("dsh.diff.before")
                        };
                        int side =
                                DshChoiceDialog.show(
                                        project,
                                        paths.get(file),
                                        DshBundle.message("dsh.forge.inline.comment"),
                                        Messages.getQuestionIcon(),
                                        sides,
                                        sides[0]);
                        if (side < 0) return;
                        String range =
                                Messages.showInputDialog(
                                        project,
                                        DshBundle.message("dsh.forge.line.range"),
                                        DshBundle.message("dsh.forge.inline.comment"),
                                        Messages.getQuestionIcon());
                        if (range == null) return;
                        String[] lines = range.strip().split("-", -1);
                        int start;
                        int end;
                        try {
                            if (lines.length < 1 || lines.length > 2)
                                throw new NumberFormatException();
                            start = Integer.parseInt(lines[0].strip());
                            end = lines.length == 1 ? start : Integer.parseInt(lines[1].strip());
                            if (start < 1 || end < start || end > 10000000)
                                throw new NumberFormatException();
                        } catch (NumberFormatException invalid) {
                            notify.accept(DshBundle.message("dsh.forge.line.invalid"));
                            return;
                        }
                        String body = reviewBody("dsh.forge.review.body");
                        if (body == null || body.isBlank()) return;
                        JsonObject comment = new JsonObject();
                        comment.addProperty("path", paths.get(file));
                        comment.addProperty("side", side == 0 ? "R" : "L");
                        comment.addProperty("startLine", start);
                        comment.addProperty("endLine", end);
                        comment.addProperty("body", body);
                        JsonObject request = new JsonObject();
                        request.addProperty("kind", "comment");
                        request.addProperty("headSha", DshJson.string(snapshot, "headSha"));
                        request.add("comment", comment);
                        JsonObject publish = requestArgs(row);
                        publish.add("request", request);
                        mutate(
                                row,
                                "submitReview",
                                publish,
                                paths.get(file)
                                        + " · "
                                        + sides[side]
                                        + " "
                                        + range
                                        + "\n\n"
                                        + body);
                    });
        }

        private void threadActions(JsonObject row, JsonObject snapshot) {
            JsonObject args = requestArgs(row);
            args.addProperty("headSha", DshJson.string(snapshot, "headSha"));
            args.addProperty("fresh", true);
            readForWrite(
                    row,
                    "changeRequestReview",
                    args,
                    review -> {
                        List<JsonObject> threads = new ArrayList<>();
                        List<String> labels = new ArrayList<>();
                        if (review.has("threads") && review.get("threads").isJsonArray())
                            for (JsonElement raw : review.getAsJsonArray("threads")) {
                                if (!raw.isJsonObject()) continue;
                                JsonObject thread = raw.getAsJsonObject();
                                if (DshJson.strictString(thread, "id") == null) continue;
                                if (!DshJson.bool(thread, "viewerCanReply", false)
                                        && !DshJson.bool(thread, "viewerCanResolve", false))
                                    continue;
                                threads.add(thread);
                                labels.add(
                                        DshJson.stringOr(thread, "path", "")
                                                + " · "
                                                + DshJson.string(thread, "id"));
                            }
                        if (threads.isEmpty()) {
                            unavailableAction();
                            return;
                        }
                        int selected =
                                DshChoiceDialog.show(
                                        project,
                                        DshBundle.message("dsh.forge.thread.action"),
                                        DshBundle.message("dsh.forge.title"),
                                        Messages.getQuestionIcon(),
                                        labels.toArray(String[]::new),
                                        labels.get(0));
                        if (selected < 0) return;
                        JsonObject thread = threads.get(selected);
                        List<String> actions = new ArrayList<>();
                        if (DshJson.bool(thread, "viewerCanReply", false)) actions.add("reply");
                        if (DshJson.bool(thread, "viewerCanResolve", false)) actions.add("resolve");
                        String[] choices =
                                actions.stream()
                                        .map(
                                                action ->
                                                        DshBundle.message(
                                                                "dsh.forge.thread." + action))
                                        .toArray(String[]::new);
                        int action =
                                DshChoiceDialog.show(
                                        project,
                                        describe(thread),
                                        DshBundle.message("dsh.forge.thread.action"),
                                        Messages.getQuestionIcon(),
                                        choices,
                                        choices[0]);
                        if (action < 0) return;
                        JsonObject write = new JsonObject();
                        write.addProperty("kind", actions.get(action));
                        write.addProperty("thread", DshJson.string(thread, "id"));
                        write.addProperty("headSha", DshJson.string(snapshot, "headSha"));
                        String detail = choices[action];
                        if ("reply".equals(actions.get(action))) {
                            String body = reviewBody("dsh.forge.review.body");
                            if (body == null || body.isBlank()) return;
                            write.addProperty("body", body);
                            boolean pending =
                                    review.has("pendingReview")
                                            && !review.get("pendingReview").isJsonNull();
                            write.addProperty("pending", pending);
                            detail +=
                                    (pending
                                                    ? "\n"
                                                            + DshBundle.message(
                                                                    "dsh.forge.pending.reply")
                                                    : "")
                                            + "\n\n"
                                            + body;
                        } else
                            write.addProperty("resolved", !DshJson.bool(thread, "resolved", false));
                        JsonObject publish = requestArgs(row);
                        publish.add("write", write);
                        mutate(row, "changeRequestReviewWrite", publish, detail);
                    });
        }

        private void pendingReviewActions(JsonObject row, JsonObject snapshot) {
            JsonObject args = requestArgs(row);
            args.addProperty("headSha", DshJson.string(snapshot, "headSha"));
            args.addProperty("fresh", true);
            readForWrite(
                    row,
                    "changeRequestReview",
                    args,
                    review -> {
                        JsonElement raw = review.get("pendingReview");
                        if (!DshJson.bool(review, "viewerCanReview", false)
                                || raw == null
                                || !raw.isJsonObject()
                                || DshJson.strictString(raw.getAsJsonObject(), "id") == null) {
                            unavailableAction();
                            return;
                        }
                        String[] events = {"comment", "approve", "request-changes", "discard"};
                        String[] labels =
                                Arrays.stream(events)
                                        .map(
                                                event ->
                                                        DshBundle.message(
                                                                "dsh.forge.review." + event))
                                        .toArray(String[]::new);
                        int selected =
                                DshChoiceDialog.show(
                                        project,
                                        DshJson.string(row, "url"),
                                        DshBundle.message("dsh.forge.pending.review"),
                                        Messages.getQuestionIcon(),
                                        labels,
                                        labels[0]);
                        if (selected < 0) return;
                        JsonObject write = new JsonObject();
                        write.addProperty("headSha", DshJson.string(snapshot, "headSha"));
                        write.addProperty("review", DshJson.string(raw.getAsJsonObject(), "id"));
                        String detail = labels[selected];
                        if (selected == 3) write.addProperty("kind", "discard-pending");
                        else {
                            String body = reviewBody("dsh.forge.review.body");
                            if (body == null) return;
                            write.addProperty("kind", "submit-pending");
                            write.addProperty("event", events[selected]);
                            write.addProperty("body", body);
                            detail += "\n\n" + body;
                        }
                        JsonObject request = requestArgs(row);
                        request.add("write", write);
                        mutate(row, "changeRequestReviewWrite", request, detail);
                    });
        }

        private void merge(JsonObject row, JsonObject snapshot) {
            if (!DshJson.bool(snapshot, "mergeReady", false)) {
                unavailableAction();
                return;
            }
            String method =
                    chooseMethod(
                            "dsh.forge.merge",
                            methods(snapshot, "mergeMethods", Set.of("merge", "squash", "rebase")));
            if (method == null) return;
            JsonObject args = requestArgs(row);
            args.addProperty("method", method);
            args.addProperty("headSha", DshJson.string(snapshot, "headSha"));
            mutate(row, "merge", args, DshBundle.message("dsh.forge.merge") + " · " + method);
        }

        private void requestReviewer(JsonObject row, JsonObject snapshot) {
            if (!DshJson.bool(snapshot, "canRequestReviews", false)) {
                unavailableAction();
                return;
            }
            String query =
                    Messages.showInputDialog(
                            project,
                            DshBundle.message("dsh.forge.reviewer.query"),
                            DshBundle.message("dsh.forge.request.reviewer"),
                            Messages.getQuestionIcon());
            if (query == null) return;
            JsonObject args = requestArgs(row);
            args.addProperty("query", query.strip());
            background(
                    () -> {
                        JsonElement raw = call("reviewerCandidates", args);
                        if (!raw.isJsonArray() || raw.getAsJsonArray().size() > 1000)
                            throw new IllegalStateException("Invalid reviewer candidates");
                        List<String> logins = new ArrayList<>();
                        for (JsonElement item : raw.getAsJsonArray())
                            if (item.isJsonObject()
                                    && DshJson.strictString(item.getAsJsonObject(), "login")
                                            != null)
                                logins.add(DshJson.string(item.getAsJsonObject(), "login"));
                        ApplicationManager.getApplication()
                                .invokeLater(
                                        () -> {
                                            if (!current() || logins.isEmpty()) {
                                                unavailableAction();
                                                return;
                                            }
                                            int selected =
                                                    DshChoiceDialog.show(
                                                            project,
                                                            DshBundle.message(
                                                                    "dsh.forge.request.reviewer"),
                                                            DshBundle.message("dsh.forge.title"),
                                                            Messages.getQuestionIcon(),
                                                            logins.toArray(String[]::new),
                                                            logins.get(0));
                                            if (selected < 0) return;
                                            JsonObject reviewer = new JsonObject();
                                            reviewer.addProperty("kind", "user");
                                            reviewer.addProperty("login", logins.get(selected));
                                            JsonArray reviewers = new JsonArray();
                                            reviewers.add(reviewer);
                                            JsonObject request = requestArgs(row);
                                            request.add("reviewers", reviewers);
                                            mutate(
                                                    row,
                                                    "requestReviews",
                                                    request,
                                                    DshBundle.message("dsh.forge.request.reviewer")
                                                            + " · "
                                                            + logins.get(selected));
                                        });
                    });
        }

        private List<String> methods(JsonObject snapshot, String key, Set<String> allowed) {
            List<String> result = new ArrayList<>();
            if (snapshot.has(key) && snapshot.get(key).isJsonArray())
                for (JsonElement value : snapshot.getAsJsonArray(key))
                    if (value.isJsonPrimitive()
                            && value.getAsJsonPrimitive().isString()
                            && allowed.contains(value.getAsString()))
                        result.add(value.getAsString());
            return result;
        }

        private String chooseMethod(String key, List<String> methods) {
            if (methods.isEmpty()) {
                unavailableAction();
                return null;
            }
            int selected =
                    DshChoiceDialog.show(
                            project,
                            DshBundle.message(key),
                            DshBundle.message("dsh.forge.title"),
                            Messages.getQuestionIcon(),
                            methods.toArray(String[]::new),
                            methods.get(0));
            return selected < 0 ? null : methods.get(selected);
        }

        private String reviewBody(String key) {
            JTextArea body = new JTextArea(10, 65);
            body.setLineWrap(true);
            body.setWrapStyleWord(true);
            DialogWrapper dialog =
                    new DialogWrapper(project) {
                        {
                            setTitle(DshBundle.message(key));
                            init();
                        }

                        @Override
                        protected JComponent createCenterPanel() {
                            return new JScrollPane(body);
                        }

                        @Override
                        public JComponent getPreferredFocusedComponent() {
                            return body;
                        }
                    };
            if (!dialog.showAndGet()) return null;
            if (body.getText().length() > 65536) {
                notify.accept(DshBundle.message("dsh.review.revision.large"));
                return null;
            }
            return body.getText();
        }

        private void readForWrite(
                JsonObject row, String method, JsonObject args, Consumer<JsonObject> ready) {
            background(
                    () -> {
                        assertRequest(row);
                        JsonElement raw = call(method, args);
                        if (!raw.isJsonObject())
                            throw new IllegalStateException("Invalid Forge response");
                        ApplicationManager.getApplication()
                                .invokeLater(
                                        () -> {
                                            if (!current()) return;
                                            try {
                                                assertRequest(row);
                                                ready.accept(raw.getAsJsonObject());
                                            } catch (Exception error) {
                                                notify.accept(DshJson.message(error));
                                            }
                                        });
                    });
        }

        private void mutate(JsonObject row, String method, JsonObject args, String detail) {
            if (mutating) return;
            if (!remote.access().allows("forgePanel/" + method, "session", session)) {
                notify.accept(DshBundle.message("dsh.remote.access.denied"));
                return;
            }
            try {
                assertRequest(row);
            } catch (Exception error) {
                notify.accept(DshJson.message(error));
                return;
            }
            String url = DshJson.string(row, "url");
            if (uncertain.contains(url)) {
                if (Messages.showYesNoDialog(
                                project,
                                DshBundle.message("dsh.forge.unknown.confirm", url),
                                DshBundle.message("dsh.forge.title"),
                                Messages.getWarningIcon())
                        != Messages.YES) return;
                uncertain.remove(url);
            }
            String head = DshJson.string(row.getAsJsonObject("snapshot"), "headSha");
            if (Messages.showYesNoDialog(
                            project,
                            detail + "\n\n" + url + "\n" + head,
                            DshBundle.message("dsh.forge.confirm"),
                            Messages.getWarningIcon())
                    != Messages.YES) return;
            mutating = true;
            updateWriteActions();
            operations.execute(
                    () -> {
                        String message;
                        boolean unknown = false;
                        boolean submitted = false;
                        try {
                            assertRequest(row);
                            submitted = true;
                            JsonElement value = remote.callFeature("forgePanel/" + method, args);
                            if (method.equals("submitReview")
                                    || method.equals("changeRequestReviewWrite")) {
                                if (!value.isJsonObject()
                                        || !value.getAsJsonObject().has("ok")
                                        || !value.getAsJsonObject().get("ok").isJsonPrimitive()
                                        || !value.getAsJsonObject()
                                                .getAsJsonPrimitive("ok")
                                                .isBoolean()) {
                                    unknown = true;
                                    message = DshBundle.message("dsh.forge.unknown");
                                } else if (!value.getAsJsonObject().get("ok").getAsBoolean()) {
                                    JsonObject failure = value.getAsJsonObject();
                                    unknown = "unknown".equals(DshJson.string(failure, "code"));
                                    message =
                                            DshJson.stringOr(
                                                    failure,
                                                    "message",
                                                    DshBundle.message("dsh.forge.unavailable"));
                                } else message = DshBundle.message("dsh.forge.saved");
                            } else message = DshBundle.message("dsh.forge.saved");
                        } catch (Exception error) {
                            unknown =
                                    submitted
                                            && (!(error
                                                            instanceof
                                                            top.harcochen.dsh.remote
                                                                            .DshRemoteException
                                                                    remoteError)
                                                    || remoteError.layer()
                                                            == top.harcochen.dsh.remote
                                                                    .DshRemoteException.Layer
                                                                    .CARRIER
                                                    || remoteError.layer()
                                                            == top.harcochen.dsh.remote
                                                                    .DshRemoteException.Layer
                                                                    .PROTOCOL
                                                    || remoteError.httpStatus() >= 500
                                                    || "forgePanel/action-unknown"
                                                            .equals(remoteError.code()));
                            message = DshJson.message(error);
                        }
                        if (unknown && !message.equals(DshBundle.message("dsh.forge.unknown")))
                            message = DshBundle.message("dsh.forge.unknown") + "\n" + message;
                        String notice = message;
                        boolean uncertainOutcome = unknown;
                        ApplicationManager.getApplication()
                                .invokeLater(
                                        () -> {
                                            mutating = false;
                                            if (!current()) return;
                                            if (uncertainOutcome) uncertain.add(url);
                                            updateWriteActions();
                                            notify.accept(notice);
                                        });
                    });
        }

        private void updateWriteActions() {
            for (JButton button : writeButtons) button.setEnabled(!mutating);
            getCancelAction().setEnabled(!mutating);
            requests.setEnabled(!mutating);
        }

        @Override
        public void doCancelAction() {
            if (!mutating) super.doCancelAction();
        }

        private void unavailableAction() {
            notify.accept(DshBundle.message("dsh.forge.action.unavailable"));
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
