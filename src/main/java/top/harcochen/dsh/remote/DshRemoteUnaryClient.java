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
        return HTTP_CLIENT
                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> parse(endpoint, rpcId, response));
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
