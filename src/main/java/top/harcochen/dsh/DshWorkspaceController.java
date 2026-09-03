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

/** Provides the native management flow for Harness workspace registrations. */
final class DshWorkspaceController {
    private final Project project;
    private final DshRpcClient client;
    private final ExecutorService operations;
    private final Runnable refreshState;
    private final Runnable stateChanged;
    private final Consumer<String> notifier;
    private final Consumer<String> errorSink;

    DshWorkspaceController(
            Project project,
            DshRpcClient client,
            ExecutorService operations,
            Runnable refreshState,
            Runnable stateChanged,
            Consumer<String> notifier,
            Consumer<String> errorSink) {
        this.project = project;
        this.client = client;
        this.operations = operations;
        this.refreshState = refreshState;
        this.stateChanged = stateChanged;
        this.notifier = notifier;
        this.errorSink = errorSink;
    }

    void manage() {
        operations.execute(
                () -> {
                    try {
                        JsonObject catalog = client.workspaces();
                        JsonArray items =
                                catalog.has("items") && catalog.get("items").isJsonArray()
                                        ? catalog.getAsJsonArray("items")
                                        : new JsonArray();
                        List<String> labels = new ArrayList<>();
                        List<JsonObject> workspaces = new ArrayList<>();
                        for (JsonElement candidate : items) {
                            if (!candidate.isJsonObject()) {
                                continue;
                            }
                            JsonObject workspace = candidate.getAsJsonObject();
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
        String[] actions = {
            DshBundle.message("dsh.workspace.action.rename"),
            DshBundle.message("dsh.workspace.action.remove"),
            DshBundle.message("dsh.workspace.action.cancel")
        };
        int action =
                Messages.showChooseDialog(
                        project,
                        title + "\n" + DshJson.stringOr(workspace, "path", ""),
                        DshBundle.message("dsh.workspace.action.title"),
                        Messages.getQuestionIcon(),
                        actions,
                        actions[0]);
        if (action == 0) {
            rename(id, title);
        } else if (action == 1) {
            remove(id, title);
        }
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
                () -> client.renameWorkspace(id, replacement.trim()));
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
                    client.deleteWorkspace(id);
                    return null;
                });
    }

    private void createForProject() {
        String base = project.getBasePath();
        if (base == null) {
            notifyUser(DshBundle.message("dsh.workspace.no.project"));
            return;
        }
        runOperation("dsh.workspace.operation.registered", () -> client.createWorkspace(base));
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
