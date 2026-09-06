package top.harcochen.dsh;

import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import java.util.function.Consumer;

/** Entry points shared by toolbar/menu actions and the webview host. */
public final class DshActions {
    private DshActions() {}

    public static void openToolWindow(Project project) {
        ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow("DSH");
        if (window != null) window.show(null);
    }

    public static void openSettings(Project project) {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, "top.harcochen.dsh.settings");
    }

    public static void runRuntime(Project project, Consumer<DshRuntimeService> operation) {
        operation.accept(DshRuntimeService.getInstance(project));
        openToolWindow(project);
    }

    public static void submitSelection(Project project, String instruction) {
        openToolWindow(project);
        ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow("DSH");
        if (window == null) return;
        window.show(
                () -> {
                    if (window.getContentManager().getContentCount() == 0) return;
                    var component = window.getContentManager().getContent(0).getComponent();
                    if (component instanceof DshToolWindowPanel panel)
                        panel.submitPromptFromIde(instruction);
                });
    }

    public static void configureApiKey(Project project) {
        openToolWindow(project);
        ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow("DSH");
        if (window == null) return;
        window.show(
                () -> {
                    if (window.getContentManager().getContentCount() == 0) return;
                    var component = window.getContentManager().getContent(0).getComponent();
                    if (component instanceof DshToolWindowPanel panel)
                        panel.runCommand("configureApiKey");
                });
    }

    public static void newSession(Project project) {
        openToolWindow(project);
        ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow("DSH");
        if (window == null) return;
        window.show(
                () -> {
                    if (window.getContentManager().getContentCount() == 0) return;
                    var component = window.getContentManager().getContent(0).getComponent();
                    if (component instanceof DshToolWindowPanel panel) panel.newSession();
                });
    }
}
