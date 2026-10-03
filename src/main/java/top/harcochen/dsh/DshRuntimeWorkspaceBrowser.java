package top.harcochen.dsh;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.text.StringUtil;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import top.harcochen.dsh.remote.DshRemoteService;
import top.harcochen.dsh.remote.DshWorkspaceFiles;

/** Session-bound Host file browser with read-only native editor previews. */
final class DshRuntimeWorkspaceBrowser implements AutoCloseable {
    private final Project project;
    private final DshRuntimeService runtime;
    private final DshRemoteService remote;
    private final ExecutorService operations;
    private final Consumer<String> notifier;
    private final Set<PreviewDialog> previews = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    DshRuntimeWorkspaceBrowser(
            Project project,
            DshRuntimeService runtime,
            DshRemoteService remote,
            ExecutorService operations,
            Consumer<String> notifier) {
        this.project = project;
        this.runtime = runtime;
        this.remote = remote;
        this.operations = operations;
        this.notifier = notifier;
    }

    void browse(String session) {
        if (session == null) {
            notifier.accept(DshBundle.message("dsh.files.no.session"));
            return;
        }
        String endpoint = runtime.getUrl();
        operations.execute(
                () -> {
                    try {
                        String path = ".";
                        while (!closed) {
                            assertEndpoint(endpoint);
                            JsonObject listing = remote.workspaceFiles().list(session, path);
                            assertEndpoint(endpoint);
                            String root = DshJson.string(listing, "path");
                            List<String> labels = new ArrayList<>();
                            List<String> paths = new ArrayList<>();
                            List<Boolean> directories = new ArrayList<>();
                            if (!root.isEmpty()) {
                                labels.add("..  " + DshBundle.message("dsh.files.parent"));
                                int slash = root.lastIndexOf('/');
                                paths.add(slash < 0 ? "." : root.substring(0, slash));
                                directories.add(true);
                            }
                            labels.add(DshBundle.message("dsh.files.refresh"));
                            paths.add(root.isEmpty() ? "." : root);
                            directories.add(true);
                            List<JsonObject> entries = new ArrayList<>();
                            for (JsonElement value : listing.getAsJsonArray("entries"))
                                entries.add(value.getAsJsonObject());
                            entries.sort(
                                    java.util.Comparator.comparing(
                                                    (JsonObject entry) ->
                                                            !"directory"
                                                                    .equals(
                                                                            DshJson.string(
                                                                                    entry, "type")))
                                            .thenComparing(entry -> DshJson.string(entry, "name")));
                            for (JsonObject entry : entries) {
                                if ("other".equals(DshJson.string(entry, "type"))) continue;
                                boolean directory =
                                        "directory".equals(DshJson.string(entry, "type"));
                                String name = DshJson.string(entry, "name");
                                labels.add(directory ? name + "/" : name);
                                paths.add(root.isEmpty() ? name : root + "/" + name);
                                directories.add(directory);
                            }
                            CompletableFuture<Integer> choice = new CompletableFuture<>();
                            ApplicationManager.getApplication()
                                    .invokeLater(
                                            () -> {
                                                if (closed || project.isDisposed()) {
                                                    choice.complete(-1);
                                                    return;
                                                }
                                                choice.complete(
                                                        Messages.showChooseDialog(
                                                                project,
                                                                DshBundle.message(
                                                                        DshJson.bool(
                                                                                        listing,
                                                                                        "truncated",
                                                                                        false)
                                                                                ? "dsh.files.truncated"
                                                                                : "dsh.files.choose"),
                                                                DshBundle.message("dsh.files.title")
                                                                        + " · /"
                                                                        + root,
                                                                Messages.getInformationIcon(),
                                                                labels.toArray(String[]::new),
                                                                labels.get(0)));
                                            });
                            int selected = choice.get();
                            if (selected < 0) return;
                            path = paths.get(selected);
                            if (directories.get(selected)) continue;
                            String filePath = path;
                            DshWorkspaceFiles.Preview file =
                                    remote.workspaceFiles()
                                            .preview(
                                                    session,
                                                    filePath,
                                                    () -> assertEndpoint(endpoint));
                            ApplicationManager.getApplication()
                                    .invokeLater(
                                            () -> {
                                                if (closed || project.isDisposed()) return;
                                                try {
                                                    assertEndpoint(endpoint);
                                                } catch (IllegalStateException stale) {
                                                    return;
                                                }
                                                new PreviewDialog(session, filePath, endpoint, file)
                                                        .show();
                                            });
                            return;
                        }
                    } catch (InterruptedException cancelled) {
                        Thread.currentThread().interrupt();
                    } catch (Exception error) {
                        if (!closed) notifier.accept(DshJson.message(error));
                    }
                });
    }

