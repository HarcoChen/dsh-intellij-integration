package top.harcochen.dsh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import top.harcochen.dsh.remote.DshRemoteContracts;
import top.harcochen.dsh.remote.DshRemoteService;

/** Session-scoped RC.2 job roster and non-consuming, resumable output tails. */
final class DshJobsController implements AutoCloseable {
    private final DshRemoteService remote;
    private final Runnable changed;
    private final Map<String, JsonObject> jobs = new LinkedHashMap<>();
    private final Map<String, AutoCloseable> follows = new LinkedHashMap<>();
    private final Map<String, Long> cursors = new LinkedHashMap<>();
    private final Set<String> finished = new HashSet<>();
    private AutoCloseable roster;
    private String session;
    private long epoch;
    private boolean ready;

    DshJobsController(DshRemoteService remote, Runnable changed) {
        this.remote = remote;
        this.changed = changed;
    }

    synchronized void select(String sessionId) {
        if (java.util.Objects.equals(session, sessionId)) return;
        close();
        session = sessionId;
        if (sessionId == null) return;
        long token = epoch;
        roster =
                remote.watchFeature(
                        "job/list",
                        () -> request(sessionId, null, null),
                        frame -> {
                            synchronized (this) {
                                if (token != epoch) return;
                                try {
                                    if (!"rows".equals(DshJson.string(frame, "type"))
                                            || !frame.has("jobs")
                                            || !frame.get("jobs").isJsonArray()
                                            || frame.getAsJsonArray("jobs").size() > 1000)
                                        throw new IllegalArgumentException("Invalid job roster");
                                    Map<String, JsonObject> next = new LinkedHashMap<>();
                                    for (JsonElement candidate : frame.getAsJsonArray("jobs")) {
                                        JsonObject row = normalize(candidate);
                                        String id = DshJson.string(row, "id");
                                        if (next.containsKey(id))
                                            throw new IllegalArgumentException("Duplicate job");
                                        JsonObject old = jobs.get(id);
                                        if (old != null)
                                            for (String key :
                                                    new String[] {
                                                        "outputText",
                                                        "outputGap",
                                                        "streaming",
                                                        "streamError"
                                                    }) {
                                                if (old.has(key))
                                                    row.add(key, old.get(key).deepCopy());
                                            }
                                        next.put(id, row);
                                    }
                                    for (String id : new HashSet<>(jobs.keySet()))
                                        if (!next.containsKey(id)) {
                                            release(follows.remove(id));
                                            cursors.remove(id);
                                            finished.remove(id);
                                        }
                                    jobs.clear();
                                    jobs.putAll(next);
                                    ready = true;
                                    for (String id : jobs.keySet())
                                        if (!follows.containsKey(id) && !finished.contains(id))
                                            follow(sessionId, id, token);
                                } catch (RuntimeException invalid) {
                                    jobs.clear();
                                    ready = true;
                                }
                                changed.run();
                            }
                        },
                        error -> {
                            synchronized (this) {
                                if (token != epoch || error == null) return;
                                if (!"carrier/disconnected".equals(DshJson.string(error, "code"))) {
                                    ready = false;
                                    for (AutoCloseable watch : follows.values()) release(watch);
                                    follows.clear();
                                }
                                changed.run();
                            }
                        });
    }

