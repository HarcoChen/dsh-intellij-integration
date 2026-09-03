package top.harcochen.dsh.action;

import top.harcochen.dsh.DshActions;
import top.harcochen.dsh.DshBundle;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAwareAction;

public final class DshAskAboutSelectionAction extends DumbAwareAction {
    @Override
    public void actionPerformed(AnActionEvent event) {
        if (event.getProject() == null) return;
        DshActions.submitSelection(event.getProject(), DshBundle.message("dsh.quick.task.ask.about.selection"));
    }

    @Override
    public void update(AnActionEvent event) {
        Editor editor = event.getData(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR);
        event.getPresentation().setEnabled(editor != null && editor.getSelectionModel().hasSelection());
    }

    @Override
    public ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
