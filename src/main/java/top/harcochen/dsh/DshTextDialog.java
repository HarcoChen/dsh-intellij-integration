package top.harcochen.dsh;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import java.awt.Font;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

/** Shared read-only text dialog used by tool-window controllers. */
final class DshTextDialog {
    private DshTextDialog() {}

    static void show(Project project, String title, String text) {
        ApplicationManager.getApplication()
                .invokeLater(
                        () -> {
                            JTextArea area = new JTextArea(text == null ? "" : text);
                            area.setEditable(false);
                            area.setLineWrap(false);
                            area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
                            JScrollPane scroll = new JScrollPane(area);
                            scroll.setPreferredSize(new java.awt.Dimension(780, 480));
                            DialogWrapper dialog =
                                    new DialogWrapper(project) {
                                        {
                                            setTitle(title);
                                            init();
                                        }

                                        @Override
                                        protected javax.swing.JComponent createCenterPanel() {
                                            return scroll;
                                        }
                                    };
                            dialog.show();
                        });
    }
}
