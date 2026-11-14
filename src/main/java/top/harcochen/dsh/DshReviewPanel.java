package top.harcochen.dsh;

import com.google.gson.JsonObject;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.DiffRequestPanel;
import com.intellij.diff.contents.DocumentContent;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import java.awt.BorderLayout;
import java.awt.event.ActionEvent;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;

/** One review surface for pending tool calls, settled tool edits, and saved turn snapshots. */
final class DshReviewPanel implements Disposable {
    record Content(String before, String after) {}

    record File(
            String label,
            String path,
            String approvalKey,
            Callable<Content> load,
            Function<String, CompletionStage<String>> decide,
            Function<Boolean, CompletionStage<RollbackResult>> rollback,
            boolean hunkRestorable) {
        File(
                String label,
                String path,
                String approvalKey,
                Callable<Content> load,
                Function<String, CompletionStage<String>> decide) {
            this(label, path, approvalKey, load, decide, null, false);
        }
    }

    record RollbackResult(boolean complete, String message) {}

    private final Project project;
    private List<File> files;
    private final ExecutorService operations;
    private final Function<JsonObject, String> attachRevision;
    private final DiffRequestPanel diff;
    private final JPanel component;
    private final Runnable requestClose;
    private Runnable modifiedListener = () -> {};
    private boolean replacing;
    private final JList<String> list =
            new com.intellij.ui.components.JBList<>(new DefaultListModel<>());
    private final com.intellij.ui.OnePixelSplitter split =
            new com.intellij.ui.OnePixelSplitter("DSH.Review.Split", 0.2f);
    private Action previous;
    private Action next;
    private Action refresh;
    private final JTextArea status = new JTextArea();
    private final Map<File, DocumentContent> drafts = new HashMap<>();
    private final Map<File, Content> contents = new HashMap<>();
    private final Set<String> settled = new HashSet<>();
    private final Set<String> revisionRejected = new HashSet<>();
    private Action approve;
    private Action reject;
    private Action revise;
    private Action revertFile;
    private Action revertHunk;
    private final Set<File> reverted = new HashSet<>();
    private boolean busy;
    private boolean closed;
    private int loadEpoch;
    private final java.util.function.Consumer<DshRuntimeService.RuntimeStatus> settingsListener =
            ignored -> reviewSettingsChanged();

    private void reviewSettingsChanged() {
        ApplicationManager.getApplication()
                .invokeLater(
                        () -> {
                            if (!closed
                                    && !DshSettingsState.getInstance(project).enableChangeReview) {
                                updateActions();
                                if (canClose()) requestClose.run();
                            }
                        });
    }

