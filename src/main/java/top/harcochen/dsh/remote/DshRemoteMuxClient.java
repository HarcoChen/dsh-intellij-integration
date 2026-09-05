package top.harcochen.dsh.remote;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One physical {@code /api/remote.mux} WebSocket carrying many logical Remote streams.
 *
 * <p>The mux only understands the carrier vocabulary: envelope frames, streamIds, cancellation,
 * terminal frames, frame limits, and the socket itself. It never replays {@code open} on its own;
 * the connection owner rebuilds subscriptions in a fresh generation. All frame handling is
 * delegated to the supplied executor so no JSON reducer runs on an HttpClient callback thread.
 */
public final class DshRemoteMuxClient {
    /** Maximum size of one complete server text frame. */
    static final int MAX_FRAME_CHARS = 32 * 1024 * 1024;

    /** Per-stream callbacks; every method runs on the mux's dispatch executor. */
    public interface StreamHandler {
        /** One stream item value. */
        void onItem(JsonObject value);

        /**
         * Terminal frame. {@code error} is the structured Remote failure for stream errors, or null
         * when the Host ended the stream normally.
         */
        void onTerminal(JsonObject error);
    }

    /** Handle for one open logical stream. */
    public interface StreamHandle {
        /** Cancel the stream; at most one cancel frame is sent. */
        void cancel();
    }

    private final Supplier<String> baseUrl;
    private final Supplier<Map<String, String>> headers;
    private final Executor dispatch;
    private final Consumer<String> diagnostic;
    private final Consumer<Integer> onSocketOpen;
    private final BiConsumer<Integer, Throwable> onSocketClosed;
    private final HttpClient http = HttpClient.newHttpClient();

    /** Executor-confined state below: guarded by the dispatch executor. */
    private WebSocket socket;

    private SocketListener listener;
    private final Map<String, OpenStream> streams = new HashMap<>();
    private final Set<String> recentTerminals = new LinkedHashSet<>();
    private long unknownStreamFrames;
    private long lateTerminalFrames;
    private long oversizedFrames;
    private boolean closed;

    public DshRemoteMuxClient(
            Supplier<String> baseUrl,
            Supplier<Map<String, String>> headers,
            Executor dispatch,
            Consumer<String> diagnostic,
            Consumer<Integer> onSocketOpen,
            BiConsumer<Integer, Throwable> onSocketClosed) {
        this.baseUrl = baseUrl;
        this.headers = headers;
        this.dispatch = dispatch;
        this.diagnostic = diagnostic;
        this.onSocketOpen = onSocketOpen;
        this.onSocketClosed = onSocketClosed;
    }

