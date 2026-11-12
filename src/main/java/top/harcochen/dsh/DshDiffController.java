package top.harcochen.dsh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;
import top.harcochen.dsh.remote.DshRemoteService;

/** Reconstructs tool/turn changes and presents native IntelliJ diff views. */
final class DshDiffController {
    private final Project project;
    private final DshRemoteService remote;
    private final DshChangeReviewStore changeReviews;
    private final ExecutorService operations;
    private final Supplier<String> sessionId;
    private final Supplier<JsonArray> sessions;
    private final Runnable stateChanged;
    private final Consumer<String> notifier;
    private final Consumer<String> errorSink;

    DshDiffController(
            Project project,
            DshRemoteService remote,
            DshChangeReviewStore changeReviews,
            ExecutorService operations,
            Supplier<String> sessionId,
            Supplier<JsonArray> sessions,
            Runnable stateChanged,
            Consumer<String> notifier,
            Consumer<String> errorSink) {
        this.project = project;
        this.remote = remote;
        this.changeReviews = changeReviews;
        this.operations = operations;
        this.sessionId = sessionId;
        this.sessions = sessions;
        this.stateChanged = stateChanged;
        this.notifier = notifier;
        this.errorSink = errorSink;
    }

    void openReview(
            String selectedCall,
            String selectedPath,
            java.util.Map<String, String> approvals,
            java.util.function.BiFunction<
                            String, String, java.util.concurrent.CompletionStage<String>>
                    decide,
            java.util.function.Function<JsonObject, String> attachRevision) {
        String current = sessionId.get();
        if (current == null) {
            notifyUser(DshBundle.message("dsh.review.no.session"));
            return;
        }
        boolean reviewEnabled = DshSettingsState.getInstance(project).enableChangeReview;
        if (!reviewEnabled && selectedCall == null) {
            notifyUser(DshBundle.message("dsh.review.disabled"));
            return;
        }
        long generation = remote.snapshot().generation;
        String original = originalDirectory(current);
        changeReviews.loadHistory(current);
        operations.execute(
                () -> {
                    try {
                        List<DshReviewDialog.File> files = new ArrayList<>();
                        JsonObject history = remote.traceHistory(current, 160);
                        var callStates = DshToolDiff.callDiffStates(history);
                        java.util.Set<String> callIds =
                                new java.util.LinkedHashSet<>(approvals.keySet());
                        callIds.addAll(callStates.keySet());
                        for (String callId : callIds) {
                            if (selectedCall != null && !selectedCall.equals(callId)) continue;
                            DshToolDiff.CallDiffState state = callStates.get(callId);
                            if (state == null) continue;
                            String key = state.settled ? null : approvals.get(callId);
                            for (String path : DshToolDiff.diffViewPaths(state.view)) {
                                String label =
                                        (key == null
                                                        ? DshBundle.message("dsh.review.tool")
                                                        : DshBundle.message("dsh.review.pending"))
                                                + " · "
                                                + path;
                                files.add(
                                        new DshReviewDialog.File(
                                                label,
                                                path,
                                                key,
                                                () -> {
                                                    assertGeneration(generation);
                                                    JsonObject latest =
                                                            remote.traceHistory(current, 160);
                                                    DshToolDiff.CallDiffState fresh =
                                                            DshToolDiff.callDiffState(
                                                                    latest, callId);
                                                    if (fresh == null
                                                            || (key != null && fresh.settled))
                                                        throw new IllegalStateException(
                                                                DshBundle.message(
                                                                        "dsh.interaction.unavailable"));
                                                    String absolute =
                                                            DshWorkingDirectory.resolve(
                                                                    path,
                                                                    DshWorkingDirectory.at(
                                                                            latest, fresh.seq,
                                                                            original));
                                                    FileContents content =
                                                            readHostFile(
                                                                    current,
                                                                    absolute,
                                                                    fresh.settled,
                                                                    generation);
                                                    List<DshToolDiff.FileDiff> hunks =
                                                            new ArrayList<>();
                                                    for (DshToolDiff.FileDiff hunk :
                                                            fresh.view.diffs)
                                                        if (path.equals(hunk.path)) hunks.add(hunk);
                                                    if (hunks.isEmpty())
                                                        throw new IllegalStateException(
                                                                "Diff is no longer available");
                                                    DiffSides sides =
                                                            reconstructSides(
                                                                    latest, callId, path, fresh,
                                                                    content, hunks, original);
                                                    assertGeneration(generation);
                                                    return new DshReviewDialog.Content(
                                                            sides.before(), sides.after());
                                                },
                                                key == null
                                                        ? null
                                                        : outcome -> {
                                                            if (remote.snapshot().generation
                                                                    != generation)
                                                                return java.util.concurrent
                                                                        .CompletableFuture
                                                                        .completedFuture(
                                                                                DshBundle.message(
                                                                                        "dsh.runtime.changed"));
                                                            return decide.apply(key, outcome);
                                                        }));
                            }
                        }
                        if (selectedCall == null) {
                            changeReviews.readSavedHistory(current);
                            appendTurnFiles(current, files);
                        }
                        assertGeneration(generation);
                        int index = 0;
                        if (selectedPath != null)
                            for (int i = 0; i < files.size(); i++)
                                if (selectedPath.equals(files.get(i).path())) {
                                    index = i;
                                    break;
                                }
                        int selected = index;
                        if (!reviewEnabled) {
                            if (files.isEmpty())
                                throw new IllegalStateException(
                                        DshBundle.message("dsh.review.empty"));
                            DshReviewDialog.File file = files.get(selected);
                            DshReviewDialog.Content content = file.load().call();
                            assertGeneration(generation);
                            showTextDiff(
                                    file.label(),
                                    content.before(),
                                    content.after(),
                                    file.path(),
                                    DshBundle.message("dsh.diff.before"),
                                    DshBundle.message("dsh.diff.proposed"));
                            return;
                        }
                        ApplicationManager.getApplication()
                                .invokeLater(
                                        () -> {
                                            if (project.isDisposed()
                                                    || remote.snapshot().generation != generation)
                                                return;
                                            DshReviewDialog dialog =
                                                    new DshReviewDialog(
                                                            project,
                                                            files,
                                                            selected,
                                                            operations,
                                                            item ->
                                                                    remote.snapshot().generation
                                                                                    != generation
                                                                            ? DshBundle.message(
                                                                                    "dsh.runtime.changed")
                                                                            : attachRevision.apply(
                                                                                    item));
                                            dialog.bind(remote, current, generation);
                                            dialog.show();
                                        });
                    } catch (Exception error) {
                        report(error);
                    }
                });
    }

