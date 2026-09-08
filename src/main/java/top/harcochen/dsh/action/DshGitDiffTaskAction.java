package top.harcochen.dsh.action;

import com.google.gson.JsonObject;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import top.harcochen.dsh.DshActions;
import top.harcochen.dsh.DshBundle;

/**
 * One implementation backs the Git diff quick tasks, mirroring dsh-ide's {@code dsh.gitDiffTask.*}:
 * attach the working-tree diff and prefill the composer.
 */
public final class DshGitDiffTaskAction extends DumbAwareAction {
    @Override
    public void actionPerformed(AnActionEvent event) {
        if (event.getProject() == null) return;
        String id = ActionManager.getInstance().getId(this);
        String kind =
                id == null
                        ? "explain"
                        : switch (id) {
                            case "Dsh.ExplainDiff" -> "explain";
                            case "Dsh.FixDiff" -> "fix";
                            case "Dsh.ReviewDiff" -> "review";
                            case "Dsh.DocsDiff" -> "docs";
                            default -> "explain";
                        };
        DshBundle.message("dsh.git.diff.task." + kind); // fail fast on an unknown kind
        JsonObject action = new JsonObject();
        action.addProperty("type", "prefillGitDiff");
        action.addProperty("kind", kind);
        DshActions.withPanel(event.getProject(), panel -> panel.runAction(action));
    }

    @Override
    public ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
