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
import java.util.Locale;
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
    private final AtomicLong pluginInventoryGeneration = new AtomicLong();

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

    synchronized void togglePanel() {
        if (panel != null) {
            generation.incrementAndGet();
            pluginInventoryGeneration.incrementAndGet();
            panel = null;
            synchronized (namespaces) {
                namespaces.clear();
            }
            stateChanged.run();
            return;
        }
        long requestedGeneration = generation.incrementAndGet();
        long requestedInventoryGeneration = pluginInventoryGeneration.incrementAndGet();
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
                        JsonObject inventoryView = loadPluginInventory();
                        // Re-check: togglePanel may have closed the panel while presentPanel ran,
                        // and writing the stale panel back would resurrect a closed dialog.
                        JsonObject presented = DshSettingsProjector.presentPanel(described);
                        if (generation.get() != requestedGeneration
                                || pluginInventoryGeneration.get()
                                        != requestedInventoryGeneration) {
                            return;
                        }
                        presented.add("pluginInventory", inventoryView);
                        panel = presented;
                    } catch (Exception error) {
                        if (generation.get() != requestedGeneration) {
                            return;
                        }
                        panel = DshSettingsProjector.failedPanel(DshJson.message(error));
                        panel.add("pluginInventory", failedPluginInventory());
                    } finally {
                        stateChanged.run();
                    }
                });
    }

    /** Refresh only the optional plugin inventory while keeping edited settings cards intact. */
    void refreshPluginInventory() {
        JsonObject current = panel;
        if (current == null || !DshJson.bool(current, "open", false)) return;
        long requested = pluginInventoryGeneration.incrementAndGet();
        JsonObject loading = new JsonObject();
        loading.add("entries", inventoryRows(current));
        loading.addProperty("loading", true);
        JsonObject updated = current.deepCopy();
        updated.add("pluginInventory", loading);
        panel = updated;
        stateChanged.run();
        operations.execute(
                () -> {
                    JsonObject inventory;
                    try {
                        runtime.startAsync().join();
                        inventory = remote.pluginInventory();
                    } catch (Exception error) {
                        inventory = failedPluginInventory();
                        errorSink.accept(DshJson.message(error));
                    }
                    synchronized (DshSettingsController.this) {
                        if (pluginInventoryGeneration.get() != requested || panel == null) return;
                        JsonObject latest = panel.deepCopy();
                        latest.add("pluginInventory", inventory);
                        panel = latest;
                    }
                    stateChanged.run();
                });
    }

    private JsonObject loadPluginInventory() {
        try {
            return remote.pluginInventory();
        } catch (Exception error) {
            LOG.debug("DSH plugin inventory is unavailable", error);
            errorSink.accept(DshJson.message(error));
            return failedPluginInventory();
        }
    }

    private static JsonObject failedPluginInventory() {
        JsonObject inventory = new JsonObject();
        inventory.add("entries", new JsonArray());
        inventory.addProperty("error", DshBundle.message("dsh.plugins.inventory.failed"));
        return inventory;
    }

    private static JsonArray inventoryRows(JsonObject settingsPanel) {
        if (settingsPanel == null || !settingsPanel.has("pluginInventory")) return new JsonArray();
        JsonElement inventory = settingsPanel.get("pluginInventory");
        if (inventory == null || !inventory.isJsonObject()) return new JsonArray();
        JsonElement entries = inventory.getAsJsonObject().get("entries");
        return entries != null && entries.isJsonArray()
                ? entries.getAsJsonArray().deepCopy()
                : new JsonArray();
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
            JsonObject presented = DshSettingsProjector.presentPanel(described);
            JsonObject current = panel;
            if (current != null && current.has("pluginInventory")) {
                presented.add("pluginInventory", current.get("pluginInventory").deepCopy());
            }
            panel = presented;
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
                        JsonObject settings = remote.describeSettings();
                        JsonObject catalog;
                        try {
                            catalog = remote.modelCatalog();
                        } catch (Exception ignored) {
                            catalog = new JsonObject();
                        }
                        JsonObject finalCatalog = catalog;
                        ApplicationManager.getApplication()
                                .invokeLater(
                                        () -> chooseProvider(providers, settings, finalCatalog));
                    } catch (Exception error) {
                        String message = DshJson.message(error);
                        errorSink.accept(message);
                        notifyUser(DshBundle.message("dsh.providers.read.failed", message));
                        stateChanged.run();
                    }
                });
    }

    private void chooseProvider(JsonArray providers, JsonObject settings, JsonObject catalog) {
        List<String> labels = new ArrayList<>();
        List<JsonObject> rows = new ArrayList<>();
        boolean writable = DshJson.bool(settings, "writable", false);
        if (writable) labels.add(DshBundle.message("dsh.providers.add.custom"));
        for (JsonElement candidate : providers) {
            if (!candidate.isJsonObject()) continue;
            JsonObject provider = candidate.getAsJsonObject();
            String id = DshJson.string(provider, "provider");
            if (id == null || id.isBlank()) continue;
            boolean active = DshJson.bool(provider, "active", false);
            StringBuilder label =
                    new StringBuilder()
                            .append(active ? "\u25cf " : "\u25cb ")
                            .append(DshJson.stringOr(provider, "displayName", id))
                            .append("  \u2014  ")
                            .append(id)
                            .append(active ? "  (active)" : "  (inactive)");
            JsonObject profile = providerProfile(provider, settings);
            String endpoint = DshJson.string(profile, "baseURL");
            if (endpoint != null && !endpoint.isBlank())
                label.append("  \u00b7  ").append(endpoint);
            String ref = credentialRef(provider, profile);
            if (ref != null && !ref.isBlank()) label.append("  \u00b7  ").append(ref);
            int modelCount = providerModelCount(provider, catalog);
            if (modelCount > 0) label.append("  \u00b7  ").append(modelCount).append(" models");
            labels.add(label.toString());
            rows.add(provider);
        }
        if (rows.isEmpty() && !writable) {
            notifyUser(DshBundle.message("dsh.providers.none"));
            return;
        }
        labels.add(DshBundle.message("dsh.providers.open.web.ui"));
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
        if (writable && selected == 0) {
            addCustomProvider(settings);
            return;
        }
        int providerIndex = writable ? selected - 1 : selected;
        if (providerIndex == rows.size()) {
            openBrowser.run();
            return;
        }
        showProviderActions(rows.get(providerIndex), settings, catalog);
    }

    private void showProviderActions(JsonObject provider, JsonObject settings, JsonObject catalog) {
        JsonObject profile = providerProfile(provider, settings);
        String ref = credentialRef(provider, profile);
        List<String> actions =
                new ArrayList<>(
                        List.of(
                                DshBundle.message("dsh.providers.action.configure"),
                                DshBundle.message("dsh.providers.action.discover"),
                                DshBundle.message("dsh.providers.action.models"),
                                DshBundle.message("dsh.providers.action.detail")));
        if (ref != null && !ref.isBlank()) {
            actions.add(
                    DshBundle.message(
                            credentialConfigured(provider, settings)
                                    ? "dsh.providers.action.remove.key"
                                    : "dsh.providers.action.set.key"));
        }
        if (DshJson.bool(settings, "hasDocument", false)) {
            actions.add(DshBundle.message("dsh.providers.action.document"));
        }
        String title =
                DshJson.stringOr(
                        provider, "displayName", DshJson.stringOr(provider, "provider", "DSH"));
        int selected =
                Messages.showChooseDialog(
                        project,
                        DshBundle.message("dsh.providers.action.message", title),
                        DshBundle.message("dsh.providers.dialog.title"),
                        Messages.getInformationIcon(),
                        actions.toArray(new String[0]),
                        actions.get(0));
        if (selected < 0) return;
        if (selected == 0) {
            configureProvider(provider, settings);
        } else if (selected == 1) {
            discoverProvider(provider, settings);
        } else if (selected == 2) {
            showProviderModels(provider, catalog);
        } else if (selected == 3) {
            showProvider(provider, settings, catalog);
        } else if (actions.get(selected)
                .equals(DshBundle.message("dsh.providers.action.document"))) {
            openDocument();
        } else if (actions.get(selected)
                .equals(DshBundle.message("dsh.providers.action.remove.key"))) {
            unsetProviderCredential(provider, settings);
        } else {
            setProviderCredential(provider, settings);
        }
    }

    private void showProvider(JsonObject provider, JsonObject settings, JsonObject catalog) {
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
        JsonObject profile = providerProfile(provider, settings);
        for (String field : List.of("baseURL", "api", "displayName", "apiKeyEnv")) {
            String value = DshJson.string(profile, field);
            if (value != null && !value.isBlank())
                detail.append(field).append(": ").append(value).append('\n');
        }
        int modelCount = providerModelCount(provider, catalog);
        if (modelCount > 0) detail.append("Models: ").append(modelCount).append('\n');
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

    private void configureProvider(JsonObject provider, JsonObject settings) {
        String namespace = DshJson.string(provider, "settingsNs");
        JsonObject ns = settingsNamespace(settings, namespace);
        if (namespace == null || ns == null || !DshJson.bool(settings, "writable", false)) {
            notifyUser(DshBundle.message("dsh.providers.readonly"));
            return;
        }
        JsonObject profile = providerProfile(provider, settings);
        String id = DshJson.stringOr(provider, "provider", "provider");
        String endpoint =
                Messages.showInputDialog(
                        project,
                        DshBundle.message("dsh.providers.endpoint.prompt"),
                        DshBundle.message("dsh.providers.configure.title", id),
                        Messages.getQuestionIcon(),
                        DshJson.stringOr(profile, "baseURL", ""),
                        null);
        if (endpoint == null) return;
        String api = DshJson.string(profile, "api");
        if ("llm-pi-ai".equals(namespace)) {
            String[] protocols = {"openai-completions", "openai-responses", "anthropic-messages"};
            int selected =
                    Messages.showChooseDialog(
                            project,
                            DshBundle.message("dsh.providers.protocol.prompt"),
                            DshBundle.message("dsh.providers.configure.title", id),
                            Messages.getQuestionIcon(),
                            protocols,
                            api == null ? protocols[0] : api);
            if (selected >= 0) api = protocols[selected];
        }
        String key =
                Messages.showPasswordDialog(
                        DshBundle.message("dsh.providers.key.optional"),
                        DshBundle.message("dsh.providers.configure.title", id));
        String ref = credentialRef(provider, profile);
        JsonArray ops = new JsonArray();
        addSetting(ops, provider, "baseURL", endpoint.isBlank() ? null : endpoint.trim());
        if (api != null && !api.isBlank()) addSetting(ops, provider, "api", api);
        if (key != null && !key.isBlank()) {
            if (ref == null || ref.isBlank()) ref = deriveCredentialRef(id);
            addSetting(ops, provider, "apiKeyEnv", ref);
        }
        if (ops.isEmpty()) return;
        String finalRef = ref;
        operations.execute(
                () -> {
                    try {
                        remote.mutateSettings(namespace, ops, revision(ns));
                        if (key != null && !key.isBlank() && finalRef != null)
                            remote.setCredential(finalRef, key.trim());
                        notifyUser(DshBundle.message("dsh.providers.configured", id));
                    } catch (Exception error) {
                        providerError(error);
                    }
                });
    }

    private void discoverProvider(JsonObject provider, JsonObject settings) {
        String namespace = DshJson.string(provider, "settingsNs");
        JsonObject ns = settingsNamespace(settings, namespace);
        if (namespace == null || ns == null) {
            notifyUser(DshBundle.message("dsh.providers.readonly"));
            return;
        }
        JsonObject profile = providerProfile(provider, settings);
        String id = DshJson.stringOr(provider, "provider", "provider");
        String endpoint =
                Messages.showInputDialog(
                        project,
                        DshBundle.message("dsh.providers.endpoint.prompt"),
                        DshBundle.message("dsh.providers.discover.title", id),
                        Messages.getQuestionIcon(),
                        DshJson.stringOr(profile, "baseURL", ""),
                        null);
        if (endpoint == null || endpoint.isBlank()) return;
        String api = DshJson.stringOr(profile, "api", "openai-completions");
        String key =
                Messages.showPasswordDialog(
                        DshBundle.message("dsh.providers.discovery.key"),
                        DshBundle.message("dsh.providers.discover.title", id));
        JsonObject draft = new JsonObject();
        draft.addProperty("provider", id);
        draft.addProperty("baseURL", endpoint.trim());
        draft.addProperty("api", api);
        if (key != null && !key.isBlank()) draft.addProperty("apiKey", key.trim());
        String finalApi = api;
        operations.execute(
                () -> {
                    try {
                        JsonArray models = remote.discoverLlmModels(namespace, draft);
                        List<String> ids = new ArrayList<>();
                        for (JsonElement candidate : models) {
                            if (!candidate.isJsonObject()) continue;
                            String model = DshJson.string(candidate.getAsJsonObject(), "id");
                            if (model != null && !model.isBlank()) ids.add(model);
                        }
                        if (ids.isEmpty()) {
                            notifyUser(DshBundle.message("dsh.providers.models.none"));
                            return;
                        }
                        ApplicationManager.getApplication()
                                .invokeLater(
                                        () ->
                                                adoptModels(
                                                        provider, settings, ids, endpoint,
                                                        finalApi));
                    } catch (Exception error) {
                        providerError(error);
                    }
                });
    }

    private void adoptModels(
            JsonObject provider,
            JsonObject settings,
            List<String> discovered,
            String endpoint,
            String api) {
        String id = DshJson.stringOr(provider, "provider", "provider");
        String entered =
                Messages.showInputDialog(
                        project,
                        DshBundle.message("dsh.providers.models.prompt", discovered.size()),
                        DshBundle.message("dsh.providers.discover.title", id),
                        Messages.getQuestionIcon(),
                        String.join(",", discovered),
                        null);
        if (entered == null) return;
        LinkedHashMap<String, Boolean> selected = new LinkedHashMap<>();
        for (String model : entered.split(",")) {
            String value = model.trim();
            if (!value.isBlank()) selected.put(value, true);
        }
        if (selected.isEmpty()) return;
        String namespace = DshJson.string(provider, "settingsNs");
        JsonObject ns = settingsNamespace(settings, namespace);
        if (ns == null) return;
        JsonArray models = new JsonArray();
        for (String model : selected.keySet()) {
            JsonObject row = new JsonObject();
            row.addProperty("id", model);
            models.add(row);
        }
        JsonArray ops = new JsonArray();
        addSetting(ops, provider, "baseURL", endpoint.trim());
        addSetting(ops, provider, "api", api);
        addSetting(ops, provider, "models", models);
        operations.execute(
                () -> {
                    try {
                        remote.mutateSettings(namespace, ops, revision(ns));
                        notifyUser(
                                DshBundle.message(
                                        "dsh.providers.models.saved", selected.size(), id));
                    } catch (Exception error) {
                        providerError(error);
                    }
                });
    }

    private void showProviderModels(JsonObject provider, JsonObject catalog) {
        String id = DshJson.stringOr(provider, "provider", "provider");
        StringBuilder text = new StringBuilder();
        if (catalog.has("groups") && catalog.get("groups").isJsonArray()) {
            for (JsonElement candidate : catalog.getAsJsonArray("groups")) {
                if (!candidate.isJsonObject()
                        || !id.equals(DshJson.string(candidate.getAsJsonObject(), "id"))) continue;
                JsonArray models = candidate.getAsJsonObject().getAsJsonArray("models");
                for (JsonElement model : models) {
                    if (!model.isJsonObject()) continue;
                    text.append(DshJson.stringOr(model.getAsJsonObject(), "id", "model"));
                    String name = DshJson.string(model.getAsJsonObject(), "name");
                    if (name != null && !name.equals(DshJson.string(model.getAsJsonObject(), "id")))
                        text.append("  ").append(name);
                    text.append('\n');
                }
            }
        }
        DshTextDialog.show(
                project,
                DshBundle.message("dsh.providers.models.title", id),
                text.length() == 0
                        ? DshBundle.message("dsh.providers.models.catalog.empty")
                        : text.toString());
    }

    private void setProviderCredential(JsonObject provider, JsonObject settings) {
        JsonObject profile = providerProfile(provider, settings);
        String id = DshJson.stringOr(provider, "provider", "provider");
        String ref = credentialRef(provider, profile);
        if (ref == null || ref.isBlank()) ref = deriveCredentialRef(id);
        String value =
                Messages.showPasswordDialog(
                        DshBundle.message("dsh.providers.key.prompt", ref),
                        DshBundle.message("dsh.providers.key.title"));
        if (value == null || value.isBlank()) return;
        String namespace = DshJson.string(provider, "settingsNs");
        JsonObject ns = settingsNamespace(settings, namespace);
        String finalRef = ref;
        operations.execute(
                () -> {
                    try {
                        if (ns != null && DshJson.bool(settings, "writable", false)) {
                            JsonArray ops = new JsonArray();
                            addSetting(ops, provider, "apiKeyEnv", finalRef);
                            remote.mutateSettings(namespace, ops, revision(ns));
                        }
                        remote.setCredential(finalRef, value.trim());
                        notifyUser(DshBundle.message("dsh.providers.key.saved", finalRef));
                    } catch (Exception error) {
                        providerError(error);
                    }
                });
    }

    private void unsetProviderCredential(JsonObject provider, JsonObject settings) {
        JsonObject profile = providerProfile(provider, settings);
        String ref = credentialRef(provider, profile);
        if (ref == null || ref.isBlank()) return;
        int answer =
                Messages.showYesNoDialog(
                        project,
                        DshBundle.message("dsh.providers.key.remove.prompt", ref),
                        DshBundle.message("dsh.providers.key.title"),
                        Messages.getWarningIcon());
        if (answer != Messages.YES) return;
        String finalRef = ref;
        operations.execute(
                () -> {
                    try {
                        remote.unsetCredential(finalRef);
                        notifyUser(DshBundle.message("dsh.providers.key.removed", finalRef));
                    } catch (Exception error) {
                        providerError(error);
                    }
                });
    }

    private void addCustomProvider(JsonObject settings) {
        if (!DshJson.bool(settings, "writable", false)) return;
        String id =
                Messages.showInputDialog(
                        project,
                        DshBundle.message("dsh.providers.custom.id.prompt"),
                        DshBundle.message("dsh.providers.add.custom"),
                        Messages.getQuestionIcon());
        if (id == null || !id.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*")) return;
        String endpoint =
                Messages.showInputDialog(
                        project,
                        DshBundle.message("dsh.providers.endpoint.prompt"),
                        DshBundle.message("dsh.providers.add.custom"),
                        Messages.getQuestionIcon());
        if (endpoint == null || endpoint.isBlank()) return;
        String namespace = "llm-pi-ai";
        JsonObject ns = settingsNamespace(settings, namespace);
        if (ns == null) return;
        JsonObject profile = new JsonObject();
        profile.addProperty("baseURL", endpoint.trim());
        profile.addProperty("api", "openai-completions");
        JsonArray ops = new JsonArray();
        JsonArray path = new JsonArray();
        path.add("providers");
        path.add(id.trim());
        JsonObject op = new JsonObject();
        op.addProperty("op", "set");
        op.add("path", path);
        op.add("value", profile);
        ops.add(op);
        operations.execute(
                () -> {
                    try {
                        remote.mutateSettings(namespace, ops, revision(ns));
                        notifyUser(DshBundle.message("dsh.providers.configured", id.trim()));
                    } catch (Exception error) {
                        providerError(error);
                    }
                });
    }

    private JsonObject providerProfile(JsonObject provider, JsonObject settings) {
        String namespace = DshJson.string(provider, "settingsNs");
        JsonObject ns = settingsNamespace(settings, namespace);
        if (ns == null) return new JsonObject();
        JsonElement current = ns.get("value");
        JsonArray path = providerPath(provider);
        for (JsonElement segment : path) {
            String key = segment.isJsonPrimitive() ? segment.getAsString() : null;
            if (key == null || current == null || !current.isJsonObject()) return new JsonObject();
            current = current.getAsJsonObject().get(key);
        }
        return current != null && current.isJsonObject()
                ? current.getAsJsonObject()
                : new JsonObject();
    }

    private static JsonObject settingsNamespace(JsonObject settings, String namespace) {
        if (namespace == null
                || settings == null
                || !settings.has("namespaces")
                || !settings.get("namespaces").isJsonArray()) return null;
        for (JsonElement value : settings.getAsJsonArray("namespaces")) {
            if (value.isJsonObject()
                    && namespace.equals(DshJson.string(value.getAsJsonObject(), "ns")))
                return value.getAsJsonObject();
        }
        return null;
    }

    private static JsonArray providerPath(JsonObject provider) {
        return provider.has("settingsPath") && provider.get("settingsPath").isJsonArray()
                ? provider.getAsJsonArray("settingsPath")
                : new JsonArray();
    }

    private static String credentialRef(JsonObject provider, JsonObject profile) {
        String configured = DshJson.string(profile, "apiKeyEnv");
        if (configured != null && !configured.isBlank()) return configured;
        String id = DshJson.string(provider, "provider");
        if (id == null || id.isBlank()) return null;
        if ("deepseek-official".equals(id)) return "DEEPSEEK_API_KEY";
        return deriveCredentialRef(id);
    }

    private static String deriveCredentialRef(String id) {
        return id.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "_API_KEY";
    }

    private static boolean credentialConfigured(JsonObject provider, JsonObject settings) {
        JsonObject profile = providerProfileStatic(provider, settings);
        return DshJson.string(profile, "apiKeyEnv") != null;
    }

    private static JsonObject providerProfileStatic(JsonObject provider, JsonObject settings) {
        String namespace = DshJson.string(provider, "settingsNs");
        JsonObject ns = settingsNamespace(settings, namespace);
        if (ns == null) return new JsonObject();
        JsonElement value = ns.get("value");
        for (JsonElement segment : providerPath(provider)) {
            if (value == null || !value.isJsonObject()) return new JsonObject();
            value = value.getAsJsonObject().get(segment.getAsString());
        }
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    private static int providerModelCount(JsonObject provider, JsonObject catalog) {
        String id = DshJson.string(provider, "provider");
        if (id == null
                || catalog == null
                || !catalog.has("groups")
                || !catalog.get("groups").isJsonArray()) return 0;
        for (JsonElement candidate : catalog.getAsJsonArray("groups")) {
            if (candidate.isJsonObject()
                    && id.equals(DshJson.string(candidate.getAsJsonObject(), "id"))) {
                JsonElement models = candidate.getAsJsonObject().get("models");
                return models != null && models.isJsonArray() ? models.getAsJsonArray().size() : 0;
            }
        }
        return 0;
    }

    private static long revision(JsonObject namespace) {
        long value = DshJson.longValue(namespace.get("revision"), -1);
        return value < 0 ? -1 : value;
    }

    private static void addSetting(JsonArray ops, JsonObject provider, String leaf, Object value) {
        JsonArray path = providerPath(provider).deepCopy();
        path.add(leaf);
        JsonObject op = new JsonObject();
        op.addProperty("op", value == null ? "unset" : "set");
        op.add("path", path);
        if (value instanceof JsonElement element) op.add("value", element.deepCopy());
        else if (value != null) op.addProperty("value", String.valueOf(value));
        ops.add(op);
    }

    private void providerError(Exception error) {
        String text = DshJson.message(error);
        errorSink.accept(text);
        notifyUser(DshBundle.message("dsh.providers.action.failed", text));
    }

    private void notifyUser(String message) {
        notifier.accept(message);
    }
}
