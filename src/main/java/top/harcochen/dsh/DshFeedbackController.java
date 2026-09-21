package top.harcochen.dsh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;
import top.harcochen.dsh.remote.DshRemoteException;
import top.harcochen.dsh.remote.DshRemoteService;

/**
 * Owns the optional message/session feedback sidecar.
 *
 * <p>Feedback is deliberately kept out of the session history projection. The Runtime persists it
 * under its own Remote namespace and returns a version token for compare-and-swap mutations; this
 * controller serializes mutations per session and exposes only bounded, validated view data to the
 * WebView.
 */
final class DshFeedbackController {
    private static final int MAX_ITEMS = 20_000;
    private static final int MAX_TEXT = 32_768;
    private static final Set<String> RATINGS = Set.of("positive", "negative");
    private static final Set<String> CATEGORIES =
            Set.of(
                    "task-result",
                    "instruction-following",
                    "product-interaction",
                    "service-stability",
                    "resource-cost",
                    "security-privacy-permission",
                    "other");

    private final DshRemoteService remote;
    private final ExecutorService operations;
    private final Supplier<String> currentSession;
    private final Runnable stateChanged;
    private final Consumer<String> notifier;
    private final Consumer<String> errorSink;
    private final Object lock = new Object();
    private final Map<String, SessionState> states = new HashMap<>();
    private final Map<String, CompletableFuture<Void>> tails = new HashMap<>();
    private final Map<String, Set<String>> assistantMessages = new HashMap<>();

    DshFeedbackController(
            DshRemoteService remote,
            ExecutorService operations,
            Supplier<String> currentSession,
            Runnable stateChanged,
            Consumer<String> notifier,
            Consumer<String> errorSink) {
        this.remote = remote;
        this.operations = operations;
        this.currentSession = currentSession;
        this.stateChanged = stateChanged;
        this.notifier = notifier;
        this.errorSink = errorSink;
    }

    /** Start one optional sidecar read. Older Runtimes simply mark the feature unavailable. */
    void refresh(String session, boolean force) {
        if (session == null || session.isBlank()) return;
        synchronized (lock) {
            SessionState existing = states.get(session);
            if (!force && existing != null && existing.status != Status.LOADING) return;
            if (existing != null && existing.status == Status.LOADING) return;
            SessionState next = existing == null ? new SessionState() : existing;
            next.status = Status.LOADING;
            next.error = null;
            states.put(session, next);
        }
        repaintIfCurrent(session);
        operations.execute(() -> load(session));
    }

    private void load(String session) {
        try {
            JsonObject result = remote.listMessageFeedback(session);
            SessionState state = state(session);
            if (!DshJson.bool(result, "ok", false)) {
                state.status = Status.ERROR;
                state.error = feedbackError(result);
                repaintIfCurrent(session);
                return;
            }
            JsonObject value = object(result, "value");
            JsonArray items = value == null ? null : array(value, "items");
            if (items == null || items.size() > MAX_ITEMS) {
                throw new IllegalStateException("Runtime returned an invalid feedback list");
            }
            synchronized (lock) {
                state.items.clear();
                for (JsonElement candidate : items) {
                    JsonObject item = validItem(candidate);
                    if (item != null) state.items.put(DshJson.string(item, "messageId"), item);
                }
                state.status = Status.READY;
                state.error = null;
            }
        } catch (DshRemoteException error) {
            synchronized (lock) {
                SessionState state = state(session);
                state.status = error.isCapabilityMissing() ? Status.UNAVAILABLE : Status.ERROR;
                state.error = error.isCapabilityMissing() ? null : error.display();
            }
            if (!error.isCapabilityMissing()) errorSink.accept(error.display());
        } catch (Exception error) {
            synchronized (lock) {
                SessionState state = state(session);
                state.status = Status.ERROR;
                state.error = DshJson.message(error);
            }
            errorSink.accept(DshJson.message(error));
        }
        repaintIfCurrent(session);
    }

