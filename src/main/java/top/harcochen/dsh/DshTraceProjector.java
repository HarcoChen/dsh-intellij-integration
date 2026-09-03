package top.harcochen.dsh;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Projects raw Harness history into the bounded ledger model used by dsh-ide's Trace view. */
final class DshTraceProjector {
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "api[-_]?key|authorization|cookie|credential|password|secret|access[-_]?token|refresh[-_]?token|bearer[-_]?token|auth[-_]?token",
            Pattern.CASE_INSENSITIVE);
    private static final int INLINE_LIMIT = 240;
    private static final int PREVIEW_LIMIT = 1_200;
    private static final int RAW_STRING_LIMIT = 8_192;
    private static final int MAX_ARRAY_ITEMS = 200;
    private static final int MAX_OBJECT_KEYS = 200;
    private static final int MAX_DEPTH = 10;

    private DshTraceProjector() {
    }

    static Projection project(JsonObject history) {
        List<Entry> entries = entries(history);
        Map<String, Entry> calls = new LinkedHashMap<>();
        Map<String, List<Entry>> results = new LinkedHashMap<>();
        Map<String, Entry> stepStarts = new HashMap<>();
        Map<String, Entry> stepEnds = new HashMap<>();
        Map<String, Entry> turnEnds = new HashMap<>();
        Map<String, Entry> compactionEnds = new HashMap<>();
        Map<String, Entry> subStarts = new LinkedHashMap<>();
        Map<String, Entry> subSettles = new LinkedHashMap<>();
        Map<String, ChunkGroup> chunks = new LinkedHashMap<>();
        Set<String> assistantSteps = new HashSet<>();

        for (Entry entry : entries) {
            JsonObject data = data(entry);
            String location = stepKey(data);
            switch (entry.type()) {
                case "tool/call" -> {
                    String callId = string(data, "callId");
                    if (callId != null) calls.putIfAbsent(callId, entry);
                }
                case "tool/result" -> {
                    String callId = toolResultCallId(entry);
                    if (callId != null) results.computeIfAbsent(callId, ignored -> new ArrayList<>()).add(entry);
                }
                case "step/start" -> {
                    if (location != null) stepStarts.putIfAbsent(location, entry);
                }
                case "step/end" -> {
                    if (location != null) stepEnds.putIfAbsent(location, entry);
                }
                case "turn/end" -> {
                    Long turn = integer(data, "turn");
                    if (turn != null) turnEnds.putIfAbsent(Long.toString(turn), entry);
                }
                case "compaction/end" -> {
                    String id = string(data, "compactionId");
                    if (id != null) compactionEnds.putIfAbsent(id, entry);
                }
                case "assistant/chunk" -> addChunk(chunks, entry);
                case "tool/code-dispatch-start" -> {
                    String callId = string(data, "subCallId");
                    if (callId != null) subStarts.putIfAbsent(callId, entry);
                }
                case "tool/code-dispatch" -> {
                    String callId = string(data, "subCallId");
                    if (callId != null) subSettles.putIfAbsent(callId, entry);
                }
                case "assistant/message" -> {
                    if (location != null) assistantSteps.add(location);
                }
                default -> {
                }
            }
        }

        List<Row> rows = new ArrayList<>();
        Map<Long, String> seqToRowId = new HashMap<>();
        Set<String> emittedTools = new HashSet<>();
        for (Entry entry : entries) {
            if ("assistant/chunk".equals(entry.type())) continue;
            if ("tool/code-dispatch".equals(entry.type())) {
                String callId = string(data(entry), "subCallId");
                if (callId != null && subStarts.containsKey(callId)) continue;
                if (callId != null) {
                    Row row = subtoolRow(null, entry, calls, subtoolDepth(callId, subStarts, subSettles));
                    addRow(rows, seqToRowId, row, List.of(entry));
                    continue;
                }
            }
            if ("tool/code-dispatch-start".equals(entry.type())) {
                String callId = string(data(entry), "subCallId");
                if (callId != null) {
                    Entry settle = subSettles.get(callId);
                    Row row = subtoolRow(entry, settle, calls, subtoolDepth(callId, subStarts, subSettles));
                    addRow(rows, seqToRowId, row, settle == null ? List.of(entry) : List.of(entry, settle));
                    continue;
                }
            }
            if ("tool/result".equals(entry.type())) {
                String callId = toolResultCallId(entry);
                if (callId != null && calls.containsKey(callId)) continue;
                if (callId != null && emittedTools.add(callId)) {
                    List<Entry> grouped = results.getOrDefault(callId, List.of(entry));
                    Row row = toolRow(null, grouped);
                    addRow(rows, seqToRowId, row, grouped);
                    continue;
                }
            }
            if ("tool/call".equals(entry.type())) {
                String callId = string(data(entry), "callId");
                if (callId != null) {
                    emittedTools.add(callId);
                    List<Entry> sources = new ArrayList<>();
                    sources.add(entry);
                    sources.addAll(results.getOrDefault(callId, List.of()));
                    addRow(rows, seqToRowId, toolRow(entry, results.getOrDefault(callId, List.of())), sources);
                    continue;
                }
            }
            if ("assistant/message".equals(entry.type())) {
                String key = stepKey(data(entry));
                ChunkGroup group = key == null ? null : chunks.get(key);
                addRow(rows, seqToRowId, assistantRow(entry, group, key == null ? null : stepStarts.get(key)),
                        group == null ? List.of(entry) : joined(entry, group.entries));
                continue;
            }
            addRow(rows, seqToRowId, genericRow(entry, turnEnds, stepEnds, compactionEnds), List.of(entry));
        }

        for (Map.Entry<String, ChunkGroup> item : chunks.entrySet()) {
            if (assistantSteps.contains(item.getKey())) continue;
            Row row = streamingAssistantRow(item.getKey(), item.getValue(), stepStarts.get(item.getKey()));
            addRow(rows, seqToRowId, row, item.getValue().entries);
        }
        rows.sort(Comparator.comparingLong((Row row) -> longValue(row.view, "seq", Long.MAX_VALUE))
                .thenComparing(row -> stringOr(row.view, "id", "")));
        return new Projection(rows, projectionItems(history), seqToRowId, entries.size());
    }

    /**
     * Refresh only the independently changing projection cells while retaining
     * the event-derived ledger rows and sequence index.
     */
    static Projection withProjectionItems(Projection current, JsonObject history) {
        return new Projection(current.rows(), projectionItems(history), current.seqToRowId(), current.totalEvents());
    }

    private static List<Entry> entries(JsonObject history) {
        JsonArray source = array(history, "events");
        List<Entry> result = new ArrayList<>();
        if (source == null) return result;
        for (JsonElement candidate : source) {
            if (!candidate.isJsonObject()) continue;
            JsonObject wrapper = candidate.getAsJsonObject();
            JsonObject event = object(wrapper, "event");
            if (event == null) event = wrapper;
            String type = string(event, "type");
            Long seq = integer(event, "seq");
            if (type == null || seq == null || seq < 0) continue;
            result.add(new Entry(wrapper, event));
        }
        result.sort(Comparator.comparingLong(Entry::seq));
        return result;
    }

    private static Row genericRow(Entry entry, Map<String, Entry> turnEnds, Map<String, Entry> stepEnds,
                                  Map<String, Entry> compactionEnds) {
        JsonObject data = data(entry);
        Long turn = integer(data, "turn");
        Long step = integer(data, "step");
        String type = entry.type();
        String category = "generic";
        String summary = inlineJson(data, INLINE_LIMIT);
        Long duration = null;
        String error = null;
        JsonObject tokens = null;
        List<Field> extra = new ArrayList<>();

        switch (type) {
            case "turn/start" -> {
                category = "boundary";
                summary = "Turn " + valueOrQuestion(turn) + " started";
                duration = duration(entry, turn == null ? null : turnEnds.get(Long.toString(turn)));
            }
            case "turn/end" -> {
                category = "boundary";
                JsonObject reason = object(data, "reason");
                String kind = stringOr(reason, "kind", "unknown");
                summary = "Turn " + valueOrQuestion(turn) + " ended · " + kind;
                if ("error".equals(kind)) error = errorMessage(reason == null ? null : reason.get("error"));
                else if ("blocked".equals(kind)) error = "Turn blocked";
            }
            case "step/start" -> {
                category = "boundary";
                summary = "Step " + valueOrQuestion(turn) + "." + valueOrQuestion(step) + " started";
                duration = duration(entry, stepKey(data) == null ? null : stepEnds.get(stepKey(data)));
            }
            case "step/end" -> {
                category = "boundary";
                summary = "Step " + valueOrQuestion(turn) + "." + valueOrQuestion(step) + " ended";
            }
            case "user/message" -> {
                JsonObject message = object(data, "message");
                if (message == null) message = data;
                JsonObject source = object(message, "source");
                String sourceKind = string(source, "kind");
                String text = contentText(message.get("content"), 6_000);
                JsonObject surfaceOp = object(entry.event, "surfaceOp");
                category = "replace".equals(string(surfaceOp, "op")) ? "compaction"
                        : "user".equals(sourceKind) || sourceKind == null ? "user" : "context";
                summary = oneLine(text.isBlank() ? "[" + stringOr(source, "kind", "user") + "]" : text, 500);
                if (sourceKind != null) extra.add(new Field("Source", sourceKind));
            }
            case "request/header" -> {
                category = "system";
                JsonObject header = object(data, "header");
                JsonObject config = object(header, "config");
                String provider = string(config, "provider");
                String model = string(config, "model");
                JsonArray tools = array(header, "tools");
                summary = "Request header" + route(provider, model) + " · " + (tools == null ? 0 : tools.size()) + " tools";
                if (provider != null) extra.add(new Field("Provider", provider));
                if (model != null) extra.add(new Field("Model", model));
            }
            case "request/context" -> {
                category = "system";
                String provider = string(data, "provider");
                String model = string(data, "model");
                summary = "Request context" + route(provider, model);
            }
            case "assistant/message" -> {
                category = "assistant";
                summary = oneLine(contentText(messageContent(data), 8_000), 500);
                if (summary.isBlank()) summary = "Assistant message";
                tokens = tokenUsage(data.get("usage"));
            }
            case "llm/retry" -> {
                category = "error";
                error = errorMessage(data.get("failure"));
                if (error == null) error = "LLM retry";
                summary = "Retry " + stringOr(data, "retry", "?") + " · " + error;
            }
            default -> {
                if (type.startsWith("compaction/")) {
                    category = "compaction";
                    if ("compaction/start".equals(type)) {
                        String id = string(data, "compactionId");
                        duration = duration(entry, id == null ? null : compactionEnds.get(id));
                        summary = "Compaction started" + (id == null ? "" : " · " + id);
                    } else if ("compaction/summary".equals(type)) {
                        summary = "Compaction summary · " + oneLine(contentText(data.get("summary"), 2_000), 400);
                        tokens = tokenUsage(data.get("usage"));
                    } else if ("compaction/end".equals(type)) {
                        error = errorMessage(data.get("error"));
                        summary = error == null ? "Compaction completed" : "Compaction failed · " + error;
                    }
                } else if (type.startsWith("tool/code-dispatch")) {
                    category = "subtool";
                    summary = subtoolSummary(data, type);
                }
            }
        }
        if (summary == null || summary.isBlank()) summary = type;
        JsonObject view = baseView("event:" + entry.seq(), entry.seq(), type, category, summary, entry.time(), turn, step);
        if (duration != null) view.addProperty("durationMs", duration);
        if (error != null) view.addProperty("error", error);
        if (tokens != null) view.add("tokens", tokens);
        return row(view, raw(entry), List.of(type, summary, safeJson(data, 8_000)), extra);
    }

    private static Row assistantRow(Entry entry, ChunkGroup chunks, Entry stepStart) {
        JsonObject data = data(entry);
        JsonObject message = object(data, "message");
        if (message == null) message = data;
        Long turn = integer(data, "turn");
        Long step = integer(data, "step");
        String finalText = contentText(message.get("content"), 10_000);
        String streamed = chunks == null ? "" : String.join("\n", chunks.reasoning, chunks.text).trim();
        String summary = oneLine(!finalText.isBlank() ? finalText : (!streamed.isBlank() ? streamed : "Assistant message"), 500);
        JsonObject view = baseView("assistant:" + valueOrQuestion(turn) + ":" + valueOrQuestion(step) + ":" + entry.seq(),
                entry.seq(), "assistant/message", "assistant", summary, entry.time(), turn, step);
        Long duration = duration(stepStart, entry);
        if (duration != null) view.addProperty("durationMs", duration);
        JsonObject usage = tokenUsage(data.get("usage"));
        if (usage == null && chunks != null) usage = chunks.usage;
        if (usage != null) view.add("tokens", usage);
        List<Field> extra = new ArrayList<>();
        JsonObject source = object(message, "source");
        String provider = string(source, "provider");
        String model = string(source, "model");
        if (provider != null) extra.add(new Field("Provider", provider));
        if (model != null) extra.add(new Field("Model", model));
        if (chunks != null) {
            extra.add(new Field("Stream events", Integer.toString(chunks.entries.size())));
            if (stepStart != null && chunks.firstTokenTime != null && chunks.firstTokenTime >= stepStart.time()) {
                extra.add(new Field("TTFT", (chunks.firstTokenTime - stepStart.time()) + " ms"));
            }
        }
        JsonObject raw = raw(entry);
        if (chunks != null) {
            JsonObject stream = new JsonObject();
            stream.addProperty("eventCount", chunks.entries.size());
            stream.addProperty("firstSeq", chunks.entries.get(0).seq());
            stream.addProperty("lastSeq", chunks.entries.get(chunks.entries.size() - 1).seq());
            raw.add("stream", stream);
        }
        return row(view, raw, List.of(finalText, streamed, safeJson(data, 8_000)), extra);
    }

    private static Row streamingAssistantRow(String key, ChunkGroup chunks, Entry stepStart) {
        if (chunks.entries.isEmpty()) return null;
        Entry first = chunks.entries.get(0);
        Entry last = chunks.entries.get(chunks.entries.size() - 1);
        JsonObject data = data(first);
        Long turn = integer(data, "turn");
        Long step = integer(data, "step");
        String summary = oneLine(!chunks.text.isBlank() ? chunks.text
                : (!chunks.reasoning.isBlank() ? chunks.reasoning : chunks.entries.size() + " stream chunks"), 500);
        JsonObject view = baseView("assistant-stream:" + key, first.seq(), "assistant/chunk", "assistant",
                summary, first.time(), turn, step);
        view.addProperty("endSeq", last.seq());
        if (chunks.usage != null) view.add("tokens", chunks.usage);
        JsonObject raw = new JsonObject();
        JsonArray events = new JsonArray();
        for (Entry entry : chunks.entries) events.add(entry.event.deepCopy());
        raw.add("events", events);
        List<Field> extra = new ArrayList<>();
        extra.add(new Field("Stream events", Integer.toString(chunks.entries.size())));
        if (stepStart != null && chunks.firstTokenTime != null && chunks.firstTokenTime >= stepStart.time()) {
            extra.add(new Field("TTFT", (chunks.firstTokenTime - stepStart.time()) + " ms"));
        }
        return row(view, raw, List.of(chunks.text, chunks.reasoning), extra);
    }

    private static Row toolRow(Entry call, List<Entry> results) {
        Entry result = results.isEmpty() ? null : results.get(0);
        Entry anchor = call == null ? result : call;
        if (anchor == null) return null;
        JsonObject callData = call == null ? null : data(call);
        JsonObject resultData = result == null ? null : data(result);
        String callId = callData == null ? null : string(callData, "callId");
        if (callId == null && result != null) callId = toolResultCallId(result);
        if (callId == null) callId = "call-" + anchor.seq();
        String name = stringOr(callData, "name", "unknown tool");
        String args = string(callData, "arguments");
        String resultText = result == null ? "" : toolResultText(result);
        String error = result == null ? null : toolResultError(result);
        JsonObject callView = presentedView(call, "call");
        JsonObject resultView = presentedView(result, "result");
        String title = string(callView, "title");
        if (title == null) title = name;
        String presentation = presentationSummary(callView);
        String resultPresentation = presentationSummary(resultView);
        if (resultPresentation != null && !resultPresentation.isBlank()) resultText = resultPresentation;
        Long turn = integer(callData != null ? callData : resultData, "turn");
        Long step = integer(callData != null ? callData : resultData, "step");
        String summary = oneLine(title + (resultText.isBlank() ? "" : " · " + resultText), 500);
        JsonObject view = baseView("tool:" + callId, anchor.seq(),
                result == null ? "tool/call" : "tool/call → tool/result", "tool", summary,
                anchor.time(), turn, step);
        view.addProperty("callId", callId);
        if (result != null) view.addProperty("endSeq", result.seq());
        Long duration = duration(call, result);
        if (duration != null) view.addProperty("durationMs", duration);
        if (error != null) view.addProperty("error", error);
        JsonObject tool = new JsonObject();
        tool.addProperty("name", name);
        if (args != null) tool.addProperty("args", oneLine(args, 800));
        if (!resultText.isBlank()) tool.addProperty("result", oneLine(resultText, 800));
        if (presentation != null) tool.addProperty("presentation", presentation);
        view.add("tool", tool);
        JsonObject raw = new JsonObject();
        if (call != null) raw.add("call", call.event.deepCopy());
        JsonArray resultArray = new JsonArray();
        for (Entry item : results) resultArray.add(raw(item));
        raw.add("results", resultArray);
        return row(view, raw, List.of(name, args == null ? "" : args, resultText, error == null ? "" : error), List.of());
    }

    private static Row subtoolRow(Entry start, Entry settle, Map<String, Entry> rootCalls, int depth) {
        Entry anchor = start == null ? settle : start;
        if (anchor == null) return null;
        JsonObject startData = start == null ? null : data(start);
        JsonObject settleData = settle == null ? null : data(settle);
        JsonObject data = startData == null ? settleData : startData;
        String callId = string(data, "subCallId");
        if (callId == null) return null;
        String parentCallId = string(data, "parentCallId");
        String rootCallId = string(data, "rootCallId");
        String name = stringOr(data, "name", "subtool");
        String args = data.has("arguments") ? inlineJson(data.get("arguments"), 800) : null;
        String result = settleData == null ? "" : contentText(settleData.get("content"), 6_000);
        String error = settleData != null && bool(settleData, "isError")
                ? (result.isBlank() ? "Subtool failed" : oneLine(result, 500)) : null;
        Long turn = integer(data, "turn");
        Long step = integer(data, "step");
        if ((turn == null || step == null) && rootCallId != null && rootCalls.containsKey(rootCallId)) {
            JsonObject rootData = data(rootCalls.get(rootCallId));
            if (turn == null) turn = integer(rootData, "turn");
            if (step == null) step = integer(rootData, "step");
        }
        String summary = oneLine(name + (result.isBlank() ? "" : " · " + result), 500);
        JsonObject view = baseView("subtool:" + callId, anchor.seq(),
                settle == null ? "tool/code-dispatch-start" : "tool/code-dispatch-start → tool/code-dispatch",
                "subtool", summary, anchor.time(), turn, step);
        view.addProperty("depth", Math.max(1, Math.min(depth, 256)));
        view.addProperty("callId", callId);
        if (parentCallId != null) view.addProperty("parentCallId", parentCallId);
        if (settle != null) view.addProperty("endSeq", settle.seq());
        Long duration = duration(start, settle);
        if (duration != null) view.addProperty("durationMs", duration);
        if (error != null) view.addProperty("error", error);
        JsonObject tool = new JsonObject();
        tool.addProperty("name", name);
        if (args != null) tool.addProperty("args", args);
        if (!result.isBlank()) tool.addProperty("result", oneLine(result, 800));
        view.add("tool", tool);
        JsonObject raw = new JsonObject();
        if (start != null) raw.add("start", start.event.deepCopy());
        if (settle != null) raw.add("result", settle.event.deepCopy());
        return row(view, raw, List.of(name, args == null ? "" : args, result, error == null ? "" : error), List.of());
    }

    private static int subtoolDepth(String callId, Map<String, Entry> starts, Map<String, Entry> settles) {
        Set<String> visited = new HashSet<>();
        String cursor = callId;
        int depth = 0;
        while (cursor != null && visited.add(cursor) && depth < 256) {
            Entry source = starts.containsKey(cursor) ? starts.get(cursor) : settles.get(cursor);
            String parent = source == null ? null : string(data(source), "parentCallId");
            depth++;
            cursor = parent != null && (starts.containsKey(parent) || settles.containsKey(parent)) ? parent : null;
        }
        return Math.max(1, depth);
    }

    private static List<ProjectionItem> projectionItems(JsonObject history) {
        JsonObject block = object(history, "projections");
        JsonObject values = object(block, "values");
        long seq = longValue(block, "asOfSeq", 0);
        List<ProjectionItem> result = new ArrayList<>();
        if (values == null) return result;
        for (Map.Entry<String, JsonElement> item : values.entrySet()) {
            String preview = oneLine(safeJson(item.getValue(), PREVIEW_LIMIT), PREVIEW_LIMIT);
            List<Field> fields = List.of(new Field("Projection", item.getKey()), new Field("Watermark seq", Long.toString(seq)));
            result.add(new ProjectionItem("projection:" + item.getKey(), item.getKey(), seq, preview,
                    (item.getKey() + "\n" + safeJson(item.getValue(), 8_000)).toLowerCase(Locale.ROOT),
                    item.getValue().deepCopy(), fields));
        }
        result.sort(Comparator.comparing(ProjectionItem::key));
        return result;
    }

    private static void addChunk(Map<String, ChunkGroup> groups, Entry entry) {
        JsonObject data = data(entry);
        String key = stepKey(data);
        JsonObject chunk = object(data, "chunk");
        if (key == null || chunk == null) return;
        ChunkGroup group = groups.computeIfAbsent(key, ignored -> new ChunkGroup());
        group.entries.add(entry);
        String type = string(chunk, "type");
        String text = string(chunk, "text");
        if ("text-delta".equals(type) && text != null) group.text = truncate(group.text + text, 16_000);
        else if ("reasoning-delta".equals(type) && text != null) group.reasoning = truncate(group.reasoning + text, 16_000);
        else if ("tool-call-delta".equals(type)) {
            String delta = string(chunk, "argumentsDelta");
            if (delta != null) group.text = truncate(group.text + delta, 16_000);
        } else if ("usage".equals(type)) group.usage = addUsage(group.usage, tokenUsage(chunk.get("usage")));
        if (group.firstTokenTime == null && ((text != null && !text.isEmpty()) || string(chunk, "argumentsDelta") != null)) {
            group.firstTokenTime = entry.time();
        }
    }

    private static Row row(JsonObject view, JsonElement raw, List<String> searchParts, List<Field> extra) {
        List<Field> fields = new ArrayList<>();
        fields.add(new Field("Event", stringOr(view, "eventType", "")));
        String sequence = Long.toString(longValue(view, "seq", 0));
        if (view.has("endSeq")) sequence += " → " + longValue(view, "endSeq", 0);
        fields.add(new Field("Sequence", sequence));
        fields.add(new Field("Time", Instant.ofEpochMilli(longValue(view, "time", 0)).toString()));
        if (view.has("durationMs")) fields.add(new Field("Duration", longValue(view, "durationMs", 0) + " ms"));
        if (view.has("turn")) fields.add(new Field("Turn", Long.toString(longValue(view, "turn", 0))));
        if (view.has("step")) fields.add(new Field("Step", Long.toString(longValue(view, "step", 0))));
        if (view.has("callId")) fields.add(new Field("Call ID", stringOr(view, "callId", "")));
        if (view.has("error")) fields.add(new Field("Error", stringOr(view, "error", "")));
        fields.addAll(extra);
        StringBuilder search = new StringBuilder();
        for (String part : searchParts) if (part != null) search.append(part).append('\n');
        return new Row(view, search.toString().toLowerCase(Locale.ROOT), raw, fields);
    }

    private static JsonObject baseView(String id, long seq, String type, String category, String summary,
                                       long time, Long turn, Long step) {
        JsonObject view = new JsonObject();
        view.addProperty("id", id);
        view.addProperty("seq", seq);
        view.addProperty("eventType", type);
        view.addProperty("category", category);
        view.addProperty("summary", summary);
        view.addProperty("time", time);
        if (turn != null) view.addProperty("turn", turn);
        if (step != null) view.addProperty("step", step);
        view.addProperty("depth", "subtool".equals(category) ? 1 : 0);
        view.addProperty("groupId", turn == null ? "session" : "turn:" + turn + (step == null ? "" : "/step:" + step));
        return view;
    }

    static String safeJson(JsonElement value, int maximum) {
        JsonElement sanitized = sanitize(value == null ? JsonNull.INSTANCE : value, 0);
        String text;
        try {
            text = PRETTY_GSON.toJson(sanitized);
        } catch (RuntimeException ignored) {
            text = "\"[unserializable]\"";
        }
        if (text.length() <= maximum) return text;
        return text.substring(0, Math.max(0, maximum - 30)) + "\n… [raw detail truncated]";
    }

    private static JsonElement sanitize(JsonElement value, int depth) {
        if (value == null || value.isJsonNull()) return JsonNull.INSTANCE;
        if (value.isJsonPrimitive()) {
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (primitive.isString()) return new JsonPrimitive(truncate(primitive.getAsString(), RAW_STRING_LIMIT));
            return primitive.deepCopy();
        }
        if (depth >= MAX_DEPTH) return new JsonPrimitive("[depth limit]");
        if (value.isJsonArray()) {
            JsonArray output = new JsonArray();
            JsonArray input = value.getAsJsonArray();
            int count = Math.min(input.size(), MAX_ARRAY_ITEMS);
            for (int index = 0; index < count; index++) output.add(sanitize(input.get(index), depth + 1));
            if (input.size() > count) output.add("[" + (input.size() - count) + " more items]");
            return output;
        }
        JsonObject output = new JsonObject();
        int count = 0;
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
            if (count++ >= MAX_OBJECT_KEYS) break;
            output.add(entry.getKey(), SENSITIVE_KEY.matcher(entry.getKey()).find()
                    ? new JsonPrimitive("[redacted]") : sanitize(entry.getValue(), depth + 1));
        }
        if (value.getAsJsonObject().size() > MAX_OBJECT_KEYS) {
            output.addProperty("[truncated]", (value.getAsJsonObject().size() - MAX_OBJECT_KEYS) + " more keys");
        }
        return output;
    }

    private static String contentText(JsonElement value, int limit) {
        if (value == null || value.isJsonNull()) return "";
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) return truncate(value.getAsString(), limit);
        if (!value.isJsonArray()) return "";
        StringBuilder output = new StringBuilder();
        for (JsonElement candidate : value.getAsJsonArray()) {
            if (!candidate.isJsonObject() || output.length() >= limit) continue;
            JsonObject part = candidate.getAsJsonObject();
            String type = string(part, "type");
            String text = null;
            if (("text".equals(type) || "reasoning".equals(type))) text = string(part, "text");
            else if ("tool-call".equals(type)) text = stringOr(part, "name", "tool") + "(" + stringOr(part, "arguments", "") + ")";
            else if ("tool-result".equals(type)) text = contentText(part.get("content"), limit - output.length());
            else if ("image".equals(type)) text = "[image]";
            else if (type != null) text = "[" + type + "]";
            if (text != null && !text.isBlank()) {
                if (!output.isEmpty()) output.append('\n');
                output.append(truncate(text, limit - output.length()));
            }
        }
        return output.toString();
    }

    private static JsonElement messageContent(JsonObject data) {
        JsonObject message = object(data, "message");
        return message != null && message.has("content") ? message.get("content") : data.get("content");
    }

    private static String toolResultCallId(Entry entry) {
        JsonObject data = data(entry);
        JsonObject message = object(data, "message");
        JsonObject source = object(message, "source");
        String callId = string(source, "callId");
        if (callId != null) return callId;
        JsonArray content = array(message, "content");
        if (content != null && !content.isEmpty() && content.get(0).isJsonObject()) {
            callId = string(content.get(0).getAsJsonObject(), "toolCallId");
            if (callId != null) return callId;
        }
        return string(data, "callId");
    }

    private static String toolResultText(Entry entry) {
        JsonObject data = data(entry);
        JsonObject message = object(data, "message");
        JsonArray content = array(message, "content");
        if (content != null && !content.isEmpty() && content.get(0).isJsonObject()) {
            String text = contentText(content.get(0).getAsJsonObject().get("content"), 6_000);
            if (!text.isBlank()) return text;
        }
        String text = contentText(data.get("content"), 6_000);
        return text.isBlank() && message != null ? contentText(message.get("content"), 6_000) : text;
    }

    private static String toolResultError(Entry entry) {
        JsonObject data = data(entry);
        String error = errorMessage(data.get("error"));
        if (error != null) return error;
        JsonObject message = object(data, "message");
        JsonArray content = array(message, "content");
        if (content != null && !content.isEmpty() && content.get(0).isJsonObject()
                && bool(content.get(0).getAsJsonObject(), "isError")) return oneLine(toolResultText(entry), 500);
        return bool(data, "isError") ? oneLine(toolResultText(entry), 500) : null;
    }

    private static JsonObject presentedView(Entry entry, String target) {
        if (entry == null) return null;
        JsonObject outer = object(entry.wrapper, "view");
        return outer != null && target.equals(string(outer, "for")) ? object(outer, "view") : null;
    }

    private static String presentationSummary(JsonObject view) {
        if (view == null) return null;
        List<String> pieces = new ArrayList<>();
        for (String key : List.of("title", "description", "cwd", "output", "path", "url", "answer")) {
            String value = string(view, key);
            if (value != null && !value.isBlank()) pieces.add(value);
        }
        String content = contentText(view.get("content"), 1_000);
        if (!content.isBlank()) pieces.add(content);
        return pieces.isEmpty() ? null : oneLine(String.join(" · ", pieces), 600);
    }

    private static String subtoolSummary(JsonObject data, String type) {
        String name = stringOr(data, "name", "subtool");
        String content = contentText(data.get("content"), 2_000);
        return oneLine(name + (content.isBlank() ? " · " + type : " · " + content), 500);
    }

    private static JsonObject tokenUsage(JsonElement value) {
        if (value == null || !value.isJsonObject()) return null;
        JsonObject source = value.getAsJsonObject();
        Long input = integer(source, "inputTokens");
        Long output = integer(source, "outputTokens");
        if (input == null || output == null || input < 0 || output < 0) return null;
        JsonObject result = new JsonObject();
        result.addProperty("inputTokens", input);
        result.addProperty("outputTokens", output);
        for (String key : List.of("cacheReadTokens", "cacheWriteTokens", "reasoningTokens")) {
            Long optional = integer(source, key);
            if (optional != null && optional >= 0) result.addProperty(key, optional);
        }
        return result;
    }

    private static JsonObject addUsage(JsonObject previous, JsonObject next) {
        if (next == null) return previous;
        if (previous == null) return next.deepCopy();
        JsonObject result = new JsonObject();
        for (String key : List.of("inputTokens", "outputTokens", "cacheReadTokens", "cacheWriteTokens", "reasoningTokens")) {
            if (previous.has(key) || next.has(key)) result.addProperty(key,
                    longValue(previous, key, 0) + longValue(next, key, 0));
        }
        return result;
    }

    private static String errorMessage(JsonElement value) {
        if (value == null || value.isJsonNull()) return null;
        if (value.isJsonPrimitive()) return oneLine(value.getAsString(), 500);
        if (!value.isJsonObject()) return null;
        JsonObject object = value.getAsJsonObject();
        String message = string(object, "message");
        String code = string(object, "code");
        if (message != null) return oneLine((code == null ? "" : "[" + code + "] ") + message, 500);
        return code != null ? code : string(object, "name");
    }

    private static String inlineJson(JsonElement value, int limit) {
        return oneLine(safeJson(value == null ? JsonNull.INSTANCE : value, limit * 3), limit);
    }

    private static JsonObject raw(Entry entry) {
        JsonObject raw = new JsonObject();
        raw.add("event", entry.event.deepCopy());
        if (entry.wrapper.has("view")) raw.add("view", entry.wrapper.get("view").deepCopy());
        return raw;
    }

    private static List<Entry> joined(Entry first, List<Entry> rest) {
        List<Entry> result = new ArrayList<>();
        result.add(first);
        result.addAll(rest);
        return result;
    }

    private static void addRow(List<Row> rows, Map<Long, String> map, Row row, List<Entry> sources) {
        if (row == null) return;
        rows.add(row);
        String id = stringOr(row.view, "id", "");
        for (Entry source : sources) map.put(source.seq(), id);
    }

    private static JsonObject data(Entry entry) {
        JsonObject data = object(entry.event, "data");
        return data == null ? new JsonObject() : data;
    }

    private static Long duration(Entry start, Entry end) {
        if (start == null || end == null || end.time() < start.time()) return null;
        return end.time() - start.time();
    }

    private static String stepKey(JsonObject data) {
        Long turn = integer(data, "turn");
        Long step = integer(data, "step");
        return turn == null || step == null ? null : turn + ":" + step;
    }

    private static String route(String provider, String model) {
        if (provider == null && model == null) return " · unknown route";
        return " · " + (provider == null ? "" : provider) + (provider != null && model != null ? "/" : "") + (model == null ? "" : model);
    }

    private static String valueOrQuestion(Long value) {
        return value == null ? "?" : Long.toString(value);
    }

    private static String oneLine(String value, int limit) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return truncate(normalized, limit);
    }

    private static String truncate(String value, int limit) {
        if (value == null || value.length() <= limit) return value == null ? "" : value;
        return value.substring(0, Math.max(0, limit - 1)) + "…";
    }

    private static JsonObject object(JsonObject parent, String key) {
        return parent != null && parent.has(key) && parent.get(key).isJsonObject() ? parent.getAsJsonObject(key) : null;
    }

    private static JsonArray array(JsonObject parent, String key) {
        return parent != null && parent.has(key) && parent.get(key).isJsonArray() ? parent.getAsJsonArray(key) : null;
    }

    private static String string(JsonObject parent, String key) {
        try {
            return parent != null && parent.has(key) && parent.get(key).isJsonPrimitive() ? parent.get(key).getAsString() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String stringOr(JsonObject parent, String key, String fallback) {
        String value = string(parent, key);
        return value == null ? fallback : value;
    }

    private static Long integer(JsonObject parent, String key) {
        try {
            return parent != null && parent.has(key) && parent.get(key).isJsonPrimitive() ? parent.get(key).getAsLong() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static long longValue(JsonObject parent, String key, long fallback) {
        Long value = integer(parent, key);
        return value == null ? fallback : value;
    }

    private static boolean bool(JsonObject parent, String key) {
        try {
            return parent != null && parent.has(key) && parent.get(key).isJsonPrimitive() && parent.get(key).getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    record Field(String label, String value) {
    }

    record Row(JsonObject view, String searchText, JsonElement raw, List<Field> fields) {
    }

    record ProjectionItem(String id, String key, long seq, String valuePreview, String searchText,
                          JsonElement raw, List<Field> fields) {
    }

    record Projection(List<Row> rows, List<ProjectionItem> projections, Map<Long, String> seqToRowId,
                      int totalEvents) {
    }

    private record Entry(JsonObject wrapper, JsonObject event) {
        long seq() {
            return longValue(event, "seq", 0);
        }

        long time() {
            return longValue(event, "time", seq());
        }

        String type() {
            return stringOr(event, "type", "unknown");
        }
    }

    private static final class ChunkGroup {
        final List<Entry> entries = new ArrayList<>();
        String text = "";
        String reasoning = "";
        Long firstTokenTime;
        JsonObject usage;
    }
}
