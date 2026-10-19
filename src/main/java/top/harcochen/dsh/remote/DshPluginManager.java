package top.harcochen.dsh.remote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Set;
import top.harcochen.dsh.DshJson;

/** Validate plugin-manager profile controls independently of composition inventory. */
final class DshPluginManager {
    private DshPluginManager() {}

    static JsonArray rows(JsonElement value, boolean bundles) {
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() > 1000)
            throw invalid();
        JsonArray result = new JsonArray();
        Set<String> seen = new HashSet<>();
        for (JsonElement candidate : value.getAsJsonArray()) {
            if (!candidate.isJsonObject()) throw invalid();
            JsonObject raw = candidate.getAsJsonObject();
            String key = bundles ? "name" : "entryId";
            String id = DshJson.strictString(raw, key);
            if (id == null || id.isBlank() || !seen.add(id)) throw invalid();
            for (String flag :
                    bundles
                            ? new String[] {"enabled", "installed", "optional", "removable"}
                            : new String[] {"enabled"}) {
                if (!raw.has(flag)
                        || !raw.get(flag).isJsonPrimitive()
                        || !raw.getAsJsonPrimitive(flag).isBoolean()) throw invalid();
            }
            if (!bundles
                    && (DshJson.strictString(raw, "moduleName") == null
                            || !raw.has("fiberPhase")
                            || (!raw.get("fiberPhase").isJsonNull()
                                    && !Set.of(
                                                    "pending",
                                                    "loading",
                                                    "active",
                                                    "failed",
                                                    "unloading")
                                            .contains(DshJson.stringOr(raw, "fiberPhase", "")))))
                throw invalid();
            String reason = DshJson.strictString(raw, "readOnlyReason");
            if (raw.has("readOnlyReason")
                    && !Set.of("management-required", "unaddressable")
                            .contains(reason == null ? "" : reason)) throw invalid();
            if (!bundles && reason == null && DshJson.strictString(raw, "patchId") == null)
                throw invalid();
            JsonObject row = raw.deepCopy();
            if (raw.has("error")) {
                if (!raw.get("error").isJsonObject()
                        || DshJson.strictString(raw.getAsJsonObject("error"), "code") == null)
                    throw invalid();
                row.addProperty(
                        "errorCode", DshJson.strictString(raw.getAsJsonObject("error"), "code"));
            }
            result.add(row);
        }
        return result;
    }

    static JsonObject change(JsonElement value, String target) {
        if (value == null || !value.isJsonObject()) throw invalid();
        JsonObject result = value.getAsJsonObject();
        if (!target.equals(DshJson.strictString(result, "target"))
                || !result.has("changed")
                || !result.get("changed").isJsonPrimitive()
                || !result.getAsJsonPrimitive("changed").isBoolean()
                || !Set.of("applied", "restart-required", "overridden", "failed", "cancelled")
                        .contains(DshJson.stringOr(result, "application", ""))) throw invalid();
        if (result.has("warnings")) {
            if (!result.get("warnings").isJsonArray()) throw invalid();
            for (JsonElement text : result.getAsJsonArray("warnings"))
                if (!text.isJsonPrimitive() || !text.getAsJsonPrimitive().isString())
                    throw invalid();
        }
        if (result.has("restartReason")
                && !Set.of(
                                "no-hmr",
                                "package-moved",
                                "host-module",
                                "unmanaged-plugin",
                                "module-comparison")
                        .contains(DshJson.stringOr(result, "restartReason", ""))) throw invalid();
        if (result.has("pendingBuilds")) {
            if (!result.get("pendingBuilds").isJsonArray()
                    || result.getAsJsonArray("pendingBuilds").size() > 1000) throw invalid();
            for (JsonElement item : result.getAsJsonArray("pendingBuilds"))
                if (!item.isJsonPrimitive()
                        || !item.getAsJsonPrimitive().isString()
                        || item.getAsString().isBlank()) throw invalid();
        }
        if (result.has("error")) {
            if (!result.get("error").isJsonObject()) throw invalid();
            JsonObject error = result.getAsJsonObject("error");
            if (DshJson.strictString(error, "code") == null
                    || (error.has("diagnostic")
                            && DshJson.strictString(error, "diagnostic") == null)) throw invalid();
            if (error.has("incompatible")) {
                if (!error.get("incompatible").isJsonArray()) throw invalid();
                for (JsonElement item : error.getAsJsonArray("incompatible")) {
                    if (!item.isJsonObject()) throw invalid();
                    JsonObject row = item.getAsJsonObject();
                    for (String field : new String[] {"name", "version", "runtimeVersion"})
                        if (DshJson.strictString(row, field) == null
                                || DshJson.string(row, field).isBlank()) throw invalid();
                    JsonElement requirements =
                            row.has("requirements") ? row.get("requirements") : row.get("peers");
                    if (requirements == null || !requirements.isJsonObject()) throw invalid();
                    for (JsonElement requirement : requirements.getAsJsonObject().asMap().values())
                        if (!requirement.isJsonPrimitive()
                                || !requirement.getAsJsonPrimitive().isString()
                                || requirement.getAsString().isBlank()) throw invalid();
                }
            }
        }
        if (result.has("packageResult")) {
            if (!result.get("packageResult").isJsonObject()) throw invalid();
            JsonObject output = result.getAsJsonObject("packageResult");
            if (!output.has("exitCode")
                    || !output.get("exitCode").isJsonPrimitive()
                    || !output.getAsJsonPrimitive("exitCode").isNumber()
                    || !Double.isFinite(output.get("exitCode").getAsDouble())
                    || output.get("exitCode").getAsDouble()
                            != Math.rint(output.get("exitCode").getAsDouble())
                    || DshJson.strictString(output, "output") == null
                    || DshJson.strictString(output, "logPath") == null
                    || !output.has("truncated")
                    || !output.get("truncated").isJsonPrimitive()
                    || !output.getAsJsonPrimitive("truncated").isBoolean()
                    || (output.has("kind") && DshJson.strictString(output, "kind") == null))
                throw invalid();
        }
        return result.deepCopy();
    }

    private static DshRemoteException invalid() {
        return DshRemoteException.protocol(
                "pluginManager", "Invalid plugin management response", null);
    }
}