    JsonObject view(String session) {
        if (session == null || session.isBlank()) return null;
        SessionState state;
        synchronized (lock) {
            state = states.get(session);
            if (state == null || state.status == Status.UNAVAILABLE) return null;
            JsonObject result = new JsonObject();
            result.addProperty("status", state.status.wire);
            JsonObject items = new JsonObject();
            for (Map.Entry<String, JsonObject> entry : state.items.entrySet()) {
                items.add(entry.getKey(), entry.getValue().deepCopy());
            }
            JsonObject pending = new JsonObject();
            for (String id : state.pending) pending.addProperty(id, true);
            JsonObject errors = new JsonObject();
            for (Map.Entry<String, String> entry : state.errors.entrySet())
                errors.addProperty(entry.getKey(), entry.getValue());
            result.add("items", items);
            result.add("pending", pending);
            result.add("errors", errors);
            if (state.error != null) result.addProperty("error", state.error);
            return result;
        }
    }

    JsonObject sessionView(String session) {
        if (session == null || session.isBlank()) return null;
        synchronized (lock) {
            SessionState state = states.get(session);
            if (state == null) return null;
            JsonObject result = new JsonObject();
            result.addProperty("open", state.sessionOpen);
            result.addProperty("status", state.sessionStatus);
            result.addProperty("sequence", state.sessionSequence);
            if (state.sessionError != null) result.addProperty("error", state.sessionError);
            return result;
        }
    }

    /** Add durable ids and sidecar state to finalized assistant rows. */
    JsonArray decorate(String session, JsonArray messages) {
        JsonArray result = new JsonArray();
        Set<String> ids = new HashSet<>();
        SessionState state;
        synchronized (lock) {
            state = session == null ? null : states.get(session);
        }
        for (JsonElement candidate : messages) {
            if (!candidate.isJsonObject()) continue;
            JsonObject row = candidate.getAsJsonObject().deepCopy();
            String id = DshJson.string(row, "messageId");
            if ("assistant".equals(DshJson.string(row, "role"))
                    && "committed".equals(DshJson.string(row, "state"))
                    && id != null
                    && !id.isBlank()) {
                ids.add(id);
                JsonObject feedback = new JsonObject();
                if (state == null || state.status == Status.UNAVAILABLE) {
                    // The durable id is still useful when a newer Runtime is connected later.
                } else {
                    feedback.addProperty("status", state.status.wire);
                    JsonObject item = state.items.get(id);
                    if (item != null) {
                        copy(item, feedback, "rating");
                        copy(item, feedback, "note");
                        copy(item, feedback, "category");
                    }
                    if (state.pending.contains(id)) feedback.addProperty("pending", true);
                    String error = state.errors.get(id);
                    if (error != null) feedback.addProperty("error", error);
                    row.add("feedback", feedback);
                }
            }
            result.add(row);
        }
        if (session != null) {
            synchronized (lock) {
                assistantMessages.put(session, ids);
            }
        }
        return result;
    }

    void toggle(String messageId, String rating) {
        String session = currentSession.get();
        if (!validTarget(session, messageId) || !RATINGS.contains(rating)) return;
        SessionState state = state(session);
        JsonObject existing = state.items.get(messageId);
        if (existing != null && rating.equals(DshJson.string(existing, "rating"))) {
            enqueue(
                    session,
                    messageId,
                    () -> applyDelete(session, messageId, DshJson.string(existing, "version")));
        } else {
            enqueue(
                    session,
                    messageId,
                    () ->
                            applyPut(
                                    session,
                                    messageId,
                                    rating,
                                    existing == null ? null : DshJson.string(existing, "note"),
                                    existing == null ? null : DshJson.string(existing, "category"),
                                    existing == null ? null : DshJson.string(existing, "version")));
        }
    }

    void submit(String messageId, String rating, String note, String category) {
        String session = currentSession.get();
        if (!validTarget(session, messageId) || !RATINGS.contains(rating)) return;
        String normalizedCategory = validCategory(category) ? category : null;
        String normalizedNote = normalizeText(note);
        SessionState state = state(session);
        JsonObject existing = state.items.get(messageId);
        enqueue(
                session,
                messageId,
                () ->
                        applyPut(
                                session,
                                messageId,
                                rating,
                                normalizedNote,
                                normalizedCategory,
                                existing == null ? null : DshJson.string(existing, "version")));
    }

    void saveNote(String messageId, String note) {
        String session = currentSession.get();
        if (!validTarget(session, messageId)) return;
        SessionState state = state(session);
        JsonObject existing = state.items.get(messageId);
        if (existing == null) return;
        enqueue(
                session,
                messageId,
                () ->
                        applyPut(
                                session,
                                messageId,
                                DshJson.string(existing, "rating"),
                                normalizeText(note),
                                DshJson.string(existing, "category"),
                                DshJson.string(existing, "version")));
    }

