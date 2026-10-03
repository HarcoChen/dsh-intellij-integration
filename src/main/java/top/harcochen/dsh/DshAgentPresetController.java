package top.harcochen.dsh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;
import top.harcochen.dsh.remote.DshRemoteService;

/** Loads, manages, and selects Harness agent presets. */
final class DshAgentPresetController {
    private static final Logger LOG = Logger.getInstance(DshAgentPresetController.class);

    private final Project project;
    private final DshRemoteService remote;
    private final ExecutorService operations;
    private final Supplier<String> sessionId;
    private final Consumer<String> pendingPreset;
    private final Runnable refreshState;
    private final Runnable stateChanged;
    private final Consumer<String> notifier;
    private final Consumer<String> errorSink;

    private volatile JsonArray catalog = new JsonArray();
    private volatile boolean modeSelectionEnabled = true;
    private volatile long catalogGeneration = -1;
    private volatile long catalogReadAt;
    private volatile long catalogSettingsEpoch = -1;

    DshAgentPresetController(
            Project project,
            DshRemoteService remote,
            ExecutorService operations,
            Supplier<String> sessionId,
            Consumer<String> pendingPreset,
            Runnable refreshState,
            Runnable stateChanged,
            Consumer<String> notifier,
            Consumer<String> errorSink) {
        this.project = project;
        this.remote = remote;
        this.operations = operations;
        this.sessionId = sessionId;
        this.pendingPreset = pendingPreset;
        this.refreshState = refreshState;
        this.stateChanged = stateChanged;
        this.notifier = notifier;
        this.errorSink = errorSink;
    }

    void refreshCatalogIfNecessary() {
        if (!catalog.isEmpty()
                && catalogGeneration == remote.snapshot().generation
                && catalogSettingsEpoch == remote.settingsEpoch()
                && System.currentTimeMillis() - catalogReadAt < 3000) {
            return;
        }
        try {
            catalog = agentPresets();
        } catch (Exception error) {
            LOG.debug("The connected Harness did not expose an agent preset catalog", error);
        }
    }

    private JsonArray agentPresets() throws top.harcochen.dsh.remote.DshRemoteException {
        long requestedEpoch = remote.settingsEpoch();
        JsonObject value = remote.agentPresetCatalog();
        modeSelectionEnabled = DshJson.bool(value, "modeSelectionEnabled", true);
        if (!modeSelectionEnabled) pendingPreset.accept(null);
        catalogGeneration = remote.snapshot().generation;
        catalogReadAt = System.currentTimeMillis();
        catalogSettingsEpoch = requestedEpoch;
        return value.has("presets") && value.get("presets").isJsonArray()
                ? value.getAsJsonArray("presets")
                : new JsonArray();
    }

    boolean modeSelectionEnabled() {
        return modeSelectionEnabled;
    }

    void prepareForSend(String session) throws top.harcochen.dsh.remote.DshRemoteException {
        JsonArray presets = agentPresets();
        catalog = presets;
        if (modeSelectionEnabled || session == null) return;
        for (JsonElement item : remote.snapshot().catalog) {
            JsonObject row = item.getAsJsonObject();
            if (!session.equals(DshJson.string(row, "sessionId"))
                    || !DshJson.bool(row, "blank", false)) continue;
            for (JsonElement candidate : presets) {
                JsonObject preset = candidate.getAsJsonObject();
                if (DshJson.bool(preset, "isDefault", false)
                        && (!preset.has("broken") || preset.get("broken").isJsonNull())) {
                    String id = DshJson.string(preset, "id");
                    if (id != null && !id.equals(DshJson.string(row, "agentPreset"))) {
                        try {
                            remote.selectAgentPreset(session, id);
                        } catch (top.harcochen.dsh.remote.DshRemoteException error) {
                            if (!"locked".equals(error.code())
                                    && (error.code() == null || !error.code().endsWith("/locked")))
                                throw error;
                        }
                    }
                    return;
                }
            }
        }
    }

    String label(String presetId) {
        if (presetId == null || presetId.isBlank()) {
            return null;
        }
        for (JsonElement candidate : catalog) {
            if (candidate.isJsonObject()
                    && presetId.equals(DshJson.string(candidate.getAsJsonObject(), "id"))) {
                String name = DshJson.string(candidate.getAsJsonObject(), "name");
                return name == null || name.isBlank() ? null : name;
            }
        }
        return null;
    }

