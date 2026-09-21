package top.harcochen.dsh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * UI-side caches for the chat panel: projected message history, command/skill catalogs, and the
 * authoritative lookup behind checkpoint actions.
 *
 * <p>Live domain state (queue, jobs, projections, interactions, the session catalog, and the
 * followed history itself) comes from the Remote snapshot published by {@code
 * top.harcochen.dsh.remote.DshRemoteService}; this store only caches derived presentation results
 * and never sees wire frames.
 */
final class DshSessionStateStore {
    private static final int MAX_SCHEDULE_ITEMS = 200;
    private static final Pattern SCHEDULE_UTC_INSTANT =
            Pattern.compile(
                    "^(?!0000)\\d{4}-(?:0[1-9]|1[0-2])-"
                            + "(?:0[1-9]|[12]\\d|3[01])T(?:[01]\\d|2[0-3]):"
                            + "[0-5]\\d:[0-5]\\d\\.\\d{3}Z$");
    private static final DateTimeFormatter SCHEDULE_FORMATTER =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final Object lock = new Object();
    private final DshMarkdownRenderCache markdownRenderCache;
    private final Map<String, HistoryProjectionCache> historyCaches =
            new java.util.LinkedHashMap<>();
    private final Map<String, JsonArray> commandCatalogs = new java.util.LinkedHashMap<>();
    private final Map<String, JsonArray> skillCatalogs = new java.util.LinkedHashMap<>();

    DshSessionStateStore(DshMarkdownRenderCache markdownRenderCache) {
        this.markdownRenderCache = markdownRenderCache;
    }

    HistoryProjection projectHistory(String session, JsonObject history, String statusLabel) {
        JsonArray events =
                history != null && history.has("events") && history.get("events").isJsonArray()
                        ? history.getAsJsonArray("events")
                        : new JsonArray();
        JsonElement stream = history == null ? null : history.get("assistantStream");
        synchronized (lock) {
            HistoryProjectionCache cached = historyCaches.get(session);
            if (cached != null && cached.matches(events, stream, statusLabel)) {
                return cached.projection();
            }
        }

        DshMessageProjector.Projection projected =
                DshMessageProjector.project(history, statusLabel);
        JsonArray rendered = markdownRenderCache.render(projected.messages, "session:" + session);
        HistoryProjectionCache fresh =
                new HistoryProjectionCache(events, stream, statusLabel, projected, rendered);
        synchronized (lock) {
            HistoryProjectionCache cached = historyCaches.get(session);
            if (cached != null && cached.matches(events, stream, statusLabel)) {
                return cached.projection();
            }
            historyCaches.put(session, fresh);
            return fresh.projection();
        }
    }

    void prune(java.util.Collection<String> liveSessions) {
        synchronized (lock) {
            historyCaches.keySet().retainAll(liveSessions);
            commandCatalogs.keySet().retainAll(liveSessions);
            skillCatalogs.keySet().retainAll(liveSessions);
        }
    }

    boolean hasCommandCatalog(String session) {
        synchronized (lock) {
            return commandCatalogs.containsKey(session);
        }
    }

    boolean hasSkillCatalog(String session) {
        synchronized (lock) {
            return skillCatalogs.containsKey(session);
        }
    }

    void putCommandCatalog(String session, JsonArray commands) {
        synchronized (lock) {
            commandCatalogs.put(session, commands);
        }
    }

    void putSkillCatalog(String session, JsonArray skills) {
        synchronized (lock) {
            skillCatalogs.put(session, skills);
        }
    }

    boolean isRegisteredCommand(String session, String name) {
        if (session == null || name == null) {
            return false;
        }
        synchronized (lock) {
            JsonArray catalog = commandCatalogs.get(session);
            if (catalog == null) {
                return false;
            }
            for (JsonElement candidate : catalog) {
                if (candidate.isJsonObject()
                        && name.equals(DshJson.string(candidate.getAsJsonObject(), "name"))) {
                    return true;
                }
            }
            return false;
        }
    }

    JsonArray commandCatalog(String session) {
        return catalogSnapshot(commandCatalogs, session);
    }

    JsonArray skillCatalog(String session) {
        return catalogSnapshot(skillCatalogs, session);
    }

