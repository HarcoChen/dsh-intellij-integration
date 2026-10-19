package top.harcochen.dsh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;
import top.harcochen.dsh.remote.DshRemoteService;

/** Guided settings edits preserve the Host's schema and revision fences. */
final class DshProviderSettingsActions {
    private DshProviderSettingsActions() {}

    static void webSearch(
            Project project,
            DshRuntimeService runtime,
            DshRemoteService remote,
            Consumer<String> notify) {
        String endpoint = runtime.getUrl();
        JsonObject described = remote.describeSettings();
        JsonObject web = namespace(described, "web"),
                api = namespace(described, "web-deepseek-api");
        if (!DshJson.bool(described, "writable", false) || web == null || api == null)
            throw new IllegalStateException(DshBundle.message("dsh.web.unavailable"));
        int selected =
                choose(
                        project,
                        "dsh.web.title",
                        "dsh.web.choose",
                        new String[] {
                            DshBundle.message("dsh.web.api"), DshBundle.message("dsh.web.messages")
                        });
        if (selected < 0) return;
        JsonObject section = selected == 0 ? api : namespace(described, "web-search-deepseek");
        if (section == null)
            throw new IllegalStateException(DshBundle.message("dsh.web.unavailable"));
        JsonObject values = object(section, "value");
        String baseURL =
                input(project, "dsh.web.endpoint", DshJson.stringOr(values, "baseURL", ""));
        if (baseURL == null) return;
        baseURL = baseURL.trim();
        if (!baseURL.isBlank()) {
            URI uri;
            try {
                uri = URI.create(baseURL);
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException(DshBundle.message("dsh.web.endpoint.invalid"));
            }
            if ((!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme()))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null)
                throw new IllegalArgumentException(DshBundle.message("dsh.web.endpoint.invalid"));
        }
        Integer count = null;
        int include = -1;
        if (selected == 0) {
            String value = input(project, "dsh.web.count", DshJson.stringOr(values, "count", "10"));
            if (value == null) return;
            if (!value.trim().matches("[0-9]+"))
                throw new IllegalArgumentException(DshBundle.message("dsh.web.count.invalid"));
            try {
                count = Integer.valueOf(value.trim());
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException(DshBundle.message("dsh.web.count.invalid"));
            }
            if (count < 1 || count > 50)
                throw new IllegalArgumentException(DshBundle.message("dsh.web.count.invalid"));
            include =
                    choose(
                            project,
                            "dsh.web.title",
                            "dsh.web.original",
                            new String[] {
                                DshBundle.message("dsh.web.include"),
                                DshBundle.message("dsh.web.query.only")
                            });
            if (include < 0) return;
        }
        String key =
                DshPluginManagementController.ui(
                        () ->
                                Messages.showPasswordDialog(
                                        DshBundle.message("dsh.web.key.prompt"),
                                        DshBundle.message("dsh.web.title")));
        if (key == null) return;
        if (!key.isEmpty() && !key.trim().matches("[\\x21-\\x7E]+"))
            throw new IllegalArgumentException(DshBundle.message("dsh.web.key.invalid"));
        assertEndpoint(endpoint, runtime);
        if (!key.trim().isEmpty())
            remote.setCredential(
                    DshJson.stringOr(values, "apiKeyEnv", "DEEPSEEK_API_KEY"), key.trim());
        assertEndpoint(endpoint, runtime);
        JsonArray ops = new JsonArray();
        ops.add(
                op(
                        baseURL.isBlank() ? "unset" : "set",
                        strings("baseURL"),
                        baseURL.isBlank() ? null : new com.google.gson.JsonPrimitive(baseURL)));
        if (selected == 0) {
            ops.add(op("set", strings("count"), new com.google.gson.JsonPrimitive(count)));
            ops.add(
                    op(
                            "set",
                            strings("sendOriginalQuestion"),
                            new com.google.gson.JsonPrimitive(include == 0)));
        }
        remote.mutateSettings(
                DshJson.string(section, "ns"), ops, DshJson.longValue(section.get("revision"), -1));
        assertEndpoint(endpoint, runtime);
        JsonArray providers = new JsonArray();
        providers.add(
                op(
                        "set",
                        strings("searchProvider"),
                        new com.google.gson.JsonPrimitive(
                                selected == 0 ? "deepseek-search-api" : "deepseek-official")));
        providers.add(
                op(
                        "set",
                        strings("fetchProvider"),
                        new com.google.gson.JsonPrimitive(
                                selected == 0 ? "deepseek-fetch-api" : "http")));
        remote.mutateSettings("web", providers, DshJson.longValue(web.get("revision"), -1));
        notify.accept(DshBundle.message("dsh.web.saved"));
    }

    static void reasoning(
            Project project,
            DshRuntimeService runtime,
            DshRemoteService remote,
            JsonObject provider,
            JsonObject settings,
            Consumer<String> notify) {
        String endpoint = runtime.getUrl();
        if (!java.util.Objects.equals(
                endpoint, DshJson.strictString(settings, "ideRuntimeEndpoint")))
            throw new IllegalStateException(DshBundle.message("dsh.runtime.changed"));
        JsonObject section = namespace(settings, DshJson.string(provider, "settingsNs"));
        if (section == null || !DshJson.bool(settings, "writable", false)) return;
        JsonArray path = provider.getAsJsonArray("settingsPath");
        JsonObject profile = object(section, "value");
        for (JsonElement segment : path) profile = object(profile, segment.getAsString());
        JsonArray models =
                profile.has("models") && profile.get("models").isJsonArray()
                        ? profile.getAsJsonArray("models").deepCopy()
                        : new JsonArray();
        if (models.isEmpty()) {
            notify.accept(DshBundle.message("dsh.reasoning.models.empty"));
            return;
        }
        List<String> labels = new ArrayList<>();
        for (JsonElement model : models)
            labels.add(DshJson.stringOr(model.getAsJsonObject(), "id", "model"));
        int selected =
                choose(
                        project,
                        "dsh.reasoning.title",
                        "dsh.reasoning.choose.model",
                        labels.toArray(new String[0]));
        if (selected < 0) return;
        JsonObject model = models.get(selected).getAsJsonObject();
        int mode =
                choose(
                        project,
                        "dsh.reasoning.title",
                        "dsh.reasoning.choose.mode",
                        new String[] {
                            DshBundle.message("dsh.reasoning.inherit"),
                            DshBundle.message("dsh.reasoning.disable"),
                            DshBundle.message("dsh.reasoning.configure")
                        });
        if (mode < 0) return;
        if (mode == 0) model.remove("reasoningEfforts");
        else if (mode == 1) model.addProperty("reasoningEfforts", false);
        else {
            List<String> schemaPath = new ArrayList<>();
            for (JsonElement segment : path) schemaPath.add(segment.getAsString());
            schemaPath.addAll(List.of("models", "0", "reasoningEfforts"));
            JsonObject schema = schemaNode(section.get("schema"), schemaPath);
            List<String> levels = new ArrayList<>();
            if (schema != null && schema.has("list") && schema.get("list").isJsonArray())
                for (JsonElement candidate : schema.getAsJsonArray("list")) {
                    if (!candidate.isJsonObject()
                            || !"dict".equals(DshJson.string(candidate.getAsJsonObject(), "type")))
                        continue;
                    JsonObject keys = object(candidate.getAsJsonObject(), "sKey");
                    if (keys.has("list") && keys.get("list").isJsonArray())
                        for (JsonElement value : keys.getAsJsonArray("list")) {
                            String level =
                                    value.isJsonObject()
                                            ? DshJson.strictString(value.getAsJsonObject(), "value")
                                            : null;
                            if (level != null) levels.add(level);
                        }
                }
            if (levels.isEmpty())
                throw new IllegalStateException(DshBundle.message("dsh.reasoning.unavailable"));
            JsonObject current = object(model, "reasoningEfforts");
            String entered =
                    DshPluginManagementController.ui(
                            () ->
                                    Messages.showInputDialog(
                                            project,
                                            DshBundle.message(
                                                    "dsh.reasoning.levels.prompt",
                                                    String.join(", ", levels)),
                                            DshBundle.message("dsh.reasoning.title"),
                                            Messages.getQuestionIcon(),
                                            String.join(",", current.keySet()),
                                            null));
            if (entered == null) return;
            LinkedHashSet<String> selectedLevels = new LinkedHashSet<>();
            for (String level : entered.split(",")) {
                if (!levels.contains(level.trim()))
                    throw new IllegalArgumentException(
                            DshBundle.message("dsh.reasoning.levels.invalid"));
                selectedLevels.add(level.trim());
            }
            if (selectedLevels.isEmpty())
                throw new IllegalArgumentException(
                        DshBundle.message("dsh.reasoning.levels.invalid"));
            JsonObject efforts = new JsonObject();
            for (String level : selectedLevels) {
                String wire =
                        DshPluginManagementController.ui(
                                () ->
                                        Messages.showInputDialog(
                                                project,
                                                DshBundle.message(
                                                        "dsh.reasoning.wire.prompt", level),
                                                DshJson.stringOr(model, "id", "DSH"),
                                                Messages.getQuestionIcon(),
                                                DshJson.stringOr(
                                                        current,
                                                        level,
                                                        level.equals("off") ? "" : level),
                                                null));
                if (wire == null) return;
                if (wire.trim().isEmpty() && !level.equals("off"))
                    throw new IllegalArgumentException(
                            DshBundle.message("dsh.reasoning.wire.invalid"));
                efforts.add(
                        level,
                        wire.trim().isEmpty()
                                ? JsonNull.INSTANCE
                                : new com.google.gson.JsonPrimitive(wire.trim()));
            }
            model.add("reasoningEfforts", efforts);
        }
        JsonArray modelPath = path.deepCopy();
        modelPath.add("models");
        JsonArray ops = new JsonArray();
        ops.add(op("set", modelPath, models));
        assertEndpoint(endpoint, runtime);
        remote.mutateSettings(
                DshJson.string(provider, "settingsNs"),
                ops,
                DshJson.longValue(section.get("revision"), -1));
        notify.accept(DshBundle.message("dsh.reasoning.saved"));
    }

    private static JsonObject schemaNode(JsonElement raw, List<String> path) {
        if (raw == null || !raw.isJsonObject()) return null;
        JsonObject node = raw.getAsJsonObject();
        if (node.has("uid") && node.has("refs") && node.get("refs").isJsonObject()) {
            JsonElement root = node.getAsJsonObject("refs").get(node.get("uid").getAsString());
            if (root == null || !root.isJsonObject()) return null;
            node = root.getAsJsonObject();
        }
        for (String segment : path) {
            String type = DshJson.string(node, "type");
            JsonElement child =
                    "object".equals(type)
                            ? object(node, "dict").get(segment)
                            : ("dict".equals(type) || "array".equals(type))
                                    ? node.get("inner")
                                    : null;
            if (child == null || !child.isJsonObject()) return null;
            node = child.getAsJsonObject();
        }
        return node;
    }

    private static JsonObject namespace(JsonObject settings, String name) {
        if (settings.has("namespaces") && settings.get("namespaces").isJsonArray())
            for (JsonElement raw : settings.getAsJsonArray("namespaces"))
                if (raw.isJsonObject()
                        && java.util.Objects.equals(
                                name, DshJson.string(raw.getAsJsonObject(), "ns")))
                    return raw.getAsJsonObject();
        return null;
    }

    private static JsonObject object(JsonObject parent, String key) {
        return parent != null && parent.has(key) && parent.get(key).isJsonObject()
                ? parent.getAsJsonObject(key)
                : new JsonObject();
    }

    private static JsonObject op(String operation, JsonArray path, JsonElement value) {
        JsonObject op = new JsonObject();
        op.addProperty("op", operation);
        op.add("path", path);
        if (value != null) op.add("value", value);
        return op;
    }

    private static JsonArray strings(String value) {
        JsonArray values = new JsonArray();
        values.add(value);
        return values;
    }

    private static String input(Project project, String prompt, String value) {
        return DshPluginManagementController.ui(
                () ->
                        Messages.showInputDialog(
                                project,
                                DshBundle.message(prompt),
                                DshBundle.message("dsh.providers.dialog.title"),
                                Messages.getQuestionIcon(),
                                value,
                                null));
    }

    private static int choose(Project project, String title, String prompt, String[] choices) {
        return DshPluginManagementController.ui(
                () ->
                        Messages.showChooseDialog(
                                project,
                                DshBundle.message(prompt),
                                DshBundle.message(title),
                                Messages.getQuestionIcon(),
                                choices,
                                choices[0]));
    }

    private static void assertEndpoint(String endpoint, DshRuntimeService runtime) {
        if (endpoint == null || !endpoint.equals(runtime.getUrl()))
            throw new IllegalStateException(DshBundle.message("dsh.runtime.changed"));
    }
}
