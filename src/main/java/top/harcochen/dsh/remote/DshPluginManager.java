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
        return result.deepCopy();
    }

    private static DshRemoteException invalid() {
        return DshRemoteException.protocol(
                "pluginManager", "Invalid plugin management response", null);
    }
}