    private void follow(String sessionId, String id, long token) {
        boolean[] opened = {false};
        follows.put(
                id,
                remote.watchFeature(
                        "job/follow",
                        () -> {
                            synchronized (this) {
                                opened[0] = false;
                                return request(sessionId, id, cursors.get(id));
                            }
                        },
                        frame -> {
                            synchronized (this) {
                                if (token != epoch || !jobs.containsKey(id)) return;
                                JsonObject row = jobs.get(id).deepCopy();
                                try {
                                    switch (DshJson.stringOr(frame, "type", "")) {
                                        case "opened" -> {
                                            JsonObject anchor = normalize(frame.get("job"));
                                            long from = natural(frame.get("from"));
                                            if (opened[0]
                                                    || !id.equals(DshJson.string(anchor, "id")))
                                                throw new IllegalArgumentException(
                                                        "Invalid job anchor");
                                            opened[0] = true;
                                            cursors.put(id, from);
                                            row.addProperty(
                                                    "outputGap",
                                                    DshJson.bool(row, "outputGap", false)
                                                            || (!row.has("outputText")
                                                                    && from > 0));
                                            row.addProperty("streaming", true);
                                            row.remove("streamError");
                                        }
                                        case "output" -> {
                                            long next = natural(frame.get("next"));
                                            if (!opened[0]
                                                    || next < cursors.getOrDefault(id, 0L)
                                                    || !frame.has("chunks")
                                                    || !frame.get("chunks").isJsonArray())
                                                throw new IllegalArgumentException(
                                                        "Invalid job output");
                                            StringBuilder text =
                                                    new StringBuilder(
                                                            DshJson.stringOr(
                                                                    row, "outputText", ""));
                                            boolean gap =
                                                    DshJson.bool(row, "outputGap", false)
                                                            || DshJson.bool(frame, "lossy", false);
                                            for (JsonElement item :
                                                    frame.getAsJsonArray("chunks")) {
                                                if (!item.isJsonObject())
                                                    throw new IllegalArgumentException(
                                                            "Invalid job chunk");
                                                JsonObject chunk = item.getAsJsonObject();
                                                natural(chunk.get("at"));
                                                String content = DshJson.string(chunk, "text");
                                                if (content == null)
                                                    throw new IllegalArgumentException(
                                                            "Invalid job text");
                                                text.append(content);
                                                gap |= DshJson.bool(chunk, "gapBefore", false);
                                            }
                                            if (text.length() > 128 * 1024) {
                                                int cut = text.length() - 128 * 1024;
                                                if (Character.isLowSurrogate(text.charAt(cut)))
                                                    cut++;
                                                text.delete(0, cut);
                                                gap = true;
                                            }
                                            cursors.put(id, next);
                                            row.addProperty("outputText", text.toString());
                                            row.addProperty("outputGap", gap);
                                            row.addProperty("streaming", true);
                                            row.remove("streamError");
                                        }
                                        case "status" -> {
                                            JsonObject settled = normalize(frame.get("job"));
                                            if (!opened[0]
                                                    || !id.equals(DshJson.string(settled, "id"))
                                                    || DshJson.bool(settled, "canKill", false)
                                                    || "stopping"
                                                            .equals(
                                                                    DshJson.string(
                                                                            settled, "status")))
                                                throw new IllegalArgumentException(
                                                        "Invalid job settlement");
                                            for (var entry : settled.entrySet())
                                                row.add(entry.getKey(), entry.getValue());
                                            row.addProperty("streaming", false);
                                            finished.add(id);
                                            release(follows.remove(id));
                                        }
                                        default ->
                                                throw new IllegalArgumentException(
                                                        "Unknown job frame");
                                    }
                                } catch (RuntimeException invalid) {
                                    row.addProperty("streamError", invalid.getMessage());
                                    row.addProperty("streaming", false);
                                    finished.add(id);
                                    release(follows.remove(id));
                                }
                                jobs.put(id, row);
                                changed.run();
                            }
                        },
                        error -> {
                            synchronized (this) {
                                if (token != epoch
                                        || !jobs.containsKey(id)
                                        || finished.contains(id)) return;
                                JsonObject row = jobs.get(id).deepCopy();
                                row.addProperty("streaming", false);
                                if (error != null)
                                    row.addProperty(
                                            "streamError",
                                            DshJson.stringOr(
                                                    error, "message", "Output unavailable"));
                                if (error == null
                                        || !"carrier/disconnected"
                                                .equals(DshJson.string(error, "code"))) {
                                    finished.add(id);
                                    release(follows.remove(id));
                                }
                                jobs.put(id, row);
                                changed.run();
                            }
                        }));
    }

