package top.harcochen.dsh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Converts Harness history events into the ChatViewState message rows. */
public final class DshMessageProjector {
    private static final String IDE_CONTEXT_MARKER = "\n\n<ide_context>\n";

    private DshMessageProjector() {
    }

    public static Projection project(JsonObject history, String agentStatusLabel) {
        JsonArray source = history != null && history.has("events") && history.get("events").isJsonArray()
                ? history.getAsJsonArray("events") : new JsonArray();
        List<JsonObject> events = new ArrayList<>();
        for (JsonElement candidate : source) {
            if (candidate.isJsonObject()) events.add(candidate.getAsJsonObject());
        }
        events.sort(Comparator.comparingLong(entry -> {
            JsonObject nested = object(entry, "event");
            return nested == null ? eventSeq(entry) : eventSeq(nested);
        }));

        TreeMap<Long, JsonObject> rows = new TreeMap<>();
        Map<String, Long> rowKeys = new LinkedHashMap<>();
        Map<String, Partial> partials = new LinkedHashMap<>();
        Map<String, Long> toolRows = new LinkedHashMap<>();
        int activeTurn = 0;
        String turnPhase = "completed";
        String turnDetail = null;

        for (JsonObject entry : events) {
            JsonObject event = object(entry, "event");
            if (event == null) event = entry;
            String type = string(event, "type");
            if (type == null) continue;
            long seq = eventSeq(event);
            long time = number(event, "time", seq);
            JsonObject data = object(event, "data");
            if (data == null) data = new JsonObject();

            if (type.equals("user/message")) {
                JsonObject message = object(data, "message");
                if (message == null) message = data;
                String text = visibleText(message.has("content") ? message.get("content") : message.get("text"));
                if (!text.isBlank()) {
                    JsonObject row = message("event:" + seq, "user", text, time, seq, "committed");
                    addImages(row, message.has("content") ? message.get("content") : null);
                    row.addProperty("renderedHtml", markdownHtml(text));
                    rows.put(seq, row);
                    rowKeys.put("event:" + seq, seq);
                }
                continue;
            }

            if (type.equals("assistant/chunk")) {
                int turn = integer(data, "turn", 0);
                int step = integer(data, "step", 0);
                String key = "partial:" + turn + ":" + step;
                Partial partial = partials.computeIfAbsent(key, ignored -> new Partial(turn, step));
                JsonObject chunk = object(data, "chunk");
                if (chunk != null) {
                    String chunkType = string(chunk, "type");
                    String chunkText = string(chunk, "text");
                    if (chunkText == null) {
                        JsonObject block = object(chunk, "block");
                        chunkText = block == null ? null : string(block, "text");
                        if (block != null && (chunkType == null || "block-end".equals(chunkType))) {
                            chunkType = string(block, "type");
                        }
                    }
                    if (chunkText != null) {
                        if ("reasoning-delta".equals(chunkType) || "reasoning".equals(chunkType)) partial.reasoning.append(chunkText);
                        else partial.text.append(chunkText);
                    }
                }
                removeRow(rows, rowKeys, key);
                JsonObject row = message(key, "assistant", partial.text.toString(), time, seq, "streaming");
                if (partial.reasoning.length() > 0) {
                    row.addProperty("reasoning", partial.reasoning.toString());
                    row.addProperty("reasoningState", "streaming");
                    row.addProperty("renderedReasoningHtml", markdownHtml(partial.reasoning.toString()));
                }
                row.addProperty("renderedHtml", markdownHtml(partial.text.toString()));
                rows.put(seq, row);
                rowKeys.put(key, seq);
                activeTurn = turn;
                turnPhase = "running";
                continue;
            }

            if (type.equals("assistant/message")) {
                JsonObject message = object(data, "message");
                if (message == null) message = data;
                int turn = integer(data, "turn", 0);
                int step = integer(data, "step", 0);
                String partialKey = "partial:" + turn + ":" + step;
                removeRow(rows, rowKeys, partialKey);
                Partial partial = partials.remove(partialKey);
                String text = visibleText(message.has("content") ? message.get("content") : message.get("text"));
                String reasoning = reasoningText(message.has("content") ? message.get("content") : null);
                if (reasoning.isBlank() && partial != null) reasoning = partial.reasoning.toString();
                String key = "event:" + seq;
                JsonObject row = message(key, "assistant", text, time, seq, "committed");
                addImages(row, message.has("content") ? message.get("content") : null);
                if (!reasoning.isBlank()) {
                    row.addProperty("reasoning", reasoning);
                    row.addProperty("reasoningState", "complete");
                    row.addProperty("renderedReasoningHtml", markdownHtml(reasoning));
                }
                row.addProperty("renderedHtml", markdownHtml(text));
                rows.put(seq, row);
                rowKeys.put(key, seq);
                activeTurn = turn;
                turnPhase = "completed";
                continue;
            }

            if (type.equals("tool/call")) {
                String callId = string(data, "callId");
                if (callId == null) callId = "call-" + seq;
                String key = "tool:" + callId;
                JsonObject row = toolRow(key, time, seq, callId, data, entry, "running", null);
                rows.put(seq, row);
                rowKeys.put(key, seq);
                toolRows.put(callId, seq);
                continue;
            }

            if (type.equals("tool/result")) {
                String callId = string(data, "callId");
                JsonObject message = object(data, "message");
                if (callId == null && message != null) {
                    JsonObject sourceObject = object(message, "source");
                    callId = sourceObject == null ? null : string(sourceObject, "callId");
                }
                if (callId == null) callId = "call-" + seq;
                Long existing = toolRows.get(callId);
                JsonObject row = existing == null ? toolRow("tool:" + callId, time, seq, callId, data, entry, "completed", null)
                        : rows.get(existing);
                if (row == null) row = toolRow("tool:" + callId, time, seq, callId, data, entry, "completed", null);
                boolean failed = data.has("error") && !data.get("error").isJsonNull();
                row.getAsJsonObject("tool").addProperty("status", failed ? "failed" : "completed");
                String result = presentationText(data, entry);
                if (!result.isBlank()) row.getAsJsonObject("tool").addProperty(failed ? "error" : "result", result);
                if (existing == null) {
                    rows.put(seq, row);
                    rowKeys.put("tool:" + callId, seq);
                    toolRows.put(callId, seq);
                }
                continue;
            }

            if (type.equals("turn/start")) {
                activeTurn = integer(data, "turn", activeTurn);
                turnPhase = "running";
                turnDetail = null;
            } else if (type.equals("turn/end")) {
                activeTurn = integer(data, "turn", activeTurn);
                JsonObject reason = object(data, "reason");
                String reasonKind = reason == null ? null : string(reason, "kind");
                if ("aborted".equals(reasonKind) || "interrupted".equals(reasonKind)) turnPhase = "cancelled";
                else if (reasonKind != null && !"completed".equals(reasonKind)) turnPhase = "failed";
                else turnPhase = "completed";
                turnDetail = reasonKind;
            }
        }

        JsonArray messages = new JsonArray();
        for (JsonObject row : rows.values()) messages.add(row);
        return new Projection(messages, activeTurn, turnPhase, turnDetail, "running".equals(turnPhase), agentStatusLabel);
    }

