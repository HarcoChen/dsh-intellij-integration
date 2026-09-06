package top.harcochen.dsh.action;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import top.harcochen.dsh.DshActions;

/**
 * Captures the suspended debugger session as a one-shot context chip and prefills the composer,
 * mirroring dsh-ide's {@code dsh.explainDebugState}.
 */
public final class DshExplainDebugStateAction extends DumbAwareAction {
    @Override
    public void actionPerformed(AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) return;
        DshActions.withPanel(project, panel -> panel.runCommand("explainDebugState"));
    }

    @Override
    public void update(AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) {
            event.getPresentation().setEnabled(false);
            return;
        }
        XDebugSession session = XDebuggerManager.getInstance(project).getCurrentSession();
        event.getPresentation().setEnabled(session != null && session.isSuspended());
    }

    @Override
    public ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
