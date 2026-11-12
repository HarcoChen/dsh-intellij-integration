package top.harcochen.dsh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import top.harcochen.dsh.remote.DshRemoteException;
import top.harcochen.dsh.remote.DshRemoteService;
import top.harcochen.dsh.remote.DshRemoteState;

/** Owns session menu actions and the selected session's model catalog. */
final class DshSessionActionsController {
    private static final Logger LOG = Logger.getInstance(DshSessionActionsController.class);

    private final Project project;
    private final DshRemoteService remote;
    private final ExecutorService operations;
    private final Supplier<String> sessionId;
    private final Supplier<DshRemoteState.SessionView> sessionView;
    private final Supplier<JsonArray> sessions;
    private final Consumer<String> selectSession;
    private final Runnable clearSession;
    private final Runnable refreshState;
    private final Runnable stateChanged;
    private final Consumer<String> notifier;
    private final Consumer<String> errorSink;

    private volatile JsonObject modelCatalog;
    private volatile String modelCatalogSession;
    private volatile JsonObject currentRouteCache;
    private volatile String currentRouteSession;

    DshSessionActionsController(
            Project project,
            DshRemoteService remote,
            ExecutorService operations,
            Supplier<String> sessionId,
            Supplier<DshRemoteState.SessionView> sessionView,
            Supplier<JsonArray> sessions,
            Consumer<String> selectSession,
            Runnable clearSession,
            Runnable refreshState,
            Runnable stateChanged,
            Consumer<String> notifier,
            Consumer<String> errorSink) {
        this.project = project;
        this.remote = remote;
        this.operations = operations;
        this.sessionId = sessionId;
        this.sessionView = sessionView;
        this.sessions = sessions;
        this.selectSession = selectSession;
        this.clearSession = clearSession;
        this.refreshState = refreshState;
        this.stateChanged = stateChanged;
        this.notifier = notifier;
        this.errorSink = errorSink;
    }

    void exportConversation() {
        new DshConversationExporter(project, remote, operations, this::notifyUser, this::report)
                .export(sessionId.get());
    }

    void refreshModelCatalog(String session) {
        if (session == null || session.equals(modelCatalogSession)) {
            return;
        }
        try {
            // Claim the session only once the load succeeds, so a transient RPC failure does not
            // permanently pin this session to an empty catalog via the guard above.
            JsonObject catalog = remote.modelCatalog();
            modelCatalog = catalog;
            modelCatalogSession = session;
        } catch (Exception error) {
            LOG.debug("The connected Harness did not expose a model catalog", error);
        }
    }

    JsonObject modelCatalog(String session) {
        return session != null && session.equals(modelCatalogSession) ? modelCatalog : null;
    }

    void invalidateModelCatalog() {
        modelCatalogSession = null;
        modelCatalog = null;
    }

    JsonObject reasoningEffort(String session, DshRemoteState.SessionView view) {
        JsonObject value = new JsonObject();
        JsonArray options = new JsonArray();
        JsonObject catalog = modelCatalog(session);
        JsonObject current = currentRoute(view, catalog);
        if (view != null) {
            currentRouteCache = current;
            currentRouteSession = session;
        }
        if (current != null && current.has("reasoningEffort")) {
            value.addProperty("current", DshJson.stringOr(current, "reasoningEffort", "default"));
        }
        String provider = current == null ? null : DshJson.string(current, "provider");
        String model = current == null ? null : DshJson.string(current, "model");
        JsonArray groups =
                catalog != null && catalog.has("groups") && catalog.get("groups").isJsonArray()
                        ? catalog.getAsJsonArray("groups")
                        : new JsonArray();
        for (JsonElement groupElement : groups) {
            if (!groupElement.isJsonObject()
                    || !DshJson.stringOr(groupElement.getAsJsonObject(), "id", "")
                            .equals(provider)) {
                continue;
            }
            addModelEfforts(options, groupElement.getAsJsonObject(), model);
        }
        value.add("options", options);
        return value;
    }

    /** Merge the host-wide catalog default with this session's model-selection projection. */
    private JsonObject currentRoute(DshRemoteState.SessionView view, JsonObject catalog) {
        if (view == null) {
            return catalog != null
                            && catalog.has("default")
                            && catalog.get("default").isJsonObject()
                    ? catalog.getAsJsonObject("default")
                    : null;
        }
        DshRemoteState.ProjectionCell cell = view.projections.get("modelSelection");
        JsonObject route =
                DshSessionStateStore.currentModelRoute(cell == null ? null : cell.value(), catalog);
        return route.has("provider") ? route : null;
    }