    private static JsonObject toolRow(String key, long time, long seq, String callId, JsonObject data,
                                      JsonObject entry, String status, String ignored) {
        JsonObject row = message(key, "tool", "", time, seq, "committed");
        JsonObject tool = new JsonObject();
        tool.addProperty("callId", callId);
        tool.addProperty("name", stringOr(data, "name", "tool"));
        JsonObject view = object(entry, "view");
        JsonObject viewValue = view == null ? null : object(view, "view");
        String title = viewValue == null ? null : string(viewValue, "title");
        tool.addProperty("title", title == null || title.isBlank() ? stringOr(data, "name", "Tool call") : title);
        tool.addProperty("status", status);
        String args = presentationText(data, entry);
        if (!args.isBlank()) tool.addProperty("args", args);
        JsonArray images = imageViews(data.get("content"));
        if (images.size() > 0) tool.add("images", images);
        row.add("tool", tool);
        return row;
    }

    private static void addImages(JsonObject row, JsonElement content) {
        JsonArray images = imageViews(content);
        if (images.size() > 0) row.add("images", images);
    }

    private static JsonArray imageViews(JsonElement value) {
        JsonArray result = new JsonArray();
        collectImages(value, result, 0);
        return result;
    }

    private static void collectImages(JsonElement value, JsonArray result, int depth) {
        if (value == null || value.isJsonNull() || depth > 16) return;
        if (value.isJsonArray()) {
            for (JsonElement child : value.getAsJsonArray()) collectImages(child, result, depth + 1);
            return;
        }
        if (!value.isJsonObject()) return;
        JsonObject object = value.getAsJsonObject();
        String type = string(object, "type");
        String attachmentId = string(object, "attachmentId");
        String mediaType = string(object, "mediaType");
        if (("image".equals(type) || attachmentId != null) && attachmentId != null
                && isImageMediaType(mediaType)) {
            JsonObject image = new JsonObject();
            image.addProperty("attachmentId", attachmentId);
            image.addProperty("mediaType", mediaType);
            image.addProperty("bytes", number(object, "bytes", 0));
            String name = string(object, "name");
            if (name != null && !name.isBlank()) image.addProperty("name", name);
            result.add(image);
            return;
        }
        if (object.has("content")) collectImages(object.get("content"), result, depth + 1);
    }

