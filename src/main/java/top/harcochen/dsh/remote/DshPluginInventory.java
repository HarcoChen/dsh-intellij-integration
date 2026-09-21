package top.harcochen.dsh.remote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Set;

/** Strict, bounded projection for the optional {@code pluginInventory/list} endpoint. */
final class DshPluginInventory {
    private static final int MAX_ENTRIES = 1_000;
    private static final int MAX_PRESETS = 256;
    private static final int MAX_ROWS = 1_000;
    private static final int MAX_TEXT = 8_192;

    private DshPluginInventory() {}

    static JsonObject normalize(JsonElement value) {
        if (value == null || !value.isJsonObject()) return null;
        JsonObject source = value.getAsJsonObject();
        JsonArray rawEntries = array(source, "entries");
        if (rawEntries == null || rawEntries.size() > MAX_ENTRIES) return null;
        JsonArray entries = new JsonArray();
        Set<String> entryIds = new HashSet<>();
        for (JsonElement candidate : rawEntries) {
            JsonObject row = entry(candidate, false);
            if (row == null || !entryIds.add(text(row, "entryId"))) return null;
            entries.add(row);
        }

        JsonObject result = new JsonObject();
        result.add("entries", entries);
        if (source.has("agentPresets")) {
            JsonArray rawPresets = array(source, "agentPresets");
            if (rawPresets == null || rawPresets.size() > MAX_PRESETS) return null;
            JsonArray presets = new JsonArray();
            Set<String> presetIds = new HashSet<>();
            for (JsonElement candidate : rawPresets) {
                JsonObject preset = preset(candidate);
                String id = preset == null ? null : text(preset, "id");
                if (preset == null || !presetIds.add(id)) return null;
                presets.add(preset);
            }
            result.add("agentPresets", presets);
        }
        return result;
    }

    private static JsonObject entry(JsonElement value, boolean presetRow) {
        if (value == null || !value.isJsonObject()) return null;
        JsonObject source = value.getAsJsonObject();
        String entryId = text(source, "entryId");
        if ((!presetRow && !bounded(entryId))
                || (presetRow && entryId != null && !bounded(entryId))) return null;
        String moduleName = text(source, "moduleName");
        String phase = phase(source.get("fiberPhase"));
        JsonElement enabledValue = enabled(source.get("enabled"));
        if (!bounded(moduleName)
                || !source.has("fiberPhase")
                || INVALID_PHASE.equals(phase)
                || enabledValue == null
                || (!presetRow
                        && (!enabledValue.isJsonPrimitive()
                                || !enabledValue.getAsJsonPrimitive().isBoolean()))) return null;
        JsonObject result = new JsonObject();
        if (entryId == null) result.add("entryId", com.google.gson.JsonNull.INSTANCE);
        else result.addProperty("entryId", entryId);
        result.addProperty("moduleName", moduleName);
        JsonElement enabled = enabledValue;
        if (enabled.isJsonPrimitive() && enabled.getAsJsonPrimitive().isBoolean()) {
            result.addProperty("enabled", enabled.getAsBoolean());
        } else {
            result.addProperty("enabled", enabled.getAsString());
        }
        addPhase(result, phase);
        String condition = text(source, "condition");
        if (condition != null) {
            if (!bounded(condition)) return null;
            result.addProperty("condition", condition);
        }
        return result;
    }

    private static JsonObject preset(JsonElement value) {
        if (value == null || !value.isJsonObject()) return null;
        JsonObject source = value.getAsJsonObject();
        String id = text(source, "id");
        String trust = text(source, "trust");
        JsonArray rawRows = array(source, "rows");
        if (!bounded(id)
                || !("system".equals(trust) || "user".equals(trust))
                || !isBoolean(source.get("isDefault"))
                || rawRows == null
                || rawRows.size() > MAX_ROWS) return null;
        JsonArray rows = new JsonArray();
        Set<String> rowKeys = new HashSet<>();
        for (JsonElement candidate : rawRows) {
            JsonObject row = entry(candidate, true);
            String key = row == null ? null : text(row, "moduleName") + "\0" + text(row, "entryId");
            if (row == null || !rowKeys.add(key)) return null;
            rows.add(row);
        }
        JsonObject result = new JsonObject();
        result.addProperty("id", id);
        result.addProperty("trust", trust);
        result.addProperty("isDefault", source.get("isDefault").getAsBoolean());
        String name = text(source, "name");
        if (name != null) {
            if (!bounded(name)) return null;
            result.addProperty("name", name);
        }
        String broken = text(source, "broken");
        if (broken != null) {
            if (!bounded(broken)) return null;
            result.addProperty("broken", broken);
        }
        result.add("rows", rows);
        return result;
    }

    private static JsonArray array(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonArray() ? object.getAsJsonArray(key) : null;
    }

    private static boolean bounded(String value) {
        return value != null && !value.isBlank() && value.length() <= MAX_TEXT;
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString()
                : null;
    }

    private static boolean isBoolean(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
    }

    private static JsonElement enabled(JsonElement value) {
        if (isBoolean(value)) return value;
        if (value != null
                && value.isJsonPrimitive()
                && value.getAsJsonPrimitive().isString()
                && "conditional".equals(value.getAsString())) return value;
        return null;
    }

    private static String phase(JsonElement value) {
        if (value == null) return INVALID_PHASE;
        if (value.isJsonNull()) return null;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            return INVALID_PHASE;
        String phase = value.getAsString();
        return switch (phase) {
            case "pending", "loading", "active", "failed", "unloading" -> phase;
            default -> INVALID_PHASE;
        };
    }

    private static final String INVALID_PHASE = "\u0000";

    private static void addPhase(JsonObject target, String phase) {
        if (phase == null) target.add("fiberPhase", com.google.gson.JsonNull.INSTANCE);
        else target.addProperty("fiberPhase", phase);
    }
}