    synchronized JsonArray view(String sessionId) {
        if (!ready || !java.util.Objects.equals(session, sessionId)) return null;
        JsonArray result = new JsonArray();
        jobs.values().forEach(row -> result.add(row.deepCopy()));
        return result;
    }

    void kill(String sessionId, String id, ExecutorService operations, Consumer<String> notify) {
        synchronized (this) {
            JsonObject row = jobs.get(id);
            if (!java.util.Objects.equals(sessionId, session)
                    || row == null
                    || !DshJson.bool(row, "canKill", false)) return;
            row = row.deepCopy();
            row.addProperty("canKill", false);
            jobs.put(id, row);
            changed.run();
        }
        operations.execute(
                () -> {
                    try {
                        JsonElement result =
                                remote.callFeature("job/kill", request(sessionId, id, null));
                        if (!result.isJsonObject()
                                || !Set.of("requested", "already-finished")
                                        .contains(
                                                DshJson.stringOr(
                                                        result.getAsJsonObject(), "outcome", "")))
                            throw new IllegalArgumentException("Invalid job/kill result");
                    } catch (Exception error) {
                        notify.accept(DshJson.message(error));
                        synchronized (this) {
                            JsonObject row = jobs.get(id);
                            if (java.util.Objects.equals(sessionId, session)
                                    && row != null
                                    && "running".equals(DshJson.string(row, "status"))) {
                                row = row.deepCopy();
                                row.addProperty("canKill", true);
                                jobs.put(id, row);
                                changed.run();
                            }
                        }
                    }
                });
    }

    private static JsonObject normalize(JsonElement value) {
        if (value == null || !value.isJsonObject())
            throw new IllegalArgumentException("Invalid job");
        JsonObject raw = value.getAsJsonObject();
        for (String key : new String[] {"id", "kind", "label"})
            if (DshJson.string(raw, key) == null)
                throw new IllegalArgumentException("Invalid job identity");
        String status = DshJson.stringOr(raw, "status", "");
        if (!Set.of("running", "stopping", "completed", "killed", "failed").contains(status)
                || !raw.has("startedAt")) throw new IllegalArgumentException("Invalid job status");
        natural(raw.get("startedAt"));
        if (!raw.has("output") || !raw.get("output").isJsonObject())
            throw new IllegalArgumentException("Invalid job ring");
        JsonObject output = raw.getAsJsonObject("output");
        if (natural(output.get("earliest")) > natural(output.get("total")))
            throw new IllegalArgumentException("Invalid ring offsets");
        JsonObject row = raw.deepCopy();
        row.addProperty("ownerSessionId", DshJson.stringOr(raw, "owner", ""));
        if (raw.has("detail")) row.add("outputSummary", raw.get("detail"));
        row.addProperty("canKill", "running".equals(status));
        return row;
    }

    static long natural(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Expected natural number");
        double number = value.getAsDouble();
        if (!Double.isFinite(number)
                || number < 0
                || number > 9_007_199_254_740_991L
                || number != Math.floor(number))
            throw new IllegalArgumentException("Expected safe natural number");
        return value.getAsLong();
    }

    private static JsonObject request(String session, String id, Long from) {
        JsonObject request = new JsonObject();
        request.addProperty("sessionId", session);
        if (id != null) request.addProperty("jobId", id);
        if (from != null) request.addProperty("from", from);
        return DshRemoteContracts.withRequest(request);
    }

    static void release(AutoCloseable handle) {
        if (handle != null)
            try {
                handle.close();
            } catch (Exception ignored) {
            }
    }

    @Override
    public synchronized void close() {
        epoch++;
        release(roster);
        roster = null;
        for (AutoCloseable watch : follows.values()) release(watch);
        follows.clear();
        jobs.clear();
        cursors.clear();
        finished.clear();
        ready = false;
        session = null;
    }
}
