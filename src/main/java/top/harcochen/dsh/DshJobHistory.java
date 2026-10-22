package top.harcochen.dsh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Durable lifecycle records never authorize control of a process. */
final class DshJobHistory {
    private DshJobHistory() {}

    static JsonArray merge(String sessionId, JsonObject history, JsonArray live) {
        Map<String, JsonObject> jobs = new LinkedHashMap<>();
        if (history != null && history.has("events"))
            for (JsonElement raw : history.getAsJsonArray("events")) {
                if (!raw.isJsonObject()) continue;
                JsonObject event = DshWorkingDirectory.event(raw.getAsJsonObject());
                String type = DshJson.string(event, "type");
                if (!"job/registered".equals(type) && !"job/settled".equals(type)) continue;
                JsonObject data =
                        event.has("data") && event.get("data").isJsonObject()
                                ? event.getAsJsonObject("data")
                                : null;
                String id = DshJson.strictString(data, "jobId");
                if (id == null
                        || DshJson.strictString(data, "owner") == null
                        || DshJson.strictString(data, "kind") == null
                        || DshJson.strictString(data, "label") == null
                        || !finite(data, "startedAt")) continue;
                boolean settled = type.equals("job/settled");
                if (settled
                        && (!Set.of("completed", "killed", "failed")
                                        .contains(DshJson.stringOr(data, "status", ""))
                                || !finite(data, "finishedAt"))) continue;
                JsonObject item = new JsonObject();
                item.addProperty("id", id);
                item.addProperty("ownerSessionId", DshJson.string(data, "owner"));
                DshJson.copyString(data, item, "kind");
                DshJson.copyString(data, item, "label");
                item.add("startedAt", data.get("startedAt"));
                if (settled) item.add("finishedAt", data.get("finishedAt"));
                item.addProperty(
                        "status", settled ? DshJson.string(data, "status") : "unavailable");
                if (DshJson.strictString(data, "detail") != null)
                    item.addProperty("outputSummary", DshJson.string(data, "detail"));
                item.addProperty("historical", true);
                item.addProperty("canKill", false);
                jobs.put(id, item);
            }
        if (live != null)
            for (JsonElement raw : live) {
                if (!raw.isJsonObject()) continue;
                JsonObject item = raw.getAsJsonObject().deepCopy();
                String id = DshJson.string(item, "id");
                if (id == null) continue;
                JsonObject previous = jobs.get(id);
                if (previous != null
                        && !java.util.Objects.equals(
                                DshJson.string(previous, "ownerSessionId"),
                                DshJson.string(item, "ownerSessionId"))) continue;
                JsonObject merged = previous == null ? new JsonObject() : previous.deepCopy();
                for (var entry : item.entrySet()) merged.add(entry.getKey(), entry.getValue());
                merged.addProperty("historical", false);
                merged.addProperty(
                        "canKill",
                        DshJson.bool(item, "canKill", false)
                                && java.util.Objects.equals(
                                        sessionId, DshJson.string(item, "ownerSessionId")));
                jobs.put(id, merged);
            }
        List<JsonObject> ordered = new ArrayList<>(jobs.values());
        ordered.sort(
                (a, b) ->
                        Double.compare(
                                b.get("startedAt").getAsDouble(),
                                a.get("startedAt").getAsDouble()));
        JsonArray result = new JsonArray();
        for (JsonObject item : ordered) result.add(item);
        return result;
    }

    private static boolean finite(JsonObject value, String key) {
        JsonElement item = value == null ? null : value.get(key);
        return item != null
                && item.isJsonPrimitive()
                && item.getAsJsonPrimitive().isNumber()
                && Double.isFinite(item.getAsDouble());
    }
}