    void openSessionFeedback() {
        String session = currentSession.get();
        if (session == null || session.isBlank()) return;
        synchronized (lock) {
            SessionState state = state(session);
            state.sessionOpen = true;
            state.sessionStatus = "idle";
            state.sessionError = null;
            state.sessionSequence++;
        }
        repaintIfCurrent(session);
    }

    void dismissSessionFeedback() {
        String session = currentSession.get();
        if (session == null || session.isBlank()) return;
        synchronized (lock) {
            SessionState state = state(session);
            if (!"submitting".equals(state.sessionStatus)) state.sessionOpen = false;
        }
        repaintIfCurrent(session);
    }

    void recordSessionFeedback(String text, String category) {
        String session = currentSession.get();
        if (session == null || session.isBlank()) return;
        String normalized = normalizeText(text);
        String normalizedCategory = validCategory(category) ? category : null;
        synchronized (lock) {
            SessionState state = state(session);
            state.sessionOpen = true;
            state.sessionStatus = "submitting";
            state.sessionError = null;
        }
        repaintIfCurrent(session);
        operations.execute(
                () -> {
                    try {
                        JsonObject result =
                                remote.recordSessionFeedback(
                                        session, normalized, normalizedCategory);
                        synchronized (lock) {
                            SessionState state = state(session);
                            if (DshJson.bool(result, "ok", false)) {
                                state.sessionOpen = false;
                                state.sessionStatus = "success";
                                state.sessionError = null;
                            } else {
                                state.sessionStatus = "error";
                                state.sessionError = feedbackError(result);
                            }
                        }
                    } catch (DshRemoteException error) {
                        synchronized (lock) {
                            SessionState state = state(session);
                            state.sessionStatus =
                                    error.isCapabilityMissing() ? "unavailable" : "error";
                            state.sessionError =
                                    error.isCapabilityMissing()
                                            ? DshBundle.message("dsh.feedback.unavailable")
                                            : error.display();
                            if (error.isCapabilityMissing()) state.sessionOpen = false;
                        }
                    } catch (Exception error) {
                        synchronized (lock) {
                            SessionState state = state(session);
                            state.sessionStatus = "error";
                            state.sessionError = DshJson.message(error);
                        }
                    }
                    repaintIfCurrent(session);
                });
    }

    void prune(Set<String> liveSessions) {
        synchronized (lock) {
            states.keySet().retainAll(liveSessions);
            assistantMessages.keySet().retainAll(liveSessions);
            tails.keySet().retainAll(liveSessions);
        }
    }

    void dispose() {
        synchronized (lock) {
            tails.clear();
            states.clear();
            assistantMessages.clear();
        }
    }

    private void enqueue(String session, String messageId, Runnable operation) {
        SessionState state = state(session);
        synchronized (lock) {
            state.pending.add(messageId);
            state.errors.remove(messageId);
        }
        repaintIfCurrent(session);
        CompletableFuture<Void> previous;
        synchronized (lock) {
            previous = tails.getOrDefault(session, CompletableFuture.completedFuture(null));
            CompletableFuture<Void> next =
                    previous.handle((ignored, error) -> null).thenRunAsync(operation, operations);
            tails.put(session, next);
            next.whenComplete(
                    (ignored, error) -> {
                        synchronized (lock) {
                            state.pending.remove(messageId);
                            if (error != null) state.errors.put(messageId, DshJson.message(error));
                            if (tails.get(session) == next) tails.remove(session);
                        }
                        repaintIfCurrent(session);
                    });
        }
    }

    private void applyPut(
            String session,
            String messageId,
            String rating,
            String note,
            String category,
            String ifVersion) {
        JsonObject result =
                remote.putMessageFeedback(session, messageId, rating, note, category, ifVersion);
        SessionState state = state(session);
        if (DshJson.bool(result, "ok", false)) {
            JsonObject item = validItem(object(result, "value"));
            if (item == null) throw new IllegalStateException("Runtime returned invalid feedback");
            synchronized (lock) {
                state.items.put(messageId, item);
                state.status = Status.READY;
                state.error = null;
            }
            return;
        }
        JsonObject error = object(result, "error");
        reconcileCurrent(state, messageId, error);
        throw new IllegalStateException(feedbackError(result));
    }