    private static void addModelEfforts(JsonArray options, JsonObject group, String selectedModel) {
        JsonArray models =
                group.has("models") && group.get("models").isJsonArray()
                        ? group.getAsJsonArray("models")
                        : new JsonArray();
        for (JsonElement modelElement : models) {
            if (!modelElement.isJsonObject()
                    || !DshJson.stringOr(modelElement.getAsJsonObject(), "id", "")
                            .equals(selectedModel)) {
                continue;
            }
            JsonObject model = modelElement.getAsJsonObject();
            JsonObject reasoning =
                    model.has("reasoning") && model.get("reasoning").isJsonObject()
                            ? model.getAsJsonObject("reasoning")
                            : null;
            JsonArray efforts =
                    reasoning != null
                                    && reasoning.has("efforts")
                                    && reasoning.get("efforts").isJsonArray()
                            ? reasoning.getAsJsonArray("efforts")
                            : new JsonArray();
            for (JsonElement effortElement : efforts) {
                if (!effortElement.isJsonObject()) {
                    continue;
                }
                String id = DshJson.string(effortElement.getAsJsonObject(), "id");
                if (id == null || id.isBlank()) {
                    continue;
                }
                JsonObject option = new JsonObject();
                option.addProperty("id", id);
                option.addProperty(
                        "label", DshJson.stringOr(effortElement.getAsJsonObject(), "name", id));
                options.add(option);
            }
        }
    }

    private SessionCenter sessionCenter;

    void close() {
        ApplicationManager.getApplication()
                .invokeLater(
                        () -> {
                            if (sessionCenter != null)
                                sessionCenter.close(DialogWrapper.CANCEL_EXIT_CODE);
                        });
    }

    void openSessionCenter() {
        ApplicationManager.getApplication()
                .invokeLater(
                        () -> {
                            if (sessionCenter != null) {
                                sessionCenter.toFront();
                                return;
                            }
                            sessionCenter = new SessionCenter();
                            sessionCenter.show();
                        });
    }

    private final class SessionCenter extends DialogWrapper {
        private final javax.swing.JTextField search = new javax.swing.JTextField();
        private final javax.swing.DefaultListModel<String> model =
                new javax.swing.DefaultListModel<>();
        private final JList<String> list = new JList<>(model);
        private final List<String> ids = new ArrayList<>();
        private final Consumer<DshRemoteState.Snapshot> listener =
                ignored -> ApplicationManager.getApplication().invokeLater(this::render);
        private volatile boolean closed;
        private final javax.swing.JCheckBox showArchived =
                new javax.swing.JCheckBox(DshBundle.message("dsh.session.center.show.archived"));
        private final javax.swing.JTextArea preview = new javax.swing.JTextArea();
        private final javax.swing.JLabel result = new javax.swing.JLabel(" ");
        private volatile int previewEpoch;
        private boolean rendering;
        private String previewSession;
        private final long generation = remote.snapshot().generation;

        SessionCenter() {
            super(project, false);
            setModal(false);
            setTitle(DshBundle.message("dsh.session.center.title"));
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            preview.setEditable(false);
            preview.setLineWrap(true);
            preview.setWrapStyleWord(true);
            list.addListSelectionListener(
                    event -> {
                        if (!event.getValueIsAdjusting()) loadPreview();
                    });
            showArchived.addActionListener(event -> render());
            search.getDocument()
                    .addDocumentListener(
                            new javax.swing.event.DocumentListener() {
                                @Override
                                public void insertUpdate(javax.swing.event.DocumentEvent event) {
                                    render();
                                }

                                @Override
                                public void removeUpdate(javax.swing.event.DocumentEvent event) {
                                    render();
                                }

                                @Override
                                public void changedUpdate(javax.swing.event.DocumentEvent event) {
                                    render();
                                }
                            });
            list.addMouseListener(
                    new java.awt.event.MouseAdapter() {
                        @Override
                        public void mouseClicked(java.awt.event.MouseEvent event) {
                            if (event.getClickCount() == 2) doOKAction();
                        }
                    });
            init();
            render();
            remote.addListener(listener);
        }

