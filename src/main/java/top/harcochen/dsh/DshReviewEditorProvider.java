package top.harcochen.dsh;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorPolicy;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFilePreCloseCheck;
import com.intellij.openapi.vfs.VirtualFileWithoutContent;
import com.intellij.testFramework.LightVirtualFile;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.List;
import java.util.function.Function;
import javax.swing.JComponent;
import org.jetbrains.annotations.NotNull;

/** Hosts native review alongside source files, using the IDE's editor tab lifecycle. */
public final class DshReviewEditorProvider implements FileEditorProvider, DumbAware {
    static void open(
            Project project,
            String key,
            String title,
            List<DshReviewPanel.File> files,
            int selected,
            Function<Runnable, DshReviewPanel> create) {
        FileEditorManager manager = FileEditorManager.getInstance(project);
        for (VirtualFile candidate : manager.getOpenFiles()) {
            if (!(candidate instanceof ReviewFile file) || !file.key.equals(key)) continue;
            for (FileEditor editor : manager.getAllEditors(file))
                if (editor instanceof ReviewEditor review) review.panel.reopen(files, selected);
            manager.openFile(file, true);
            return;
        }
        manager.openFile(new ReviewFile(project, key, title, create), true);
    }

    @Override
    public boolean accept(@NotNull Project project, @NotNull VirtualFile file) {
        return file instanceof ReviewFile review && review.project == project;
    }

    @Override
    public @NotNull FileEditor createEditor(@NotNull Project project, @NotNull VirtualFile file) {
        return new ReviewEditor(project, (ReviewFile) file);
    }

    @Override
    public @NotNull String getEditorTypeId() {
        return "dsh-review-editor";
    }

    @Override
    public @NotNull FileEditorPolicy getPolicy() {
        return FileEditorPolicy.HIDE_DEFAULT_EDITOR;
    }

    private static final class ReviewFile extends LightVirtualFile
            implements VirtualFileWithoutContent {
        private final Project project;
        private final String key;
        private final Function<Runnable, DshReviewPanel> create;

        ReviewFile(
                Project project,
                String key,
                String title,
                Function<Runnable, DshReviewPanel> create) {
            super(title, com.intellij.openapi.fileTypes.PlainTextFileType.INSTANCE, "");
            this.project = project;
            this.key = key;
            this.create = create;
            setWritable(false);
        }
    }

    private static final class ReviewEditor extends UserDataHolderBase implements FileEditor {
        private final ReviewFile file;
        private final DshReviewPanel panel;
        private final PropertyChangeSupport changes = new PropertyChangeSupport(this);
        private boolean modified;
        private boolean disposed;

        ReviewEditor(Project project, ReviewFile file) {
            this.file = file;
            panel = file.create.apply(() -> FileEditorManager.getInstance(project).closeFile(file));
            panel.onModified(
                    () -> {
                        boolean next = panel.hasDrafts();
                        boolean previous = modified;
                        modified = next;
                        changes.firePropertyChange(FileEditor.getPropModified(), previous, next);
                    });
        }

        @Override
        public @NotNull JComponent getComponent() {
            return panel.component();
        }

        @Override
        public JComponent getPreferredFocusedComponent() {
            return panel.preferredFocus();
        }

        @Override
        public @NotNull String getName() {
            return DshBundle.message("dsh.review.title");
        }

        @Override
        public void setState(@NotNull FileEditorState state) {}

        @Override
        public boolean isModified() {
            return panel.hasDrafts();
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
        public void addPropertyChangeListener(@NotNull PropertyChangeListener listener) {
            changes.addPropertyChangeListener(listener);
        }

        @Override
        public void removePropertyChangeListener(@NotNull PropertyChangeListener listener) {
            changes.removePropertyChangeListener(listener);
        }

        @Override
        public void dispose() {
            disposed = true;
            Disposer.dispose(panel);
        }
    }

    /** The native tab close button must honor pending writes and unsubmitted correction drafts. */
    public static final class CloseCheck implements VirtualFilePreCloseCheck {
        @Override
        public boolean canCloseFile(@NotNull VirtualFile file) {
            if (!(file instanceof ReviewFile review) || review.project.isDisposed()) return true;
            for (FileEditor editor :
                    FileEditorManager.getInstance(review.project).getAllEditors(file))
                if (editor instanceof ReviewEditor current && !current.panel.canClose())
                    return false;
            return true;
        }
    }
}
