package top.harcochen.dsh.action;

import com.google.gson.JsonObject;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import java.nio.file.Path;
import top.harcochen.dsh.DshActions;

/** Prefills a workspace-scoped prompt for one resource, mirroring dsh-ide's askAboutResource. */
public final class DshAskAboutResourceAction extends DumbAwareAction {
    @Override
    public void actionPerformed(AnActionEvent event) {
        Project project = event.getProject();
        VirtualFile file = event.getData(CommonDataKeys.VIRTUAL_FILE);
        if (project == null || file == null || project.getBasePath() == null) return;
        Path base = Path.of(project.getBasePath()).toAbsolutePath().normalize();
        Path candidate = Path.of(file.getPath()).toAbsolutePath().normalize();
        if (!candidate.startsWith(base)) return;
        String relative = base.relativize(candidate).toString().replace('\\', '/');
        if (relative.isBlank()) relative = ".";
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
