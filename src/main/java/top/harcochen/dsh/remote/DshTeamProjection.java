package top.harcochen.dsh.remote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Set;
import top.harcochen.dsh.DshJson;

/** RC.2 Agent Teams are durable projections; the service exports no Team Remote methods. */
public final class DshTeamProjection {
    private DshTeamProjection() {}

    public static JsonObject normalize(JsonElement value) {
        if (value == null || !value.isJsonObject()) return null;
        JsonObject team = value.getAsJsonObject();
        if (!team.has("members")
                || !team.get("members").isJsonArray()
                || !team.has("tasks")
                || !team.get("tasks").isJsonArray()
                || team.getAsJsonArray("members").size() > 500
                || team.getAsJsonArray("tasks").size() > 2000) return null;
        Set<String> members = new HashSet<>();
        for (JsonElement valueMember : team.getAsJsonArray("members")) {
            if (!valueMember.isJsonObject()) return null;
            JsonObject member = valueMember.getAsJsonObject();
            if (DshJson.strictString(member, "id") == null
                    || !members.add(DshJson.strictString(member, "id"))
                    || DshJson.strictString(member, "name") == null
                    || !Set.of("lead", "teammate").contains(DshJson.stringOr(member, "role", ""))
                    || !Set.of("provisioning", "active", "failed")
                            .contains(DshJson.stringOr(member, "phase", ""))) return null;
        }
        Set<String> tasks = new HashSet<>();
        for (JsonElement valueTask : team.getAsJsonArray("tasks")) {
            if (!valueTask.isJsonObject()) return null;
            JsonObject task = valueTask.getAsJsonObject();
            if (DshJson.strictString(task, "id") == null
                    || !tasks.add(DshJson.strictString(task, "id"))
                    || DshJson.strictString(task, "subject") == null
                    || DshJson.strictString(task, "description") == null
                    || !Set.of("pending", "in_progress", "completed", "deleted")
                            .contains(DshJson.stringOr(task, "status", ""))
                    || !task.has("ready")
                    || !task.get("ready").isJsonPrimitive()
                    || !task.getAsJsonPrimitive("ready").isBoolean()) return null;
            double revision =
                    task.has("revision")
                                    && task.get("revision").isJsonPrimitive()
                                    && task.getAsJsonPrimitive("revision").isNumber()
                            ? task.get("revision").getAsDouble()
                            : 0;
            if (!Double.isFinite(revision)
                    || revision < 1
                    || revision > 9_007_199_254_740_991L
                    || revision != Math.floor(revision)) return null;
            for (String key : new String[] {"blockedBy", "writeScopes", "writeScopeWarnings"}) {
                if (!task.has(key) || !task.get(key).isJsonArray()) return null;
                for (JsonElement text : task.getAsJsonArray(key))
                    if (!text.isJsonPrimitive() || !text.getAsJsonPrimitive().isString())
                        return null;
            }
        }
        return team.deepCopy();
    }

    public static JsonArray members(JsonObject team, String parent) {
        JsonArray nodes = new JsonArray();
        for (JsonElement candidate : team.getAsJsonArray("members")) {
            JsonObject member = candidate.getAsJsonObject();
            if (parent.equals(DshJson.strictString(member, "id"))
                    || !"active".equals(DshJson.strictString(member, "phase"))) continue;
            JsonObject node = new JsonObject();
            node.addProperty("kind", "child");
            node.addProperty("id", DshJson.strictString(member, "id"));
            node.addProperty("label", DshJson.strictString(member, "name"));
            node.addProperty("parentSessionId", parent);
            node.addProperty("depth", 1);
            node.addProperty("parentAvailable", true);
            node.addProperty("mode", "continuable");
            node.addProperty("activity", "inactive");
            node.addProperty("hasChildren", false);
            nodes.add(node);
        }
        return nodes;
    }
}