    /** Begin one physical connection attempt. Completion arrives through the listener callbacks. */
    public void connect(int generation) {
        String base = normalizeBase(baseUrl.get());
        if (base == null || closed) {
            dispatch.execute(
                    () ->
                            onSocketClosed.accept(
                                    generation,
                                    DshRemoteException.carrier(
                                            DshRemoteContracts.MUX_PATH,
                                            "DSH Runtime is not connected",
                                            null)));
            return;
        }
        String wsUrl =
                (base.startsWith("https://") ? "wss://" : "ws://")
                        + base.substring(base.indexOf("://") + 3)
                        + DshRemoteContracts.MUX_PATH;
        WebSocket.Builder builder = http.newWebSocketBuilder();
        builder.connectTimeout(java.time.Duration.ofSeconds(10));
        for (Map.Entry<String, String> entry : headers.get().entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isBlank()) {
                builder.header(entry.getKey(), entry.getValue());
            }
        }
        SocketListener next = new SocketListener(generation);
        listener = next;
        builder.buildAsync(URI.create(wsUrl), next)
                .whenComplete(
                        (opened, error) -> {
                            if (error != null) {
                                diagnostic.accept("DSH remote mux connection failed: " + error);
                                dispatch.execute(
                                        () -> {
                                            if (listener == next) listener = null;
                                            onSocketClosed.accept(generation, error);
                                        });
                            }
                        });
    }

    /** Close the physical socket; every logical stream fails with a carrier error. */
    public void shutdown() {
        closed = true;
        dispatch.execute(() -> closeSocket("disposed"));
    }

    /**
     * Open one logical stream. Must run on the dispatch executor. The handler receives items and
     * exactly one terminal notification, always on the dispatch executor.
     */
    public StreamHandle open(String endpoint, JsonObject args, StreamHandler handler) {
        String streamId = UUID.randomUUID().toString();
        OpenStream stream = new OpenStream(endpoint, handler);
        streams.put(streamId, stream);
        boolean sent = sendText(DshRemoteContracts.streamOpen(streamId, endpoint, args).toString());
        if (!sent) {
            streams.remove(streamId);
            handler.onTerminal(
                    errorObject("carrier", "DSH remote mux socket is not open", new JsonObject()));
            return () -> {};
        }
        return new Handle(streamId);
    }

    /** Whether the physical socket is currently open. Must run on the dispatch executor. */
    public boolean isOpen() {
        return socket != null && !socket.isOutputClosed() && !closed;
    }

    /** Diagnostics snapshot for the connection report. */
    public String diagnostics() {
        return "logicalStreams="
                + streams.size()
                + ", unknownStreamFrames="
                + unknownStreamFrames
                + ", lateTerminalFrames="
                + lateTerminalFrames
                + ", oversizedFrames="
                + oversizedFrames;
    }

    private final class Handle implements StreamHandle {
        private final String streamId;
        private boolean cancelled;

        private Handle(String streamId) {
            this.streamId = streamId;
        }

        @Override
        public void cancel() {
            if (cancelled) return;
            cancelled = true;
            OpenStream stream = streams.get(streamId);
            if (stream != null) stream.cancelRequested = true;
            sendText(DshRemoteContracts.streamCancel(streamId).toString());
        }
    }

    private static final class OpenStream {
        final String endpoint;
        final StreamHandler handler;
        boolean cancelRequested;
        boolean terminal;

        OpenStream(String endpoint, StreamHandler handler) {
            this.endpoint = endpoint;
            this.handler = handler;
        }
    }

    private final class SocketListener implements WebSocket.Listener {
        private final int generation;
        private final StringBuilder text = new StringBuilder();

        private SocketListener(int generation) {
            this.generation = generation;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            dispatch.execute(
                    () -> {
                        if (listener == this) {
                            socket = webSocket;
                            onSocketOpen.accept(generation);
                        }
                    });
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            text.append(data);
            if (text.length() > MAX_FRAME_CHARS) {
                oversizedFrames++;
                failSocket(webSocket, generation, "oversized remote mux frame");
                return CompletableFuture.completedFuture(null);
            }
            if (last) {
                String complete = text.toString();
                text.setLength(0);
                dispatch.execute(() -> receive(this, complete));
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            failSocket(webSocket, generation, "DSH remote mux socket closed (" + statusCode + ")");
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            failSocket(
                    webSocket,
                    generation,
                    error.getMessage() == null
                            ? "DSH remote mux socket failed"
                            : error.getMessage());
        }
    }

    /** Runs on the dispatch executor. */
    private void receive(SocketListener source, String message) {
        if (listener != source || closed) return;
        JsonObject frame;
        try {
            JsonElement parsed = JsonParser.parseString(message);
            if (!parsed.isJsonObject()) throw new IllegalStateException("frame is not an object");
            frame = parsed.getAsJsonObject();
        } catch (RuntimeException error) {
            failAll(
                    DshRemoteException.protocol(
                            DshRemoteContracts.MUX_PATH,
                            "Remote mux frame is not valid JSON",
                            error));
            closeSocket("invalid frame");
            onSocketClosed.accept(
                    source.generation,
                    DshRemoteException.protocol(
                            DshRemoteContracts.MUX_PATH,
                            "Remote mux frame is not valid JSON",
                            error));
            return;
        }
        String type = string(frame, "type");
        String streamId = string(frame, "streamId");
        if (type == null || streamId == null) {
            diagnostic.accept("Ignoring a remote mux frame without type/streamId");
            return;
        }
        OpenStream stream = streams.get(streamId);
        if (stream == null) {
            if (recentTerminals.contains(streamId)) {
                lateTerminalFrames++;
                diagnostic.accept("Ignoring a late frame for a terminated remote stream");
            } else {
                unknownStreamFrames++;
                diagnostic.accept("Ignoring a frame for an unknown remote stream");
            }
            return;
        }
        if (stream.terminal) {
            lateTerminalFrames++;
            diagnostic.accept("Ignoring a duplicate terminal remote stream frame");
            return;
        }
        switch (type) {
            case "item" -> {
                JsonObject value =
                        frame.has("value") && frame.get("value").isJsonObject()
                                ? frame.getAsJsonObject("value")
                                : null;
                try {
                    stream.handler.onItem(value);
                } catch (RuntimeException error) {
                    diagnostic.accept("Remote stream handler failed: " + error);
                }
            }
            case "error" -> {
                JsonObject error =
                        frame.has("error") && frame.get("error").isJsonObject()
                                ? frame.getAsJsonObject("error")
                                : errorObject(
                                        "protocol",
                                        "Remote stream failed without an error object",
                                        new JsonObject());
                finish(streamId, stream, error);
            }
            case "end" -> finish(streamId, stream, null);
            default -> diagnostic.accept("Ignoring an unknown remote mux frame type: " + type);
        }
    }

    private void finish(String streamId, OpenStream stream, JsonObject error) {
        stream.terminal = true;
        streams.remove(streamId);
        rememberTerminal(streamId);
        try {
            stream.handler.onTerminal(error);
        } catch (RuntimeException failure) {
            diagnostic.accept("Remote stream terminal handler failed: " + failure);
        }
    }

    private void rememberTerminal(String streamId) {
        recentTerminals.add(streamId);
        while (recentTerminals.size() > 1024) {
            String oldest = recentTerminals.iterator().next();
            recentTerminals.remove(oldest);
        }
    }

    private void failAll(Throwable error) {
        List<String> ids = new ArrayList<>(streams.keySet());
        for (String streamId : ids) {
            OpenStream stream = streams.remove(streamId);
            if (stream == null || stream.terminal) continue;
            stream.terminal = true;
            try {
                stream.handler.onTerminal(
                        errorObject(
                                "carrier",
                                error.getMessage() == null
                                        ? String.valueOf(error)
                                        : error.getMessage(),
                                new JsonObject()));
            } catch (RuntimeException failure) {
                diagnostic.accept("Remote stream failure handler threw: " + failure);
            }
        }
    }

    /** Runs on the dispatch executor via a hop from the listener callback. */
    private void failSocket(WebSocket webSocket, int generation, String reason) {
        dispatch.execute(
                () -> {
                    if (webSocket != socket && socket != null) return;
                    Throwable error =
                            DshRemoteException.carrier(DshRemoteContracts.MUX_PATH, reason, null);
                    failAll(error);
                    closeSocket(reason);
                    if (!closed) onSocketClosed.accept(generation, error);
                });
    }

    private void closeSocket(String reason) {
        WebSocket current = socket;
        socket = null;
        listener = null;
        if (current != null) {
            try {
                current.sendClose(WebSocket.NORMAL_CLOSURE, reason);
            } catch (RuntimeException ignored) {
                // The socket may already be closed; nothing to recover.
            }
        }
    }

    /** Must run on the dispatch executor. Returns false when the socket is not open. */
    private boolean sendText(String message) {
        WebSocket current = socket;
        if (current == null || closed) return false;
        try {
            current.sendText(message, true);
            return true;
        } catch (RuntimeException error) {
            diagnostic.accept("Unable to send a remote mux frame: " + error);
            return false;
        }
    }

    /** Trim trailing slashes so path joins cannot produce a double slash. */
    private static String normalizeBase(String value) {
        if (value == null) return null;
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed.replaceAll("/+$", "");
    }

    static JsonObject errorObject(String code, String message, JsonObject details) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        error.add("details", details);
        return error;
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsString()
                : null;
    }
}
