package top.harcochen.dsh.remote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the Gateway-internal {@code $events} logical stream for one connection generation.
 *
 * <p>The first stream item must be the {@code ready} frame; it establishes the generation-scoped
 * {@code clientId} that every waterfall answer must carry. Anything else on the downlink — {@code
 * emit}, {@code waterfall}, {@code cancel} — is validated and handed to the listener. Answers
 * travel over the reserved unary {@code $events/result} endpoint and are refused once the
 * generation that produced the {@code clientId} is gone.
 */
final class DshRemoteEventClient implements DshRemoteMuxClient.StreamHandler {
    /** Sink for validated event frames; every method runs on the connection executor. */
    interface Listener {
        void onReady(String clientId, String hostHome, int generation);

        void onEmit(String event, JsonArray args, int generation);

        void onWaterfall(
                String event, String eventId, String agentId, JsonObject request, int generation);

        void onCancel(String eventId, int generation);

        /** A legitimate frame nobody consumes; low-level diagnostic only. */
        void onDropped(String reason);

        /** A protocol violation that must tear down the whole connection generation. */
        void onFailure(String message);
    }

    private final DshRemoteMuxClient mux;
    private final DshRemoteUnaryClient unary;
    private final Listener listener;
    private final int generation;
    private final AtomicReference<String> clientId = new AtomicReference<>();
    private DshRemoteMuxClient.StreamHandle handle;
    private boolean sawReady;
    private boolean terminal;

    DshRemoteEventClient(
            DshRemoteMuxClient mux, DshRemoteUnaryClient unary, Listener listener, int generation) {
        this.mux = mux;
        this.unary = unary;
        this.listener = listener;
        this.generation = generation;
    }

    /** Open the forwarded-event stream. Must run on the connection executor. */
    void open() {
        handle = mux.open(DshRemoteContracts.EVENT_STREAM_ENDPOINT, new JsonObject(), this);
    }

    /** Answer one pending waterfall through {@code $events/result}. May block on HTTP. */
    void answer(String eventId, JsonObject outcome) throws DshRemoteException {
        String id = clientId.get();
        if (id == null || id.isBlank()) {
            throw DshRemoteException.carrier(
                    DshRemoteContracts.EVENT_RESULT_ENDPOINT,
                    "The Remote event stream is not ready; the answer was not sent",
                    null);
        }
        unary.call(
                DshRemoteContracts.EVENT_RESULT_ENDPOINT,
                DshRemoteContracts.argsEventResult(id, eventId, outcome));
    }

    /** Cancel the logical stream locally. Must run on the connection executor. */
    void close() {
        terminal = true;
        if (handle != null) handle.cancel();
        clientId.set(null);
    }

    @Override
    public void onItem(JsonObject value) {
        if (terminal) return;
        if (value == null) {
            fail("Remote event stream carried an empty item");
            return;
        }
        String type = string(value, "type");
        if (!sawReady) {
            if (!"ready".equals(type)) {
                fail("Remote event stream did not begin with a ready frame");
                return;
            }
            String id = string(value, "clientId");
            JsonObject host =
                    value.has("host") && value.get("host").isJsonObject()
                            ? value.getAsJsonObject("host")
                            : null;
            String home = host == null ? null : string(host, "home");
            if (id == null || id.isBlank()) {
                fail("Remote event ready frame has no clientId");
                return;
            }
            sawReady = true;
            clientId.set(id);
            listener.onReady(id, home == null ? "" : home, generation);
            return;
        }
        switch (type == null ? "" : type) {
            case "emit" -> {
                String event = string(value, "event");
                JsonArray args =
                        value.has("args") && value.get("args").isJsonArray()
                                ? value.getAsJsonArray("args")
                                : new JsonArray();
                if (event == null || event.isBlank()) {
                    listener.onDropped("Ignoring an emit frame without an event name");
                    return;
                }
                listener.onEmit(event, args.deepCopy(), generation);
            }
            case "waterfall" -> {
                String event = string(value, "event");
                String eventId = string(value, "eventId");
                String agentId = string(value, "agentId");
                JsonObject request =
                        value.has("request") && value.get("request").isJsonObject()
                                ? value.getAsJsonObject("request")
                                : null;
                if (event == null || eventId == null || agentId == null || request == null) {
                    listener.onDropped("Ignoring a malformed waterfall frame");
                    return;
                }
                listener.onWaterfall(event, eventId, agentId, request.deepCopy(), generation);
            }
            case "cancel" -> {
                String eventId = string(value, "eventId");
                if (eventId == null) {
                    listener.onDropped("Ignoring a cancel frame without an eventId");
                    return;
                }
                listener.onCancel(eventId, generation);
            }
            case "ready" -> fail("Remote event stream emitted a second ready frame");
            default -> listener.onDropped("Ignoring an unknown remote event frame type: " + type);
        }
    }

    @Override
    public void onTerminal(JsonObject error) {
        if (terminal) return;
        terminal = true;
        clientId.set(null);
        if (error == null) {
            fail("Remote event stream ended before the generation was replaced");
            return;
        }
        fail("Remote event stream failed: " + string(error, "message"));
    }

    private void fail(String message) {
        terminal = true;
        clientId.set(null);
        if (handle != null) handle.cancel();
        listener.onFailure(message);
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsString()
                : null;
    }

    /** Outcome builder: {@code {kind:"result", value}}. */
    static JsonObject resultOutcome(JsonElement value) {
        JsonObject outcome = new JsonObject();
        outcome.addProperty("kind", "result");
        if (value != null && !value.isJsonNull()) outcome.add("value", value.deepCopy());
        return outcome;
    }
}
