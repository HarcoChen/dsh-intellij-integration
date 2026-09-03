package top.harcochen.dsh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Thread-safe owner of history caches and transient per-session mux projections. */
final class DshSessionStateStore {
    private static final int QUEUE_PREVIEW_CHARS = 200;

    private final Object lock = new Object();
    private final Runnable stateChanged;
    private final DshMarkdownRenderCache markdownRenderCache;
    private final Map<String, LinkedHashMap<String, JsonObject>> interactionsBySession =
            new LinkedHashMap<>();
    private final Map<String, JsonArray> queueBySession = new LinkedHashMap<>();
    private final Map<String, JsonArray> jobsBySession = new LinkedHashMap<>();
    private final Map<String, Map<String, ProjectionCell>> projectionCellsBySession =
            new LinkedHashMap<>();
    private final Map<String, HistoryProjectionCache> historyCaches = new LinkedHashMap<>();
    private final Map<String, JsonArray> commandCatalogs = new LinkedHashMap<>();
    private final Map<String, JsonArray> skillCatalogs = new LinkedHashMap<>();

    DshSessionStateStore(DshMarkdownRenderCache markdownRenderCache, Runnable stateChanged) {
        this.markdownRenderCache = markdownRenderCache;
        this.stateChanged = stateChanged;
    }

    HistoryProjection projectHistory(String session, JsonObject history, String statusLabel) {
        JsonArray events =
                history != null && history.has("events") && history.get("events").isJsonArray()
                        ? history.getAsJsonArray("events")
                        : new JsonArray();
        synchronized (lock) {
            HistoryProjectionCache cached = historyCaches.get(session);
            if (cached != null && cached.matches(events, statusLabel)) {
                return cached.projection();
            }
        }

        DshMessageProjector.Projection projected =
                DshMessageProjector.project(history, statusLabel);
        JsonArray rendered = markdownRenderCache.render(projected.messages, "session:" + session);
        HistoryProjectionCache fresh =
                new HistoryProjectionCache(events, statusLabel, projected, rendered);
        synchronized (lock) {
            HistoryProjectionCache cached = historyCaches.get(session);
            if (cached != null && cached.matches(events, statusLabel)) {
                return cached.projection();
            }
            historyCaches.put(session, fresh);
            return fresh.projection();
        }
    }

    Set<String> prune(JsonArray sessions, String selectedSession) {
        Set<String> live = new HashSet<>();
        for (JsonElement candidate : sessions) {
            if (!candidate.isJsonObject()) {
                continue;
            }
            String id = DshJson.string(candidate.getAsJsonObject(), "sessionId");
            if (id != null) {
                live.add(id);
            }
        }
        if (selectedSession != null) {
            live.add(selectedSession);
        }
        synchronized (lock) {
            queueBySession.keySet().retainAll(live);
            jobsBySession.keySet().retainAll(live);
            projectionCellsBySession.keySet().retainAll(live);
            historyCaches.keySet().retainAll(live);
            commandCatalogs.keySet().retainAll(live);
            skillCatalogs.keySet().retainAll(live);
            interactionsBySession.keySet().retainAll(live);
        }
        return live;
    }

    boolean hasCommandCatalog(String session) {
        return hasCatalog(commandCatalogs, session);
    }

    boolean hasSkillCatalog(String session) {
        return hasCatalog(skillCatalogs, session);
    }