    void manage() {
        operations.execute(
                () -> {
                    try {
                        JsonObject value = remote.agentPresetCatalog();
                        JsonArray presets =
                                value.has("presets") && value.get("presets").isJsonArray()
                                        ? value.getAsJsonArray("presets")
                                        : new JsonArray();
                        String defaultNamespace = defaultNamespace();
                        List<String> labels = new ArrayList<>();
                        List<JsonObject> rows = new ArrayList<>();
                        for (JsonElement candidate : presets) {
                            if (!candidate.isJsonObject()) {
                                continue;
                            }
                            JsonObject preset = candidate.getAsJsonObject();
                            String id = DshJson.string(preset, "id");
                            if (id == null) {
                                continue;
                            }
                            StringBuilder label =
                                    new StringBuilder(DshJson.stringOr(preset, "name", id));
                            label.append("  \u2014  ").append(id);
                            if (DshJson.bool(preset, "isDefault", false)) {
                                label.append("  (default)");
                            }
                            String broken = DshJson.string(preset, "broken");
                            if (broken != null && !broken.isBlank()) {
                                label.append("  \u00b7  broken");
                            }
                            labels.add(label.toString());
                            rows.add(preset);
                        }
                        if (labels.isEmpty()) {
                            notifyUser(DshBundle.message("dsh.presets.none"));
                            return;
                        }
                        ApplicationManager.getApplication()
                                .invokeLater(() -> chooseAction(labels, rows, defaultNamespace));
                    } catch (Exception error) {
                        String message = DshJson.message(error);
                        errorSink.accept(message);
                        notifyUser(DshBundle.message("dsh.presets.read.failed", message));
                        stateChanged.run();
                    }
                });
    }

    private void chooseAction(List<String> labels, List<JsonObject> rows, String defaultNamespace) {
        int selected =
                Messages.showChooseDialog(
                        project,
                        DshBundle.message("dsh.presets.dialog.message"),
                        DshBundle.message("dsh.presets.dialog.title"),
                        Messages.getQuestionIcon(),
                        labels.toArray(new String[0]),
                        labels.get(0));
        if (selected < 0 || selected >= rows.size()) {
            return;
        }
        JsonObject preset = rows.get(selected);
        String id = DshJson.string(preset, "id");
        List<String> actionLabels = new ArrayList<>();
        List<String> actionIds = new ArrayList<>();
        actionLabels.add(DshBundle.message("dsh.presets.action.view.composition"));
        actionIds.add("view");
        actionLabels.add(DshBundle.message("dsh.presets.action.use.for.next.session"));
        actionIds.add("use");
        if (defaultNamespace != null
                && !DshJson.bool(preset, "isDefault", false)
                && DshJson.string(preset, "broken") == null) {
            actionLabels.add(DshBundle.message("dsh.presets.action.make.default"));
            actionIds.add("default");
        }
        actionLabels.add(DshBundle.message("dsh.presets.action.cancel"));
        actionIds.add("cancel");
        String[] displayLabels = actionLabels.toArray(new String[0]);
        int chosen =
                Messages.showChooseDialog(
                        project,
                        DshJson.stringOr(preset, "name", id)
                                + "\n"
                                + DshJson.stringOr(
                                        preset,
                                        "description",
                                        DshBundle.message("dsh.presets.no.description")),
                        DshBundle.message("dsh.presets.action.dialog.title"),
                        Messages.getQuestionIcon(),
                        displayLabels,
                        displayLabels[0]);
        if (chosen < 0 || chosen >= actionIds.size()) {
            return;
        }
        performAction(actionIds.get(chosen), id, defaultNamespace);
    }

    private String defaultNamespace() {
        try {
            JsonObject described = remote.describeSettings();
            if (!DshJson.bool(described, "writable", false) || !described.has("namespaces"))
                return null;
            for (JsonElement value : described.getAsJsonArray("namespaces")) {
                String ns = DshJson.string(value.getAsJsonObject(), "ns");
                if ("agent-preset-registry".equals(ns) || "agent-presets".equals(ns)) return ns;
            }
        } catch (Exception unavailable) {
            LOG.debug("Preset default settings unavailable", unavailable);
        }
        return null;
    }

