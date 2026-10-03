package top.harcochen.dsh.remote;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Unary caller for the RC Remote API.
 *
 * <p>The feature-facing payload is always exactly one {@code args} object; this class owns the
 * Connection envelope and validates every response before handing the endpoint value to a caller.
 * Callers must run on background executors: authentication may perform one blocking HTTP exchange,
 * and no IntelliJ EDT code may wait on these calls.
 */
public final class DshRemoteUnaryClient {
    private static final HttpClient HTTP_CLIENT =
            HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

    private final Supplier<String> baseUrl;
    private final Supplier<Integer> timeoutMs;
    private final DshRemoteAuth auth;
    private final Consumer<DshRemoteException> authFailureListener;

    public DshRemoteUnaryClient(
            Supplier<String> baseUrl,
            Supplier<Integer> timeoutMs,
            DshRemoteAuth auth,
            Consumer<DshRemoteException> authFailureListener) {
        this.baseUrl = baseUrl;
        this.timeoutMs = timeoutMs;
        this.auth = auth;
        this.authFailureListener = authFailureListener;
    }

    /**
     * Execute one Remote RPC and return the value of its success envelope. Blocks the calling
     * thread until completion or timeout; only call from background executors.
     */
    public JsonElement call(String endpoint, JsonObject args) throws DshRemoteException {
        try {
            return callAsync(endpoint, args).join();
        } catch (java.util.concurrent.CompletionException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            if (cause instanceof DshRemoteException remote) {
                throw remote;
            }
            throw DshRemoteException.carrier(endpoint, String.valueOf(cause), cause);
        }
    }

    DshRemoteBinaryResponse.FileBytes callFileBytes(JsonObject args) {
        String endpoint = "workspaceFiles/readBytes";
        auth.cookie();
        JsonObject envelope = DshRemoteContracts.requestEnvelope(endpoint, args);
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(
                                URI.create(
                                        baseUrl.get().replaceAll("/+$", "") + "/api/" + endpoint))
                        .timeout(Duration.ofMillis(clampedTimeout()))
                        .header("content-type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(envelope.toString()));
        auth.headers().forEach(builder::header);
        CompletableFuture<HttpResponse<byte[]>> transport =
                HTTP_CLIENT.sendAsync(builder.build(), ignored -> new PreviewBody());
        try {
            HttpResponse<byte[]> response =
                    transport.get(clampedTimeout(), java.util.concurrent.TimeUnit.MILLISECONDS);
            byte[] bytes = response.body();
            return DshRemoteBinaryResponse.parse(
                    response.headers().firstValue("content-type").orElse(null),
                    bytes,
                    endpoint,
                    envelope.get("rpcId").getAsString(),
                    response.statusCode());
        } catch (InterruptedException cancelled) {
            transport.cancel(true);
            Thread.currentThread().interrupt();
            throw DshRemoteException.carrier(endpoint, "Workspace file read cancelled", cancelled);
        } catch (java.util.concurrent.TimeoutException timeout) {
            transport.cancel(true);
            throw DshRemoteException.carrier(endpoint, "Workspace file read timed out", timeout);
        } catch (java.util.concurrent.ExecutionException error) {
            if (error.getCause() instanceof DshRemoteException remote) throw remote;
            throw DshRemoteException.carrier(endpoint, "Workspace file read failed", error);
        } catch (DshRemoteException error) {
            if (error.isAuth()) {
                auth.invalidate();
                if (authFailureListener != null) authFailureListener.accept(error);
            }
            throw error;
        }
    }

    /** Bound memory while still receiving the body; the outer future bounds total read time. */
    private static final class PreviewBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate =
                HttpResponse.BodySubscribers.ofByteArray();
        private java.util.concurrent.Flow.Subscription subscription;
        private int received;
        private boolean done;

        @Override
        public java.util.concurrent.CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(java.util.concurrent.Flow.Subscription value) {
            subscription = value;
            delegate.onSubscribe(value);
        }

        @Override
        public void onNext(java.util.List<java.nio.ByteBuffer> buffers) {
            if (done) return;
            for (java.nio.ByteBuffer buffer : buffers) {
                if (buffer.remaining() > 2 * 1024 * 1024 - received) {
                    done = true;
                    subscription.cancel();
                    delegate.onError(
                            DshRemoteException.protocol(
                                    "workspaceFiles/readBytes",
                                    "Workspace file response exceeded the preview budget",
                                    null));
                    return;
                }
                received += buffer.remaining();
            }
            delegate.onNext(buffers);
        }

        @Override
        public void onError(Throwable error) {
            if (!done) {
                done = true;
                delegate.onError(error);
            }
        }

        @Override
        public void onComplete() {
            if (!done) {
                done = true;
                delegate.onComplete();
            }
        }
    }

