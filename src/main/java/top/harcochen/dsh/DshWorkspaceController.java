package top.harcochen.dsh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import top.harcochen.dsh.remote.DshRemoteService;
import top.harcochen.dsh.remote.DshRemoteState;

/** Provides the native management flow for Harness workspace registrations. */
final class DshWorkspaceController {
    private final Project project;
    private final DshRemoteService remote;
    private final ExecutorService operations;
    private final Runnable refreshState;
    private final Runnable stateChanged;
    private final Consumer<String> notifier;
    private final Consumer<String> errorSink;

    DshWorkspaceController(
            Project project,
            DshRemoteService remote,
            ExecutorService operations,
            Runnable refreshState,
            Runnable stateChanged,
            Consumer<String> notifier,
            Consumer<String> errorSink) {
        this.project = project;
        this.remote = remote;
        this.operations = operations;
        this.refreshState = refreshState;
        this.stateChanged = stateChanged;
        this.notifier = notifier;
        this.errorSink = errorSink;
    }

    void manage() {
        // The workspace registry is authoritative from workspace/follow; no unary list exists.
        DshRemoteState.Snapshot snapshot = remote.snapshot();
        operations.execute(
                () -> {
                    try {
                        List<String> labels = new ArrayList<>();
                        List<JsonObject> workspaces = new ArrayList<>();
                        for (JsonObject workspace : snapshot.workspaces) {
                            String id = DshJson.string(workspace, "workspaceId");
                            if (id == null) {
                                continue;
                            }
                            JsonArray sessionIds =
                                    workspace.has("sessionIds")
                                                    && workspace.get("sessionIds").isJsonArray()
                                            ? workspace.getAsJsonArray("sessionIds")
                                            : new JsonArray();
                            labels.add(
                                    DshJson.stringOr(workspace, "title", id)
                                            + "  —  "
                                            + DshJson.stringOr(workspace, "path", "")
                                            + "  ("
                                            + sessionIds.size()
                                            + " sessions)");
                            workspaces.add(workspace);
                        }
                        labels.add(DshBundle.message("dsh.workspace.register.current"));
                        labels.add(DshBundle.message("dsh.workspace.close"));
                        ApplicationManager.getApplication()
                                .invokeLater(() -> chooseAction(labels, workspaces));
                    } catch (Exception error) {
                        String message = DshJson.message(error);
                        errorSink.accept(message);
                        notifyUser(DshBundle.message("dsh.workspace.read.failed", message));
                        stateChanged.run();
                    }
                });
    }

    private void chooseAction(List<String> labels, List<JsonObject> workspaces) {
        int selected =
                Messages.showChooseDialog(
                        project,
                        DshBundle.message("dsh.workspace.dialog.message"),
                        DshBundle.message("dsh.workspace.dialog.title"),
                        Messages.getQuestionIcon(),
                        labels.toArray(new String[0]),
                        labels.get(0));
        if (selected < 0 || selected >= labels.size() || selected == labels.size() - 1) {
            return;
        }
        if (selected == workspaces.size()) {
            createForProject();
            return;
        }
        JsonObject workspace = workspaces.get(selected);
        String id = DshJson.string(workspace, "workspaceId");
        String title = DshJson.stringOr(workspace, "title", id);
        int index = selected;
        List<String> actionLabels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        actionLabels.add(DshBundle.message("dsh.workspace.action.rename"));
        actions.add(() -> rename(id, title));
        actionLabels.add(DshBundle.message("dsh.workspace.action.remove"));
        actions.add(() -> remove(id, title));
        if (index > 0) {
            actionLabels.add(DshBundle.message("dsh.workspace.action.move.up"));
            actions.add(() -> moveWorkspace(workspace, workspaces.get(index - 1)));
        }
        if (index < workspaces.size() - 1) {
            actionLabels.add(DshBundle.message("dsh.workspace.action.move.down"));
            actions.add(
                    () -> {
                        // insertBefore(next, id) places the next row before this
                        // one, which is a move-down for the selected workspace.
                        moveWorkspace(workspaces.get(index + 1), workspace);
                    });
        }
        JsonArray sessionIds =
                workspace.has("sessionIds") && workspace.get("sessionIds").isJsonArray()
                        ? workspace.getAsJsonArray("sessionIds")
                        : new JsonArray();
        if (!sessionIds.isEmpty()) {
            actionLabels.add(DshBundle.message("dsh.workspace.action.reorder"));
            actions.add(() -> reorderSessions(id, title, sessionIds));
        }
        actionLabels.add(DshBundle.message("dsh.workspace.action.cancel"));
        String[] actionLabelsArray = actionLabels.toArray(new String[0]);
        int action =
                Messages.showChooseDialog(
                        project,
                        title + "\n" + DshJson.stringOr(workspace, "path", ""),
                        DshBundle.message("dsh.workspace.action.title"),
                        Messages.getQuestionIcon(),
                        actionLabelsArray,
                        actionLabelsArray[0]);
        if (action >= 0 && action < actions.size()) {
            actions.get(action).run();
        }
    }

    private void moveWorkspace(JsonObject workspace, JsonObject before) {
        String id = DshJson.string(workspace, "workspaceId");
        String beforeId = DshJson.string(before, "workspaceId");
        runOperation(
                "dsh.workspace.operation.reordered",
                () -> {
                    remote.insertWorkspaceBefore(id, beforeId);
                    return null;
                });
    }

