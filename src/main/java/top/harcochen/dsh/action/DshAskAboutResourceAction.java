package top.harcochen.dsh.action;

import com.google.gson.JsonObject;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import java.nio.file.Path;
import top.harcochen.dsh.DshActions;
import top.harcochen.dsh.DshBundle;

/** Prefills a workspace-scoped prompt for one resource, mirroring dsh-ide's askAboutResource. */
public final class DshAskAboutResourceAction extends DumbAwareAction {
    @Override
    public void actionPerformed(AnActionEvent event) {
        Project project = event.getProject();
        VirtualFile file = event.getData(CommonDataKeys.VIRTUAL_FILE);
        if (project == null || file == null || project.getBasePath() == null) return;
        String relative = VfsUtilCore.getRelativePath(file, project.getBaseDir(), '/');
        if (relative == null || relative.isBlank()) {
            // The selection may live outside the content root; keep paths inside the project.
            Path base = Path.of(project.getBasePath()).normalize();
            Path candidate = Path.of(file.getPath()).normalize();
            if (!candidate.startsWith(base)) {
                NotificationGroupManager.getInstance()
                        .getNotificationGroup("DeepSeek Harness")
                        .createNotification(
                                DshBundle.message("dsh.ask.resource.outside"),
                                NotificationType.WARNING)
                        .notify(project);
                return;
            }
            relative = base.relativize(candidate).toString().replace('\\', '/');
        }
        JsonObject action = new JsonObject();
        action.addProperty("type", "askAboutResource");
        action.addProperty("path", relative);
        action.addProperty("isDirectory", file.isDirectory());
        DshActions.withPanel(project, panel -> panel.runAction(action));
    }

    @Override
    public void update(AnActionEvent event) {
        event.getPresentation()
                .setEnabled(
                        event.getProject() != null
                                && event.getData(CommonDataKeys.VIRTUAL_FILE) != null);
    }

    @Override
    public ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