    void refresh() {
        for (PreviewDialog preview : previews) preview.refresh();
    }

    private void assertEndpoint(String endpoint) {
        if (closed || endpoint == null || !endpoint.equals(runtime.getUrl()))
            throw new IllegalStateException(DshBundle.message("dsh.files.endpoint.changed"));
    }

    private final class PreviewDialog extends DialogWrapper {
        private final String session;
        private final String path;
        private final String endpoint;
        private final Editor viewer;
        private AutoCloseable watch;
        private volatile boolean disposed;
        private final AtomicBoolean reading = new AtomicBoolean();
        private final AtomicBoolean dirty = new AtomicBoolean();

        PreviewDialog(
                String session, String path, String endpoint, DshWorkspaceFiles.Preview initial) {
            super(project, false);
            this.session = session;
            this.path = path;
            this.endpoint = endpoint;
            viewer =
                    EditorFactory.getInstance()
                            .createViewer(
                                    EditorFactory.getInstance()
                                            .createDocument(
                                                    com.intellij.openapi.util.text.StringUtil
                                                            .convertLineSeparators(initial.text())),
                                    project);
            setTitle(DshBundle.message("dsh.files.preview") + " · " + initial.absolutePath());
            setModal(false);
            init();
            previews.add(this);
            watch =
                    remote.watchFeature(
                            "workspaceFiles/changes",
                            () -> {
                                assertEndpoint(endpoint);
                                return DshWorkspaceFiles.args(session, path);
                            },
                            frame -> {
                                if (disposed) return;
                                String kind = DshJson.string(frame, "kind");
                                if ("ready".equals(kind) || "change".equals(kind)) refresh();
                                else notifier.accept(DshBundle.message("dsh.files.watch.invalid"));
                            },
                            error -> {
                                if (!disposed && !endpoint.equals(runtime.getUrl())) {
                                    DshJobsController.release(watch);
                                    watch = null;
                                    notifier.accept(
                                            DshBundle.message("dsh.files.endpoint.changed"));
                                }
                            });
        }

        @Override
        protected JComponent createCenterPanel() {
            JPanel panel = new JPanel(new BorderLayout());
            panel.add(viewer.getComponent(), BorderLayout.CENTER);
            JButton button = new JButton(DshBundle.message("dsh.files.refresh"));
            button.addActionListener(event -> refresh());
            panel.add(button, BorderLayout.SOUTH);
            panel.setPreferredSize(new java.awt.Dimension(850, 560));
            return panel;
        }

        void refresh() {
            if (disposed || closed) return;
            dirty.set(true);
            if (!reading.compareAndSet(false, true)) return;
            operations.execute(
                    () -> {
                        try {
                            do {
                                dirty.set(false);
                                DshWorkspaceFiles.Preview result =
                                        remote.workspaceFiles()
                                                .preview(
                                                        session,
                                                        path,
                                                        () -> {
                                                            if (disposed)
                                                                throw new IllegalStateException(
                                                                        "Preview closed");
                                                            assertEndpoint(endpoint);
                                                        });
                                ApplicationManager.getApplication()
                                        .invokeLater(
                                                () -> {
                                                    if (disposed || closed) return;
                                                    try {
                                                        assertEndpoint(endpoint);
                                                    } catch (IllegalStateException stale) {
                                                        return;
                                                    }
                                                    updateText(result.text());
                                                });
                            } while (dirty.get() && !disposed && !closed);
                        } catch (Exception error) {
                            dirty.set(false);
                            if (!disposed && !closed) notifier.accept(DshJson.message(error));
                        } finally {
                            reading.set(false);
                            if (dirty.get() && !disposed && !closed) refresh();
                        }
                    });
        }

        private void updateText(String text) {
            WriteAction.run(
                    () -> viewer.getDocument().setText(StringUtil.convertLineSeparators(text)));
        }

        @Override
        protected void dispose() {
            disposed = true;
            DshJobsController.release(watch);
            previews.remove(this);
            EditorFactory.getInstance().releaseEditor(viewer);
            super.dispose();
        }
    }

    @Override
    public void close() {
        closed = true;
        for (PreviewDialog preview : previews) DshJobsController.release(preview.watch);
        ApplicationManager.getApplication()
                .invokeLater(
                        () -> {
                            for (PreviewDialog preview : new ArrayList<>(previews))
                                preview.close(DialogWrapper.CANCEL_EXIT_CODE);
                        });
    }
}