    /** Move one workspace session up or down via workspace/insertSessionBefore. */
    private void reorderSessions(String workspaceId, String title, JsonArray sessionIds) {
        List<String> choices = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (JsonElement candidate : sessionIds) {
            if (!candidate.isJsonPrimitive()) continue;
            String id = candidate.getAsString();
            String sessionTitle = titleForSession(id);
            choices.add((sessionTitle == null ? id : sessionTitle) + "  (" + abbreviate(id) + ")");
            ids.add(id);
        }
        if (ids.isEmpty()) {
            notifyUser(DshBundle.message("dsh.workspace.session.pick.message", title));
            return;
        }
        ApplicationManager.getApplication()
                .invokeLater(
                        () -> {
                            int picked =
                                    Messages.showChooseDialog(
                                            project,
                                            DshBundle.message(
                                                    "dsh.workspace.session.pick.message", title),
                                            DshBundle.message("dsh.workspace.session.pick.title"),
                                            Messages.getQuestionIcon(),
                                            choices.toArray(new String[0]),
                                            choices.get(0));
                            if (picked < 0 || picked >= ids.size()) return;
                            chooseSessionMove(workspaceId, ids, picked);
                        });
    }

    private void chooseSessionMove(String workspaceId, List<String> ids, int picked) {
        String id = ids.get(picked);
        List<String> moves = new ArrayList<>();
        List<int[]> deltas = new ArrayList<>();
        if (picked > 0) {
            moves.add(DshBundle.message("dsh.workspace.action.move.up"));
            deltas.add(new int[] {-1});
        }
        if (picked < ids.size() - 1) {
            moves.add(DshBundle.message("dsh.workspace.action.move.down"));
            deltas.add(new int[] {1});
        }
        moves.add(DshBundle.message("dsh.workspace.action.cancel"));
        String[] labels = moves.toArray(new String[0]);
        ApplicationManager.getApplication()
                .invokeLater(
                        () -> {
                            int move =
                                    Messages.showChooseDialog(
                                            project,
                                            DshBundle.message(
                                                    "dsh.workspace.session.move.message",
                                                    abbreviate(id)),
                                            DshBundle.message("dsh.workspace.session.pick.title"),
                                            Messages.getQuestionIcon(),
                                            labels,
                                            labels[0]);
                            if (move < 0 || move >= deltas.size()) return;
                            int delta = deltas.get(move)[0];
                            String before =
                                    picked + delta + 1 < ids.size()
                                            ? ids.get(picked + delta + 1)
                                            : null;
                            runOperation(
                                    "dsh.workspace.operation.reordered",
                                    () -> {
                                        remote.insertWorkspaceSessionBefore(
                                                workspaceId, id, before);
                                        return null;
                                    });
                        });
    }

    private String titleForSession(String sessionId) {
        for (JsonElement candidate : remote.snapshot().catalog) {
            if (candidate.isJsonObject()
                    && sessionId.equals(DshJson.string(candidate.getAsJsonObject(), "sessionId"))) {
                return DshJson.stringOr(candidate.getAsJsonObject(), "title", null);
            }
        }
        return null;
    }

    private static String abbreviate(String value) {
        return value.length() <= 12 ? value : value.substring(0, 12) + "…";
    }

    private void rename(String id, String title) {
        String replacement =
                Messages.showInputDialog(
                        project,
                        DshBundle.message("dsh.workspace.rename.message"),
                        DshBundle.message("dsh.workspace.rename.title"),
                        Messages.getQuestionIcon(),
                        title,
                        null);
        if (replacement == null || replacement.isBlank()) {
            return;
        }
        runOperation(
                "dsh.workspace.operation.renamed",
                () -> remote.renameWorkspace(id, replacement.trim()));
    }

    private void remove(String id, String title) {
        int confirmed =
                Messages.showYesNoDialog(
                        project,
                        DshBundle.message("dsh.workspace.remove.confirm.message", title),
                        DshBundle.message("dsh.workspace.remove.confirm.title"),
                        Messages.getWarningIcon());
        if (confirmed != Messages.YES) {
            return;
        }
        runOperation(
                "dsh.workspace.operation.removed",
                () -> {
                    remote.deleteWorkspace(id);
                    return null;
                });
    }

    private void createForProject() {
        String base = project.getBasePath();
        if (base == null) {
            notifyUser(DshBundle.message("dsh.workspace.no.project"));
            return;
        }
        runOperation("dsh.workspace.operation.registered", () -> remote.createWorkspace(base));
    }

    private void runOperation(String successKey, WorkspaceOperation operation) {
        operations.execute(
                () -> {
                    try {
                        operation.run();
                        notifyUser(DshBundle.message(successKey));
                        refreshState.run();
                    } catch (Exception error) {
                        String message = DshJson.message(error);
                        errorSink.accept(message);
                        notifyUser(DshBundle.message("dsh.workspace.operation.failed", message));
                        stateChanged.run();
                    }
                });
    }

    private void notifyUser(String message) {
        notifier.accept(message);
    }

    @FunctionalInterface
    private interface WorkspaceOperation {
        JsonObject run() throws Exception;
    }
}
