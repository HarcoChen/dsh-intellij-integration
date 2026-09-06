package top.harcochen.dsh;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.LightVirtualFile;
import java.awt.datatransfer.StringSelection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Consumer;

/** Resolves rendered code blocks and applies their IDE-native actions. */
final class DshCodeActionController {
    private final Project project;
    private final DshMarkdownRenderCache markdownRenderCache;
    private final Consumer<String> notifier;

    DshCodeActionController(
            Project project,
            DshMarkdownRenderCache markdownRenderCache,
            Consumer<String> notifier) {
        this.project = project;
        this.markdownRenderCache = markdownRenderCache;
        this.notifier = notifier;
    }

    void handle(JsonObject action) {
        String type = DshJson.string(action, "type");
        String renderId = DshJson.string(action, "renderId");
        String blockId = DshJson.string(action, "codeBlockId");
        if (type == null || renderId == null || blockId == null) {
            return;
        }
        final String code;
        try {
            code = markdownRenderCache.codeBlockText(renderId, blockId);
        } catch (IllegalArgumentException error) {
            notifyUser(error.getMessage());
            return;
        }
        String language = DshJson.string(action, "language");
        ApplicationManager.getApplication()
                .invokeLater(
                        () -> {
                            switch (type) {
                                case "copyCode" ->
                                        CopyPasteManager.getInstance()
                                                .setContents(new StringSelection(code));
                                case "insertCode" -> insertCode(code);
                                case "openCode" -> openCode(code, language);
                                case "applyCode" -> applyCode(code, language);
                                default -> {}
                            }
                        });
    }

