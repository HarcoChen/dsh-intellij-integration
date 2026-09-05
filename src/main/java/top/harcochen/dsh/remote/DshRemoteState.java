package top.harcochen.dsh.remote;

import static top.harcochen.dsh.DshJson.bool;
import static top.harcochen.dsh.DshJson.longValue;
import static top.harcochen.dsh.DshJson.string;
import static top.harcochen.dsh.DshJson.stringOr;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Domain state for one Remote connection, confined to the connection executor.
 *
 * <p>All mutating methods must run on the connection executor, so no internal locks are needed and
 * frame order matches the wire. Published structures are copy-on-write: a mutation replaces (never
 * mutates) the JsonArray/JsonObject a published snapshot still references, which lets the facade
 * hand snapshots to the EDT without deep copies.
 */
public final class DshRemoteState {
    /** Queue preview clamp shared with the previous wire store. */
    private static final int QUEUE_PREVIEW_CHARS = 200;

    /** Upper bound for a followed session's retained event tail. */
    private static final int MAX_FOLLOW_EVENTS = 5_000;

    private String phase = "stopped";
    private String message;
    private long generation;

    // ---- session catalog -----------------------------------------------------
    private final Map<String, JsonObject> catalogBySession = new LinkedHashMap<>();

    // ---- workspace registry --------------------------------------------------
    private final Map<String, JsonObject> workspacesById = new LinkedHashMap<>();
    private final Set<String> archivedSessionIds = new LinkedHashSet<>();

    // ---- session/control state ----------------------------------------------
    private final Map<String, SessionControl> controlBySession = new LinkedHashMap<>();

    /**
     * The control stream baseline arrives before the {@code session/list} catalog baseline, so it
     * is held here and applied once the catalog exists; sessions missing from it are then treated
     * as empty, per the control contract.
     */
    private JsonObject pendingControlBaseline;

    // ---- follows -------------------------------------------------------------
    private final Map<String, Follow> follows = new LinkedHashMap<>();

    DshRemoteState() {}

    // ---------------------------------------------------------------------------
    // generation lifecycle
    // ---------------------------------------------------------------------------

    void beginGeneration(long generation, String phase) {
        this.generation = generation;
        this.phase = phase;
        this.message = null;
        catalogBySession.clear();
        controlBySession.clear();
        pendingControlBaseline = null;
        workspacesById.clear();
        archivedSessionIds.clear();
    }

    void setPhase(String phase, String message) {
        this.phase = phase;
        this.message = message;
    }

    // ---------------------------------------------------------------------------
    // session catalog
    // ---------------------------------------------------------------------------

    /** Apply the `session/list` baseline. Items are stored raw. */
    void applySessionList(JsonArray items) {
        catalogBySession.clear();
        if (items != null) {
            for (JsonElement candidate : items) {
                if (!candidate.isJsonObject()) continue;
                JsonObject item = candidate.getAsJsonObject();
                String id = string(item, "sessionId");
                if (id == null || id.isBlank()) continue;
                catalogBySession.put(id, item.deepCopy());
            }
        }
        if (pendingControlBaseline != null) {
            JsonObject baseline = pendingControlBaseline;
            pendingControlBaseline = null;
            applyControlFrame(baseline);
        }
    }