    private JsonArray catalogSnapshot(Map<String, JsonArray> source, String session) {
        if (session == null) {
            return new JsonArray();
        }
        synchronized (lock) {
            JsonArray known = source.get(session);
            return known == null ? new JsonArray() : known.deepCopy();
        }
    }

    /**
     * Resolve a finalized user/assistant message sequence to the turn it belongs to.
     *
     * <p>The WebView only sends a sequence number. Keeping this lookup in the state store means
     * checkpoint actions are checked against the latest authoritative history rather than trusting
     * an arbitrary number supplied by the untrusted page.
     */
    Integer checkpointTurn(String session, long sequence) {
        if (session == null || sequence < 0) return null;
        JsonArray events;
        synchronized (lock) {
            HistoryProjectionCache cache = historyCaches.get(session);
            if (cache == null) return null;
            events = cache.events.deepCopy();
        }
        java.util.List<JsonObject> ordered = new java.util.ArrayList<>();
        for (JsonElement candidate : events) {
            if (!candidate.isJsonObject()) continue;
            JsonObject wrapper = candidate.getAsJsonObject();
            JsonObject event = historyEvent(wrapper);
            if (event != null) ordered.add(wrapper);
        }
        ordered.sort(
                java.util.Comparator.comparingLong(
                        wrapper ->
                                DshJson.longValue(
                                        historyEvent(wrapper).get("seq"), Long.MAX_VALUE)));

        JsonObject target = null;
        for (JsonObject wrapper : ordered) {
            JsonObject event = historyEvent(wrapper);
            long eventSeq = DshJson.longValue(event.get("seq"), Long.MIN_VALUE);
            if (eventSeq == sequence) {
                target = event;
                break;
            }
        }
        if (target == null) return null;
        String type = DshJson.string(target, "type");
        if (!"user/message".equals(type) && !"assistant/message".equals(type)) return null;
        if ("user/message".equals(type)) {
            JsonObject data = historyObject(target, "data");
            JsonObject source = data == null ? null : historyObject(data, "source");
            if (source != null && !"user".equals(DshJson.string(source, "kind"))) return null;
        }

        JsonObject targetData = historyObject(target, "data");
        Integer explicit = positiveTurn(targetData == null ? null : targetData.get("turn"));
        if (explicit != null) return explicit;

        Integer active = null;
        for (JsonObject wrapper : ordered) {
            JsonObject event = historyEvent(wrapper);
            long eventSeq = DshJson.longValue(event.get("seq"), Long.MAX_VALUE);
            if (eventSeq > sequence) break;
            JsonObject data = historyObject(event, "data");
            Integer turn = positiveTurn(data == null ? null : data.get("turn"));
            String eventType = DshJson.string(event, "type");
            if ("turn/start".equals(eventType) && turn != null) {
                active = turn;
            } else if ("turn/end".equals(eventType) && turn != null && turn.equals(active)) {
                active = null;
            }
        }
        return active;
    }

    private static JsonObject historyEvent(JsonObject wrapper) {
        return wrapper != null && wrapper.has("event") && wrapper.get("event").isJsonObject()
                ? wrapper.getAsJsonObject("event")
                : wrapper;
    }

    private static JsonObject historyObject(JsonObject parent, String key) {
        return parent != null && parent.has(key) && parent.get(key).isJsonObject()
                ? parent.getAsJsonObject(key)
                : null;
    }