    private static boolean isImageMediaType(String value) {
        return "image/png".equals(value) || "image/jpeg".equals(value)
                || "image/webp".equals(value) || "image/gif".equals(value);
    }

    private static String presentationText(JsonObject data, JsonObject entry) {
        JsonObject view = object(entry, "view");
        JsonObject viewValue = view == null ? null : object(view, "view");
        List<String> pieces = new ArrayList<>();
        if (viewValue != null) {
            for (String key : List.of("description", "cwd", "path", "url", "output", "answer")) {
                String value = string(viewValue, key);
                if (value != null && !value.isBlank()) pieces.add(value);
            }
        }
        String content = visibleText(data.get("content"));
        if (content.isBlank()) {
            JsonObject message = object(data, "message");
            if (message != null) content = visibleText(message.get("content"));
        }
        if (!content.isBlank()) pieces.add(content);
        String args = string(data, "arguments");
        if (args != null && !args.isBlank()) pieces.add(redactArguments(args));
        return oneLine(String.join(" · ", pieces), 2_400);
    }

    private static JsonObject message(String id, String role, String text, long time, long seq, String state) {
        JsonObject row = new JsonObject();
        row.addProperty("id", id);
        row.addProperty("role", role);
        row.addProperty("text", text == null ? "" : text);
        row.addProperty("createdAt", time);
        row.addProperty("seq", Math.max(0, seq));
        row.addProperty("state", state);
        return row;
    }

    private static void removeRow(TreeMap<Long, JsonObject> rows, Map<String, Long> keys, String key) {
        Long seq = keys.remove(key);
        if (seq != null) rows.remove(seq);
    }

    private static String visibleText(JsonElement value) {
        StringBuilder result = new StringBuilder();
        collectText(value, result, false, 0);
        String text = result.toString();
        int marker = text.indexOf(IDE_CONTEXT_MARKER);
        return marker < 0 ? text : text.substring(0, marker);
    }

    private static String reasoningText(JsonElement value) {
        StringBuilder result = new StringBuilder();
        collectText(value, result, true, 0);
        return result.toString();
    }

