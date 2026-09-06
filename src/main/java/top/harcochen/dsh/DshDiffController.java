package top.harcochen.dsh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

    void openToolDiff(String callId, String path) {
        String current = sessionId.get();
        if (callId == null || path == null || current == null) {
            return;
        }
        operations.execute(
                () -> {
                    try {
                        JsonObject history = remote.traceHistory(current, 160);
                        DshToolDiff.CallDiffState state =
                                DshToolDiff.callDiffState(history, callId);
                        if (state == null) {
                            throw new IllegalStateException("This diff is no longer available.");
                        }
                        Path absolute = resolveAgainstProject(path);
                        FileContents file = readFile(absolute, path, state.settled);
                        List<DshToolDiff.FileDiff> hunks = new ArrayList<>();
                        for (DshToolDiff.FileDiff diff : state.view.diffs) {
                            if (diff.path.equals(path)) {
                                hunks.add(diff);
                            }
                        }
                        if (hunks.isEmpty()) {
                            throw new IllegalStateException(
                                    "This diff no longer covers " + path + ".");
                        }
                        DiffSides sides =
                                reconstructSides(history, callId, path, state, file, hunks);
                        showTextDiff(
                                path + (state.settled ? " (tool edit)" : " (proposed edit)"),
                                sides.before(),
                                sides.after(),
                                path,
                                DshBundle.message("dsh.diff.before"),
                                state.settled
                                        ? DshBundle.message("dsh.diff.after")
                                        : DshBundle.message("dsh.diff.proposed"));
                    } catch (Exception error) {
                        report(error);
                    }
                });
    }

    private static FileContents readFile(Path absolute, String path, boolean settled) {
        try {
            return new FileContents(
                    DshToolDiff.normalizeNewlines(
                            Files.readString(absolute, StandardCharsets.UTF_8)),
                    true);
        } catch (Exception unreadable) {
            if (settled) {
                throw new IllegalStateException(
                        "“" + path + "” is no longer readable, so its diff cannot be rebuilt.");
            }
            return new FileContents("", false);
        }
    }

    private static DiffSides reconstructSides(
            JsonObject history,
            String callId,
            String path,
            DshToolDiff.CallDiffState state,
            FileContents file,
            List<DshToolDiff.FileDiff> hunks) {
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
                        file.contents(), DshToolDiff.collectCallHunks(history, path), callId);
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
        if (turn <= 0 || fileId == null || current == null) {
            return;
        }
        operations.execute(
                () -> {
                    try {
                        DshChangeReviewStore.FileSides sides =
                                changeReviews.sides(current, turn, fileId);
                        if (sides.binary()) {
                            notifyUser(DshBundle.message("dsh.diff.binary.change"));
                            return;
                        }
                        showTextDiff(
                                sides.title(),
                                sides.beforeText(),
                                sides.afterText(),
                                sides.path(),
                                DshBundle.message("dsh.diff.before.turn", turn),
                                DshBundle.message("dsh.diff.after.turn", turn));
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
        operations.execute(() -> restore(current, turn, onSuccess));
    }

    private void restore(String current, int turn, Runnable onSuccess) {
        try {
            if (isSessionRunning(current)) {
                throw new IllegalStateException(DshBundle.message("dsh.restore.wait.for.turn"));
            }
            changeReviews.restore(current, turn);
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

    private Path resolveAgainstProject(String path) {
        Path candidate = Path.of(path);
        if (candidate.isAbsolute()) {
            return candidate;
        }
        String base = project.getBasePath();
        return base == null
                ? candidate.toAbsolutePath()
                : Path.of(base).resolve(candidate).normalize();
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
