package top.harcochen.dsh.action;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import top.harcochen.dsh.DshActions;

public final class DshNewSessionAction extends DumbAwareAction {
    @Override
    public void actionPerformed(AnActionEvent event) {
        if (event.getProject() != null) DshActions.newSession(event.getProject());
    }

    @Override
    public ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
