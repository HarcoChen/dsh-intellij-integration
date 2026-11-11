package top.harcochen.dsh.action;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import top.harcochen.dsh.DshHeadlessTasks;

/** Opens local one-shot task management directly from Find Action or Tools. */
public final class DshHeadlessTaskAction extends DumbAwareAction {
    @Override
    public void actionPerformed(AnActionEvent event) {
        if (event.getProject() != null) DshHeadlessTasks.open(event.getProject());
    }

    @Override
    public void update(AnActionEvent event) {
        event.getPresentation().setEnabled(event.getProject() != null);
    }

    @Override
    public ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
