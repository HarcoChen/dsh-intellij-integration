package top.harcochen.dsh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import top.harcochen.dsh.remote.DshRemoteService;

/** Manages Runtime settings, credentials, and provider status dialogs. */
final class DshSettingsController {
    private static final Logger LOG = Logger.getInstance(DshSettingsController.class);

    private final Project project;
    private final DshRuntimeService runtime;
    private final DshRemoteService remote;
    private final ExecutorService operations;
    private final Runnable stateChanged;
    private final Runnable openBrowser;
    private final Consumer<String> notifier;
    private final Consumer<String> errorSink;
    private final Map<String, JsonObject> namespaces = new LinkedHashMap<>();
    private final AtomicLong generation = new AtomicLong();

    private volatile JsonObject panel;

    DshSettingsController(
            Project project,
            DshRuntimeService runtime,
            DshRemoteService remote,
            ExecutorService operations,
            Runnable stateChanged,
            Runnable openBrowser,
            Consumer<String> notifier,
            Consumer<String> errorSink) {
        this.project = project;
        this.runtime = runtime;
        this.remote = remote;
        this.operations = operations;
        this.stateChanged = stateChanged;
        this.openBrowser = openBrowser;
        this.notifier = notifier;
        this.errorSink = errorSink;
    }

    JsonObject panel() {
        return panel;
    }

    void configureApiKey() {
        ApplicationManager.getApplication()
                .invokeLater(
                        () -> {
                            String environment = DshSettingsState.getInstance(project).apiKeyEnv;
                            String value =
                                    Messages.showPasswordDialog(
                                            DshBundle.message(
                                                    "dsh.api.key.dialog.message", environment),
                                            DshBundle.message("dsh.api.key.dialog.title"));
                            if (value == null || value.isBlank()) {
                                return;
                            }
                            DshCredentials.store(project, value);
                            notifyUser(DshBundle.message("dsh.api.key.saved"));
                        });
    }

    void togglePanel() {
        if (panel != null) {
            generation.incrementAndGet();
            panel = null;
            synchronized (namespaces) {
                namespaces.clear();
            }
            stateChanged.run();
            return;
        }
        long requestedGeneration = generation.incrementAndGet();
        panel = DshSettingsProjector.loadingPanel();
        stateChanged.run();
        operations.execute(
                () -> {
                    try {
                        runtime.startAsync().join();
                        JsonObject described = remote.describeSettings();
                        if (generation.get() != requestedGeneration) {
                            return;
                        }
                        synchronized (namespaces) {
                            namespaces.clear();
                            JsonArray rows =
                                    described.has("namespaces")
                                                    && described.get("namespaces").isJsonArray()
                                            ? described.getAsJsonArray("namespaces")
                                            : new JsonArray();
                            for (JsonElement candidate : rows) {
                                if (!candidate.isJsonObject()) {
                                    continue;
                                }
                                String namespace =
                                        DshJson.string(candidate.getAsJsonObject(), "ns");
                                if (namespace != null) {
                                    namespaces.put(
                                            namespace, candidate.getAsJsonObject().deepCopy());
                                }
                            }
                        }
                        // Re-check: togglePanel may have closed the panel while presentPanel ran,
                        // and writing the stale panel back would resurrect a closed dialog.
                        JsonObject presented = DshSettingsProjector.presentPanel(described);
                        if (generation.get() != requestedGeneration) {
                            return;
                        }
                        panel = presented;
                    } catch (Exception error) {
                        if (generation.get() != requestedGeneration) {
                            return;
                        }
                        panel = DshSettingsProjector.failedPanel(DshJson.message(error));
                    } finally {
                        stateChanged.run();
                    }
                });
    }

    void mutate(JsonObject action) {
        JsonObject currentPanel = panel;
        String namespace = DshJson.string(action, "ns");
        long revision = DshJson.longValue(action.get("revision"), -1);
        if (currentPanel == null
                || namespace == null
                || !DshJson.bool(currentPanel, "open", false)
                || !DshJson.bool(currentPanel, "writable", false)) {
            notifyUser(DshBundle.message("dsh.settings.out.of.date"));
            return;
        }
        JsonObject card = findCard(currentPanel, namespace);
        if (card == null || DshJson.longValue(card.get("revision"), -2) != revision) {
            notifyUser(DshBundle.message("dsh.settings.out.of.date"));
            return;
        }
        JsonArray operationsToApply;
        try {
            operationsToApply =
                    DshSettingsProjector.mutationOps(
                            card.getAsJsonArray("fields"),
                            action.has("changes") && action.get("changes").isJsonArray()
                                    ? action.getAsJsonArray("changes")
                                    : new JsonArray());
        } catch (RuntimeException error) {
            notifyUser(DshJson.message(error));
            return;
        }
        if (operationsToApply.isEmpty()) {
            return;
        }
        boolean writable = DshJson.bool(currentPanel, "writable", false);
        boolean hasDocument = DshJson.bool(currentPanel, "hasDocument", false);
        operations.execute(
                () -> applyMutation(namespace, operationsToApply, revision, writable, hasDocument));
    }

