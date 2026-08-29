package top.harcochen.dsh.action;

import top.harcochen.dsh.DshActions;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAwareAction;

/** One implementation backs Explain/Fix/Review/Docs, just like dsh-ide's quick task registry. */
public final class DshQuickTaskAction extends DumbAwareAction {
    @Override
    public void actionPerformed(AnActionEvent event) {
        if (event.getProject() == null) return;
        String id = ActionManager.getInstance().getId(this);
        String task = id == null ? "analyze" : switch (id) {
            case "Dsh.Fix" -> "修复当前选中的代码，并解释修改原因。";
            case "Dsh.Review" -> "审查当前选中的代码，指出潜在问题和改进建议。";
            case "Dsh.Docs" -> "为当前选中的代码编写清晰的文档或注释。";
            default -> "解释当前选中的代码，并说明关键设计。";
        };
        DshActions.submitSelection(event.getProject(), task);
    }

    @Override
    public void update(AnActionEvent event) {
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        event.getPresentation().setEnabled(editor != null && editor.getSelectionModel().hasSelection());
    }

    @Override
    public ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
