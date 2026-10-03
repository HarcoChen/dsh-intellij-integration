package top.harcochen.dsh;

import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.project.Project;

/** Application-wide update notice, also available from Find Action. */
final class DshWhatsNew {
    private DshWhatsNew() {}

    static void show(Project project, boolean force) {
        var plugin = PluginManagerCore.getPlugin(PluginId.getId("top.harcochen.dsh"));
        if (plugin == null || project.isDisposed()) return;
        String version = plugin.getVersion();
        String key = "top.harcochen.dsh.whatsNew.version";
        PropertiesComponent properties = PropertiesComponent.getInstance();
        if (!force && version.equals(properties.getValue(key))) return;
        NotificationGroupManager.getInstance()
                .getNotificationGroup("DeepSeek Harness")
                .createNotification(
                        DshBundle.message("dsh.whatsnew.title", version),
                        DshBundle.message("dsh.whatsnew.body"),
                        NotificationType.INFORMATION)
                .addAction(
                        NotificationAction.createSimple(
                                DshBundle.message("dsh.whatsnew.settings"),
                                () -> DshActions.openSettings(project)))
                .notify(project);
        if (!force) properties.setValue(key, version);
    }
}
