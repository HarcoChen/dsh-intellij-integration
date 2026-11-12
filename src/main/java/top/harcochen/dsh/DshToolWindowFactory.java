package top.harcochen.dsh;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/** Creates one project-local DSH chat surface. */
public final class DshToolWindowFactory implements ToolWindowFactory, DumbAware {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        DshToolWindowPanel panel = new DshToolWindowPanel(project);
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content);
        var review = ActionManager.getInstance().getAction("Dsh.ReviewChanges");
        if (review != null) {
            review.getTemplatePresentation().setIcon(AllIcons.Actions.Diff);
            toolWindow.setTitleActions(List.of(review));
        }
    }

    @Override
    public boolean shouldBeAvailable(@NotNull Project project) {
        return true;
    }
}
