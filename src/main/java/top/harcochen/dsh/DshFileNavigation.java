package top.harcochen.dsh;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import top.harcochen.dsh.remote.DshRemoteService;

/** Resolve at the event directory, then use either the project editor or the Host opener. */
final class DshFileNavigation {
    private DshFileNavigation() {}

    static void open(
            Project project,
            DshRemoteService remote,
            DshRuntimeService runtime,
            ExecutorService operations,
            JsonObject action,
            String directory,
            BooleanSupplier current,
            Consumer<String> notify) {
        String path = DshJson.strictString(action, "path");
        if (path == null || path.isBlank()) return;
        String endpoint = runtime.getUrl();
        int line = DshJson.integer(action, "line", 1);
        int column = DshJson.integer(action, "column", 1);
        operations.execute(
                () -> {
                    String resolved;
                    try {
                        resolved = DshWorkingDirectory.resolve(path, directory);
                    } catch (RuntimeException invalid) {
                        notify.accept(DshBundle.message("dsh.file.invalid.path", path));
                        return;
                    }
                    if (!current.getAsBoolean()) return;
                    Path local = localFile(project.getBasePath(), resolved);
                    if (local != null) {
                        ApplicationManager.getApplication()
                                .invokeLater(
                                        () -> {
                                            if (!current.getAsBoolean()) return;
                                            VirtualFile file =
                                                    LocalFileSystem.getInstance()
                                                            .refreshAndFindFileByNioFile(local);
                                            if (file == null || file.isDirectory()) {
                                                notify.accept(
                                                        DshBundle.message(
                                                                "dsh.file.not.found", path));
                                                return;
                                            }
                                            new OpenFileDescriptor(
                                                            project,
                                                            file,
                                                            Math.max(0, line - 1),
                                                            Math.max(0, column - 1))
                                                    .navigate(true);
                                        });
                        return;
                    }
                    try {
                        if (endpoint != null
                                && endpoint.equals(runtime.getUrl())
                                && current.getAsBoolean()
                                && remote.canOpenWorkspacePath()) {
                            if (!endpoint.equals(runtime.getUrl()) || !current.getAsBoolean())
                                return;
                            remote.openWorkspacePath(resolved);
                            return;
                        }
                    } catch (RuntimeException unavailable) {
                        // The bounded local-path diagnostic remains useful when the optional opener
                        // fails.
                    }
                    if (current.getAsBoolean())
                        notify.accept(DshBundle.message("dsh.file.not.found", path));
                });
    }

    private static Path localFile(String projectDirectory, String resolved) {
        if (projectDirectory == null) return null;
        // A Windows Host path must never become a relative filename on a POSIX IDE.
        if (!System.getProperty("os.name", "").startsWith("Windows")
                && (resolved.matches("^[A-Za-z]:.*") || resolved.startsWith("\\\\"))) return null;
        try {
            Path root = Path.of(projectDirectory).toRealPath();
            Path candidate = Path.of(resolved);
            if (!candidate.isAbsolute()) return null;
            Path target = candidate.toRealPath();
            return target.startsWith(root) && Files.isRegularFile(target) ? target : null;
        } catch (Exception missing) {
            return null;
        }
    }
}