    private static JsonObject findCard(JsonObject panel, String namespace) {
        for (JsonElement candidate : panel.getAsJsonArray("cards")) {
            if (candidate.isJsonObject()
                    && namespace.equals(DshJson.string(candidate.getAsJsonObject(), "ns"))) {
                return candidate.getAsJsonObject();
            }
        }
        return null;
    }

    private void applyMutation(
            String namespace,
            JsonArray operationsToApply,
            long revision,
            boolean writable,
            boolean hasDocument) {
        try {
            JsonObject updated = remote.mutateSettings(namespace, operationsToApply, revision);
            JsonObject described = new JsonObject();
            described.addProperty("writable", writable);
            described.addProperty("hasDocument", hasDocument);
            JsonArray rows = new JsonArray();
            synchronized (namespaces) {
                namespaces.put(namespace, updated.deepCopy());
                for (JsonObject row : namespaces.values()) {
                    rows.add(row.deepCopy());
                }
            }
            described.add("namespaces", rows);
            panel = DshSettingsProjector.presentPanel(described);
        } catch (Exception error) {
            String message = DshJson.message(error);
            errorSink.accept(message);
            notifyUser(message);
        } finally {
            stateChanged.run();
        }
    }

    void openDocument() {
        operations.execute(
                () -> {
                    try {
                        remote.openSettingsDocument();
                    } catch (Exception error) {
                        LOG.debug(
                                "DSH settings document is unavailable; opening the browser root",
                                error);
                        openBrowser.run();
                    }
                });
    }

    void manageProviders() {
        operations.execute(
                () -> {
                    try {
                        JsonArray providers = remote.providers();
                        List<String> labels = new ArrayList<>();
                        List<JsonObject> rows = new ArrayList<>();
                        for (JsonElement candidate : providers) {
                            if (!candidate.isJsonObject()) {
                                continue;
                            }
                            JsonObject provider = candidate.getAsJsonObject();
                            String id = DshJson.string(provider, "provider");
                            if (id == null) {
                                continue;
                            }
                            boolean active = DshJson.bool(provider, "active", false);
                            StringBuilder label = new StringBuilder();
                            label.append(active ? "\u25cf " : "\u25cb ")
                                    .append(DshJson.stringOr(provider, "displayName", id))
                                    .append("  \u2014  ")
                                    .append(id)
                                    .append(active ? "  (active)" : "  (inactive)");
                            String namespace = DshJson.string(provider, "settingsNs");
                            if (namespace != null && !namespace.isBlank()) {
                                label.append("  \u00b7  settings: ").append(namespace);
                            }
                            labels.add(label.toString());
                            rows.add(provider);
                        }
                        if (labels.isEmpty()) {
                            notifyUser(DshBundle.message("dsh.providers.none"));
                            return;
                        }
                        labels.add(DshBundle.message("dsh.providers.open.web.ui"));
                        ApplicationManager.getApplication()
                                .invokeLater(() -> chooseProvider(labels, rows));
                    } catch (Exception error) {
                        String message = DshJson.message(error);
                        errorSink.accept(message);
                        notifyUser(DshBundle.message("dsh.providers.read.failed", message));
                        stateChanged.run();
                    }
                });
    }

    private void chooseProvider(List<String> labels, List<JsonObject> rows) {
        int selected =
                Messages.showChooseDialog(
                        project,
                        DshBundle.message("dsh.providers.dialog.message"),
                        DshBundle.message("dsh.providers.dialog.title"),
                        Messages.getInformationIcon(),
                        labels.toArray(new String[0]),
                        labels.get(0));
        if (selected < 0) {
            return;
        }
        if (selected == rows.size()) {
            openBrowser.run();
            return;
        }
        showProvider(rows.get(selected));
    }

    private void showProvider(JsonObject provider) {
        StringBuilder detail = new StringBuilder();
        detail.append("Provider: ").append(DshJson.stringOr(provider, "provider", "")).append('\n');
        detail.append("Display name: ")
                .append(DshJson.stringOr(provider, "displayName", ""))
                .append('\n');
        detail.append("Status: ")
                .append(DshJson.bool(provider, "active", false) ? "active" : "inactive")
                .append('\n');
        String namespace = DshJson.string(provider, "settingsNs");
        detail.append("Settings namespace: ")
                .append(namespace == null || namespace.isBlank() ? "<none>" : namespace)
                .append('\n');
        JsonArray path =
                provider.has("settingsPath") && provider.get("settingsPath").isJsonArray()
                        ? provider.getAsJsonArray("settingsPath")
                        : new JsonArray();
        if (!path.isEmpty()) {
            List<String> segments = new ArrayList<>();
            for (JsonElement segment : path) {
                if (segment.isJsonPrimitive()) {
                    segments.add(segment.getAsString());
                }
            }
            detail.append("Settings path: ").append(String.join(".", segments)).append('\n');
        }
        if (provider.has("declared") && provider.get("declared").isJsonPrimitive()) {
            detail.append("Declared by configuration: ")
                    .append(DshJson.bool(provider, "declared", false) ? "yes" : "no")
                    .append('\n');
        }
        DshTextDialog.show(
                project, DshBundle.message("dsh.providers.detail.title"), detail.toString());
    }

    private void notifyUser(String message) {
        notifier.accept(message);
    }
}
