package top.harcochen.dsh;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Lightweight client for transient Harness mux frames such as approvals and questions. */
final class DshMuxClient implements AutoCloseable {
    private static final Logger LOG = Logger.getInstance(DshMuxClient.class);

    private final Supplier<String> baseUrl;
    private final Supplier<Map<String, String>> requestHeaders;
    private final Supplier<Boolean> ensureAuthenticated;
    private final Consumer<JsonObject> receiver;
    private final HttpClient http = HttpClient.newHttpClient();
    private final AtomicBoolean connecting = new AtomicBoolean();
    private volatile WebSocket socket;
    private volatile String connectedUrl;
    private volatile boolean closed;

    DshMuxClient(Supplier<String> baseUrl, Consumer<JsonObject> receiver) {
        this(baseUrl, () -> Map.of(), () -> true, receiver);
    }

    DshMuxClient(
            Supplier<String> baseUrl,
            Supplier<Map<String, String>> requestHeaders,
            Supplier<Boolean> ensureAuthenticated,
            Consumer<JsonObject> receiver) {
        this.baseUrl = baseUrl;
        this.requestHeaders = requestHeaders;
        this.ensureAuthenticated = ensureAuthenticated;
        this.receiver = receiver;
    }

    void ensureConnected() {
        try {
            String configured = DshRpcClient.normalizeUrl(baseUrl.get());
            if (closed || configured == null) return;
            if (!Boolean.TRUE.equals(ensureAuthenticated.get())) return;
            if (socket != null && configured.equals(connectedUrl)) return;
            if (!connecting.compareAndSet(false, true)) return;
            URI uri =
                    URI.create(
                            (configured.startsWith("https://") ? "wss://" : "ws://")
                                    + configured.substring(configured.indexOf("://") + 3)
                                    + "/api/events.mux");
            WebSocket.Builder builder = http.newWebSocketBuilder();
            for (Map.Entry<String, String> entry : requestHeaders.get().entrySet()) {
                if (entry.getKey() != null
                        && entry.getValue() != null
                        && !entry.getValue().isBlank()) {
                    builder.header(entry.getKey(), entry.getValue());
                }
            }
            builder.buildAsync(uri, new Listener(configured))
                    .whenComplete(
                            (opened, error) -> {
                                connecting.set(false);
                                if (error != null) {
                                    LOG.debug("DSH mux connection failed", error);
                                    return;
                                }
                                if (closed) opened.sendClose(WebSocket.NORMAL_CLOSURE, "disposed");
                            });
        } catch (RuntimeException error) {
            connecting.set(false);
            LOG.debug("Unable to prepare DSH mux connection", error);
        }
    }

    @Override
    public void close() {
        closed = true;
        WebSocket current = socket;
        socket = null;
        connectedUrl = null;
        if (current != null) current.sendClose(WebSocket.NORMAL_CLOSURE, "disposed");
    }

    private final class Listener implements WebSocket.Listener {
        private final String url;
        private final StringBuilder text = new StringBuilder();

        private Listener(String url) {
            this.url = url;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            socket = webSocket;
            connectedUrl = url;
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                try {
                    JsonElement parsed = JsonParser.parseString(text.toString());
                    if (parsed.isJsonObject()) {
                        JsonObject envelope = parsed.getAsJsonObject();
                        if ("server-request".equals(string(envelope, "type"))
                                && envelope.has("payload")
                                && envelope.get("payload").isJsonObject()) {
                            JsonObject payload = envelope.getAsJsonObject("payload").deepCopy();
                            String rpcId = string(envelope, "rpcId");
                            if (rpcId != null) payload.addProperty("_rpcId", rpcId);
                            receiver.accept(payload);
                        }
                    }
                } catch (RuntimeException error) {
                    LOG.debug("Ignoring malformed DSH mux frame", error);
                } finally {
                    text.setLength(0);
                }
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (socket == webSocket) {
                socket = null;
                connectedUrl = null;
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (socket == webSocket) {
                socket = null;
                connectedUrl = null;
            }
            LOG.debug("DSH mux stream failed", error);
        }

        private String string(JsonObject object, String key) {
            return object.has(key) && object.get(key).isJsonPrimitive()
                    ? object.get(key).getAsString()
                    : null;
        }
    }
}