        @Override
        protected JComponent createCenterPanel() {
            JPanel content = new JPanel(new BorderLayout(0, 8));
            content.add(
                    new JLabel(DshBundle.message("dsh.session.center.hint")), BorderLayout.NORTH);
            JPanel body = new JPanel(new BorderLayout(0, 8));
            body.add(search, BorderLayout.NORTH);
            javax.swing.JSplitPane split =
                    new javax.swing.JSplitPane(
                            javax.swing.JSplitPane.HORIZONTAL_SPLIT,
                            new JScrollPane(list),
                            new JScrollPane(preview));
            split.setResizeWeight(0.55);
            body.add(split, BorderLayout.CENTER);
            JPanel controls = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEADING));
            controls.add(showArchived);
            addButton(
                    controls,
                    "dsh.review.refresh",
                    () -> {
                        previewSession = null;
                        loadPreview();
                    });
            addButton(controls, "dsh.session.rename.title", this::renameSelected);
            addButton(controls, "dsh.session.center.pin", () -> mutateSelected("pin"));
            addButton(controls, "dsh.session.center.archive", () -> mutateSelected("archive"));
            addButton(controls, "dsh.workspace.action.move.up", () -> moveSelected(-1));
            addButton(controls, "dsh.workspace.action.move.down", () -> moveSelected(1));
            JPanel bottom = new JPanel(new BorderLayout());
            bottom.add(controls, BorderLayout.CENTER);
            bottom.add(result, BorderLayout.SOUTH);
            body.add(bottom, BorderLayout.SOUTH);
            content.add(body, BorderLayout.CENTER);
            content.setPreferredSize(new Dimension(1100, 560));
            return content;
        }

        @Override
        public JComponent getPreferredFocusedComponent() {
            return search;
        }

        private void render() {
            if (closed) return;
            if (remote.snapshot().generation != generation) {
                preview.setText("");
                result.setText(DshBundle.message("dsh.runtime.changed"));
                return;
            }
            String selected =
                    list.getSelectedIndex() < 0 || list.getSelectedIndex() >= ids.size()
                            ? null
                            : ids.get(list.getSelectedIndex());
            if (previewSession != null
                    && !remote.access().allows("session/follow", "session", previewSession)) {
                preview.setText("");
                previewSession = null;
                ++previewEpoch;
            }
            String query = search.getText().strip().toLowerCase(Locale.ROOT);
            List<JsonObject> rows = new ArrayList<>();
            JsonArray catalog = sessions.get().deepCopy();
            java.util.Set<String> known = new java.util.HashSet<>();
            for (JsonElement raw : catalog)
                if (raw.isJsonObject())
                    known.add(DshJson.string(raw.getAsJsonObject(), "sessionId"));
            if (showArchived.isSelected())
                for (String id : remote.snapshot().archivedSessionIds) {
                    if (known.contains(id)) continue;
                    JsonObject row = new JsonObject();
                    row.addProperty("sessionId", id);
                    row.addProperty("archived", true);
                    catalog.add(row);
                }
            for (JsonElement raw : catalog) {
                if (!raw.isJsonObject()) continue;
                JsonObject row = raw.getAsJsonObject();
                boolean archived =
                        DshJson.bool(row, "archived", false)
                                || remote.snapshot()
                                        .archivedSessionIds
                                        .contains(DshJson.string(row, "sessionId"));
                if ((archived && !showArchived.isSelected())
                        || "subagent".equals(DshJson.string(row, "origin"))) continue;
                if (row.toString().toLowerCase(Locale.ROOT).contains(query)) rows.add(row);
            }
            java.util.Map<String, Integer> workspaceOrder = new java.util.HashMap<>();
            int ordinal = 0;
            for (JsonObject workspace : remote.snapshot().workspaces) {
                JsonArray members = workspace.getAsJsonArray("sessionIds");
                if (members != null)
                    for (JsonElement member : members)
                        if (member.isJsonPrimitive())
                            workspaceOrder.put(member.getAsString(), ordinal++);
            }
            rows.sort(
                    java.util.Comparator.comparingInt(this::priority)
                            .thenComparingInt(
                                    row ->
                                            workspaceOrder.getOrDefault(
                                                    DshJson.string(row, "sessionId"),
                                                    Integer.MAX_VALUE))
                            .thenComparing(
                                    java.util.Comparator.comparingLong(
                                                    (JsonObject row) ->
                                                            DshJson.longValue(
                                                                    row.get("updatedAt"), 0))
                                            .reversed()));
            rendering = true;
            ids.clear();
            model.clear();
            for (JsonObject row : rows) {
                String id = DshJson.string(row, "sessionId");
                if (id == null) continue;
                ids.add(id);
                String status =
                        DshJson.bool(row, "attention", false)
                                ? DshBundle.message("dsh.session.center.attention")
                                : row.has("lastError")
                                        ? DshBundle.message("dsh.session.center.error")
                                        : DshJson.bool(row, "running", false)
                                                ? DshBundle.message("dsh.session.center.running")
                                                : DshBundle.message("dsh.session.center.idle");
                model.addElement(
                        DshJson.stringOr(row, "title", id)
                                + " · "
                                + DshJson.stringOr(
                                        row,
                                        "workspaceTitle",
                                        DshBundle.message("dsh.session.center.ungrouped"))
                                + " · "
                                + status
                                + (remote.snapshot().archivedSessionIds.contains(id)
                                        ? " · " + DshBundle.message("dsh.session.manage.archived")
                                        : "")
                                + (remote.snapshot().pinnedSessionIds.contains(id)
                                        ? " · " + DshBundle.message("dsh.session.manage.pinned")
                                        : "")
                                + ("migration-required".equals(DshJson.string(row, "formatStatus"))
                                        ? " · " + DshBundle.message("dsh.session.center.migration")
                                        : "")
                                + " · "
                                + DshJson.stringOr(row, "cwd", id));
            }
            int index = selected == null ? -1 : ids.indexOf(selected);
            if (index >= 0) list.setSelectedIndex(index);
            else if (!ids.isEmpty()) list.setSelectedIndex(0);
            rendering = false;
            loadPreview();
        }

        private void addButton(JPanel panel, String label, Runnable action) {
            javax.swing.JButton button = new javax.swing.JButton(DshBundle.message(label));
            button.addActionListener(event -> action.run());
            panel.add(button);
        }

        private String selectedId() {
            int index = list.getSelectedIndex();
            return index < 0 || index >= ids.size() ? null : ids.get(index);
        }

        private void runMutation(Runnable mutation) {
            if (closed || remote.snapshot().generation != generation) {
                result.setText(DshBundle.message("dsh.runtime.changed"));
                return;
            }
            result.setText(DshBundle.message("dsh.review.submitting"));
            operations.execute(
                    () -> {
                        try {
                            if (remote.snapshot().generation != generation)
                                throw new IllegalStateException(
                                        DshBundle.message("dsh.runtime.changed"));
                            mutation.run();
                            refreshState.run();
                            ApplicationManager.getApplication()
                                    .invokeLater(
                                            () -> {
                                                if (!closed) {
                                                    result.setText(
                                                            DshBundle.message(
                                                                    "dsh.session.center.saved"));
                                                    render();
                                                }
                                            });
                        } catch (Exception error) {
                            ApplicationManager.getApplication()
                                    .invokeLater(
                                            () -> {
                                                if (!closed) result.setText(DshJson.message(error));
                                            });
                        }
                    });
        }

        private void renameSelected() {
            String id = selectedId();
            if (id == null) return;
            String title =
                    Messages.showInputDialog(
                            project,
                            DshBundle.message("dsh.session.rename.message"),
                            DshBundle.message("dsh.session.rename.title"),
                            Messages.getQuestionIcon());
            if (title != null && !title.isBlank())
                runMutation(() -> remote.renameSession(id, title.strip()));
        }

        private void mutateSelected(String action) {
            String id = selectedId();
            if (id == null) return;
            boolean archived = remote.snapshot().archivedSessionIds.contains(id);
            boolean pinned = remote.snapshot().pinnedSessionIds.contains(id);
            if ("archive".equals(action)
                    && !archived
                    && Messages.showYesNoDialog(
                                    project,
                                    DshBundle.message("dsh.session.archive.message"),
                                    DshBundle.message("dsh.session.archive.title"),
                                    Messages.getQuestionIcon())
                            != Messages.YES) return;
            runMutation(
                    () -> {
                        if ("pin".equals(action)) {
                            if (archived)
                                throw new IllegalStateException(
                                        DshBundle.message("dsh.session.center.restore.first"));
                            if (pinned) remote.unpinSession(id);
                            else remote.pinSession(id);
                        } else {
                            if (archived) remote.unarchiveSession(id);
                            else {
                                remote.archiveSession(id);
                                if (id.equals(sessionId.get())) clearSession.run();
                            }
                        }
                    });
        }

        private void moveSelected(int direction) {
            String id = selectedId();
            if (id == null) return;
            for (JsonObject workspace : remote.snapshot().workspaces) {
                JsonArray values = workspace.getAsJsonArray("sessionIds");
                if (values == null) continue;
                List<String> order = new ArrayList<>();
                for (JsonElement value : values)
                    if (value.isJsonPrimitive()) order.add(value.getAsString());
                int index = order.indexOf(id);
                if (index < 0 || index + direction < 0 || index + direction >= order.size())
                    continue;
                int anchor = direction < 0 ? index - 1 : index + 2;
                String before = anchor < order.size() ? order.get(anchor) : null;
                String workspaceId = DshJson.string(workspace, "workspaceId");
                runMutation(() -> remote.insertWorkspaceSessionBefore(workspaceId, id, before));
                return;
            }
            result.setText(DshBundle.message("dsh.session.center.move.unavailable"));
        }

        private void loadPreview() {
            if (rendering) return;
            String id = selectedId();
            if (java.util.Objects.equals(id, previewSession)) return;
            previewSession = id;
            int epoch = ++previewEpoch;
            if (id == null) {
                preview.setText("");
                return;
            }
            preview.setText(DshBundle.message("dsh.review.loading"));
            operations.execute(
                    () -> {
                        String text;
                        try {
                            if (remote.snapshot().generation != generation)
                                throw new IllegalStateException(
                                        DshBundle.message("dsh.runtime.changed"));
                            if (!remote.access().allows("session/follow", "session", id))
                                throw new IllegalStateException(
                                        DshBundle.message("dsh.remote.access.denied"));
                            JsonObject history =
                                    remote.traceHistory(
                                            id,
                                            80,
                                            160,
                                            () ->
                                                    closed
                                                            || epoch != previewEpoch
                                                            || remote.snapshot().generation
                                                                    != generation
                                                            || !remote.access()
                                                                    .allows(
                                                                            "session/follow",
                                                                            "session",
                                                                            id));
                            StringBuilder buffer = new StringBuilder(id + "\n\n");
                            for (JsonObject entry : DshToolDiff.entries(history)) {
                                JsonObject event = DshWorkingDirectory.event(entry);
                                JsonObject data =
                                        event.has("data") && event.get("data").isJsonObject()
                                                ? event.getAsJsonObject("data")
                                                : null;
                                if (data == null
                                        || !data.has("message")
                                        || !data.get("message").isJsonObject()) continue;
                                JsonObject message = data.getAsJsonObject("message");
                                buffer.append(DshJson.stringOr(message, "role", "message"))
                                        .append(":\n");
                                appendPreviewText(message.get("content"), buffer);
                                buffer.append("\n\n");
                                if (buffer.length() > 32000) break;
                            }
                            text = buffer.substring(0, Math.min(buffer.length(), 32000));
                        } catch (Exception error) {
                            text = DshJson.message(error);
                        }
                        String rendered = text;
                        ApplicationManager.getApplication()
                                .invokeLater(
                                        () -> {
                                            if (!closed
                                                    && epoch == previewEpoch
                                                    && remote.snapshot().generation == generation) {
                                                preview.setText(rendered);
                                                preview.setCaretPosition(0);
                                            }
                                        });
                    });
        }

        private void appendPreviewText(JsonElement value, StringBuilder text) {
            if (value == null || text.length() >= 32000) return;
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString())
                text.append(
                        value.getAsString(),
                        0,
                        Math.min(value.getAsString().length(), 32000 - text.length()));
            else if (value.isJsonArray())
                for (JsonElement item : value.getAsJsonArray()) appendPreviewText(item, text);
            else if (value.isJsonObject() && value.getAsJsonObject().has("text"))
                appendPreviewText(value.getAsJsonObject().get("text"), text);
        }

        private int priority(JsonObject row) {
            return DshJson.bool(row, "attention", false)
                    ? 0
                    : row.has("lastError") ? 1 : DshJson.bool(row, "running", false) ? 2 : 3;
        }

        @Override
        protected void doOKAction() {
            int index = list.getSelectedIndex();
            if (index < 0 || index >= ids.size()) return;
            String id = ids.get(index);
            if (!remote.access().allows("session/follow", "session", id)) {
                notifyUser(DshBundle.message("dsh.remote.access.denied"));
                return;
            }
            if (remote.snapshot().generation != generation) {
                result.setText(DshBundle.message("dsh.runtime.changed"));
                return;
            }
            if (remote.snapshot().archivedSessionIds.contains(id)) {
                result.setText(DshBundle.message("dsh.session.center.restore.first"));
                return;
            }
            selectSession.accept(id);
            super.doOKAction();
        }

        @Override
        protected void dispose() {
            closed = true;
            remote.removeListener(listener);
            sessionCenter = null;
            super.dispose();
        }
    }

    void search() {
        ApplicationManager.getApplication()
                .invokeLater(
                        () -> {
                            String query =
                                    Messages.showInputDialog(
                                            project,
                                            DshBundle.message("dsh.session.search.message"),
                                            DshBundle.message("dsh.session.search.title"),
                                            Messages.getQuestionIcon());
                            if (query == null) {
                                return;
                            }
                            chooseSearchResult(query);
                        });
    }

    void manageSessions() {
        operations.execute(
                () -> {
                    try {
                        DshRuntimeService.getInstance(project).startAsync().join();
                        long deadline =
                                System.nanoTime()
                                        + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
                        while (!DshRemoteService.PHASE_CONNECTED.equals(remote.phase())
                                && System.nanoTime() < deadline) {
                            Thread.sleep(100);
                        }
                        if (!DshRemoteService.PHASE_CONNECTED.equals(remote.phase())) {
                            throw new IllegalStateException("DSH Runtime sessions are not ready");
                        }
                        ApplicationManager.getApplication().invokeLater(this::chooseManagedSession);
                    } catch (Exception error) {
                        report(error);
                    }
                });
    }

    private void chooseManagedSession() {
        DshRemoteState.Snapshot snapshot = remote.snapshot();
        LinkedHashSet<String> ids = new LinkedHashSet<>(snapshot.pinnedSessionIds);
        for (JsonElement candidate : snapshot.catalog) {
            if (candidate.isJsonObject()) {
                String id = DshJson.string(candidate.getAsJsonObject(), "sessionId");
                if (id != null) ids.add(id);
            }
        }
        ids.addAll(snapshot.archivedSessionIds);
        if (ids.isEmpty()) {
            notifyUser(DshBundle.message("dsh.session.manage.empty"));
            return;
        }
        List<String> labels = new ArrayList<>();
        List<String> choices = new ArrayList<>(ids);
        for (String id : choices) {
            String title = id;
            for (JsonElement candidate : snapshot.catalog) {
                if (candidate.isJsonObject()
                        && id.equals(DshJson.string(candidate.getAsJsonObject(), "sessionId"))) {
                    title = DshJson.stringOr(candidate.getAsJsonObject(), "title", id);
                    break;
                }
            }
            String status =
                    snapshot.archivedSessionIds.contains(id)
                            ? DshBundle.message("dsh.session.manage.archived")
                            : snapshot.pinnedSessionIds.contains(id)
                                    ? DshBundle.message("dsh.session.manage.pinned")
                                    : "";
            labels.add(title + "  (" + id + ")" + (status.isBlank() ? "" : "  · " + status));
        }
        int picked =
                DshChoiceDialog.show(
                        project,
                        DshBundle.message("dsh.session.manage.choose"),
                        DshBundle.message("dsh.session.manage.title"),
                        Messages.getQuestionIcon(),
                        labels.toArray(new String[0]),
                        labels.get(0));
        if (picked < 0) return;
        String id = choices.get(picked);
        boolean archived = snapshot.archivedSessionIds.contains(id);
        boolean pinned = snapshot.pinnedSessionIds.contains(id);
        String[] actions =
                archived
                        ? new String[] {DshBundle.message("dsh.session.manage.restore")}
                        : new String[] {
                            DshBundle.message("dsh.session.manage.open"),
                            DshBundle.message(
                                    pinned ? "dsh.session.manage.unpin" : "dsh.session.manage.pin")
                        };
        int action =
                DshChoiceDialog.show(
                        project,
                        labels.get(picked),
                        DshBundle.message("dsh.session.manage.title"),
                        Messages.getQuestionIcon(),
                        actions,
                        actions[0]);
        if (action < 0) return;
        if (!archived && action == 0) {
            selectSession.accept(id);
            refreshState.run();
            return;
        }
        operations.execute(
                () -> {
                    try {
                        if (archived) remote.unarchiveSession(id);
                        else if (pinned) remote.unpinSession(id);
                        else remote.pinSession(id);
                        refreshState.run();
                    } catch (Exception error) {
                        report(error);
                    }
                });
    }

    private void chooseSearchResult(String query) {
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        List<String> choices = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (JsonElement candidate : sessions.get()) {
            if (!candidate.isJsonObject()) {
                continue;
            }
            JsonObject item = candidate.getAsJsonObject();
            String title = DshJson.stringOr(item, "title", "");
            String id = DshJson.stringOr(item, "sessionId", "");
            if (normalized.isBlank()
                    || title.toLowerCase(Locale.ROOT).contains(normalized)
                    || id.toLowerCase(Locale.ROOT).contains(normalized)) {
                choices.add(title + "  (" + id + ")");
                ids.add(id);
            }
        }
        if (choices.isEmpty()) {
            notifyUser(DshBundle.message("dsh.session.search.no.results"));
            return;
        }
        int selected =
                DshChoiceDialog.show(
                        project,
                        DshBundle.message("dsh.session.select.message"),
                        DshBundle.message("dsh.session.select.title"),
                        Messages.getQuestionIcon(),
                        choices.toArray(new String[0]),
                        choices.get(0));
        if (selected >= 0 && selected < ids.size()) {
            selectSession.accept(ids.get(selected));
            refreshState.run();
        }
    }

    void rename() {
        String current = sessionId.get();
        if (current == null) {
            return;
        }
        ApplicationManager.getApplication()
                .invokeLater(
                        () -> {
                            String title =
                                    Messages.showInputDialog(
                                            project,
                                            DshBundle.message("dsh.session.rename.message"),
                                            DshBundle.message("dsh.session.rename.title"),
                                            Messages.getQuestionIcon());
                            if (title == null || title.trim().isEmpty()) {
                                return;
                            }
                            operations.execute(() -> rename(current, title.trim()));
                        });
    }

    private void rename(String session, String title) {
        try {
            remote.renameSession(session, title);
            refreshState.run();
        } catch (Exception error) {
            report(error);
        }
    }

    void fork() {
        forkAt(null);
    }

    /** Fork the current session, optionally cutting it at a finalized message sequence. */
    void forkAt(Long atSeq) {
        String current = sessionId.get();
        if (current == null) {
            return;
        }
        operations.execute(
                () -> {
                    try {
                        String forked = remote.forkSession(current, atSeq);
                        if (!forked.isBlank()) {
                            selectSession.accept(forked);
                        }
                        refreshState.run();
                    } catch (Exception error) {
                        report(error);
                    }
                });
    }

    void archive() {
        String current = sessionId.get();
        if (current == null) {
            return;
        }
        ApplicationManager.getApplication()
                .invokeLater(
                        () -> {
                            int answer =
                                    Messages.showYesNoDialog(
                                            project,
                                            DshBundle.message("dsh.session.archive.message"),
                                            DshBundle.message("dsh.session.archive.title"),
                                            Messages.getQuestionIcon());
                            if (answer == Messages.YES) {
                                operations.execute(() -> archive(current));
                            }
                        });
    }

    private void archive(String session) {
        try {
            remote.archiveSession(session);
            if (session.equals(sessionId.get())) clearSession.run();
            refreshState.run();
        } catch (DshRemoteException active) {
            if (!"workspace/session-active".equals(active.code())) {
                report(active);
                return;
            }
            ApplicationManager.getApplication()
                    .invokeLater(
                            () -> {
                                int answer =
                                        Messages.showYesNoDialog(
                                                project,
                                                DshBundle.message(
                                                        "dsh.session.archive.stop.message"),
                                                DshBundle.message("dsh.session.archive.title"),
                                                Messages.getWarningIcon());
                                if (answer == Messages.YES) {
                                    operations.execute(
                                            () -> {
                                                try {
                                                    remote.archiveSession(session, true);
                                                    if (session.equals(sessionId.get()))
                                                        clearSession.run();
                                                    refreshState.run();
                                                } catch (Exception error) {
                                                    report(error);
                                                }
                                            });
                                }
                            });
        } catch (Exception error) {
            report(error);
        }
    }

    void selectModel() {
        String current = sessionId.get();
        if (current == null) {
            return;
        }
        operations.execute(() -> loadModels(current));
    }

    private void loadModels(String session) {
        try {
            JsonObject catalog = remote.modelCatalog();
            JsonArray groups =
                    catalog.has("groups") && catalog.get("groups").isJsonArray()
                            ? catalog.getAsJsonArray("groups")
                            : new JsonArray();
            List<String> labels = new ArrayList<>();
            List<ModelChoice> choices = new ArrayList<>();
            for (JsonElement groupElement : groups) {
                if (!groupElement.isJsonObject()) {
                    continue;
                }
                JsonObject group = groupElement.getAsJsonObject();
                String provider =
                        DshJson.stringOr(group, "id", DshJson.stringOr(group, "name", "provider"));
                JsonArray models =
                        group.has("models") && group.get("models").isJsonArray()
                                ? group.getAsJsonArray("models")
                                : new JsonArray();
                for (JsonElement modelElement : models) {
                    if (!modelElement.isJsonObject()) {
                        continue;
                    }
                    JsonObject model = modelElement.getAsJsonObject();
                    String id = DshJson.stringOr(model, "id", "");
                    if (id.isBlank()) {
                        continue;
                    }
                    labels.add(
                            DshJson.stringOr(group, "name", provider)
                                    + " / "
                                    + DshJson.stringOr(model, "name", id));
                    choices.add(new ModelChoice(provider, id));
                }
            }
            if (labels.isEmpty()) {
                notifyUser(DshBundle.message("dsh.model.no.choices"));
                return;
            }
            ApplicationManager.getApplication()
                    .invokeLater(() -> chooseModel(session, labels, choices));
        } catch (Exception error) {
            report(error);
        }
    }

    private void chooseModel(String session, List<String> labels, List<ModelChoice> choices) {
        String[] values = labels.toArray(new String[0]);
        int selected =
                DshChoiceDialog.show(
                        project,
                        DshBundle.message("dsh.model.select.message"),
                        DshBundle.message("dsh.model.select.title"),
                        Messages.getQuestionIcon(),
                        values,
                        values[0]);
        if (selected >= 0 && selected < choices.size()) {
            ModelChoice choice = choices.get(selected);
            operations.execute(() -> selectModel(session, choice.provider(), choice.model(), null));
        }
    }

    void selectReasoningEffort(String effort) {
        String session = sessionId.get();
        JsonObject catalog = modelCatalog(session);
        if (session == null || effort == null || catalog == null) {
            return;
        }
        JsonObject current =
                session.equals(currentRouteSession) && currentRouteCache != null
                        ? currentRouteCache
                        : currentRoute(sessionView.get(), catalog);
        String provider = current == null ? null : DshJson.string(current, "provider");
        String model = current == null ? null : DshJson.string(current, "model");
        if (provider == null || model == null) {
            notifyUser(DshBundle.message("dsh.model.no.current"));
            return;
        }
        operations.execute(() -> selectModel(session, provider, model, effort));
    }

    private void selectModel(String session, String provider, String model, String effort) {
        try {
            remote.selectModel(session, provider, model, effort);
            modelCatalog = remote.modelCatalog();
            modelCatalogSession = session;
            refreshState.run();
        } catch (Exception error) {
            report(error);
        }
    }

    void openReasoningEffort() {
        String session = sessionId.get();
        JsonObject value = reasoningEffort(session, sessionView.get());
        JsonArray options = value.getAsJsonArray("options");
        if (options == null || options.isEmpty()) {
            notifyUser(DshBundle.message("dsh.model.no.reasoning.effort"));
            return;
        }
        stateChanged.run();
    }

    void setPermissionPreset(String preset) {
        String current = sessionId.get();
        if (current == null || preset == null) {
            return;
        }
        operations.execute(
                () -> {
                    try {
                        DshRemoteState.SessionView view = remote.snapshot().sessions.get(current);
                        DshRemoteState.ProjectionCell cell =
                                view == null ? null : view.projections.get("permissions");
                        JsonObject permissions =
                                DshSessionStateStore.permissions(
                                        cell == null ? null : cell.value(),
                                        remote.permissionOptions());
                        boolean found = false;
                        if (permissions != null)
                            for (JsonElement option : permissions.getAsJsonArray("options")) {
                                if (preset.equals(DshJson.string(option.getAsJsonObject(), "value"))
                                        && !"custom".equals(preset)) found = true;
                            }
                        if (!found)
                            throw new IllegalArgumentException(
                                    DshBundle.message("dsh.permission.unavailable"));
                        JsonElement execution =
                                remote.executeCommand(current, "/permission " + preset);
                        showCommandResult(execution);
                        refreshState.run();
                    } catch (Exception error) {
                        report(error);
                    }
                });
    }

    private void showCommandResult(JsonElement execution) {
        if (execution == null || !execution.isJsonObject()) {
            return;
        }
        JsonObject result =
                execution.getAsJsonObject().has("result")
                                && execution.getAsJsonObject().get("result").isJsonObject()
                        ? execution.getAsJsonObject().getAsJsonObject("result")
                        : null;
        String kind = result == null ? null : DshJson.string(result, "kind");
        String text = result == null ? null : DshJson.string(result, "text");
        if ("error".equals(kind)) {
            throw new IllegalStateException(
                    text == null ? DshBundle.message("dsh.command.failed") : text);
        }
        if (text != null && !text.isBlank()) {
            notifyUser(text.trim());
        }
    }

    private void report(Exception error) {
        errorSink.accept(DshJson.message(error));
        stateChanged.run();
    }

    private void notifyUser(String message) {
        notifier.accept(message);
    }

    private record ModelChoice(String provider, String model) {}

    /** Model picker with enough horizontal space for provider/model identifiers. */
}