    private boolean hasCatalog(Map<String, JsonArray> source, String session) {
        synchronized (lock) {
            return source.containsKey(session);
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

    void seedProjections(String session, JsonObject history) {
        JsonObject block =
                history != null
                                && history.has("projections")
                                && history.get("projections").isJsonObject()
                        ? history.getAsJsonObject("projections")
                        : null;
        JsonObject values =
                block != null && block.has("values") && block.get("values").isJsonObject()
                        ? block.getAsJsonObject("values")
                        : new JsonObject();
        long asOfSeq =
                block != null && block.has("asOfSeq") && block.get("asOfSeq").isJsonPrimitive()
                        ? DshJson.longValue(block.get("asOfSeq"), -1L)
                        : -1L;
        synchronized (lock) {
            Map<String, ProjectionCell> cells =
                    projectionCellsBySession.computeIfAbsent(
                            session, ignored -> new LinkedHashMap<>());
            cells.entrySet()
                    .removeIf(
                            entry -> entry.getValue().seq < asOfSeq && !values.has(entry.getKey()));
            for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
                ProjectionCell known = cells.get(entry.getKey());
                if (known == null || known.seq <= asOfSeq) {
                    cells.put(
                            entry.getKey(),
                            new ProjectionCell(entry.getValue().deepCopy(), asOfSeq));
                }
            }
        }
    }

    ProjectionSnapshot projection(String session, String key) {
        if (session == null) {
            return null;
        }
        synchronized (lock) {
            Map<String, ProjectionCell> cells = projectionCellsBySession.get(session);
            ProjectionCell cell = cells == null ? null : cells.get(key);
            return cell == null ? null : new ProjectionSnapshot(cell.value, cell.seq);
        }
    }

    JsonElement projectionValue(String session, String key) {
        ProjectionSnapshot snapshot = projection(session, key);
        return snapshot == null || snapshot.value() == null || snapshot.value().isJsonNull()
                ? null
                : snapshot.value();
    }

    void receiveMuxFrame(JsonObject frame, String selectedSession, JsonArray sessions) {
        String type = DshJson.string(frame, "type");
        String frameSession = DshJson.string(frame, "sessionId");
        if (type == null || frameSession == null) {
            return;
        }
        boolean changed;
        synchronized (lock) {
            changed = acceptMuxFrame(frame, type, frameSession, sessions);
        }
        boolean titleChanged =
                changed
                        && "session/projection".equals(type)
                        && "title".equals(DshJson.string(frame, "key"));
        if (changed && (frameSession.equals(selectedSession) || titleChanged)) {
            stateChanged.run();
        }
    }

    private boolean acceptMuxFrame(
            JsonObject frame, String type, String frameSession, JsonArray sessions) {
        if ("session/queue".equals(type)) {
            queueBySession.put(frameSession, queueDockItems(frame.get("items")));
            return true;
        }
        if ("session/jobs".equals(type)) {
            jobsBySession.put(frameSession, jobCenterItems(frameSession, frame.get("jobs")));
            return true;
        }
        if ("session/projection".equals(type)) {
            boolean accepted = acceptProjectionFrame(frameSession, frame);
            if (accepted && "title".equals(DshJson.string(frame, "key"))) {
                applySessionTitle(frameSession, frame, sessions);
            }
            return accepted;
        }
        LinkedHashMap<String, JsonObject> interactions =
                interactionsBySession.computeIfAbsent(
                        frameSession, ignored -> new LinkedHashMap<>());
        if ("session/subscribed".equals(type)) {
            interactions.clear();
            queueBySession.remove(frameSession);
            jobsBySession.remove(frameSession);
            return true;
        }
        if ("approval/requested".equals(type)) {
            String rpcId = DshJson.string(frame, "_rpcId");
            String approvalId = DshJson.string(frame, "approvalId");
            String toolName = DshJson.string(frame, "toolName");
            if (rpcId == null || approvalId == null || toolName == null) {
                return false;
            }
            JsonObject item = new JsonObject();
            item.addProperty("key", "a:" + rpcId);
            item.addProperty("kind", "approval");
            item.addProperty("status", "pending");
            item.addProperty("approvalId", approvalId);
            item.addProperty("toolName", toolName);
            DshJson.copyString(frame, item, "reason");
            DshJson.copyString(frame, item, "callId");
            interactions.put("a:" + rpcId, item);
            return true;
        }
        if ("approval/resolved".equals(type)) {
            String approvalId = DshJson.string(frame, "approvalId");
            if (approvalId != null) {
                interactions
                        .entrySet()
                        .removeIf(
                                entry ->
                                        approvalId.equals(
                                                DshJson.string(entry.getValue(), "approvalId")));
                return true;
            }
            return false;
        }
        if ("question/requested".equals(type)) {
            String rpcId = DshJson.string(frame, "_rpcId");
            if (rpcId == null || !frame.has("questions") || !frame.get("questions").isJsonArray()) {
                return false;
            }
            JsonObject item = new JsonObject();
            item.addProperty("key", "q:" + rpcId);
            item.addProperty("kind", "question");
            item.addProperty("status", "pending");
            item.add("questions", frame.getAsJsonArray("questions").deepCopy());
            interactions.put("q:" + rpcId, item);
            return true;
        }
        if ("question/resolved".equals(type)) {
            String rpcId = DshJson.string(frame, "questionRpcId");
            if (rpcId != null) {
                interactions.remove("q:" + rpcId);
                return true;
            }
        }
        return false;
    }

    private boolean acceptProjectionFrame(String session, JsonObject frame) {
        String key = DshJson.string(frame, "key");
        if (key == null
                || key.isBlank()
                || !frame.has("seq")
                || !frame.get("seq").isJsonPrimitive()) {
            return false;
        }
        long seq = DshJson.longValue(frame.get("seq"), Long.MIN_VALUE);
        if (seq == Long.MIN_VALUE) {
            return false;
        }
        Map<String, ProjectionCell> cells =
                projectionCellsBySession.computeIfAbsent(session, ignored -> new LinkedHashMap<>());
        ProjectionCell known = cells.get(key);
        if (known != null && known.seq > seq) {
            return false;
        }
        JsonElement value = frame.has("value") ? frame.get("value").deepCopy() : JsonNull.INSTANCE;
        cells.put(key, new ProjectionCell(value, seq));
        return true;
    }

    private static void applySessionTitle(String session, JsonObject frame, JsonArray sessions) {
        JsonElement value = frame.has("value") ? frame.get("value") : null;
        if (value == null || !value.isJsonPrimitive()) {
            return;
        }
        String title = value.getAsString();
        if (title.isBlank()) {
            return;
        }
        for (JsonElement candidate : sessions) {
            if (!candidate.isJsonObject()) {
                continue;
            }
            JsonObject item = candidate.getAsJsonObject();
            if (session.equals(DshJson.string(item, "sessionId"))) {
                item.addProperty("title", title);
                return;
            }
        }
    }

    JsonArray queue(String session) {
        return transientSnapshot(queueBySession, session);
    }

    JsonArray jobs(String session) {
        return transientSnapshot(jobsBySession, session);
    }

    private JsonArray transientSnapshot(Map<String, JsonArray> source, String session) {
        if (session == null) {
            return new JsonArray();
        }
        synchronized (lock) {
            JsonArray known = source.get(session);
            return known == null ? new JsonArray() : known.deepCopy();
        }
    }

    JsonArray interactions(String session) {
        JsonArray result = new JsonArray();
        if (session == null) {
            return result;
        }
        synchronized (lock) {
            Map<String, JsonObject> interactions = interactionsBySession.get(session);
            if (interactions != null) {
                for (JsonObject item : interactions.values()) {
                    result.add(item.deepCopy());
                }
            }
        }
        return result;
    }

    boolean hasPendingInteractions(String session) {
        if (session == null) {
            return false;
        }
        synchronized (lock) {
            Map<String, JsonObject> interactions = interactionsBySession.get(session);
            if (interactions == null) {
                return false;
            }
            return interactions.values().stream()
                    .anyMatch(
                            item -> {
                                String status = DshJson.string(item, "status");
                                return "pending".equals(status) || "submitting".equals(status);
                            });
        }
    }

    void updateInteractionStatus(String session, String key, String status, String error) {
        if (key == null) {
            return;
        }
        synchronized (lock) {
            JsonObject item =
                    interactionsBySession.getOrDefault(session, new LinkedHashMap<>()).get(key);
            if (item == null) {
                return;
            }
            item.addProperty("status", status);
            if (error == null) {
                item.remove("error");
            } else {
                item.addProperty("error", error);
            }
        }
        stateChanged.run();
    }

    JsonArray todos(String session) {
        JsonElement value = projectionValue(session, "todos");
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

    JsonObject imageLimits(String session) {
        JsonElement value = projectionValue(session, "imageLimits");
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

    JsonObject sessionStats(String session) {
        JsonElement value = projectionValue(session, "sessionStats");
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

    JsonObject tokenUsage(String session, JsonObject modelCatalog) {
        JsonObject route = new JsonObject();
        JsonObject catalog = modelCatalog;
        JsonObject current =
                catalog != null && catalog.has("current") && catalog.get("current").isJsonObject()
                        ? catalog.getAsJsonObject("current")
                        : null;
        if (current != null) {
            DshJson.copyString(current, route, "provider");
            DshJson.copyString(current, route, "model");
            DshJson.copyString(current, route, "reasoningEffort");
        }
        JsonObject billing = billingProjection(session);
        JsonObject context = contextPressureProjection(session);
        JsonObject breakdown = contextBreakdownProjection(session);
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

    private JsonObject billingProjection(String session) {
        JsonElement usage = projectionValue(session, "tokenUsage");
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

    private JsonObject contextPressureProjection(String session) {
        JsonElement pressure = projectionValue(session, "contextPressure");
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

    private JsonObject contextBreakdownProjection(String session) {
        JsonElement composition = projectionValue(session, "contextBreakdown");
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

    private static JsonArray queueDockItems(JsonElement items) {
        JsonArray result = new JsonArray();
        if (items == null || !items.isJsonArray()) {
            return result;
        }
        for (JsonElement candidate : items.getAsJsonArray()) {
            if (!candidate.isJsonObject()) {
                continue;
            }
            JsonObject item = candidate.getAsJsonObject();
            String id = DshJson.string(item, "id");
            String placement = DshJson.string(item, "placement");
            if (id == null || id.isBlank()) {
                continue;
            }
            if (!"queued".equals(placement) && !"steering".equals(placement)) {
                continue;
            }
            JsonObject message =
                    item.has("message") && item.get("message").isJsonObject()
                            ? item.getAsJsonObject("message")
                            : new JsonObject();
            JsonArray content =
                    message.has("content") && message.get("content").isJsonArray()
                            ? message.getAsJsonArray("content")
                            : new JsonArray();
            StringBuilder flat = new StringBuilder();
            StringBuilder editable = new StringBuilder();
            boolean textOnly = true;
            for (JsonElement blockElement : content) {
                if (!blockElement.isJsonObject()) {
                    textOnly = false;
                    continue;
                }
                JsonObject block = blockElement.getAsJsonObject();
                String blockType = DshJson.string(block, "type");
                String text = DshJson.string(block, "text");
                if ("text".equals(blockType) && text != null) {
                    if (!flat.isEmpty()) {
                        flat.append(' ');
                    }
                    flat.append(text);
                    editable.append(text);
                } else {
                    textOnly = false;
                    if (!flat.isEmpty()) {
                        flat.append(' ');
                    }
                    flat.append('[').append(blockType == null ? "content" : blockType).append(']');
                }
            }
            JsonObject row = new JsonObject();
            row.addProperty("id", id);
            row.addProperty("placement", placement);
            row.addProperty(
                    "preview", clampPreview(flat.toString().replaceAll("\\s+", " ").trim()));
            if (textOnly) {
                row.addProperty("editableText", editable.toString());
            }
            result.add(row);
        }
        return result;
    }

    private static String clampPreview(String preview) {
        int[] points = preview.codePoints().toArray();
        if (points.length <= QUEUE_PREVIEW_CHARS) {
            return preview;
        }
        return new String(points, 0, QUEUE_PREVIEW_CHARS) + "\u2026";
    }

    private static JsonArray jobCenterItems(String ownerSessionId, JsonElement jobs) {
        JsonArray result = new JsonArray();
        if (jobs == null || !jobs.isJsonArray()) {
            return result;
        }
        for (JsonElement candidate : jobs.getAsJsonArray()) {
            if (!candidate.isJsonObject()) {
                continue;
            }
            JsonObject job = candidate.getAsJsonObject();
            String id = DshJson.string(job, "id");
            String kind = DshJson.string(job, "kind");
            String label = DshJson.string(job, "label");
            String status = DshJson.string(job, "status");
            if (id == null || kind == null || label == null || status == null) {
                continue;
            }
            if (!"running".equals(status)
                    && !"stopping".equals(status)
                    && !"completed".equals(status)
                    && !"killed".equals(status)
                    && !"failed".equals(status)) {
                continue;
            }
            if (!job.has("startedAt") || !job.get("startedAt").isJsonPrimitive()) {
                continue;
            }
            long startedAt = DshJson.longValue(job.get("startedAt"), Long.MIN_VALUE);
            if (startedAt == Long.MIN_VALUE) {
                continue;
            }
            JsonObject row = new JsonObject();
            row.addProperty("id", id);
            row.addProperty("kind", kind);
            row.addProperty("label", label);
            row.addProperty("ownerSessionId", ownerSessionId);
            row.addProperty("status", status);
            String detail = DshJson.string(job, "detail");
            if (detail != null) {
                row.addProperty("outputSummary", detail);
            }
            row.addProperty("startedAt", startedAt);
            if (job.has("finishedAt") && job.get("finishedAt").isJsonPrimitive()) {
                long finishedAt = DshJson.longValue(job.get("finishedAt"), Long.MIN_VALUE);
                if (finishedAt != Long.MIN_VALUE) {
                    row.addProperty("finishedAt", finishedAt);
                }
            }
            result.add(row);
        }
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

    record ProjectionSnapshot(JsonElement value, long seq) {}

    private static final class ProjectionCell {
        private final JsonElement value;
        private final long seq;

        private ProjectionCell(JsonElement value, long seq) {
            this.value = value;
            this.seq = seq;
        }
    }

    private static final class HistoryProjectionCache {
        private final JsonArray events;
        private final String statusLabel;
        private final DshMessageProjector.Projection projection;
        private final JsonArray messages;

        private HistoryProjectionCache(
                JsonArray events,
                String statusLabel,
                DshMessageProjector.Projection projection,
                JsonArray messages) {
            this.events = events;
            this.statusLabel = statusLabel;
            this.projection = projection;
            this.messages = messages;
        }

        private boolean matches(JsonArray candidateEvents, String candidateStatusLabel) {
            return Objects.equals(statusLabel, candidateStatusLabel)
                    && events.equals(candidateEvents);
        }

        private HistoryProjection projection() {
            return new HistoryProjection(projection, messages);
        }
    }
}
