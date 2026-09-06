package top.harcochen.dsh.action;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import top.harcochen.dsh.DshActions;

/** Inserts an {@code @path} reference for the active editor into the chat composer. */
public final class DshInsertEditorReferenceAction extends DumbAwareAction {
    @Override
    public void actionPerformed(AnActionEvent event) {
        if (event.getProject() == null) return;
        DshActions.withPanel(
                event.getProject(), panel -> panel.runCommand("insertEditorReference"));
    }

    @Override
    public ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
