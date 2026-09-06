package top.harcochen.dsh.remote;

import com.google.gson.JsonObject;

/**
 * Structured failure across the four Remote error layers.
 *
 * <p>Layers follow the adaptation plan: {@code AUTH} for HTTP 401/403 and credential problems,
 * {@code CARRIER} for socket/timeout cancellation, {@code PROTOCOL} for illegal envelopes, frames
 * and contract mismatches (including every {@code gateway/*} Remote code), and {@code REMOTE} for
 * business failures such as {@code session/not-found}. The code always crosses the wire as a
 * string; callers never branch on Java exception types.
 */
public final class DshRemoteException extends RuntimeException {
    public enum Layer {
        AUTH,
        CARRIER,
        PROTOCOL,
        REMOTE
    }

    private final Layer layer;
    private final String endpoint;
    private final String code;
    private final JsonObject details;
    private final int httpStatus;

    private DshRemoteException(
            Layer layer,
            String endpoint,
            String code,
            String message,
            JsonObject details,
            int httpStatus,
            Throwable cause) {
        super(message, cause);
        this.layer = layer;
        this.endpoint = endpoint;
        this.code = code;
        this.details = details == null ? new JsonObject() : details;
        this.httpStatus = httpStatus;
    }

    /** HTTP 401/403: credentials or authority are no longer valid. */
    public static DshRemoteException auth(String endpoint, int httpStatus) {
        return new DshRemoteException(
                Layer.AUTH,
                endpoint,
                "http-" + httpStatus,
                "DSH Runtime rejected the credentials (HTTP " + httpStatus + ")",
                new JsonObject(),
                httpStatus,
                null);
    }

    /** Authentication exchange failed before any RPC was attempted. */
    public static DshRemoteException auth(
            String endpoint, String code, String message, Throwable cause) {
        return new DshRemoteException(
                Layer.AUTH, endpoint, code, message, new JsonObject(), 0, cause);
    }

    /** Non-2xx HTTP status that is not an auth failure. */
    public static DshRemoteException http(String endpoint, int httpStatus) {
        return new DshRemoteException(
                Layer.REMOTE,
                endpoint,
                "http-" + httpStatus,
                "DSH Runtime returned HTTP " + httpStatus,
                new JsonObject(),
                httpStatus,
                null);
    }

    /** HTTP 404: the connected Runtime does not expose this capability or method. */
    public static DshRemoteException capability(String endpoint) {
        return new DshRemoteException(
                Layer.REMOTE,
                endpoint,
                "http-404",
                "The connected DSH Runtime does not expose " + endpoint,
                new JsonObject(),
                404,
                null);
    }

    /** Transport-level failure: socket, timeout, or cancellation. */
    public static DshRemoteException carrier(String endpoint, String message, Throwable cause) {
        return new DshRemoteException(
                Layer.CARRIER, endpoint, "carrier", message, new JsonObject(), 0, cause);
    }

    /** Illegal envelope, frame, rpcId/streamId, or value shape. */
    public static DshRemoteException protocol(String endpoint, String message, Throwable cause) {
        return new DshRemoteException(
                Layer.PROTOCOL, endpoint, "protocol", message, new JsonObject(), 0, cause);
    }

    /** A structured Remote failure restored from the wire. */
    public static DshRemoteException remote(
            String endpoint, String code, String message, JsonObject details) {
        Layer layer = code != null && code.startsWith("gateway/") ? Layer.PROTOCOL : Layer.REMOTE;
        return new DshRemoteException(layer, endpoint, code, message, details, 0, null);
    }

    /** Restore one logical-stream error frame. */
    public static DshRemoteException fromStreamError(JsonObject error) {
        String code =
                error != null && error.has("code") && error.get("code").isJsonPrimitive()
                        ? error.get("code").getAsString()
                        : "protocol";
        String message =
                error != null && error.has("message") && error.get("message").isJsonPrimitive()
                        ? error.get("message").getAsString()
                        : "Remote stream failed";
        JsonObject details =
                error != null && error.has("details") && error.get("details").isJsonObject()
                        ? error.getAsJsonObject("details")
                        : new JsonObject();
        return remote(DshRemoteContracts.STREAM_SESSION_FOLLOW, code, message, details);
    }

    public Layer layer() {
        return layer;
    }

    public String endpoint() {
        return endpoint;
    }

    public String code() {
        return code;
    }

    public JsonObject details() {
        return details.deepCopy();
    }

    public int httpStatus() {
        return httpStatus;
    }

    public boolean isAuth() {
        return layer == Layer.AUTH;
    }

    /** HTTP 404: the endpoint is absent from this Runtime composition. */
    public boolean isCapabilityMissing() {
        return httpStatus == 404;
    }

    /** Contract mismatch between this plugin and the connected Runtime. */
    public boolean isContractMismatch() {
        return layer == Layer.PROTOCOL
                || "gateway/arguments-invalid".equals(code)
                || "gateway/result-invalid".equals(code);
    }

    /** Short, redacted user-facing text; never includes token, cookie, or payload content. */
    public String display() {
        String message = getMessage();
        return message == null || message.isBlank() ? code : message;
    }
}