    private void performAction(String action, String id, String namespace) {
        switch (action) {
            case "default" ->
                    operations.execute(
                            () -> {
                                try {
                                    JsonObject patch = new JsonObject();
                                    patch.addProperty(
                                            "agent-presets".equals(namespace)
                                                    ? "default"
                                                    : "selectedDefault",
                                            id);
                                    remote.updateSettings(namespace, patch);
                                    catalog = new JsonArray();
                                    notifyUser(DshBundle.message("dsh.presets.default.saved", id));
                                    refreshState.run();
                                } catch (Exception error) {
                                    notifyUser(DshJson.message(error));
                                }
                            });
            case "view" -> showComposition(id);
            case "use" -> select(id);
            default -> {}
        }
    }

    private void showComposition(String id) {
        operations.execute(
                () -> {
                    try {
                        JsonObject document = remote.readAgentPreset(id);
                        StringBuilder text = new StringBuilder();
                        text.append(DshJson.stringOr(document, "name", id)).append('\n');
                        String description = DshJson.string(document, "description");
                        if (description != null && !description.isBlank()) {
                            text.append(DshBundle.message("dsh.presets.detail.description"))
                                    .append(description)
                                    .append('\n');
                        }
                        text.append('\n').append(DshJson.stringOr(document, "content", ""));
                        DshTextDialog.show(
                                project,
                                DshBundle.message("dsh.presets.detail.title", id),
                                text.toString());
                    } catch (Exception error) {
                        notifyUser(
                                DshBundle.message(
                                        "dsh.presets.read.detail.failed", DshJson.message(error)));
                    }
                });
    }

    void select(String requestedPreset) {
        String currentSession = sessionId.get();
        operations.execute(
                () -> {
                    try {
                        JsonArray presets = agentPresets();
                        if (!modeSelectionEnabled)
                            throw new IllegalStateException(
                                    DshBundle.message("dsh.presets.selection.disabled"));
                        List<String> labels = new ArrayList<>();
                        List<String> ids = new ArrayList<>();
                        for (JsonElement presetElement : presets) {
                            if (!presetElement.isJsonObject()) {
                                continue;
                            }
                            JsonObject preset = presetElement.getAsJsonObject();
                            String id = DshJson.stringOr(preset, "id", "");
                            if (id.isBlank()
                                    || (preset.has("broken")
                                            && !preset.get("broken").isJsonNull()
                                            && !DshJson.stringOr(preset, "broken", "").isBlank())) {
                                continue;
                            }
                            ids.add(id);
                            labels.add(DshJson.stringOr(preset, "name", id) + "  (" + id + ")");
                        }
                        if (labels.isEmpty()) {
                            notifyUser(DshBundle.message("dsh.presets.not.exposed"));
                            return;
                        }
                        String target =
                                requestedPreset == null || requestedPreset.isBlank()
                                        ? null
                                        : requestedPreset.trim();
                        if (target != null && !ids.contains(target)) {
                            notifyUser(DshBundle.message("dsh.presets.not.found", target));
                            return;
                        }
                        if (target == null) {
                            ApplicationManager.getApplication()
                                    .invokeLater(() -> choosePreset(currentSession, labels, ids));
                        } else {
                            apply(currentSession, target);
                        }
                    } catch (Exception error) {
                        errorSink.accept(DshJson.message(error));
                        stateChanged.run();
                    }
                });
    }

    private void choosePreset(String currentSession, List<String> labels, List<String> ids) {
        int selected =
                Messages.showChooseDialog(
                        project,
                        DshBundle.message("dsh.presets.select.message"),
                        DshBundle.message("dsh.presets.select.title"),
                        Messages.getQuestionIcon(),
                        labels.toArray(new String[0]),
                        labels.get(0));
        if (selected >= 0 && selected < ids.size()) {
            apply(currentSession, ids.get(selected));
        }
    }

    private void apply(String currentSession, String target) {
        if (currentSession == null || currentSession.isBlank()) {
            pendingPreset.accept(target);
            stateChanged.run();
            return;
        }
        operations.execute(
                () -> {
                    try {
                        agentPresets();
                        if (!modeSelectionEnabled)
                            throw new IllegalStateException(
                                    DshBundle.message("dsh.presets.selection.disabled"));
                        if (!java.util.Objects.equals(currentSession, sessionId.get()))
                            throw new IllegalStateException(
                                    DshBundle.message("dsh.plan.session.changed"));
                        remote.selectAgentPreset(currentSession, target);
                        refreshState.run();
                    } catch (Exception error) {
                        errorSink.accept(DshJson.message(error));
                        stateChanged.run();
                    }
                });
    }

    private void notifyUser(String message) {
        notifier.accept(message);
    }
}