    private void appendTurnFiles(String current, List<DshReviewDialog.File> files) {
        if (!remote.access().owner()) return;
        long generation = remote.snapshot().generation;
        for (JsonElement value : changeReviews.view(current)) {
            JsonObject review = value.getAsJsonObject();
            int turn = DshJson.integer(review, "turn", 0);
            if (!"ready".equals(DshJson.string(review, "state"))) continue;
            for (JsonElement raw : review.getAsJsonArray("files")) {
                JsonObject file = raw.getAsJsonObject();
                String path = DshJson.string(file, "path");
                String id = DshJson.string(file, "id");
                files.add(
                        new DshReviewDialog.File(
                                DshBundle.message("dsh.change.review.turn.prefix", turn)
                                        + (DshJson.bool(review, "archived", false)
                                                ? " · " + DshBundle.message("dsh.review.saved")
                                                : "")
                                        + " · "
                                        + path,
                                path,
                                null,
                                () -> {
                                    DshChangeReviewStore.FileSides sides =
                                            changeReviews.sides(current, turn, id);
                                    if (sides.binary())
                                        throw new IllegalStateException(
                                                DshBundle.message("dsh.files.binary"));
                                    return new DshReviewDialog.Content(
                                            sides.beforeText(), sides.afterText());
                                },
                                null,
                                DshJson.bool(file, "restorable", false)
                                        ? hunk ->
                                                rollback(current, turn, id, path, generation, hunk)
                                        : null));
            }
        }
    }

    private record RollbackSelection(Integer block, String revision) {}

