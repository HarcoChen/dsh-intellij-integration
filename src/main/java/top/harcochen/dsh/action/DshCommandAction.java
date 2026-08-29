package top.harcochen.dsh.action;

import top.harcochen.dsh.DshActions;
import top.harcochen.dsh.DshToolWindowPanel;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;

/** Bridges command-palette actions to the existing DSH tool-window controller. */
public final class DshCommandAction extends DumbAwareAction {
    @Override
    public void actionPerformed(AnActionEvent event) {
        if (event.getProject() == null) return;
        DshActions.openToolWindow(event.getProject());
        ToolWindow window = ToolWindowManager.getInstance(event.getProject()).getToolWindow("DSH");
        if (window == null) return;
        String id = ActionManager.getInstance().getId(this);
        String command = switch (id == null ? "" : id) {
            case "Dsh.OpenBrowser" -> "openBrowser";
            case "Dsh.OpenLogs" -> "openLogs";
            case "Dsh.ConfigureApiKey" -> "configureApiKey";
            case "Dsh.ManageProviders" -> "manageProviders";
            case "Dsh.ManageAgentPresets" -> "manageAgentPresets";
            case "Dsh.Diagnose" -> "diagnoseEnvironment";
            default -> "openBrowser";
        };
        window.show(() -> {
            if (window.getContentManager().getContentCount() == 0) return;
            var component = window.getContentManager().getContent(0).getComponent();
            if (component instanceof DshToolWindowPanel panel) {
                if ("diagnoseEnvironment".equals(command)) {
                    panel.showDiagnostics();
                } else {
                    panel.runCommand(command);
                }
            }
        });
    }

    @Override
    public ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
