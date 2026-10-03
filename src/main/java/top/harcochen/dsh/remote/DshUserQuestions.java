package top.harcochen.dsh.remote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Set;
import top.harcochen.dsh.DshJson;

/** Bounded RC.2 durable user-question projection validation. */
final class DshUserQuestions {
    private DshUserQuestions() {}

    static boolean questions(JsonElement value) {
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() > 100)
            return false;
        Set<String> ids = new HashSet<>();
        for (JsonElement item : value.getAsJsonArray()) {
            if (!item.isJsonObject()) return false;
            JsonObject q = item.getAsJsonObject();
            String id = DshJson.strictString(q, "id");
            if (id == null
                    || id.isBlank()
                    || !ids.add(id)
                    || DshJson.strictString(q, "question") == null
                    || !q.has("options")
                    || !q.get("options").isJsonArray()) return false;
            for (JsonElement option : q.getAsJsonArray("options")) {
                if (!option.isJsonObject()
                        || DshJson.strictString(option.getAsJsonObject(), "label") == null)
                    return false;
            }
        }
        return true;
    }

    static JsonObject normalize(JsonElement value) {
        if (value == null || !value.isJsonObject()) return null;
        JsonObject p = value.getAsJsonObject();
        for (String field : new String[] {"active", "settled"}) {
            if (!p.has(field)
                    || !p.get(field).isJsonArray()
                    || p.getAsJsonArray(field).size() > 1000) return null;
        }
        Set<String> ids = new HashSet<>();
        for (JsonElement item : p.getAsJsonArray("active")) {
            if (!item.isJsonObject()) return null;
            JsonObject q = item.getAsJsonObject();
            String call = DshJson.strictString(q, "callId");
            if (call == null
                    || call.isBlank()
                    || !ids.add(call)
                    || !Set.of("open", "continued").contains(DshJson.stringOr(q, "state", ""))
                    || !questions(q.get("questions"))) return null;
        }
        for (JsonElement item : p.getAsJsonArray("settled")) {
            if (!item.isJsonObject()) return null;
            JsonObject q = item.getAsJsonObject();
            String call = DshJson.strictString(q, "callId");
            if (call == null
                    || call.isBlank()
                    || !ids.add(call)
                    || !q.has("answers")
                    || !q.get("answers").isJsonArray()) return null;
            JsonArray answers = q.getAsJsonArray("answers");
            if (answers.size() > 100) return null;
            for (JsonElement answer : answers) {
                if (!answer.isJsonObject()) return null;
                JsonObject a = answer.getAsJsonObject();
                if (DshJson.strictString(a, "id") == null
                        || !a.has("selected")
                        || !a.get("selected").isJsonArray()) return null;
                for (JsonElement selection : a.getAsJsonArray("selected")) {
                    if (!selection.isJsonPrimitive() || !selection.getAsJsonPrimitive().isString())
                        return null;
                }
                if (a.has("custom") && DshJson.strictString(a, "custom") == null) return null;
            }
        }
        return p.deepCopy();
    }
}
