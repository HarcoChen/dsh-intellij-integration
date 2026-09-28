package top.harcochen.dsh.remote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Set;

/** Bounded validation for the optional Cordis dynamic-plugin inventory. */
final class DshDynamicPlugin {
    private static final int MAX_PLUGINS = 512;
    private static final int MAX_PACKAGES = 256;
    private static final int MAX_WAITING = 128;
    private static final int MAX_TEXT = 8_192;
    private static final int MAX_STACK = 64 * 1024;

    private static final Set<String> RUN_MODES = Set.of("run", "update");
    private static final Set<String> RUN_STATUSES =
            Set.of(
                    "awaiting-approval",
                    "starting-host",
                    "client-pending",
                    "running",
                    "waiting",
                    "rejected",
                    "failed",
                    "cancelled",
                    "stopped");
    private static final Set<String> HALF_STATUSES =
            Set.of("absent", "pending", "stopped", "running", "waiting", "failed");
    private static final Set<String> DIAGNOSTIC_PHASES =
            Set.of(
                    "approval",
                    "host-load",
                    "host-apply",
                    "client-load",
                    "client-apply",
                    "client-render");

    private DshDynamicPlugin() {}

    static JsonArray normalizeInventory(JsonElement value) {
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() > MAX_PLUGINS)
            return null;
        JsonArray result = new JsonArray();
        Set<String> ids = new HashSet<>();
        for (JsonElement candidate : value.getAsJsonArray()) {
            JsonObject row = row(candidate);
            String id = row == null ? null : text(row, "pluginId");
            if (row == null || !ids.add(id)) return null;
            result.add(row);
        }
        return result;
    }

    static JsonObject normalizeReceipt(JsonElement value, String kind) {
        if (value == null || !value.isJsonObject()) return null;
        JsonObject source = value.getAsJsonObject();
        JsonObject result = new JsonObject();
        if (bool(source, "ok")) {
            result.addProperty("ok", true);
            if ("remove".equals(kind)
                    && source.has("wasRunning")
                    && isBoolean(source.get("wasRunning")))
                result.addProperty("wasRunning", source.get("wasRunning").getAsBoolean());
            if ("remove".equals(kind) && !result.has("wasRunning")) return null;
            return result;
        }
        if (!source.has("ok") || !isBoolean(source.get("ok")) || source.get("ok").getAsBoolean())
            return null;
        String reason = text(source, "reason");
        String message = text(source, "message");
        if (!("plugin-missing".equals(reason)
                        || ("stop".equals(kind) && "not-running".equals(reason)))
                || !bounded(message)) return null;
        result.addProperty("ok", false);
        result.addProperty("reason", reason);
        result.addProperty("message", message);
        return result;
    }

    static JsonObject normalizeResolve(JsonElement value) {
        if (value == null || !value.isJsonObject()) return null;
        JsonObject source = value.getAsJsonObject();
        if (!isBoolean(source.get("accepted"))) return null;
        JsonObject result = new JsonObject();
        result.addProperty("accepted", source.get("accepted").getAsBoolean());
        return result;
    }

    private static JsonObject row(JsonElement value) {
        if (value == null || !value.isJsonObject()) return null;
        JsonObject source = value.getAsJsonObject();
        String pluginId = text(source, "pluginId");
        String agentId = text(source, "agentId");
        JsonArray rawPackages = array(source, "packages");
        if (!bounded(pluginId)
                || !bounded(agentId)
                || rawPackages == null
                || rawPackages.isEmpty()
                || rawPackages.size() > MAX_PACKAGES) return null;
        JsonArray packages = new JsonArray();
        Set<String> packageIds = new HashSet<>();
        for (JsonElement candidate : rawPackages) {
            JsonObject pkg = packageValue(candidate);
            String packageId = pkg == null ? null : text(pkg, "packageId");
            if (pkg == null || !packageIds.add(packageId)) return null;
            packages.add(pkg);
        }
        JsonObject result = new JsonObject();
        result.addProperty("pluginId", pluginId);
        result.addProperty("agentId", agentId);
        result.add("packages", packages);
        if (!copyOptionalString(source, result, "currentPackageId")
                || !copyOptionalString(source, result, "nextPackageId")) return null;
        JsonObject active = activeRun(source.get("activeRun"));
        if (source.has("activeRun") && active == null) return null;
        if (active != null) result.add("activeRun", active);
        JsonObject latest = runAttempt(source.get("latestRun"));
        if (source.has("latestRun") && latest == null) return null;
        if (latest != null) result.add("latestRun", latest);
        return result;
    }

    private static JsonObject packageValue(JsonElement value) {
        if (value == null || !value.isJsonObject()) return null;
        JsonObject source = value.getAsJsonObject();
        String id = text(source, "packageId");
        String name = text(source, "name");
        String purpose = text(source, "purpose");
        if (!bounded(id)
                || !bounded(name)
                || !bounded(purpose)
                || !isBoolean(source.get("hasHostHalf"))
                || !isBoolean(source.get("hasClientHalf"))) return null;
        JsonObject result = new JsonObject();
        result.addProperty("packageId", id);
        result.addProperty("name", name);
        result.addProperty("purpose", purpose);
        result.addProperty("hasHostHalf", source.get("hasHostHalf").getAsBoolean());
        result.addProperty("hasClientHalf", source.get("hasClientHalf").getAsBoolean());
        return result;
    }

    private static JsonObject activeRun(JsonElement value) {
        if (value == null || value.isJsonNull()) return null;
        if (!value.isJsonObject()) return null;
        JsonObject source = value.getAsJsonObject();
        String run = text(source, "pluginRunId");
        String packageId = text(source, "packageId");
        if (!bounded(run) || !bounded(packageId)) return null;
        JsonObject result = new JsonObject();
        result.addProperty("pluginRunId", run);
        result.addProperty("packageId", packageId);
        return result;
    }

    private static JsonObject runAttempt(JsonElement value) {
        if (value == null || value.isJsonNull() || !value.isJsonObject()) return null;
        JsonObject source = value.getAsJsonObject();
        String runId = text(source, "pluginRunId");
        String packageId = text(source, "packageId");
        String mode = text(source, "mode");
        String status = text(source, "status");
        JsonObject host = half(source.get("host"));
        JsonObject client = half(source.get("client"));
        if (!bounded(runId)
                || !bounded(packageId)
                || !RUN_MODES.contains(mode)
                || !RUN_STATUSES.contains(status)
                || host == null
                || client == null) return null;
        JsonObject result = new JsonObject();
        result.addProperty("pluginRunId", runId);
        result.addProperty("packageId", packageId);
        result.addProperty("mode", mode);
        result.addProperty("status", status);
        if (!copyOptionalString(source, result, "approvalRequestId")) return null;
        if (source.has("requiresApproval")) {
            if (!isBoolean(source.get("requiresApproval"))) return null;
            result.addProperty("requiresApproval", source.get("requiresApproval").getAsBoolean());
        }
        result.add("host", host);
        result.add("client", client);
        JsonObject error = diagnostic(source.get("error"));
        if (source.has("error") && error == null) return null;
        if (error != null) result.add("error", error);
        return result;
    }

    private static JsonObject half(JsonElement value) {
        if (value == null || !value.isJsonObject()) return null;
        JsonObject source = value.getAsJsonObject();
        String status = text(source, "status");
        JsonArray waiting = array(source, "waitingFor");
        if (!HALF_STATUSES.contains(status) || waiting == null || waiting.size() > MAX_WAITING)
            return null;
        JsonArray copied = new JsonArray();
        for (JsonElement item : waiting) {
            String service =
                    item != null && item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()
                            ? item.getAsString()
                            : null;
            if (!bounded(service)) return null;
            copied.add(service);
        }
        JsonObject result = new JsonObject();
        result.addProperty("status", status);
        result.add("waitingFor", copied);
        if (!copyOptionalString(source, result, "error")) return null;
        return result;
    }

    private static JsonObject diagnostic(JsonElement value) {
        if (value == null || value.isJsonNull() || !value.isJsonObject()) return null;
        JsonObject source = value.getAsJsonObject();
        String phase = text(source, "phase");
        String message = text(source, "message");
        String pluginId = text(source, "pluginId");
        String packageId = text(source, "packageId");
        String runId = text(source, "pluginRunId");
        if (!DIAGNOSTIC_PHASES.contains(phase)
                || !bounded(message)
                || !bounded(pluginId)
                || !bounded(packageId)
                || !bounded(runId)) return null;
        JsonObject result = new JsonObject();
        result.addProperty("phase", phase);
        result.addProperty("message", message);
        if (!copyOptionalString(source, result, "stack", MAX_STACK)) return null;
        result.addProperty("pluginId", pluginId);
        result.addProperty("packageId", packageId);
        result.addProperty("pluginRunId", runId);
        return result;
    }

    private static JsonArray array(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonArray() ? object.getAsJsonArray(key) : null;
    }

    private static boolean bool(JsonObject object, String key) {
        return isBoolean(object.get(key)) && object.get(key).getAsBoolean();
    }

    private static boolean isBoolean(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
    }

    private static boolean bounded(String value) {
        return value != null && !value.isBlank() && value.length() <= MAX_TEXT;
    }

    private static boolean copyOptionalString(JsonObject source, JsonObject target, String key) {
        return copyOptionalString(source, target, key, MAX_TEXT);
    }

    private static boolean copyOptionalString(
            JsonObject source, JsonObject target, String key, int maximum) {
        if (!source.has(key)) return true;
        String value = text(source, key);
        if (value == null || value.isBlank() || value.length() > maximum) return false;
        target.addProperty(key, value);
        return true;
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString()
                : null;
    }
}
