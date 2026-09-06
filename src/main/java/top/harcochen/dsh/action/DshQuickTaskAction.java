package top.harcochen.dsh.action;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAwareAction;
import top.harcochen.dsh.DshActions;
import top.harcochen.dsh.DshBundle;

/** One implementation backs Explain/Fix/Review/Docs, just like dsh-ide's quick task registry. */
public final class DshQuickTaskAction extends DumbAwareAction {
    @Override
    public void actionPerformed(AnActionEvent event) {
        if (event.getProject() == null) return;
        String id = ActionManager.getInstance().getId(this);
        String task =
                id == null
                        ? "analyze"
                        : switch (id) {
                            case "Dsh.Fix" -> DshBundle.message("dsh.quick.task.fix");
                            case "Dsh.Review" -> DshBundle.message("dsh.quick.task.review");
                            case "Dsh.Docs" -> DshBundle.message("dsh.quick.task.docs");
                            default -> DshBundle.message("dsh.quick.task.explain");
                        };
        DshActions.submitSelection(event.getProject(), task);
    }

    @Override
    public void update(AnActionEvent event) {
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        event.getPresentation()
                .setEnabled(editor != null && editor.getSelectionModel().hasSelection());
    }

    @Override
    public ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