    /** Apply one `api-session/*` emit to the catalog. Returns true when a row changed. */
    boolean applyCatalogEmit(String event, JsonArray args) {
        switch (event) {
            case "api-session/added" -> {
                if (args.isEmpty() || !args.get(0).isJsonObject()) return false;
                JsonObject summary = args.get(0).getAsJsonObject();
                String id = string(summary, "sessionId");
                if (id == null || id.isBlank()) return false;
                JsonObject existing = catalogBySession.get(id);
                if (existing != null) {
                    // Merge: keep fields the summary omits (projections, agentPreset).
                    JsonObject merged = existing.deepCopy();
                    for (Map.Entry<String, String> property : entryStrings(summary)) {
                        if (!merged.has(property.getKey())) {
                            merged.addProperty(property.getKey(), property.getValue());
                        }
                    }
                    for (String flag : List.of("running", "blank")) {
                        if (summary.has(flag)) merged.add(flag, summary.get(flag).deepCopy());
                    }
                    catalogBySession.put(id, merged);
                } else {
                    catalogBySession.put(id, summary.deepCopy());
                }
                return true;
            }
            case "api-session/removed" -> {
                if (args.isEmpty() || !args.get(0).isJsonPrimitive()) return false;
                String id = args.get(0).getAsString();
                boolean removed = catalogBySession.remove(id) != null;
                removed |= controlBySession.remove(id) != null;
                return removed;
            }
            case "api-session/status" -> {
                if (args.size() < 2 || !args.get(0).isJsonPrimitive()) return false;
                JsonObject row = catalogBySession.get(args.get(0).getAsString());
                if (row == null) return false;
                JsonObject updated = row.deepCopy();
                updated.addProperty(
                        "running", args.get(1).isJsonPrimitive() && args.get(1).getAsBoolean());
                catalogBySession.put(string(updated, "sessionId"), updated);
                return true;
            }
            case "api-session/activity" -> {
                if (args.size() < 2 || !args.get(0).isJsonPrimitive()) return false;
                JsonObject row = catalogBySession.get(args.get(0).getAsString());
                if (row == null) return false;
                JsonObject updated = row.deepCopy();
                updated.addProperty("updatedAt", longValue(args.get(1), 0L));
                catalogBySession.put(string(updated, "sessionId"), updated);
                return true;
            }
            case "api-session/error" -> {
                if (args.size() < 2 || !args.get(0).isJsonPrimitive()) return false;
                JsonObject row = catalogBySession.get(args.get(0).getAsString());
                if (row == null) return false;
                JsonObject updated = row.deepCopy();
                updated.addProperty(
                        "lastError",
                        args.get(1).isJsonPrimitive() ? args.get(1).getAsString() : "");
                catalogBySession.put(string(updated, "sessionId"), updated);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private static List<Map.Entry<String, String>> entryStrings(JsonObject object) {
        List<Map.Entry<String, String>> entries = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (entry.getValue().isJsonPrimitive()
                    && entry.getValue().getAsJsonPrimitive().isString()) {
                entries.add(Map.entry(entry.getKey(), entry.getValue().getAsString()));
            }
        }
        return entries;
    }

    // ---------------------------------------------------------------------------
    // workspace/follow
    // ---------------------------------------------------------------------------

    void applyWorkspaceFrame(JsonObject frame) {
        String type = string(frame, "type");
        if (type == null) return;
        switch (type) {
            case "baseline" -> {
                JsonObject value =
                        frame.has("value") && frame.get("value").isJsonObject()
                                ? frame.getAsJsonObject("value")
                                : new JsonObject();
                workspacesById.clear();
                JsonArray items =
                        value.has("items") && value.get("items").isJsonArray()
                                ? value.getAsJsonArray("items")
                                : new JsonArray();
                for (JsonElement candidate : items) {
                    if (!candidate.isJsonObject()) continue;
                    JsonObject workspace = candidate.getAsJsonObject();
                    String id = string(workspace, "workspaceId");
                    if (id != null && !id.isBlank()) workspacesById.put(id, workspace.deepCopy());
                }
                Set<String> archived = new LinkedHashSet<>();
                JsonArray archivedIds =
                        value.has("archivedSessionIds")
                                        && value.get("archivedSessionIds").isJsonArray()
                                ? value.getAsJsonArray("archivedSessionIds")
                                : new JsonArray();
                for (JsonElement candidate : archivedIds) {
                    if (candidate.isJsonPrimitive()) archived.add(candidate.getAsString());
                }
                archivedSessionIds.clear();
                archivedSessionIds.addAll(archived);
            }
            case "upsert" -> {
                JsonObject workspace =
                        frame.has("workspace") && frame.get("workspace").isJsonObject()
                                ? frame.getAsJsonObject("workspace")
                                : null;
                String id = workspace == null ? null : string(workspace, "workspaceId");
                if (id != null && !id.isBlank()) workspacesById.put(id, workspace.deepCopy());
            }
            case "remove" -> {
                String id = string(frame, "workspaceId");
                if (id != null) workspacesById.remove(id);
            }
            case "order" -> {
                JsonArray order =
                        frame.has("workspaceIds") && frame.get("workspaceIds").isJsonArray()
                                ? frame.getAsJsonArray("workspaceIds")
                                : null;
                if (order == null) return;
                Map<String, JsonObject> ordered = new LinkedHashMap<>();
                for (JsonElement candidate : order) {
                    if (!candidate.isJsonPrimitive()) continue;
                    String id = candidate.getAsString();
                    JsonObject workspace = workspacesById.remove(id);
                    if (workspace != null) ordered.put(id, workspace);
                }
                ordered.putAll(workspacesById);
                workspacesById.clear();
                workspacesById.putAll(ordered);
            }
            case "archived" -> {
                JsonArray archivedIds =
                        frame.has("archivedSessionIds")
                                        && frame.get("archivedSessionIds").isJsonArray()
                                ? frame.getAsJsonArray("archivedSessionIds")
                                : null;
                if (archivedIds == null) return;
                archivedSessionIds.clear();
                for (JsonElement candidate : archivedIds) {
                    if (candidate.isJsonPrimitive())
                        archivedSessionIds.add(candidate.getAsString());
                }
            }
            default -> {
                // Unknown workspace frame types are ignored; the next baseline recalibrates.
            }
        }
    }

    // ---------------------------------------------------------------------------
    // session/control
    // ---------------------------------------------------------------------------

    void applyControlFrame(JsonObject frame) {
        String type = string(frame, "type");
        if (type == null) return;
        if ("baseline".equals(type)) {
            JsonObject value =
                    frame.has("value") && frame.get("value").isJsonObject()
                            ? frame.getAsJsonObject("value")
                            : new JsonObject();
            JsonObject queues =
                    value.has("queues") && value.get("queues").isJsonObject()
                            ? value.getAsJsonObject("queues")
                            : new JsonObject();
            JsonObject jobs =
                    value.has("jobs") && value.get("jobs").isJsonObject()
                            ? value.getAsJsonObject("jobs")
                            : new JsonObject();
            JsonObject projections =
                    value.has("projections") && value.get("projections").isJsonObject()
                            ? value.getAsJsonObject("projections")
                            : new JsonObject();
            controlBySession.clear();
            if (catalogBySession.isEmpty()) {
                // The catalog baseline has not arrived yet; hold the baseline and
                // apply it when session/list lands (applySessionList).
                pendingControlBaseline = frame.deepCopy();
                return;
            }
            pendingControlBaseline = null;
            for (String sessionId : catalogBySession.keySet()) {
                SessionControl control = controlFor(sessionId);
                control.replaceQueue(asArray(queues.get(sessionId)));
                control.replaceJobs(asArray(jobs.get(sessionId)));
                JsonElement block = projections.get(sessionId);
                if (block != null && block.isJsonObject())
                    seedProjection(sessionId, block.getAsJsonObject());
            }
            return;
        }
        String sessionId = string(frame, "sessionId");
        if (sessionId == null || sessionId.isBlank()) return;
        SessionControl control = controlFor(sessionId);
        switch (type) {
            case "queue" -> control.replaceQueue(asArray(frame.get("items")));
            case "jobs" -> control.replaceJobs(asArray(frame.get("jobs")));
            case "projection" -> {
                String key = string(frame, "key");
                if (key == null || key.isBlank() || !frame.has("seq")) return;
                control.applyProjection(
                        key, frame.get("value"), longValue(frame.get("seq"), Long.MIN_VALUE));
            }
            default -> {
                // Unknown control frame types are ignored; the next baseline recalibrates.
            }
        }
    }

    /** Merge a projections block ({asOfSeq, values}) into the session's cells. */
    void seedProjection(String sessionId, JsonObject block) {
        if (block == null) return;
        long asOfSeq = block.has("asOfSeq") ? longValue(block.get("asOfSeq"), -1L) : -1L;
        JsonObject values =
                block.has("values") && block.get("values").isJsonObject()
                        ? block.getAsJsonObject("values")
                        : new JsonObject();
        SessionControl control = controlFor(sessionId);
        control.seed(asOfSeq, values);
    }

    private SessionControl controlFor(String sessionId) {
        return controlBySession.computeIfAbsent(sessionId, ignored -> new SessionControl());
    }

    private static JsonArray asArray(JsonElement element) {
        return element != null && element.isJsonArray()
                ? element.getAsJsonArray().deepCopy()
                : new JsonArray();
    }

    // ---------------------------------------------------------------------------
    // session/follow
    // ---------------------------------------------------------------------------

    /** Canonical key for one session address. */
    static String addressKey(JsonObject address) {
        String kind = string(address, "kind");
        if ("subagent".equals(kind)) {
            return "subagent:"
                    + stringOr(address, "parentSessionId", "")
                    + ":"
                    + stringOr(address, "childSessionId", "")
                    + ":"
                    + stringOr(address, "mode", "");
        }
        return "session:" + stringOr(address, "sessionId", "");
    }

    void openFollow(JsonObject address, int maxMessages) {
        Follow follow = new Follow(address.deepCopy(), maxMessages);
        follows.put(addressKey(address), follow);
    }

    boolean hasFollow(String addressKey) {
        return follows.containsKey(addressKey);
    }

    void closeFollow(String addressKey) {
        follows.remove(addressKey);
    }

    Set<String> followedAddresses() {
        return Set.copyOf(follows.keySet());
    }

    /**
     * Apply one follow frame. Returns false when a sequence gap was detected and the stream must be
     * reopened for this address.
     */
    boolean applyFollowFrame(String addressKey, JsonObject frame) {
        Follow follow = follows.get(addressKey);
        if (follow == null) return true;
        String type = string(frame, "type");
        if ("snapshot".equals(type)) {
            if (!frame.has("cursor")) {
                return false;
            }
            long cursor = longValue(frame.get("cursor"), Long.MIN_VALUE);
            if (cursor < -1) return false;
            follow.replaceEvents(
                    asArray(frame.get("records")), cursor, bool(frame, "hasMore", false));
            JsonObject projections =
                    frame.has("projections") && frame.get("projections").isJsonObject()
                            ? frame.getAsJsonObject("projections")
                            : null;
            if (projections != null) {
                String sessionId = sessionIdOf(follow.address);
                if (sessionId != null) seedProjection(sessionId, projections);
            }
            return true;
        }
        if ("event".equals(type)) {
            JsonObject event =
                    frame.has("event") && frame.get("event").isJsonObject()
                            ? frame.getAsJsonObject("event")
                            : null;
            if (event == null || !event.has("seq")) return true;
            long seq = longValue(event.get("seq"), Long.MIN_VALUE);
            if (seq < 0) return true;
            if (seq <= follow.cursor) return true; // idempotent duplicate
            if (seq != follow.cursor + 1) return false; // gap: reopen
            follow.appendEvent(event.deepCopy());
            String sessionId = sessionIdOf(follow.address);
            if (sessionId != null) {
                JsonObject data =
                        event.has("data") && event.get("data").isJsonObject()
                                ? event.getAsJsonObject("data")
                                : null;
                JsonObject source =
                        data == null
                                ? null
                                : data.has("source") && data.get("source").isJsonObject()
                                        ? data.getAsJsonObject("source")
                                        : null;
                if (source != null) {
                    String rpcId = string(source, "rpcId");
                    if (rpcId != null) controlFor(sessionId).observeDurableRequest(rpcId);
                }
            }
            return true;
        }
        return true;
    }

    private static String sessionIdOf(JsonObject address) {
        return "session".equals(string(address, "kind")) ? string(address, "sessionId") : null;
    }

    /** True when the durable echo of one prompt requestId has been observed. */
    boolean hasDurableEcho(String sessionId, String requestId) {
        if (sessionId == null || requestId == null) return false;
        SessionControl control = controlBySession.get(sessionId);
        return control != null && control.durableRequestIds.contains(requestId);
    }

    // ---------------------------------------------------------------------------
    // interactions
    // ---------------------------------------------------------------------------

    /** Create one pending approval from an `approval/request` waterfall. */
    void requestApproval(String sessionId, String eventId, JsonObject request) {
        String toolName = string(request, "toolName");
        if (toolName == null) return;
        SessionControl control = controlFor(sessionId);
        JsonObject item = new JsonObject();
        item.addProperty("key", "a:" + eventId);
        item.addProperty("kind", "approval");
        item.addProperty("status", "pending");
        item.addProperty("approvalId", eventId);
        item.addProperty("toolName", toolName);
        copyIfPresent(request, item, "reason");
        copyIfPresent(request, item, "callId");
        control.putInteraction(item);
    }

    /** Create one pending question from a `user-questions/request` waterfall. */
    void requestQuestion(String sessionId, String eventId, JsonObject request) {
        if (!request.has("questions") || !request.get("questions").isJsonArray()) return;
        SessionControl control = controlFor(sessionId);
        JsonObject item = new JsonObject();
        item.addProperty("key", "q:" + eventId);
        item.addProperty("kind", "question");
        item.addProperty("status", "pending");
        item.add("questions", request.getAsJsonArray("questions").deepCopy());
        control.putInteraction(item);
    }

    /** Host withdrew one pending waterfall. */
    void cancelInteraction(String eventId) {
        for (SessionControl control : controlBySession.values()) {
            control.cancelInteraction(eventId);
        }
    }

    void setInteractionStatus(String sessionId, String key, String status, String error) {
        SessionControl control = controlBySession.get(sessionId);
        if (control != null) control.setInteractionStatus(key, status, error);
    }

    /** Remove one interaction after a successful answer. */
    void resolveInteraction(String sessionId, String key) {
        SessionControl control = controlBySession.get(sessionId);
        if (control != null) control.removeInteraction(key);
    }

    private static void copyIfPresent(JsonObject source, JsonObject target, String key) {
        if (source.has(key) && source.get(key).isJsonPrimitive()) {
            target.add(key, source.get(key).deepCopy());
        }
    }

    // ---------------------------------------------------------------------------
    // snapshot
    // ---------------------------------------------------------------------------

    /** One session's live view for the UI. */
    public static final class SessionView {
        public final JsonArray queue;
        public final JsonArray jobs;
        public final JsonArray interactions;
        public final Map<String, ProjectionCell> projections;
        public final Set<String> durableRequestIds;

        SessionView(
                JsonArray queue,
                JsonArray jobs,
                JsonArray interactions,
                Map<String, ProjectionCell> projections,
                Set<String> durableRequestIds) {
            this.queue = queue;
            this.jobs = jobs;
            this.interactions = interactions;
            this.projections = projections;
            this.durableRequestIds = durableRequestIds;
        }
    }

    /** One followed address's history view. */
    public static final class FollowView {
        public final JsonObject address;
        public final JsonArray events;
        public final long cursor;
        public final boolean hasMore;

        FollowView(JsonObject address, JsonArray events, long cursor, boolean hasMore) {
            this.address = address;
            this.events = events;
            this.cursor = cursor;
            this.hasMore = hasMore;
        }
    }

    public record ProjectionCell(JsonElement value, long seq) {}

    /** Immutable view handed to the facade listeners. */
    public static final class Snapshot {
        public final String phase;
        public final String message;
        public final long generation;
        public final JsonArray catalog;
        public final Map<String, SessionView> sessions;
        public final Map<String, FollowView> follows;
        public final List<JsonObject> workspaces;
        public final Set<String> archivedSessionIds;

        Snapshot(
                String phase,
                String message,
                long generation,
                JsonArray catalog,
                Map<String, SessionView> sessions,
                Map<String, FollowView> follows,
                List<JsonObject> workspaces,
                Set<String> archivedSessionIds) {
            this.phase = phase;
            this.message = message;
            this.generation = generation;
            this.catalog = catalog;
            this.sessions = sessions;
            this.follows = follows;
            this.workspaces = workspaces;
            this.archivedSessionIds = archivedSessionIds;
        }
    }

    Snapshot snapshot() {
        // Catalog rows normalized the way the webview expects them.
        List<JsonObject> rows = new ArrayList<>();
        Map<String, JsonObject> workspaceBySession = new HashMap<>();
        for (JsonObject workspace : workspacesById.values()) {
            JsonArray sessionIds =
                    workspace.has("sessionIds") && workspace.get("sessionIds").isJsonArray()
                            ? workspace.getAsJsonArray("sessionIds")
                            : new JsonArray();
            for (JsonElement candidate : sessionIds) {
                if (candidate.isJsonPrimitive())
                    workspaceBySession.put(candidate.getAsString(), workspace);
            }
        }

        Map<String, SessionView> sessions = new HashMap<>();
        for (Map.Entry<String, JsonObject> entry : catalogBySession.entrySet()) {
            String sessionId = entry.getKey();
            JsonObject raw = entry.getValue();
            SessionControl control = controlBySession.get(sessionId);
            Map<String, ProjectionCell> cells =
                    control == null ? Map.of() : Map.copyOf(control.projections);
            JsonObject row = new JsonObject();
            row.addProperty("sessionId", sessionId);
            String title = projectedTitle(sessionId, cells, raw);
            row.addProperty(
                    "title",
                    title == null || title.isBlank()
                            ? sessionId.substring(0, Math.min(12, sessionId.length()))
                            : title);
            row.addProperty("running", bool(raw, "running", false));
            row.addProperty("attention", hasPendingInteraction(control));
            row.addProperty("archived", archivedSessionIds.contains(sessionId));
            JsonObject workspace = workspaceBySession.get(sessionId);
            if (workspace != null) {
                copyIfPresent(workspace, row, "workspaceId");
                String workspaceTitle = string(workspace, "title");
                if (workspaceTitle != null) row.addProperty("workspaceTitle", workspaceTitle);
            }
            if (raw.has("blank")) row.add("blank", raw.get("blank").deepCopy());
            if (raw.has("agentPreset")) row.add("agentPreset", raw.get("agentPreset").deepCopy());
            JsonElement rawProjections = raw.get("projections");
            if (rawProjections != null && rawProjections.isJsonObject()) {
                JsonObject values = rawProjections.getAsJsonObject();
                JsonObject block =
                        values.has("values") && values.get("values").isJsonObject()
                                ? values.getAsJsonObject("values")
                                : null;
                if (block != null
                        && block.has("subagentTiming")
                        && block.get("subagentTiming").isJsonObject()) {
                    row.add("subagentTiming", block.getAsJsonObject("subagentTiming").deepCopy());
                }
            }
            String lastError = string(raw, "lastError");
            if (lastError != null && !lastError.isBlank()) row.addProperty("lastError", lastError);
            rows.add(row);
            sessions.put(
                    sessionId,
                    new SessionView(
                            control == null ? new JsonArray() : control.queueDock(),
                            control == null ? new JsonArray() : control.jobRows(sessionId),
                            control == null ? new JsonArray() : control.interactionRows(),
                            cells,
                            control == null ? Set.of() : Set.copyOf(control.durableRequestIds)));
        }
        rows.sort(
                Comparator.comparing((JsonObject value) -> bool(value, "running", false))
                        .reversed());
        JsonArray catalog = new JsonArray();
        for (JsonObject row : rows) catalog.add(row);

        Map<String, FollowView> followViews = new HashMap<>();
        for (Map.Entry<String, Follow> entry : follows.entrySet()) {
            Follow follow = entry.getValue();
            followViews.put(
                    entry.getKey(),
                    new FollowView(follow.address, follow.events, follow.cursor, follow.hasMore));
        }

        return new Snapshot(
                phase,
                message,
                generation,
                catalog,
                Map.copyOf(sessions),
                Map.copyOf(followViews),
                List.copyOf(workspacesById.values()),
                Set.copyOf(archivedSessionIds));
    }

    private static String projectedTitle(
            String sessionId, Map<String, ProjectionCell> cells, JsonObject raw) {
        ProjectionCell cell = cells.get("title");
        if (cell != null && cell.value() != null && cell.value().isJsonPrimitive()) {
            String title = cell.value().getAsString();
            if (!title.isBlank()) return title;
        }
        String title = string(raw, "title");
        if (title != null && !title.isBlank()) return title;
        JsonElement projections = raw.get("projections");
        if (projections != null && projections.isJsonObject()) {
            JsonObject values = projections.getAsJsonObject();
            JsonObject block =
                    values.has("values") && values.get("values").isJsonObject()
                            ? values.getAsJsonObject("values")
                            : null;
            String projected = block == null ? null : string(block, "title");
            if (projected != null && !projected.isBlank()) return projected;
        }
        return null;
    }

    private static boolean hasPendingInteraction(SessionControl control) {
        if (control == null) return false;
        for (JsonObject item : control.interactions.values()) {
            String status = string(item, "status");
            if ("pending".equals(status) || "submitting".equals(status)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------------------
    // per-session control containers
    // ---------------------------------------------------------------------------

    private static final class SessionControl {
        JsonArray queue = new JsonArray();
        JsonArray jobs = new JsonArray();
        final Map<String, ProjectionCell> projections = new LinkedHashMap<>();
        final Map<String, JsonObject> interactions = new LinkedHashMap<>();
        final Set<String> durableRequestIds = new LinkedHashSet<>();

        void replaceQueue(JsonArray items) {
            this.queue = queueDockItems(items);
        }

        void replaceJobs(JsonArray jobs) {
            this.jobs = jobCenterItems(jobs);
        }

        void applyProjection(String key, JsonElement value, long seq) {
            if (seq == Long.MIN_VALUE) return;
            ProjectionCell known = projections.get(key);
            if (known != null && known.seq() > seq) return;
            Map<String, ProjectionCell> updated = new LinkedHashMap<>(projections);
            updated.put(
                    key,
                    new ProjectionCell(value == null ? JsonNull.INSTANCE : value.deepCopy(), seq));
            projections.clear();
            projections.putAll(updated);
        }

        void seed(long asOfSeq, JsonObject values) {
            Map<String, ProjectionCell> updated = new LinkedHashMap<>(projections);
            // Drop local cells the baseline does not carry and that are older than it.
            updated.entrySet()
                    .removeIf(
                            entry ->
                                    entry.getValue().seq() < asOfSeq
                                            && !values.has(entry.getKey()));
            for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
                ProjectionCell known = updated.get(entry.getKey());
                if (known == null || known.seq() <= asOfSeq) {
                    updated.put(
                            entry.getKey(),
                            new ProjectionCell(entry.getValue().deepCopy(), asOfSeq));
                }
            }
            projections.clear();
            projections.putAll(updated);
        }

        void putInteraction(JsonObject item) {
            Map<String, JsonObject> updated = new LinkedHashMap<>(interactions);
            String key = string(item, "key");
            updated.put(key, item);
            interactions.clear();
            interactions.putAll(updated);
        }

        void setInteractionStatus(String key, String status, String error) {
            JsonObject item = interactions.get(key);
            if (item == null) return;
            JsonObject updated = item.deepCopy();
            updated.addProperty("status", status);
            if (error == null) updated.remove("error");
            else updated.addProperty("error", error);
            Map<String, JsonObject> replacement = new LinkedHashMap<>(interactions);
            replacement.put(key, updated);
            interactions.clear();
            interactions.putAll(replacement);
        }

        void removeInteraction(String key) {
            if (!interactions.containsKey(key)) return;
            Map<String, JsonObject> updated = new LinkedHashMap<>(interactions);
            updated.remove(key);
            interactions.clear();
            interactions.putAll(updated);
        }

        void cancelInteraction(String eventId) {
            List<String> hits = new ArrayList<>();
            for (Map.Entry<String, JsonObject> entry : interactions.entrySet()) {
                if (eventId.equals(string(entry.getValue(), "approvalId"))
                        || entry.getKey().equals("q:" + eventId)
                        || entry.getKey().equals("a:" + eventId)) {
                    hits.add(entry.getKey());
                }
            }
            if (hits.isEmpty()) return;
            Map<String, JsonObject> updated = new LinkedHashMap<>(interactions);
            for (String key : hits) {
                JsonObject item = updated.get(key).deepCopy();
                item.addProperty("status", "unavailable");
                item.addProperty("error", "The request is no longer waiting for an answer.");
                updated.put(key, item);
            }
            interactions.clear();
            interactions.putAll(updated);
        }

        void observeDurableRequest(String requestId) {
            durableRequestIds.add(requestId);
            if (durableRequestIds.size() > 256) {
                String oldest = durableRequestIds.iterator().next();
                durableRequestIds.remove(oldest);
            }
        }

        JsonArray queueDock() {
            return queue;
        }

        JsonArray jobRows(String ownerSessionId) {
            JsonArray result = new JsonArray();
            for (JsonElement candidate : jobs) {
                JsonObject row = candidate.getAsJsonObject();
                JsonObject copy = row.deepCopy();
                if (!copy.has("ownerSessionId")) copy.addProperty("ownerSessionId", ownerSessionId);
                result.add(copy);
            }
            return result;
        }

        JsonArray interactionRows() {
            JsonArray result = new JsonArray();
            for (JsonObject item : interactions.values()) result.add(item);
            return result;
        }
    }

    // ---------------------------------------------------------------------------
    // wire-shape normalizers (moved from the legacy session state store)
    // ---------------------------------------------------------------------------

    private static final class Follow {
        final JsonObject address;
        final int maxMessages;
        JsonArray events = new JsonArray();
        long cursor = -1;
        boolean hasMore;

        Follow(JsonObject address, int maxMessages) {
            this.address = address;
            this.maxMessages = maxMessages;
        }

        void replaceEvents(JsonArray records, long cursor, boolean hasMore) {
            JsonArray replaced = records.deepCopy();
            trimHead(replaced);
            this.events = replaced;
            this.cursor = cursor;
            this.hasMore = hasMore;
        }

        void appendEvent(JsonObject event) {
            JsonArray appended = events.deepCopy();
            appended.add(event);
            trimHead(appended);
            this.events = appended;
            this.cursor = longValue(event.get("seq"), cursor);
        }

        private void trimHead(JsonArray target) {
            while (target.size() > MAX_FOLLOW_EVENTS) target.remove(0);
        }
    }

    static JsonArray queueDockItems(JsonElement items) {
        JsonArray result = new JsonArray();
        if (items == null || !items.isJsonArray()) return result;
        for (JsonElement candidate : items.getAsJsonArray()) {
            if (!candidate.isJsonObject()) continue;
            JsonObject item = candidate.getAsJsonObject();
            String id = string(item, "id");
            String placement = string(item, "placement");
            if (id == null || id.isBlank()) continue;
            if (!"queued".equals(placement) && !"steering".equals(placement)) continue;
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
                String blockType = string(block, "type");
                String text = string(block, "text");
                if ("text".equals(blockType) && text != null) {
                    if (flat.length() > 0) flat.append(' ');
                    flat.append(text);
                    editable.append(text);
                } else {
                    textOnly = false;
                    if (flat.length() > 0) flat.append(' ');
                    flat.append('[').append(blockType == null ? "content" : blockType).append(']');
                }
            }
            JsonObject row = new JsonObject();
            row.addProperty("id", id);
            row.addProperty("placement", placement);
            row.addProperty(
                    "preview", clampPreview(flat.toString().replaceAll("\\s+", " ").trim()));
            // A queued item carrying the prompt's client requestId retires the
            // matching optimistic submission echo.
            String rpcId = string(item, "rpcId");
            if (rpcId != null && !rpcId.isBlank()) row.addProperty("rpcId", rpcId);
            if (textOnly) row.addProperty("editableText", editable.toString());
            result.add(row);
        }
        return result;
    }

    private static String clampPreview(String preview) {
        int[] points = preview.codePoints().toArray();
        if (points.length <= QUEUE_PREVIEW_CHARS) return preview;
        return new String(points, 0, QUEUE_PREVIEW_CHARS) + "\u2026";
    }

    static JsonArray jobCenterItems(JsonElement jobs) {
        JsonArray result = new JsonArray();
        if (jobs == null || !jobs.isJsonArray()) return result;
        for (JsonElement candidate : jobs.getAsJsonArray()) {
            if (!candidate.isJsonObject()) continue;
            JsonObject job = candidate.getAsJsonObject();
            String id = string(job, "id");
            String kind = string(job, "kind");
            String label = string(job, "label");
            String status = string(job, "status");
            if (id == null || kind == null || label == null || status == null) continue;
            if (!"running".equals(status)
                    && !"stopping".equals(status)
                    && !"completed".equals(status)
                    && !"killed".equals(status)
                    && !"failed".equals(status)) {
                continue;
            }
            if (!job.has("startedAt") || !job.get("startedAt").isJsonPrimitive()) continue;
            long startedAt = longValue(job.get("startedAt"), Long.MIN_VALUE);
            if (startedAt == Long.MIN_VALUE) continue;
            JsonObject row = new JsonObject();
            row.addProperty("id", id);
            row.addProperty("kind", kind);
            row.addProperty("label", label);
            row.addProperty("status", status);
            String detail = string(job, "detail");
            if (detail != null) row.addProperty("outputSummary", detail);
            row.addProperty("startedAt", startedAt);
            if (job.has("finishedAt") && job.get("finishedAt").isJsonPrimitive()) {
                long finishedAt = longValue(job.get("finishedAt"), Long.MIN_VALUE);
                if (finishedAt != Long.MIN_VALUE) row.addProperty("finishedAt", finishedAt);
            }
            result.add(row);
        }
        return result;
    }
}
