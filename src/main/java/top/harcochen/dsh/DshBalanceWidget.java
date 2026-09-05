package top.harcochen.dsh;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import com.intellij.util.Consumer;
import java.awt.event.MouseEvent;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class DshBalanceWidget implements StatusBarWidget, StatusBarWidget.TextPresentation {

    static final String ID = "DshBalance";
    private final Project project;
    private final DshBalanceService service;
    private StatusBar statusBar;

    DshBalanceWidget(@NotNull Project project) {
        this.project = project;
        this.service = new DshBalanceService(project);
        Disposer.register(this, service);
        service.addListener(
                snapshot ->
                        ApplicationManager.getApplication()
                                .invokeLater(
                                        () -> {
                                            if (statusBar != null) statusBar.updateWidget(ID);
                                        }));
        service.start();
    }

    @Override
    public @NonNls @NotNull String ID() {
        return ID;
    }

    @Override
    public void install(@NotNull StatusBar statusBar) {
        this.statusBar = statusBar;
    }

    @Override
    public @Nullable WidgetPresentation getPresentation() {
        return this;
    }

    @Override
    public @NotNull @Nls String getText() {
        DshBalanceService.Snapshot s = service.snapshot();
        return s == null ? "" : s.text();
    }

    @Override
    public @Nullable @Nls String getTooltipText() {
        DshBalanceService.Snapshot s = service.snapshot();
        return s == null ? null : s.tooltip();
    }

    @Override
    public float getAlignment() {
        return 0.5f;
    }

    @Override
    public @Nullable Consumer<MouseEvent> getClickConsumer() {
        return event -> {
            DshBalanceService.Snapshot s = service.snapshot();
            if (s != null && s.state() == DshBalanceService.State.NO_KEY) {
                DshActions.configureApiKey(project);
            } else {
                ApplicationManager.getApplication().executeOnPooledThread(service::refresh);
            }
        };
    }

    @Override
    public void dispose() {}

    public static final class Factory implements StatusBarWidgetFactory {
        @Override
        public @NonNls @NotNull String getId() {
            return ID;
        }

        @Override
        public @Nls @NotNull String getDisplayName() {
            return DshBundle.message("dsh.balance.widget.name");
        }

        @Override
        public @NotNull StatusBarWidget createWidget(@NotNull Project project) {
            return new DshBalanceWidget(project);
        }
    }
}