    private java.util.concurrent.CompletionStage<DshReviewDialog.RollbackResult> rollback(
            String current, int turn, String id, String path, long generation, boolean hunk) {
        return java.util.concurrent.CompletableFuture.supplyAsync(
                () -> {
                    try {
                        List<Path> paths = changeReviews.revertPaths(current, turn, id);
                        assertWritable(current, generation, paths);
                        DshChangeReviewStore.HunkPreview preview =
                                hunk ? changeReviews.revertHunks(current, turn, id) : null;
                        if (preview != null && preview.blocks().isEmpty())
                            return new DshReviewDialog.RollbackResult(
                                    false, DshBundle.message("dsh.review.hunks.empty"));
                        RollbackSelection selection =
                                DshPluginManagementController.ui(
                                        () -> confirmRollback(path, preview));
                        if (selection == null)
                            return new DshReviewDialog.RollbackResult(
                                    false, DshBundle.message("dsh.review.revert.cancelled"));
                        boolean complete =
                                changeReviews.revert(
                                        current,
                                        turn,
                                        id,
                                        selection.block(),
                                        selection.revision(),
                                        () -> assertWritable(current, generation, paths));
                        com.intellij.openapi.vfs.VirtualFileManager.getInstance()
                                .asyncRefresh(null);
                        return new DshReviewDialog.RollbackResult(
                                complete, DshBundle.message("dsh.review.reverted", path));
                    } catch (Exception error) {
                        throw new java.util.concurrent.CompletionException(error);
                    }
                },
                operations);
    }

    private RollbackSelection confirmRollback(
            String path, DshChangeReviewStore.HunkPreview preview) {
        if (project.isDisposed()) return null;
        if (preview == null) {
            int answer =
                    Messages.showYesNoDialog(
                            project,
                            DshBundle.message("dsh.review.revert.file.confirm", path),
                            DshBundle.message("dsh.review.revert.file"),
                            Messages.getWarningIcon());
            return answer == Messages.YES ? new RollbackSelection(null, null) : null;
        }
        String[] labels =
                preview.blocks().stream()
                        .map(
                                value ->
                                        DshBundle.message(
                                                "dsh.review.hunk.lines",
                                                value.beforeLine(),
                                                value.beforeLines(),
                                                value.afterLine(),
                                                value.afterLines()))
                        .toArray(String[]::new);
        int chosen =
                DshChoiceDialog.show(
                        project,
                        path,
                        DshBundle.message("dsh.review.revert.hunk"),
                        Messages.getQuestionIcon(),
                        labels,
                        labels[0]);
        if (chosen < 0) return null;
        DshReviewHunks.Block selected = preview.blocks().get(chosen);
        String snippet = "− " + selected.after() + "\n+ " + selected.before();
        int answer =
                Messages.showYesNoDialog(
                        project,
                        DshBundle.message("dsh.review.revert.hunk.confirm", path)
                                + "\n\n"
                                + snippet.substring(0, Math.min(snippet.length(), 6000)),
                        DshBundle.message("dsh.review.revert.hunk"),
                        Messages.getWarningIcon());
        return answer == Messages.YES
                ? new RollbackSelection(selected.index(), preview.revision())
                : null;
    }

    private void assertWritable(String current, long generation, List<Path> paths) {
        assertGeneration(generation);
        if (!DshSettingsState.getInstance(project).enableChangeReview
                || !remote.access().owner()
                || !DshRuntimeService.getInstance(project).hasLocalProcess())
            throw new IllegalStateException(DshBundle.message("dsh.change.review.cannot.restore"));
        if (isSessionRunning(current))
            throw new IllegalStateException(DshBundle.message("dsh.restore.wait.for.turn"));
        assertSavedDocuments(paths);
    }

    static void assertSavedDocuments(List<Path> paths) {
        boolean dirty =
                com.intellij.openapi.application.ReadAction.compute(
                        () -> {
                            var manager =
                                    com.intellij.openapi.fileEditor.FileDocumentManager
                                            .getInstance();
                            for (var document : manager.getUnsavedDocuments()) {
                                var file = manager.getFile(document);
                                if (file == null) continue;
                                String canonical = file.getCanonicalPath();
                                Path edited =
                                        Path.of(canonical == null ? file.getPath() : canonical)
                                                .toAbsolutePath()
                                                .normalize();
                                if (paths.stream()
                                        .anyMatch(
                                                path ->
                                                        path.toAbsolutePath()
                                                                .normalize()
                                                                .equals(edited))) return true;
                            }
                            return false;
                        });
        if (dirty)
            throw new IllegalStateException(
                    DshBundle.message("dsh.review.unsaved", paths.toString()));
    }

