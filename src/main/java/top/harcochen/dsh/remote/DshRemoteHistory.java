package top.harcochen.dsh.remote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import top.harcochen.dsh.DshJson;

/** Decodes V3 surface ranges and legacy compact records at the Remote boundary. */
public final class DshRemoteHistory {
    private DshRemoteHistory() {}

    public static JsonArray records(JsonArray records) {
        JsonArray result = new JsonArray();
        for (JsonElement candidate : records) {
            if (!candidate.isJsonObject()) throw invalid();
            JsonObject row = candidate.getAsJsonObject();
            String type = DshJson.string(row, "type");
            if ("chunks".equals(type)) {
                JsonElement eventValue = row.get("event");
                if (eventValue == null || !eventValue.isJsonObject()) throw invalid();
                JsonObject event = eventValue.getAsJsonObject();
                JsonElement dataValue = event.get("data");
                if (dataValue == null || !dataValue.isJsonObject()) throw invalid();
                JsonObject data = dataValue.getAsJsonObject();
                String tag = DshJson.string(event, "type");
                if (tag == null || !tag.startsWith("chunkrow/")) throw invalid();
                JsonObject run = data.deepCopy();
                run.remove("turn");
                run.remove("step");
                run.addProperty("type", tag.substring("chunkrow/".length()));
                run.add("time0", event.get("time"));
                JsonArray compact = new JsonArray();
                compact.add(run);
                long seq = DshAssistantStream.integer(event.get("seq"), 0);
                for (JsonElement expanded : DshAssistantStream.expand(compact)) {
                    JsonObject chunkEvent = new JsonObject();
                    chunkEvent.addProperty("type", "assistant/chunk");
                    chunkEvent.addProperty("seq", seq++);
                    chunkEvent.add("time", expanded.getAsJsonObject().get("time"));
                    JsonObject chunkData = new JsonObject();
                    chunkData.add("turn", data.get("turn"));
                    chunkData.add("step", data.get("step"));
                    chunkData.add("chunk", expanded.getAsJsonObject().get("chunk"));
                    chunkEvent.add("data", chunkData);
                    result.add(event(chunkEvent));
                }
            } else {
                result.add(event("event".equals(type) ? row.getAsJsonObject("event") : row));
            }
        }
        return result;
    }

    public static JsonObject event(JsonObject source) {
        if (source == null
                || DshJson.string(source, "type") == null
                || !source.has("data")
                || !DshRemoteContracts.isRemoteJson(source)) throw invalid();
        DshAssistantStream.integer(source.get("seq"), 0);
        DshAssistantStream.integer(source.get("time"), -9007199254740991L);
        JsonObject event = source.deepCopy();
        JsonElement surface = event.get("surfaceOp");
        if (surface != null && surface.isJsonObject()) {
            JsonObject range = surface.getAsJsonObject();
            long start =
                    DshAssistantStream.integer(
                            range.get(range.has("startSeq") ? "startSeq" : "start"), 0);
            long end =
                    DshAssistantStream.integer(
                            range.get(range.has("endSeq") ? "endSeq" : "end"), 0);
            if (!"replace".equals(DshJson.string(range, "op")) || start > end) throw invalid();
            range.remove("startSeq");
            range.remove("endSeq");
            range.addProperty("start", start);
            range.addProperty("end", end);
        }
        String type = DshJson.string(event, "type");
        if (("assistant/message".equals(type) || "assistant/attempt".equals(type))
                && event.get("data").isJsonObject()
                && event.getAsJsonObject("data").has("stream")) {
            DshAssistantStream.expand(event.getAsJsonObject("data").get("stream"));
        }
        return event;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Remote session history is malformed");
    }
}