    DshReviewPanel(
            Project project,
            List<File> files,
            int selected,
            ExecutorService operations,
            Function<JsonObject, String> attachRevision,
            Runnable requestClose) {
        this.project = project;
        this.files = List.copyOf(files);
        this.operations = operations;
        this.attachRevision = attachRevision;
        this.requestClose = requestClose;
        DshRuntimeService runtime = DshRuntimeService.getInstance(project);
        runtime.addStatusListener(settingsListener);
        com.intellij.openapi.util.Disposer.register(
                this, () -> runtime.removeStatusListener(settingsListener));
        diff = DiffManager.getInstance().createRequestPanel(project, this, null);
        status.setEditable(false);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);
        status.setOpaque(false);
        status.setRows(2);
        status.setFont(javax.swing.UIManager.getFont("Label.font"));
        status.setVisible(false);
        DefaultListModel<String> model = (DefaultListModel<String>) list.getModel();
        for (File file : files) model.addElement(file.label());
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addListSelectionListener(
                event -> {
                    if (!replacing && !event.getValueIsAdjusting()) select();
                });
        component = createComponent();
        if (files.isEmpty()) setStatus(DshBundle.message("dsh.review.empty"));
        else list.setSelectedIndex(Math.max(0, Math.min(selected, files.size() - 1)));
        updateActions();
    }

    void bind(top.harcochen.dsh.remote.DshRemoteService remote, String session, long generation) {
        long accessRevision = remote.access().revision();
        java.util.function.Consumer<top.harcochen.dsh.remote.DshRemoteState.Snapshot> listener =
                snapshot -> {
                    if (snapshot.generation != generation
                            || remote.access().revision() != accessRevision
                            || !remote.access().allows("session/follow", "session", session))
                        ApplicationManager.getApplication()
                                .invokeLater(
                                        () -> {
                                            if (!closed) requestClose.run();
                                        });
                };
        java.util.function.Consumer<top.harcochen.dsh.remote.DshRemoteState.Snapshot>
                stateListener =
                        snapshot -> {
                            var view = snapshot.sessions.get(session);
                            if (view == null || snapshot.generation != generation) return;
                            java.util.Map<String, String> states = new HashMap<>();
                            for (var raw : view.interactions)
                                if (raw.isJsonObject()) {
                                    JsonObject item = raw.getAsJsonObject();
                                    states.put(
                                            DshJson.string(item, "key"),
                                            DshJson.string(item, "status"));
                                }
                            ApplicationManager.getApplication()
                                    .invokeLater(
                                            () -> {
                                                if (closed || busy) return;
                                                File active = selected();
                                                for (File file : files)
                                                    if (file.approvalKey() != null) {
                                                        String state =
                                                                states.get(file.approvalKey());
                                                        if (!revisionRejected.contains(
                                                                        file.approvalKey())
                                                                && (state == null
                                                                        || java.util.Set.of(
                                                                                        "resolved",
                                                                                        "unavailable",
                                                                                        "cancelled")
                                                                                .contains(state)))
                                                            settled.add(file.approvalKey());
                                                        if (settled.contains(file.approvalKey())
                                                                && drafts.containsKey(file))
                                                            drafts.get(file)
                                                                    .getDocument()
                                                                    .setReadOnly(true);
                                                    }
                                                if (active != null
                                                        && active.approvalKey() != null
                                                        && settled.contains(active.approvalKey()))
                                                    setStatus(
                                                            DshBundle.message(
                                                                    "dsh.review.settled"));
                                                updateActions();
                                            });
                        };
        remote.addListener(stateListener);
        com.intellij.openapi.util.Disposer.register(
                this, () -> remote.removeListener(stateListener));
        remote.addListener(listener);
        com.intellij.openapi.util.Disposer.register(this, () -> remote.removeListener(listener));
        stateListener.accept(remote.snapshot());
        listener.accept(remote.snapshot());
    }

    private JPanel createComponent() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        split.setFirstComponent(
                files.size() > 1 ? new com.intellij.ui.components.JBScrollPane(list) : null);
        split.setSecondComponent(diff.getComponent());
        DefaultActionGroup actions = new DefaultActionGroup();
        previous = action("dsh.review.previous", () -> navigate(-1));
        next = action("dsh.review.next", () -> navigate(1));
        refresh = action("dsh.review.refresh", this::refreshSelected);
        actions.add(toolbarAction(previous, com.intellij.icons.AllIcons.Actions.Back));
        actions.add(toolbarAction(next, com.intellij.icons.AllIcons.Actions.Forward));
        actions.add(toolbarAction(refresh, com.intellij.icons.AllIcons.Actions.Refresh));
        actions.addSeparator();
        for (Action action : createActions()) actions.add(toolbarAction(action, null));
        ActionToolbar toolbar =
                ActionManager.getInstance().createActionToolbar("DSH.Review", actions, true);
        toolbar.setTargetComponent(panel);
        panel.add(toolbar.getComponent(), BorderLayout.NORTH);
        panel.add(split, BorderLayout.CENTER);
        panel.add(status, BorderLayout.SOUTH);
        return panel;
    }

    private Action[] createActions() {
        approve = action("dsh.diff.approval.approve", () -> decide("allowed-once", false));
        reject = action("dsh.diff.approval.reject", () -> decide("rejected", false));
        revise = action("dsh.review.revise", () -> decide("rejected", true));
        revertFile = action("dsh.review.revert.file", () -> rollback(false));
        revertHunk = action("dsh.review.revert.hunk", () -> rollback(true));
        return new Action[] {approve, reject, revise, revertFile, revertHunk};
    }

    private Action action(String key, Runnable run) {
        return new AbstractAction(DshBundle.message(key)) {
            @Override
            public void actionPerformed(ActionEvent event) {
                if (!closed && isEnabled()) run.run();
            }
        };
    }

    private com.intellij.openapi.actionSystem.AnAction toolbarAction(
            Action action, javax.swing.Icon icon) {
        DumbAwareAction result =
                new DumbAwareAction((String) action.getValue(Action.NAME), null, icon) {
                    @Override
                    public ActionUpdateThread getActionUpdateThread() {
                        return ActionUpdateThread.EDT;
                    }

                    @Override
                    public void update(AnActionEvent event) {
                        event.getPresentation().setEnabled(!closed && action.isEnabled());
                    }

                    @Override
                    public void actionPerformed(AnActionEvent event) {
                        action.actionPerformed(
                                new ActionEvent(component, ActionEvent.ACTION_PERFORMED, "review"));
                    }
                };
        result.getTemplatePresentation()
                .putClientProperty(
                        com.intellij.openapi.actionSystem.ex.ActionUtil.SHOW_TEXT_IN_TOOLBAR,
                        icon == null);
        return result;
    }

    JComponent component() {
        return component;
    }

    JComponent preferredFocus() {
        return files.size() > 1 ? list : diff.getPreferredFocusedComponent();
    }

    void onModified(Runnable listener) {
        modifiedListener = listener;
    }

    boolean hasDrafts() {
        return drafts.keySet().stream()
                .anyMatch(file -> !settled.contains(file.approvalKey()) && changed(file));
    }

    void reopen(List<File> latest, int selected) {
        if (busy) {
            setStatus(DshBundle.message("dsh.review.submitting"));
            return;
        }
        if (hasDrafts()) {
            if (selected >= 0 && selected < latest.size()) {
                File target = latest.get(selected);
                for (int index = 0; index < files.size(); index++)
                    if (files.get(index).label().equals(target.label())
                            && java.util.Objects.equals(
                                    files.get(index).approvalKey(), target.approvalKey())) {
                        list.setSelectedIndex(index);
                        return;
                    }
            }
            setStatus(DshBundle.message("dsh.review.tab.pending"));
            return;
        }
        ++loadEpoch;
        replacing = true;
        files = List.copyOf(latest);
        drafts.clear();
        contents.clear();
        settled.clear();
        revisionRejected.clear();
        reverted.clear();
        DefaultListModel<String> model = (DefaultListModel<String>) list.getModel();
        model.clear();
        for (File file : files) model.addElement(file.label());
        split.setFirstComponent(
                files.size() > 1 ? new com.intellij.ui.components.JBScrollPane(list) : null);
        replacing = false;
        if (files.isEmpty()) {
            diff.setRequest(null);
            setStatus(DshBundle.message("dsh.review.empty"));
            updateActions();
        } else list.setSelectedIndex(Math.max(0, Math.min(selected, files.size() - 1)));
    }

    private void refreshSelected() {
        if (busy) return;
        File file = selected();
        if (file == null) return;
        if (changed(file)
                && com.intellij.openapi.ui.Messages.showYesNoDialog(
                                project,
                                DshBundle.message("dsh.review.discard"),
                                DshBundle.message("dsh.review.title"),
                                com.intellij.openapi.ui.Messages.getQuestionIcon())
                        != com.intellij.openapi.ui.Messages.YES) return;
        drafts.remove(file);
        contents.remove(file);
        select();
    }

    private void navigate(int delta) {
        if (busy) return;
        int index = list.getSelectedIndex() + delta;
        if (index >= 0 && index < files.size()) list.setSelectedIndex(index);
    }

    private File selected() {
        int index = list.getSelectedIndex();
        return index < 0 || index >= files.size() ? null : files.get(index);
    }

    private void select() {
        File file = selected();
        int epoch = ++loadEpoch;
        diff.setRequest(null);
        if (file == null) {
            updateActions();
            return;
        }
        if (contents.containsKey(file)) {
            show(file);
            return;
        }
        setStatus(DshBundle.message("dsh.review.loading"));
        updateActions();
        CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return file.load().call();
                            } catch (Exception error) {
                                throw new java.util.concurrent.CompletionException(error);
                            }
                        },
                        operations)
                .whenComplete(
                        (content, error) ->
                                ApplicationManager.getApplication()
                                        .invokeLater(
                                                () -> {
                                                    if (closed
                                                            || project.isDisposed()
                                                            || epoch != loadEpoch) return;
                                                    if (error != null)
                                                        setStatus(errorMessage(error));
                                                    else {
                                                        contents.put(file, content);
                                                        show(file);
                                                    }
                                                    updateActions();
                                                }));
    }

    private void show(File file) {
        Content content = contents.get(file);
        var type = FileTypeManager.getInstance().getFileTypeByFileName(file.path());
        DiffContentFactory factory = DiffContentFactory.getInstance();
        DocumentContent after =
                drafts.computeIfAbsent(
                        file,
                        ignored -> {
                            DocumentContent created =
                                    file.decide() == null
                                            ? factory.create(project, content.after(), type)
                                            : factory.createEditable(
                                                    project, content.after(), type);
                            created.getDocument()
                                    .setReadOnly(
                                            file.decide() == null
                                                    || settled.contains(file.approvalKey()));
                            created.getDocument()
                                    .addDocumentListener(
                                            new DocumentListener() {
                                                @Override
                                                public void documentChanged(DocumentEvent event) {
                                                    updateActions();
                                                }
                                            },
                                            DshReviewPanel.this);
                            return created;
                        });
        SimpleDiffRequest request =
                new SimpleDiffRequest(
                        file.label(),
                        factory.create(project, content.before(), type),
                        after,
                        DshBundle.message("dsh.diff.before"),
                        DshBundle.message("dsh.diff.proposed"));
        diff.setRequest(request);
        setStatus(
                settled.contains(file.approvalKey())
                        ? DshBundle.message("dsh.review.settled")
                        : "");
        updateActions();
    }

    private boolean changed(File file) {
        return drafts.containsKey(file)
                && !drafts.get(file).getDocument().getText().equals(contents.get(file).after());
    }

    private boolean groupChanged(File file) {
        return files.stream()
                .anyMatch(
                        other ->
                                java.util.Objects.equals(file.approvalKey(), other.approvalKey())
                                        && changed(other));
    }

    private void updateActions() {
        if (approve == null) return;
        for (var entry : drafts.entrySet())
            entry.getValue()
                    .getDocument()
                    .setReadOnly(
                            busy
                                    || entry.getKey().decide() == null
                                    || settled.contains(entry.getKey().approvalKey()));
        File file = selected();
        boolean pending =
                !busy
                        && DshSettingsState.getInstance(project).enableChangeReview
                        && file != null
                        && file.decide() != null
                        && contents.containsKey(file)
                        && !settled.contains(file.approvalKey());
        boolean rejected = file != null && revisionRejected.contains(file.approvalKey());
        approve.setEnabled(pending && !rejected && !groupChanged(file));
        reject.setEnabled(pending && !rejected);
        revise.setEnabled(pending && groupChanged(file));
        boolean canRevert =
                !busy
                        && DshSettingsState.getInstance(project).enableChangeReview
                        && file != null
                        && file.rollback() != null
                        && !reverted.contains(file);
        revertFile.setEnabled(canRevert);
        revertHunk.setEnabled(canRevert && file.hunkRestorable() && contents.containsKey(file));
        previous.setEnabled(!busy && list.getSelectedIndex() > 0);
        next.setEnabled(
                !busy
                        && list.getSelectedIndex() >= 0
                        && list.getSelectedIndex() + 1 < files.size());
        refresh.setEnabled(!busy && file != null);
        list.setEnabled(!busy);
        modifiedListener.run();
    }

    private void rollback(boolean hunk) {
        File file = selected();
        if (busy
                || file == null
                || file.rollback() == null
                || reverted.contains(file)
                || (hunk && !file.hunkRestorable())) return;
        busy = true;
        setStatus(DshBundle.message("dsh.review.submitting"));
        updateActions();
        file.rollback()
                .apply(hunk)
                .whenComplete(
                        (result, error) ->
                                ApplicationManager.getApplication()
                                        .invokeLater(
                                                () -> {
                                                    if (closed || project.isDisposed()) return;
                                                    busy = false;
                                                    if (error != null)
                                                        setStatus(errorMessage(error));
                                                    else if (result != null) {
                                                        if (result.complete()) reverted.add(file);
                                                        if (result.message() != null)
                                                            setStatus(result.message());
                                                    }
                                                    updateActions();
                                                }));
    }

    private void decide(String outcome, boolean revision) {
        File file = selected();
        if (busy
                || file == null
                || file.decide() == null
                || settled.contains(file.approvalKey())
                || (!revision && revisionRejected.contains(file.approvalKey()))
                || !DshSettingsState.getInstance(project).enableChangeReview) return;
        JsonObject item = new JsonObject();
        if (revision) {
            StringBuilder text =
                    new StringBuilder(
                            "Please revise the rejected tool call using these user-edited file contents. "
                                    + "Read the current files first and request approval for the revised edits.\n");
            for (File other : files)
                if (file.approvalKey().equals(other.approvalKey()) && changed(other)) {
                    text.append("\nFile: ")
                            .append(other.path())
                            .append('\n')
                            .append(drafts.get(other).getDocument().getText())
                            .append('\n');
                }
            if (text.toString().getBytes(StandardCharsets.UTF_8).length > 65536) {
                setStatus(DshBundle.message("dsh.review.revision.large"));
                return;
            }
            item.addProperty("id", "review:" + file.approvalKey());
            item.addProperty("kind", "review");
            item.addProperty("path", "review:" + file.approvalKey());
            item.addProperty("label", DshBundle.message("dsh.review.revision.label"));
            item.addProperty("content", text.toString());
            item.addProperty("byteLength", text.toString().getBytes(StandardCharsets.UTF_8).length);
        }
        busy = true;
        setStatus(DshBundle.message("dsh.review.submitting"));
        updateActions();
        // Recheck every loaded file in this tool call before granting it. Preview drift requires
        // reopening.
        CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                if ("allowed-once".equals(outcome))
                                    for (File other : files)
                                        if (file.approvalKey().equals(other.approvalKey())
                                                && contents.containsKey(other)
                                                && !contents.get(other).equals(other.load().call()))
                                            return DshBundle.message("dsh.files.changed");
                                return null;
                            } catch (Exception error) {
                                return DshJson.message(error);
                            }
                        },
                        operations)
                .thenCompose(
                        failure ->
                                failure == null
                                        ? revisionRejected.contains(file.approvalKey())
                                                ? CompletableFuture.completedFuture(null)
                                                : file.decide().apply(outcome)
                                        : CompletableFuture.completedFuture(failure))
                .whenComplete(
                        (failure, error) ->
                                ApplicationManager.getApplication()
                                        .invokeLater(
                                                () -> {
                                                    if (closed || project.isDisposed()) return;
                                                    busy = false;
                                                    if (error != null)
                                                        setStatus(errorMessage(error));
                                                    else if (failure != null) setStatus(failure);
                                                    else {
                                                        if (revision) {
                                                            revisionRejected.add(
                                                                    file.approvalKey());
                                                            String attachError;
                                                            try {
                                                                attachError =
                                                                        attachRevision.apply(item);
                                                            } catch (
                                                                    RuntimeException
                                                                            attachmentFailure) {
                                                                attachError =
                                                                        DshJson.message(
                                                                                attachmentFailure);
                                                            }
                                                            if (attachError == null) {
                                                                revisionRejected.remove(
                                                                        file.approvalKey());
                                                                settled.add(file.approvalKey());
                                                            }
                                                            setStatus(revisionStatus(attachError));
                                                        } else {
                                                            settled.add(file.approvalKey());
                                                            setStatus(
                                                                    DshBundle.message(
                                                                            "allowed-once"
                                                                                            .equals(
                                                                                                    outcome)
                                                                                    ? "dsh.review.approved"
                                                                                    : "dsh.review.rejected"));
                                                        }
                                                        for (File other : files)
                                                            if (file.approvalKey()
                                                                            .equals(
                                                                                    other
                                                                                            .approvalKey())
                                                                    && settled.contains(
                                                                            file.approvalKey())
                                                                    && drafts.containsKey(other))
                                                                drafts.get(other)
                                                                        .getDocument()
                                                                        .setReadOnly(true);
                                                    }
                                                    updateActions();
                                                }));
    }

    private void setStatus(String message) {
        status.setText(message);
        status.setVisible(message != null && !message.isBlank());
    }

    private static String revisionStatus(String failure) {
        return failure == null
                ? DshBundle.message("dsh.review.revision.ready")
                : failure + "\n" + DshBundle.message("dsh.review.revision.retry");
    }

    private static String errorMessage(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error.getMessage() == null ? error.toString() : error.getMessage();
    }

    boolean canClose() {
        if (busy) {
            setStatus(DshBundle.message("dsh.review.submitting"));
            return false;
        }
        if (hasDrafts()
                && com.intellij.openapi.ui.Messages.showYesNoDialog(
                                project,
                                DshBundle.message("dsh.review.discard"),
                                DshBundle.message("dsh.review.title"),
                                com.intellij.openapi.ui.Messages.getQuestionIcon())
                        != com.intellij.openapi.ui.Messages.YES) return false;
        return true;
    }

    @Override
    public void dispose() {
        closed = true;
        ++loadEpoch;
    }
}