    private void assertGeneration(long generation) {
        if (remote.snapshot().generation != generation)
            throw new IllegalStateException(DshBundle.message("dsh.runtime.changed"));
    }

    private FileContents readHostFile(
            String session, String absolute, boolean settled, long generation) {
        try {
            String text =
                    remote.workspaceFiles()
                            .preview(session, absolute, () -> assertGeneration(generation))
                            .text();
            return new FileContents(DshToolDiff.normalizeNewlines(text), true);
        } catch (top.harcochen.dsh.remote.DshRemoteException error) {
            if (!settled && "workspace-file/not-found".equals(error.code()))
                return new FileContents("", false);
            throw error;
        }
    }

    private static DiffSides reconstructSides(
            JsonObject history,
            String callId,
            String path,
            DshToolDiff.CallDiffState state,
            FileContents file,
            List<DshToolDiff.FileDiff> hunks,
            String original) {
        if (!state.settled) {
            String proposed = DshToolDiff.applyProposedHunks(file.contents(), hunks);
            if (proposed == null) {
                throw new IllegalStateException(
                        "“"
                                + path
                                + "” does not match what this call expects, so DSH cannot preview it.");
            }
            return new DiffSides(file.readable() ? file.contents() : "", proposed);
        }
        DshToolDiff.Rewind rewound =
                DshToolDiff.rewindAround(
                        file.contents(),
                        DshToolDiff.collectCallHunks(history, path, original, callId),
                        callId);
        if (rewound == null) {
            throw new IllegalStateException(
                    "“"
                            + path
                            + "” has changed since this edit, so DSH cannot rebuild a faithful diff of it.");
        }
        return new DiffSides(rewound.before, rewound.after);
    }

    void openChangeDiff(int turn, String fileId) {
        String current = sessionId.get();
        if (turn <= 0 || fileId == null || current == null) return;
        long generation = remote.snapshot().generation;
        operations.execute(
                () -> {
                    try {
                        changeReviews.loadHistory(current);
                        changeReviews.readSavedHistory(current);
                        DshChangeReviewStore.FileSides selected =
                                changeReviews.sides(current, turn, fileId);
                        List<DshReviewDialog.File> files = new ArrayList<>();
                        appendTurnFiles(current, files);
                        String prefix =
                                DshBundle.message("dsh.change.review.turn.prefix", turn) + " · ";
                        int index = 0;
                        for (int i = 0; i < files.size(); i++)
                            if (files.get(i).label().startsWith(prefix)
                                    && selected.path().equals(files.get(i).path())) {
                                index = i;
                                break;
                            }
                        int initial = index;
                        ApplicationManager.getApplication()
                                .invokeLater(
                                        () -> {
                                            if (project.isDisposed()
                                                    || remote.snapshot().generation != generation)
                                                return;
                                            DshReviewDialog dialog =
                                                    new DshReviewDialog(
                                                            project,
                                                            files,
                                                            initial,
                                                            operations,
                                                            ignored ->
                                                                    DshBundle.message(
                                                                            "dsh.review.history.hint"));
                                            dialog.bind(remote, current, generation);
                                            dialog.show();
                                        });
                    } catch (Exception error) {
                        report(error);
                    }
                });
    }

    void restoreTurnChanges(int turn) {
        restoreTurnChanges(turn, null);
    }

    /** Restore a turn and invoke {@code onSuccess} after the guarded write completes. */
    void restoreTurnChanges(int turn, Runnable onSuccess) {
        String current = sessionId.get();
        if (turn <= 0 || current == null) {
            return;
        }
        if (isSessionRunning(current)) {
            notifyUser(DshBundle.message("dsh.restore.wait.for.turn"));
            return;
        }
        if (!changeReviews.isRestorable(current, turn)) {
            notifyUser(DshBundle.message("dsh.restore.cannot.restore"));
            return;
        }
        int count = changeReviews.restorableFileCount(current, turn);
        ApplicationManager.getApplication()
                .invokeLater(() -> confirmRestore(current, turn, count, onSuccess));
    }