    private void insertCode(String code) {
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) {
            notifyUser(DshBundle.message("dsh.code.no.editor.for.insert"));
            return;
        }
        WriteCommandAction.runWriteCommandAction(
                project,
                DshBundle.message("dsh.code.insert.command.name"),
                null,
                () -> {
                    int start =
                            editor.getSelectionModel().hasSelection()
                                    ? editor.getSelectionModel().getSelectionStart()
                                    : editor.getCaretModel().getOffset();
                    int end =
                            editor.getSelectionModel().hasSelection()
                                    ? editor.getSelectionModel().getSelectionEnd()
                                    : start;
                    editor.getDocument().replaceString(start, end, code);
                    editor.getSelectionModel().removeSelection();
                    editor.getCaretModel().moveToOffset(start + code.length());
                });
    }

    private void openCode(String code, String language) {
        String extension = extensionForLanguage(language);
        FileType fileType = FileTypeManager.getInstance().getFileTypeByExtension(extension);
        LightVirtualFile file =
                new LightVirtualFile(
                        DshBundle.message("dsh.code.open.file.name") + extension, fileType, code);
        FileEditorManager.getInstance(project).openFile(file, true);
    }

    /**
     * Replaces one explicitly selected project file after a native diff preview and a second
     * on-disk consistency check.
     */
    private void applyCode(String code, String language) {
        String base = project.getBasePath();
        if (base == null) {
            notifyUser(DshBundle.message("dsh.code.no.project.for.apply"));
            return;
        }
        VirtualFile root = LocalFileSystem.getInstance().findFileByPath(base);
        VirtualFile chosen =
                FileChooser.chooseFile(
                        FileChooserDescriptorFactory.createSingleFileDescriptor()
                                .withTitle(DshBundle.message("dsh.code.apply.chooser.title"))
                                .withDescription(
                                        DshBundle.message("dsh.code.apply.chooser.description")),
                        project,
                        root);
        if (chosen == null) {
            return;
        }
        if (chosen.isDirectory() || !chosen.isInLocalFileSystem()) {
            notifyUser(DshBundle.message("dsh.code.apply.not.regular.file"));
            return;
        }
        Path target;
        Path projectRoot;
        try {
            target = Path.of(chosen.getPath()).toRealPath();
            projectRoot = Path.of(base).toRealPath();
        } catch (Exception error) {
            notifyUser(DshBundle.message("dsh.code.apply.resolve.failed", DshJson.message(error)));
            return;
        }
        if (!target.startsWith(projectRoot)) {
            notifyUser(DshBundle.message("dsh.code.apply.outside.project"));
            return;
        }
        Document document = FileDocumentManager.getInstance().getDocument(chosen);
        if (document == null) {
            notifyUser(DshBundle.message("dsh.code.apply.not.text"));
            return;
        }
        if (FileDocumentManager.getInstance().isDocumentUnsaved(document)) {
            notifyUser(DshBundle.message("dsh.code.apply.unsaved.changes"));
            return;
        }
        String beforeText = document.getText();
        byte[] beforeDisk;
        try {
            beforeDisk = Files.readAllBytes(target);
        } catch (Exception error) {
            notifyUser(DshBundle.message("dsh.code.apply.read.failed", DshJson.message(error)));
            return;
        }
        String path = displayPath(chosen);
        FileType fileType =
                language == null || language.isBlank()
                        ? chosen.getFileType()
                        : FileTypeManager.getInstance()
                                .getFileTypeByFileName(
                                        "dsh-code." + extensionForLanguage(language));
        com.intellij.diff.DiffContentFactory factory =
                com.intellij.diff.DiffContentFactory.getInstance();
        com.intellij.diff.requests.SimpleDiffRequest request =
                new com.intellij.diff.requests.SimpleDiffRequest(
                        DshBundle.message("dsh.code.apply.diff.title", path),
                        factory.create(project, beforeText, fileType),
                        factory.create(project, code, fileType),
                        DshBundle.message("dsh.code.apply.diff.current"),
                        DshBundle.message("dsh.code.apply.diff.proposed"));
        com.intellij.diff.DiffManager.getInstance().showDiff(project, request);
        int answer =
                Messages.showYesNoDialog(
                        project,
                        DshBundle.message("dsh.code.apply.confirm.message", path),
                        DshBundle.message("dsh.code.apply.confirm.title"),
                        Messages.getWarningIcon());
        if (answer != Messages.YES) {
            return;
        }
        try {
            Path latest = Path.of(chosen.getPath()).toRealPath();
            byte[] latestDisk = Files.readAllBytes(latest);
            if (!latest.equals(target)
                    || !latest.startsWith(projectRoot)
                    || !java.util.Arrays.equals(latestDisk, beforeDisk)
                    || !document.getText().equals(beforeText)
                    || FileDocumentManager.getInstance().isDocumentUnsaved(document)) {
                notifyUser(DshBundle.message("dsh.code.apply.file.changed"));
                return;
            }
        } catch (Exception error) {
            notifyUser(DshBundle.message("dsh.code.apply.file.changed"));
            return;
        }
        WriteCommandAction.runWriteCommandAction(
                project,
                DshBundle.message("dsh.code.apply.command.name"),
                null,
                () -> document.setText(code));
        FileDocumentManager.getInstance().saveDocument(document);
        notifyUser(DshBundle.message("dsh.code.apply.success", path));
    }

    private static String extensionForLanguage(String language) {
        if (language == null || language.isBlank()) {
            return "txt";
        }
        return switch (language.toLowerCase(Locale.ROOT)) {
            case "javascript", "js" -> "js";
            case "typescript", "ts" -> "ts";
            case "python", "py" -> "py";
            case "java" -> "java";
            case "kotlin", "kt" -> "kt";
            case "shell", "bash", "sh", "zsh" -> "sh";
            case "json" -> "json";
            case "yaml", "yml" -> "yaml";
            case "xml" -> "xml";
            case "html" -> "html";
            case "css" -> "css";
            case "markdown", "md" -> "md";
            case "c++", "cpp" -> "cpp";
            case "c#", "csharp" -> "cs";
            default ->
                    language.matches("[A-Za-z0-9]{1,12}")
                            ? language.toLowerCase(Locale.ROOT)
                            : "txt";
        };
    }

    private String displayPath(VirtualFile file) {
        String root = project.getBasePath();
        if (root == null) {
            return file.getPath();
        }
        try {
            return Path.of(root).relativize(Path.of(file.getPath())).toString().replace('\\', '/');
        } catch (IllegalArgumentException ignored) {
            return file.getPath();
        }
    }

    private void notifyUser(String text) {
        notifier.accept(text);
    }
}