    private void applyDelete(String session, String messageId, String ifVersion) {
        if (ifVersion == null || ifVersion.isBlank()) return;
        JsonObject result = remote.deleteMessageFeedback(session, messageId, ifVersion);
        SessionState state = state(session);
        if (DshJson.bool(result, "ok", false)) {
            synchronized (lock) {
                state.items.remove(messageId);
                state.status = Status.READY;
            }
            return;
        }
        JsonObject error = object(result, "error");
        reconcileCurrent(state, messageId, error);
        throw new IllegalStateException(feedbackError(result));
    }

    private void reconcileCurrent(SessionState state, String messageId, JsonObject error) {
        JsonObject current = error == null ? null : object(error, "current");
        synchronized (lock) {
            if (current == null || current.isJsonNull()) state.items.remove(messageId);
            else {
                JsonObject item = validItem(current);
                if (item != null) state.items.put(messageId, item);
            }
        }
    }

    private boolean validTarget(String session, String messageId) {
        if (session == null || session.isBlank() || messageId == null || messageId.isBlank())
            return false;
        synchronized (lock) {
            Set<String> ids = assistantMessages.get(session);
            return ids != null && ids.contains(messageId);
        }
    }

    private SessionState state(String session) {
        synchronized (lock) {
            return states.computeIfAbsent(session, ignored -> new SessionState());
        }
    }

    private void repaintIfCurrent(String session) {
        if (session.equals(currentSession.get())) stateChanged.run();
    }

    private static String normalizeText(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty()
                ? null
                : trimmed.substring(0, Math.min(MAX_TEXT, trimmed.length()));
    }

    private static boolean validCategory(String category) {
        return category != null && CATEGORIES.contains(category);
    }

    private static JsonObject validItem(JsonElement value) {
        if (value == null || !value.isJsonObject()) return null;
        JsonObject item = value.getAsJsonObject();
        String messageId = DshJson.string(item, "messageId");
        String rating = DshJson.string(item, "rating");
        String version = DshJson.string(item, "version");
        if (messageId == null
                || messageId.isBlank()
                || messageId.length() > 512
                || !RATINGS.contains(rating)
                || version == null
                || version.isBlank()) return null;
        JsonObject result = new JsonObject();
        result.addProperty("messageId", messageId);
        result.addProperty("rating", rating);
        if (DshJson.string(item, "note") != null)
            result.addProperty("note", DshJson.string(item, "note"));
        if (validCategory(DshJson.string(item, "category")))
            result.addProperty("category", DshJson.string(item, "category"));
        result.addProperty("version", version);
        if (item.has("createdAt")) result.add("createdAt", item.get("createdAt").deepCopy());
        if (item.has("updatedAt")) result.add("updatedAt", item.get("updatedAt").deepCopy());
        return result;
    }

    private static String feedbackError(JsonObject result) {
        JsonObject error = object(result, "error");
        String code = error == null ? null : DshJson.string(error, "code");
        return switch (code == null ? "" : code) {
            case "session-not-found" -> DshBundle.message("dsh.feedback.session.missing");
            case "target-not-found" -> DshBundle.message("dsh.feedback.message.missing");
            case "version-conflict" -> DshBundle.message("dsh.feedback.conflict");
            case "note-blank" -> DshBundle.message("dsh.feedback.note.blank");
            case "note-too-large" -> DshBundle.message("dsh.feedback.note.large");
            default -> DshBundle.message("dsh.feedback.rejected");
        };
    }

    private static JsonObject object(JsonObject parent, String key) {
        return parent != null && parent.has(key) && parent.get(key).isJsonObject()
                ? parent.getAsJsonObject(key)
                : null;
    }

    private static JsonArray array(JsonObject parent, String key) {
        return parent != null && parent.has(key) && parent.get(key).isJsonArray()
                ? parent.getAsJsonArray(key)
                : null;
    }

    private static void copy(JsonObject source, JsonObject target, String key) {
        if (source.has(key)) target.add(key, source.get(key).deepCopy());
    }

    private enum Status {
        LOADING("loading"),
        READY("ready"),
        ERROR("error"),
        UNAVAILABLE("unavailable");

        final String wire;

        Status(String wire) {
            this.wire = wire;
        }
    }

    private static final class SessionState {
        Status status = Status.LOADING;
        final Map<String, JsonObject> items = new HashMap<>();
        final Set<String> pending = new HashSet<>();
        final Map<String, String> errors = new HashMap<>();
        String error;
        boolean sessionOpen;
        String sessionStatus = "idle";
        long sessionSequence;
        String sessionError;
    }
}