    private void confirmRestore(String current, int turn, int count, Runnable onSuccess) {
        int answer =
                Messages.showYesNoDialog(
                        project,
                        DshBundle.message("dsh.restore.confirm.message", count, turn),
                        DshBundle.message("dsh.restore.confirm.title"),
                        Messages.getWarningIcon());
        if (answer != Messages.YES) {
            return;
        }
        long generation = remote.snapshot().generation;
        operations.execute(() -> restore(current, turn, generation, onSuccess));
    }

    private void restore(String current, int turn, long generation, Runnable onSuccess) {
        try {
            if (isSessionRunning(current)) {
                throw new IllegalStateException(DshBundle.message("dsh.restore.wait.for.turn"));
            }
            List<Path> paths = changeReviews.restorePaths(current, turn);
            changeReviews.restore(current, turn, () -> assertWritable(current, generation, paths));
            com.intellij.openapi.vfs.VirtualFileManager.getInstance().asyncRefresh(null);
            notifyUser(DshBundle.message("dsh.restore.success", turn));
            if (onSuccess != null) {
                onSuccess.run();
            }
        } catch (Exception error) {
            report(error);
        }
    }

    private boolean isSessionRunning(String current) {
        for (JsonElement candidate : sessions.get()) {
            if (candidate.isJsonObject()
                    && current.equals(DshJson.string(candidate.getAsJsonObject(), "sessionId"))) {
                return DshJson.bool(candidate.getAsJsonObject(), "running", false);
            }
        }
        return false;
    }

    private String originalDirectory(String current) {
        for (JsonElement raw : sessions.get()) {
            if (raw.isJsonObject()
                    && current.equals(DshJson.string(raw.getAsJsonObject(), "sessionId")))
                return DshWorkingDirectory.original(raw.getAsJsonObject(), project.getBasePath());
        }
        return project.getBasePath();
    }

    private void showTextDiff(
            String title,
            String before,
            String after,
            String path,
            String beforeTitle,
            String afterTitle) {
        ApplicationManager.getApplication()
                .invokeLater(
                        () -> {
                            if (project.isDisposed()) return;
                            FileType fileType = fileTypeOf(path);
                            com.intellij.diff.DiffContentFactory factory =
                                    com.intellij.diff.DiffContentFactory.getInstance();
                            com.intellij.diff.requests.SimpleDiffRequest request =
                                    new com.intellij.diff.requests.SimpleDiffRequest(
                                            title,
                                            factory.create(
                                                    project,
                                                    before == null ? "" : before,
                                                    fileType),
                                            factory.create(
                                                    project, after == null ? "" : after, fileType),
                                            beforeTitle,
                                            afterTitle);
                            request.putUserData(
                                    com.intellij.diff.util.DiffUserDataKeys.FORCE_READ_ONLY, true);
                            com.intellij.diff.DiffManager.getInstance().showDiff(project, request);
                        });
    }

    /**
     * File type for a repository path. Runs on the EDT inside {@code invokeLater}, where a thrown
     * {@link java.nio.file.InvalidPathException} would escape the caller's catch and silently
     * suppress the diff view, so an unparseable path degrades to an unrecognized type instead.
     */
    private static FileType fileTypeOf(String path) {
        String name = path;
        try {
            Path fileName = Path.of(path).getFileName();
            if (fileName != null) name = fileName.toString();
        } catch (RuntimeException ignored) {
            int separator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            if (separator >= 0 && separator + 1 < path.length())
                name = path.substring(separator + 1);
        }
        return FileTypeManager.getInstance().getFileTypeByFileName(name);
    }

    private void report(Exception error) {
        String message = DshJson.message(error);
        errorSink.accept(message);
        notifyUser(message);
        stateChanged.run();
    }

    private void notifyUser(String message) {
        notifier.accept(message);
    }

    private record FileContents(String contents, boolean readable) {}

    private record DiffSides(String before, String after) {}
}