    /**
     * Execute one Remote RPC asynchronously. The returned future completes with the endpoint value,
     * or exceptionally with a {@link DshRemoteException}. The launch-token exchange (when one is
     * due) runs on the calling thread; socket I/O does not.
     */
    public CompletableFuture<JsonElement> callAsync(String endpoint, JsonObject args) {
        DshRemoteContracts.assertEndpoint(endpoint);
        String base = baseUrl.get();
        if (base != null) base = base.strip().replaceAll("/+$", "");
        if (base == null || base.isBlank()) {
            return CompletableFuture.failedFuture(
                    DshRemoteException.carrier(endpoint, "DSH Runtime is not connected", null));
        }
        try {
            auth.cookie();
        } catch (DshRemoteException error) {
            if (authFailureListener != null) authFailureListener.accept(error);
            return CompletableFuture.failedFuture(error);
        }
        String rpcId;
        String body;
        try {
            JsonObject envelope = DshRemoteContracts.requestEnvelope(endpoint, args);
            rpcId = envelope.get("rpcId").getAsString();
            body = envelope.toString();
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(
                    DshRemoteException.protocol(
                            endpoint, "Remote request could not be encoded", error));
        }
        HttpRequest request;
        try {
            HttpRequest.Builder builder =
                    HttpRequest.newBuilder()
                            .uri(URI.create(base + "/api/" + endpoint))
                            .timeout(Duration.ofMillis(clampedTimeout()))
                            .POST(HttpRequest.BodyPublishers.ofString(body));
            for (Map.Entry<String, String> entry : auth.headers().entrySet()) {
                builder.header(entry.getKey(), entry.getValue());
            }
            builder.header("content-type", "application/json");
            request = builder.build();
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(
                    DshRemoteException.protocol(endpoint, "Remote request URL is invalid", error));
        }
        CompletableFuture<HttpResponse<String>> transport =
                HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        CompletableFuture<JsonElement> result =
                transport.thenApply(response -> parse(endpoint, rpcId, response));
        result.whenComplete(
                (value, error) -> {
                    if (result.isCancelled()) transport.cancel(true);
                });
        return result;
    }

    /**
     * No-side-effect capability probe used when attaching to an existing Runtime. A successful
     * response or any structured Remote failure proves the Runtime speaks the Remote protocol; auth
     * and transport failures do not.
     */
    public boolean probe() {
        try {
            call(DshRemoteContracts.SESSION_LIST, DshRemoteContracts.argsSessionList());
            return true;
        } catch (DshRemoteException error) {
            return error.layer() == DshRemoteException.Layer.REMOTE
                    || error.layer() == DshRemoteException.Layer.PROTOCOL;
        }
    }

    /** True when a session cookie for the current authority is already established. */
    public boolean isAuthEstablished() {
        return auth.isEstablished();
    }

    /**
     * Perform the launch-token exchange when one is due. Returns the failure, or null on success;
     * only call from a background executor.
     */
    public DshRemoteException tryAuthenticate() {
        try {
            auth.cookie();
            return null;
        } catch (DshRemoteException error) {
            if (authFailureListener != null) authFailureListener.accept(error);
            return error;
        }
    }

    private JsonElement parse(String endpoint, String rpcId, HttpResponse<String> response) {
        try {
            return DshRemoteContracts.parseUnaryResponse(
                    endpoint, response.body(), rpcId, response.statusCode());
        } catch (DshRemoteException error) {
            if (error.isAuth()) {
                auth.invalidate();
                if (authFailureListener != null) authFailureListener.accept(error);
            }
            throw error;
        }
    }

    private int clampedTimeout() {
        Integer configured;
        try {
            configured = timeoutMs.get();
        } catch (RuntimeException ignored) {
            configured = null;
        }
        return configured == null ? 600_000 : Math.max(1_000, Math.min(configured, 3_600_000));
    }
}
