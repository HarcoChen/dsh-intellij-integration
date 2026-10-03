package top.harcochen.dsh;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorPolicy;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.LightVirtualFile;
import java.awt.BorderLayout;
import java.beans.PropertyChangeListener;
import javax.swing.JComponent;
import javax.swing.JPanel;
import org.jetbrains.annotations.NotNull;

/** An editor-area view of the existing chat controller, with no second Session/state store. */
public final class DshChatEditorProvider implements FileEditorProvider, DumbAware {
    private static final Key<ChatFile> FILE = Key.create("dsh.chat.editor.file");

    public static void open(Project project) {
        ChatFile file = project.getUserData(FILE);
        if (file == null) {
            file = new ChatFile();
            project.putUserData(FILE, file);
        }
        FileEditorManager.getInstance(project).openFile(file, true);
    }

    @Override
    public boolean accept(@NotNull Project project, @NotNull VirtualFile file) {
        return file instanceof ChatFile;
    }

    @Override
    public @NotNull FileEditor createEditor(@NotNull Project project, @NotNull VirtualFile file) {
        return new ChatEditor(project, file);
    }

    @Override
    public @NotNull String getEditorTypeId() {
        return "dsh-chat-editor";
    }

    @Override
    public @NotNull FileEditorPolicy getPolicy() {
        return FileEditorPolicy.HIDE_DEFAULT_EDITOR;
    }

    private static final class ChatFile extends LightVirtualFile {
        ChatFile() {
            super("DSH Chat", com.intellij.openapi.fileTypes.PlainTextFileType.INSTANCE, "");
            setWritable(false);
        }
    }

    private static final class ChatEditor extends UserDataHolderBase implements FileEditor {
        private final JPanel component = new JPanel(new BorderLayout());
        private final VirtualFile file;
        private DshToolWindowPanel.ChatMirror mirror;
        private boolean disposed;

        ChatEditor(Project project, VirtualFile file) {
            this.file = file;
            DshActions.withPanel(
                    project,
                    host -> {
                        if (disposed) return;
                        mirror = host.createMirror();
                        component.add(mirror, BorderLayout.CENTER);
                        component.revalidate();
                        component.repaint();
                    });
        }

        @Override
        public @NotNull JComponent getComponent() {
            return component;
        }

        @Override
        public JComponent getPreferredFocusedComponent() {
            return component;
        }

        @Override
        public @NotNull String getName() {
            return "DSH Chat";
        }

        @Override
        public void setState(@NotNull FileEditorState state) {}

        @Override
        public boolean isModified() {
            return false;
        }

        @Override
        public boolean isValid() {
            return !disposed;
        }

        @Override
        public VirtualFile getFile() {
            return file;
        }

        @Override
        public void addPropertyChangeListener(@NotNull PropertyChangeListener listener) {}

        @Override
        public void removePropertyChangeListener(@NotNull PropertyChangeListener listener) {}

        @Override
        public void dispose() {
            disposed = true;
            if (mirror != null) mirror.dispose();
        }
    }
}