    private static Integer positiveTurn(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) return null;
        long number = DshJson.longValue(value, -1L);
        return number > 0 && number <= Integer.MAX_VALUE ? (int) number : null;
    }

    // ---------------------------------------------------------------------------
    // static projection view builders fed from the Remote snapshot
    // ---------------------------------------------------------------------------

    static JsonArray todos(JsonElement value) {
        if (value == null || !value.isJsonArray()) {
            return null;
        }
        JsonArray source = value.getAsJsonArray();
        if (source.isEmpty() || source.size() > 200) {
            return null;
        }
        JsonArray result = new JsonArray();
        Set<String> seen = new HashSet<>();
        for (JsonElement candidate : source) {
            if (!candidate.isJsonObject()) {
                return null;
            }
            JsonObject item = candidate.getAsJsonObject();
            String content = DshJson.string(item, "content");
            String status = DshJson.string(item, "status");
            if (content == null || content.isBlank() || !seen.add(content)) {
                return null;
            }
            if (!"pending".equals(status)
                    && !"in_progress".equals(status)
                    && !"completed".equals(status)) {
                return null;
            }
            JsonObject row = new JsonObject();
            row.addProperty("content", content);
            row.addProperty("status", status);
            result.add(row);
        }
        return result;
    }

    /** Validate and expose the optional read-only Runtime Schedule projection. */
    static JsonArray schedule(JsonElement value) {
        if (value == null
                || !value.isJsonArray()
                || value.getAsJsonArray().size() > MAX_SCHEDULE_ITEMS) {
            return null;
        }
        JsonArray result = new JsonArray();
        Set<String> seen = new HashSet<>();
        for (JsonElement candidate : value.getAsJsonArray()) {
            if (!candidate.isJsonObject()) return null;
            JsonObject source = candidate.getAsJsonObject();
            String id = DshJson.string(source, "id");
            String prompt = DshJson.string(source, "prompt");
            String scheduledAt = DshJson.string(source, "scheduledAt");
            if (!validScheduleText(id)
                    || !validScheduleText(prompt)
                    || !validScheduleInstant(scheduledAt)
                    || !seen.add(id)) return null;
            JsonObject row = new JsonObject();
            row.addProperty("id", id);
            row.addProperty("prompt", prompt);
            row.addProperty("scheduledAt", scheduledAt);
            String kind = DshJson.string(source, "kind");
            if ("after".equals(kind)) {
                Long seconds = safeInteger(source.get("afterSeconds"), 1);
                if (seconds == null) return null;
                row.addProperty("kind", kind);
                row.addProperty("afterSeconds", seconds);
            } else if ("every".equals(kind)) {
                Long seconds = safeInteger(source.get("everySeconds"), 300);
                if (seconds == null) return null;
                row.addProperty("kind", kind);
                row.addProperty("everySeconds", seconds);
            } else if ("at".equals(kind)) {
                row.addProperty("kind", kind);
            } else {
                return null;
            }
            result.add(row);
        }
        return result;
    }

    private static boolean validScheduleText(String value) {
        return value != null && !value.isEmpty() && value.trim().equals(value);
    }

    private static boolean validScheduleInstant(String value) {
        if (value == null || !SCHEDULE_UTC_INSTANT.matcher(value).matches()) return false;
        try {
            return SCHEDULE_FORMATTER.format(Instant.parse(value)).equals(value);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static Long safeInteger(JsonElement value, long minimum) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            return null;
        try {
            double number = value.getAsDouble();
            if (!Double.isFinite(number)
                    || number != Math.rint(number)
                    || number < minimum
                    || number > 9_007_199_254_740_991d) return null;
            return value.getAsLong();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static JsonObject imageLimits(JsonElement value) {
        if (value == null || !value.isJsonObject()) {
            return null;
        }
        JsonObject source = value.getAsJsonObject();
        if (!positiveNumber(source, "maxImageBytes")
                || !positiveNumber(source, "maxImagesPerMessage")
                || !positiveNumber(source, "maxMessageImageBytes")
                || !source.has("mediaTypes")
                || !source.get("mediaTypes").isJsonArray()) {
            return null;
        }
        JsonArray mediaTypes = new JsonArray();
        for (JsonElement candidate : source.getAsJsonArray("mediaTypes")) {
            if (candidate.isJsonPrimitive() && isImageMediaType(candidate.getAsString())) {
                mediaTypes.add(candidate.getAsString());
            }
        }
        if (mediaTypes.isEmpty()) {
            return null;
        }
        JsonObject result = new JsonObject();
        result.add("maxImageBytes", source.get("maxImageBytes").deepCopy());
        result.add("maxImagesPerMessage", source.get("maxImagesPerMessage").deepCopy());
        result.add("maxMessageImageBytes", source.get("maxMessageImageBytes").deepCopy());
        result.add("mediaTypes", mediaTypes);
        return result;
    }

    static JsonObject sessionStats(JsonElement value) {
        if (value == null || !value.isJsonObject()) {
            return null;
        }
        JsonObject source = value.getAsJsonObject();
        String[] fields = {
            "turns", "steps", "llmMs", "toolMs", "ttftMs", "ttftSteps", "decodeMs", "decodeTokens"
        };
        JsonObject result = new JsonObject();
        for (String field : fields) {
            if (!nonNegativeNumber(source, field)) {
                return null;
            }
            result.add(field, source.get(field).deepCopy());
        }
        return result;
    }

    /** Validate and expose the optional public plan-mode projection. */
    static JsonObject plan(JsonElement value) {
        if (value == null || !value.isJsonObject()) return null;
        JsonObject source = value.getAsJsonObject();
        if (!source.has("active")
                || !source.has("pending")
                || !source.get("active").isJsonPrimitive()
                || !source.get("pending").isJsonPrimitive()
                || !source.getAsJsonPrimitive("active").isBoolean()
                || !source.getAsJsonPrimitive("pending").isBoolean()) {
            return null;
        }
        JsonObject result = new JsonObject();
        result.addProperty("active", source.get("active").getAsBoolean());
        result.addProperty("pending", source.get("pending").getAsBoolean());
        return result;
    }

    /** Merge the session's model-selection projection with the host-wide catalog default. */
    static JsonObject currentModelRoute(JsonElement modelSelection, JsonObject catalog) {
        JsonObject route = new JsonObject();
        JsonObject current =
                catalog != null && catalog.has("default") && catalog.get("default").isJsonObject()
                        ? catalog.getAsJsonObject("default")
                        : null;
        if (modelSelection != null && modelSelection.isJsonObject()) {
            JsonObject selection = modelSelection.getAsJsonObject();
            JsonObject next =
                    selection.has("next") && selection.get("next").isJsonObject()
                            ? selection.getAsJsonObject("next")
                            : null;
            JsonObject lastUsed =
                    next == null
                                    && selection.has("lastUsed")
                                    && selection.get("lastUsed").isJsonObject()
                            ? selection.getAsJsonObject("lastUsed")
                            : null;
            JsonObject projected = next != null ? next : lastUsed;
            if (projected != null) current = projected;
        }
        if (current == null) return route;
        DshJson.copyString(current, route, "provider");
        DshJson.copyString(current, route, "model");
        DshJson.copyString(current, route, "reasoningEffort");
        return route;
    }

    static JsonObject tokenUsage(
            JsonObject route,
            JsonElement tokenUsageCell,
            JsonElement contextPressureCell,
            JsonElement contextBreakdownCell) {
        JsonObject billing = billingProjection(tokenUsageCell);
        JsonObject context = contextPressureProjection(contextPressureCell);
        JsonObject breakdown = contextBreakdownProjection(contextBreakdownCell);
        if (route.isEmpty() && billing == null && context == null && breakdown == null) {
            return null;
        }
        JsonObject result = new JsonObject();
        result.add("route", route);
        if (billing != null) {
            result.add("billing", billing);
        }
        if (context != null) {
            result.add("context", context);
        }
        if (breakdown != null) {
            result.add("breakdown", breakdown);
        }
        return result;
    }

    private static JsonObject billingProjection(JsonElement usage) {
        if (usage == null || !usage.isJsonObject()) {
            return null;
        }
        JsonObject source = usage.getAsJsonObject();
        if (!nonNegativeNumber(source, "uncachedInputTokens")
                || !nonNegativeNumber(source, "outputTokens")
                || !nonNegativeNumber(source, "cacheReadTokens")
                || !nonNegativeNumber(source, "cacheWriteTokens")) {
            return null;
        }
        JsonObject billing = new JsonObject();
        billing.add("uncachedInputTokens", source.get("uncachedInputTokens").deepCopy());
        billing.add("outputTokens", source.get("outputTokens").deepCopy());
        billing.add("cacheReadTokens", source.get("cacheReadTokens").deepCopy());
        billing.add("cacheWriteTokens", source.get("cacheWriteTokens").deepCopy());
        return billing;
    }

    private static JsonObject contextPressureProjection(JsonElement pressure) {
        if (pressure == null || !pressure.isJsonObject()) {
            return null;
        }
        JsonObject source = pressure.getAsJsonObject();
        JsonObject result = new JsonObject();
        if (nonNegativeNumber(source, "pressureTokens")) {
            result.add("pressureTokens", source.get("pressureTokens").deepCopy());
        }
        if (nonNegativeNumber(source, "projectedTokens")) {
            result.add("projectedTokens", source.get("projectedTokens").deepCopy());
        }
        if (positiveNumber(source, "contextWindow")) {
            result.add("contextWindow", source.get("contextWindow").deepCopy());
        }
        return result.isEmpty() ? null : result;
    }

    private static JsonObject contextBreakdownProjection(JsonElement composition) {
        if (composition == null || !composition.isJsonObject()) {
            return null;
        }
        JsonObject source = composition.getAsJsonObject();
        if (!nonNegativeNumber(source, "systemTokens")
                || !nonNegativeNumber(source, "toolsTokens")
                || !nonNegativeNumber(source, "messageTokens")) {
            return null;
        }
        JsonObject result = new JsonObject();
        result.add("systemTokens", source.get("systemTokens").deepCopy());
        result.add("toolsTokens", source.get("toolsTokens").deepCopy());
        result.add("messageTokens", source.get("messageTokens").deepCopy());
        return result;
    }

    static JsonObject permissions(JsonElement value) {
        if (value == null || !value.isJsonObject()) {
            return null;
        }
        JsonObject source = value.getAsJsonObject();
        String currentValue = DshJson.string(source, "currentValue");
        if (currentValue == null
                || !source.has("options")
                || !source.get("options").isJsonArray()) {
            return null;
        }
        JsonArray options = new JsonArray();
        String currentLabel = null;
        for (JsonElement candidate : source.getAsJsonArray("options")) {
            if (!candidate.isJsonObject()) {
                continue;
            }
            JsonObject raw = candidate.getAsJsonObject();
            String optionValue = DshJson.string(raw, "value");
            String optionName = DshJson.string(raw, "name");
            if (optionValue == null
                    || optionValue.isBlank()
                    || optionName == null
                    || optionName.isBlank()) {
                continue;
            }
            JsonObject option = new JsonObject();
            option.addProperty("value", optionValue);
            option.addProperty("label", optionName);
            String description = DshJson.string(raw, "description");
            if (description != null && !description.isBlank()) {
                option.addProperty("description", description);
            }
            options.add(option);
            if (optionValue.equals(currentValue)) {
                currentLabel = optionName;
            }
        }
        if (currentLabel == null) {
            return null;
        }
        JsonObject result = new JsonObject();
        result.addProperty("currentValue", currentValue);
        result.addProperty("currentLabel", currentLabel);
        result.add("options", options);
        return result;
    }

    private static boolean nonNegativeNumber(JsonObject source, String key) {
        if (source == null
                || !source.has(key)
                || !source.get(key).isJsonPrimitive()
                || !source.get(key).getAsJsonPrimitive().isNumber()) {
            return false;
        }
        try {
            double value = source.get(key).getAsDouble();
            return Double.isFinite(value) && value >= 0;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean positiveNumber(JsonObject source, String key) {
        return nonNegativeNumber(source, key) && source.get(key).getAsDouble() > 0;
    }

    private static boolean isImageMediaType(String value) {
        return "image/png".equals(value)
                || "image/jpeg".equals(value)
                || "image/webp".equals(value)
                || "image/gif".equals(value);
    }

    record HistoryProjection(DshMessageProjector.Projection projection, JsonArray messages) {}

    private static final class HistoryProjectionCache {
        private final JsonArray events;
        private final JsonElement stream;
        private final String statusLabel;
        private final DshMessageProjector.Projection projection;
        private final JsonArray messages;

        private HistoryProjectionCache(
                JsonArray events,
                JsonElement stream,
                String statusLabel,
                DshMessageProjector.Projection projection,
                JsonArray messages) {
            this.events = events;
            this.stream = stream;
            this.statusLabel = statusLabel;
            this.projection = projection;
            this.messages = messages;
        }

        private boolean matches(
                JsonArray candidateEvents,
                JsonElement candidateStream,
                String candidateStatusLabel) {
            return Objects.equals(statusLabel, candidateStatusLabel)
                    && Objects.equals(stream, candidateStream)
                    && events.equals(candidateEvents);
        }

        private HistoryProjection projection() {
            return new HistoryProjection(projection, messages);
        }
    }
}
