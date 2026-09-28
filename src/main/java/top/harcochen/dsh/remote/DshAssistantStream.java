package top.harcochen.dsh.remote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Set;
import top.harcochen.dsh.DshJson;

/** RC 0.1.5 process-local output; revisions and chunk indexes are independent of durable seq. */
public final class DshAssistantStream {
    private long revision;
    private long nextIndex;
    private JsonObject active;
    private JsonObject settlement;

    public JsonObject snapshot() {
        return settlement == null && active != null ? active.deepCopy() : null;
    }

    public void replace(JsonElement value, long cursor) {
        JsonObject baseline = object(value);
        revision = integer(baseline.get("revision"), 0);
        active = null;
        settlement = null;
        nextIndex = 0;
        if (!baseline.has("activeAttempt")) return;
        JsonObject attempt = object(baseline.get("activeAttempt"));
        id(attempt, "attemptId");
        if (integer(attempt.get("startedAfterSeq"), -1) > cursor) fail();
        integer(attempt.get("turn"), 0);
        integer(attempt.get("step"), 0);
        nextIndex = integer(attempt.get("nextIndex"), 0);
        JsonArray chunks = expand(attempt.get("stream"));
        if (chunks.size() != nextIndex) fail();
        active = attempt.deepCopy();
        active.remove("stream");
        active.add("chunks", chunks);
    }

    public void acceptEvent(JsonObject event) {
        if (active == null) return;
        String type = DshJson.string(event, "type");
        if (!"assistant/message".equals(type) && !"assistant/attempt".equals(type)) return;
        if ("assistant/message".equals(type)
                && !"append".equals(DshJson.string(event, "surfaceOp"))) return;
        JsonObject data = object(event.get("data"));
        long seq = integer(event.get("seq"), 0);
        if (seq <= integer(active.get("startedAfterSeq"), -1)
                || !active.get("turn").equals(data.get("turn"))
                || !active.get("step").equals(data.get("step"))) return;
        if (settlement != null) fail();
        settlement = new JsonObject();
        settlement.addProperty("seq", seq);
        settlement.addProperty("eventType", type);
    }

    public void acceptFrame(JsonElement value, long cursor) {
        JsonObject frame = object(value);
        long incoming = integer(frame.get("revision"), 0);
        if (incoming != revision + 1) fail();
        revision = incoming;
        String attemptId = id(frame, "attemptId");
        String type = id(frame, "type");
        if ("start".equals(type)) {
            if (active != null || integer(frame.get("startedAfterSeq"), -1) > cursor) fail();
            integer(frame.get("turn"), 0);
            integer(frame.get("step"), 0);
            active = frame.deepCopy();
            active.add("chunks", new JsonArray());
            nextIndex = 0;
            settlement = null;
            return;
        }
        if (!"chunk".equals(type) && !"end".equals(type)) fail();
        if (active == null || !attemptId.equals(id(active, "attemptId"))) return;
        if (integer(frame.get("index"), 0) != nextIndex) fail();
        if ("chunk".equals(type)) {
            if (settlement != null) fail();
            active.getAsJsonArray("chunks").add(timed(frame.get("time"), frame.get("chunk")));
            nextIndex++;
            return;
        }
        JsonObject outcome = object(frame.get("outcome"));
        String kind = id(outcome, "kind");
        if ("committed".equals(kind)) {
            if (settlement == null
                    || !settlement.get("seq").equals(outcome.get("seq"))
                    || !settlement.get("eventType").equals(outcome.get("eventType"))) fail();
        } else if (!"abandoned".equals(kind) || settlement != null) fail();
        active = null;
        settlement = null;
    }

    /** Expand compact runs without losing delta boundaries or per-chunk times. */
    public static JsonArray expand(JsonElement value) {
        if (value == null || !value.isJsonArray()) fail();
        JsonArray result = new JsonArray();
        for (JsonElement candidate : value.getAsJsonArray()) {
            JsonObject row = object(candidate);
            String type = id(row, "type");
            if ("chunk".equals(type)) {
                if (!row.keySet().equals(Set.of("type", "time", "chunk"))) fail();
                result.add(timed(row.get("time"), row.get("chunk")));
                continue;
            }
            boolean tool = "tool-call-chunks".equals(type);
            if (!tool && !"text-chunks".equals(type) && !"reasoning-chunks".equals(type)) fail();
            Set<String> expected =
                    tool
                            ? (row.has("name")
                                    ? Set.of("type", "time0", "index", "dt", "id", "args", "name")
                                    : Set.of("type", "time0", "index", "dt", "id", "args"))
                            : Set.of("type", "time0", "index", "dt", "texts");
            if (!row.keySet().equals(expected)) fail();
            long time = integer(row.get("time0"), -9007199254740991L);
            long index = integer(row.get("index"), 0);
            JsonElement members = row.get(tool ? "args" : "texts");
            JsonElement deltas = row.get("dt");
            if (members == null
                    || !members.isJsonArray()
                    || members.getAsJsonArray().isEmpty()
                    || deltas == null
                    || !deltas.isJsonArray()
                    || deltas.getAsJsonArray().size() != members.getAsJsonArray().size() - 1)
                fail();
            for (int i = 0; i < members.getAsJsonArray().size(); i++) {
                JsonElement member = members.getAsJsonArray().get(i);
                if (!member.isJsonPrimitive() || !member.getAsJsonPrimitive().isString()) fail();
                if (i > 0) time += integer(deltas.getAsJsonArray().get(i - 1), -9007199254740991L);
                JsonObject chunk = new JsonObject();
                chunk.addProperty(
                        "type",
                        tool
                                ? "tool-call-delta"
                                : "text-chunks".equals(type) ? "text-delta" : "reasoning-delta");
                chunk.addProperty("index", index);
                if (tool) {
                    chunk.addProperty("id", id(row, "id"));
                    if (row.has("name")) chunk.addProperty("name", id(row, "name"));
                }
                chunk.add(tool ? "argumentsDelta" : "text", member.deepCopy());
                result.add(timed(new com.google.gson.JsonPrimitive(time), chunk));
            }
        }
        return result;
    }

    private static JsonObject timed(JsonElement time, JsonElement value) {
        integer(time, -9007199254740991L);
        JsonObject chunk = object(value);
        id(chunk, "type");
        if (!DshRemoteContracts.isRemoteJson(chunk)) fail();
        JsonObject result = new JsonObject();
        result.add("time", time.deepCopy());
        result.add("chunk", chunk.deepCopy());
        return result;
    }

    static long integer(JsonElement value, long minimum) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            fail();
        double number = value.getAsDouble();
        if (!Double.isFinite(number)
                || Math.rint(number) != number
                || Math.abs(number) > 9007199254740991L
                || number < minimum) fail();
        return (long) number;
    }

    private static String id(JsonObject object, String key) {
        String value = DshJson.string(object, key);
        if (value == null || value.isEmpty() || !object.getAsJsonPrimitive(key).isString()) fail();
        return value;
    }

    private static JsonObject object(JsonElement value) {
        if (value == null || !value.isJsonObject()) fail();
        return value.getAsJsonObject();
    }

    private static void fail() {
        throw new IllegalArgumentException(
                "Remote Assistant stream is malformed or out of sequence");
    }
}