    private static void collectText(JsonElement value, StringBuilder output, boolean reasoning, int depth) {
        if (value == null || value.isJsonNull() || depth > 16) return;
        if (value.isJsonPrimitive()) {
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (primitive.isString()) output.append(primitive.getAsString());
            return;
        }
        if (value.isJsonArray()) {
            for (JsonElement child : value.getAsJsonArray()) collectText(child, output, reasoning, depth + 1);
            return;
        }
        JsonObject object = value.getAsJsonObject();
        String type = string(object, "type");
        boolean thisReasoning = "reasoning".equals(type);
        if (thisReasoning == reasoning) {
            String text = string(object, "text");
            if (text != null) output.append(text);
        }
        if (object.has("content")) collectText(object.get("content"), output, reasoning, depth + 1);
    }

    /** Conservative Markdown rendering; the webview still owns the layout and controls. */
    public static String markdownHtml(String value) {
        if (value == null || value.isEmpty()) return "";
        String escaped = escapeHtml(value);
        escaped = escaped.replaceAll("(?s)```([\\w+#.-]*)\\n(.*?)```", "<pre><code>$2</code></pre>");
        escaped = escaped.replaceAll("`([^`]+)`", "<code>$1</code>");
        escaped = escaped.replaceAll("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>");
        escaped = escaped.replaceAll("(?m)^### (.+)$", "<h3>$1</h3>");
        escaped = escaped.replaceAll("(?m)^## (.+)$", "<h2>$1</h2>");
        escaped = escaped.replaceAll("(?m)^# (.+)$", "<h1>$1</h1>");
        escaped = escaped.replaceAll("\\n", "<br>");
        return "<p>" + escaped + "</p>";
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static long eventSeq(JsonObject event) {
        return number(event, "seq", Long.MAX_VALUE / 2);
    }

    private static long number(JsonObject object, String key, long fallback) {
        try {
            return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsLong() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static int integer(JsonObject object, String key, int fallback) {
        long value = number(object, key, fallback);
        return value < Integer.MIN_VALUE || value > Integer.MAX_VALUE ? fallback : (int) value;
    }

    private static JsonObject object(JsonObject parent, String key) {
        return parent != null && parent.has(key) && parent.get(key).isJsonObject() ? parent.getAsJsonObject(key) : null;
    }

    private static String string(JsonObject parent, String key) {
        return parent != null && parent.has(key) && parent.get(key).isJsonPrimitive() ? parent.get(key).getAsString() : null;
    }

    private static String stringOr(JsonObject parent, String key, String fallback) {
        String value = string(parent, key);
        return value == null ? fallback : value;
    }

    private static String oneLine(String value, int limit) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, Math.max(0, limit - 1)) + "…";
    }

    private static String redactArguments(String value) {
        return value.replaceAll(
                "(?i)([\\\"']?(?:api[-_]?key|auth(?:orization)?|credential|password|secret|token)[\\\"']?\\s*[:=]\\s*)([\\\"']?)([^,}\\s\\\"']+)([\\\"']?)",
                "$1$2[redacted]$4");
    }

    private static final class Partial {
        final int turn;
        final int step;
        final StringBuilder text = new StringBuilder();
        final StringBuilder reasoning = new StringBuilder();

        Partial(int turn, int step) {
            this.turn = turn;
            this.step = step;
        }
    }

    public static final class Projection {
        public final JsonArray messages;
        public final int turn;
        public final String phase;
        public final String detail;
        public final boolean running;
        public final String agentStatusLabel;

        private Projection(JsonArray messages, int turn, String phase, String detail,
                           boolean running, String agentStatusLabel) {
            this.messages = messages;
            this.turn = turn;
            this.phase = phase;
            this.detail = detail;
            this.running = running;
            this.agentStatusLabel = agentStatusLabel;
        }

        public static Projection empty() {
            return new Projection(new JsonArray(), 0, "completed", null, false, null);
        }
    }
}
