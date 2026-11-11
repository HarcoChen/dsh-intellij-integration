package top.harcochen.dsh;

import com.google.gson.JsonObject;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.DiffRequestPanel;
import com.intellij.diff.contents.DocumentContent;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import java.awt.BorderLayout;
import java.awt.Dimension;
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
import javax.swing.Action;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;

/** One review surface for pending tool calls, settled tool edits, and saved turn snapshots. */
final class DshReviewDialog extends DialogWrapper {
    record Content(String before, String after) {}

    record File(
            String label,
            String path,
            String approvalKey,
            Callable<Content> load,
            Function<String, CompletionStage<String>> decide) {}

    private final Project project;
    private final List<File> files;
    private final ExecutorService operations;
    private final Function<JsonObject, String> attachRevision;
    private final DiffRequestPanel diff;
    private final JList<String> list = new JList<>(new DefaultListModel<>());
    private final JTextArea status = new JTextArea();
    private final Map<File, DocumentContent> drafts = new HashMap<>();
    private final Map<File, Content> contents = new HashMap<>();
    private final Set<String> settled = new HashSet<>();
    private Action approve;
    private Action reject;
    private Action revise;
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
                                    && !DshSettingsState.getInstance(project).enableChangeReview)
                                close(CANCEL_EXIT_CODE);
                        });
    }

    DshReviewDialog(
            Project project,
            List<File> files,
            int selected,
            ExecutorService operations,
            Function<JsonObject, String> attachRevision) {
        super(project, false);
        this.project = project;
        this.files = List.copyOf(files);
        this.operations = operations;
        this.attachRevision = attachRevision;
        DshRuntimeService runtime = DshRuntimeService.getInstance(project);
        runtime.addStatusListener(settingsListener);
        com.intellij.openapi.util.Disposer.register(
                getDisposable(), () -> runtime.removeStatusListener(settingsListener));
        diff = DiffManager.getInstance().createRequestPanel(project, getDisposable(), null);
        setTitle(DshBundle.message("dsh.review.title"));
        setModal(false);
        setCancelButtonText(DshBundle.message("dsh.diff.approval.close"));
        status.setEditable(false);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);
        status.setOpaque(false);
        status.setRows(3);
        DefaultListModel<String> model = (DefaultListModel<String>) list.getModel();
        for (File file : files) model.addElement(file.label());
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addListSelectionListener(
                event -> {
                    if (!event.getValueIsAdjusting()) select();
                });
        init();
        if (files.isEmpty()) status.setText(DshBundle.message("dsh.review.empty"));
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
                                            if (!closed) close(CANCEL_EXIT_CODE);
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
                                                        if (state == null
                                                                || java.util.Set.of(
                                                                                "resolved",
                                                                                "unavailable",
                                                                                "cancelled")
                                                                        .contains(state))
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
                                                    status.setText(
                                                            DshBundle.message(
                                                                    "dsh.review.settled"));
                                                updateActions();
                                            });
                        };
        remote.addListener(stateListener);
        com.intellij.openapi.util.Disposer.register(
                getDisposable(), () -> remote.removeListener(stateListener));
        remote.addListener(listener);
        com.intellij.openapi.util.Disposer.register(
                getDisposable(), () -> remote.removeListener(listener));
        stateListener.accept(remote.snapshot());
        listener.accept(remote.snapshot());
    }

    @Override
    protected JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        JSplitPane split =
                new JSplitPane(
                        JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(list), diff.getComponent());
        split.setResizeWeight(0.25);
        split.setDividerLocation(280);
        panel.add(split, BorderLayout.CENTER);
        panel.add(status, BorderLayout.SOUTH);
        panel.setPreferredSize(new Dimension(1100, 700));
        return panel;
    }

    @Override
    protected Action[] createActions() {
        approve = action("dsh.diff.approval.approve", () -> decide("allowed-once", false));
        reject = action("dsh.diff.approval.reject", () -> decide("rejected", false));
        revise = action("dsh.review.revise", () -> decide("rejected", true));
        return new Action[] {
            action("dsh.review.previous", () -> navigate(-1)),
            action("dsh.review.next", () -> navigate(1)),
            action("dsh.review.refresh", this::refreshSelected),
            approve,
            reject,
            revise,
            getCancelAction()
        };
    }

    private Action action(String key, Runnable run) {
        return new DialogWrapperAction(DshBundle.message(key)) {
            @Override
            protected void doAction(ActionEvent event) {
                run.run();
            }
        };
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
        status.setText(DshBundle.message("dsh.review.loading"));
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
                                                        status.setText(errorMessage(error));
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
                                            getDisposable());
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
        status.setText(
                settled.contains(file.approvalKey())
                        ? DshBundle.message("dsh.review.settled")
                        : file.decide() == null
                                ? DshBundle.message("dsh.review.history.hint")
                                : DshBundle.message("dsh.review.edit.hint"));
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
        File file = selected();
        boolean pending =
                !busy
                        && file != null
                        && file.decide() != null
                        && contents.containsKey(file)
                        && !settled.contains(file.approvalKey());
        approve.setEnabled(pending && !groupChanged(file));
        reject.setEnabled(pending);
        revise.setEnabled(pending && groupChanged(file));
        list.setEnabled(!busy);
        getCancelAction().setEnabled(!busy);
    }

    private void decide(String outcome, boolean revision) {
        File file = selected();
        if (busy
                || file == null
                || file.decide() == null
                || settled.contains(file.approvalKey())
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
                status.setText(DshBundle.message("dsh.review.revision.large"));
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
        status.setText(DshBundle.message("dsh.review.submitting"));
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
                                        ? file.decide().apply(outcome)
                                        : CompletableFuture.completedFuture(failure))
                .whenComplete(
                        (failure, error) ->
                                ApplicationManager.getApplication()
                                        .invokeLater(
                                                () -> {
                                                    if (closed || project.isDisposed()) return;
                                                    busy = false;
                                                    if (error != null)
                                                        status.setText(errorMessage(error));
                                                    else if (failure != null)
                                                        status.setText(failure);
                                                    else {
                                                        settled.add(file.approvalKey());
                                                        if (revision) {
                                                            String attachError =
                                                                    attachRevision.apply(item);
                                                            status.setText(
                                                                    attachError == null
                                                                            ? DshBundle.message(
                                                                                    "dsh.review.revision.ready")
                                                                            : attachError);
                                                        } else
                                                            status.setText(
                                                                    DshBundle.message(
                                                                            "allowed-once"
                                                                                            .equals(
                                                                                                    outcome)
                                                                                    ? "dsh.review.approved"
                                                                                    : "dsh.review.rejected"));
                                                        for (File other : files)
                                                            if (file.approvalKey()
                                                                            .equals(
                                                                                    other
                                                                                            .approvalKey())
                                                                    && drafts.containsKey(other))
                                                                drafts.get(other)
                                                                        .getDocument()
                                                                        .setReadOnly(true);
                                                    }
                                                    updateActions();
                                                }));
    }

    private static String errorMessage(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error.getMessage() == null ? error.toString() : error.getMessage();
    }

    @Override
    public void doCancelAction() {
        if (busy) return;
        if (drafts.keySet().stream()
                        .anyMatch(file -> !settled.contains(file.approvalKey()) && changed(file))
                && com.intellij.openapi.ui.Messages.showYesNoDialog(
                                project,
                                DshBundle.message("dsh.review.discard"),
                                DshBundle.message("dsh.review.title"),
                                com.intellij.openapi.ui.Messages.getQuestionIcon())
                        != com.intellij.openapi.ui.Messages.YES) return;
        super.doCancelAction();
    }

    @Override
    protected void dispose() {
        closed = true;
        ++loadEpoch;
        super.dispose();
    }
}
