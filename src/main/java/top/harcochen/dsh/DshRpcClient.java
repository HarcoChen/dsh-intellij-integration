package top.harcochen.dsh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Small, typed-at-the-boundary HTTP client for the Harness Web RPC surface.
 *
 * It deliberately keeps the business value as Gson JSON. Harness owns the
 * evolving schemas; the IntelliJ Platform adapter validates the stable envelope and lets
 * the presentation layer consume extension projections without silently
 * dropping newer fields.
 */
public final class DshRpcClient {
    private static final Logger LOG = Logger.getInstance(DshRpcClient.class);
    private final HttpClient httpClient;
    private final Supplier<String> baseUrl;
    private final Supplier<Integer> timeoutMs;

    public DshRpcClient(@NotNull Supplier<String> baseUrl, @NotNull Supplier<Integer> timeoutMs) {
        this.baseUrl = baseUrl;
        this.timeoutMs = timeoutMs;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** Execute one Harness RPC and return the value field of its success envelope. */
    public JsonElement call(@NotNull String method, @NotNull JsonObject payload) throws DshRpcException {
        String configured = normalizeUrl(baseUrl.get());
        if (configured == null) {
            throw new DshRpcException(method, "not-connected", "DSH Runtime is not connected");
        }

        String rpcId = UUID.randomUUID().toString();
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("type", "client-request");
        requestBody.addProperty("rpcId", rpcId);
        requestBody.addProperty("method", method);
        requestBody.add("payload", payload.deepCopy());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(configured + "/api/" + method))
                .timeout(Duration.ofMillis(clampedTimeout()))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new DshRpcException(method, "http-" + response.statusCode(),
                        "Harness RPC returned HTTP " + response.statusCode());
            }
            JsonElement parsed;
            try {
                parsed = JsonParser.parseString(response.body());
            } catch (RuntimeException error) {
                throw new DshRpcException(method, "invalid-json", "Harness RPC returned invalid JSON", error);
            }
            if (!parsed.isJsonObject()) {
                throw new DshRpcException(method, "invalid-envelope", "Harness RPC returned a non-object envelope");
            }
            JsonObject envelope = parsed.getAsJsonObject();
            if (!"server-response".equals(string(envelope, "type"))
                    || !rpcId.equals(string(envelope, "rpcId"))) {
                throw new DshRpcException(method, "invalid-envelope", "Harness RPC response envelope did not match the request");
            }
            JsonObject result = object(envelope, "result");
            if (result == null || !result.has("ok") || !result.get("ok").isJsonPrimitive()) {
                throw new DshRpcException(method, "invalid-result", "Harness RPC returned an invalid result envelope");
            }
            if (!result.get("ok").getAsBoolean()) {
                JsonObject rpcError = object(result, "error");
                String code = rpcError == null ? "remote-error" : stringOr(rpcError, "code", "remote-error");
                String message = rpcError == null ? "Harness rejected the request" : stringOr(rpcError, "message", "Harness rejected the request");
                throw new DshRpcException(method, code, message);
            }
            // commands/execute is allowed to omit value; JsonNull gives callers
            // a stable representation for that protocol exception.
            return result.has("value") ? result.get("value") : JsonNull.INSTANCE;
        } catch (DshRpcException error) {
            throw error;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new DshRpcException(method, "interrupted", "Harness RPC was interrupted", error);
        } catch (Exception error) {
            throw new DshRpcException(method, "transport-error", error.getMessage() == null ? error.toString() : error.getMessage(), error);
        }
    }

    public JsonObject describe() throws DshRpcException {
        return objectValue("host.describe", new JsonObject());
    }

    public JsonArray sessions() throws DshRpcException {
        JsonArray all = new JsonArray();
        String cursor = null;
        // dsh-ide paginates by cursor. Keep a defensive page cap so a malformed
        // server cannot make an opened Tool Window spin forever.
        for (int page = 0; page < 100; page++) {
            JsonObject payload = new JsonObject();
            if (cursor != null && !cursor.isBlank()) payload.addProperty("cursor", cursor);
            JsonObject value = objectValue("session.list", payload);
            if (value.has("items") && value.get("items").isJsonArray()) {
                for (JsonElement item : value.getAsJsonArray("items")) all.add(item);
            }
            String next = string(value, "nextCursor");
            if (next == null || next.isBlank() || next.equals(cursor)) break;
            cursor = next;
        }
        return all;
    }

    public JsonArray searchSessions(String query) throws DshRpcException {
        JsonObject payload = new JsonObject();
        payload.addProperty("query", query == null ? "" : query);
        JsonObject value = objectValue("session.search", payload);
        return value.has("items") && value.get("items").isJsonArray()
                ? value.getAsJsonArray("items") : new JsonArray();
    }

    public JsonObject history(String sessionId, int maxMessages) throws DshRpcException {
        JsonObject payload = new JsonObject();
        payload.addProperty("sessionId", sessionId);
        payload.addProperty("maxMessages", Math.max(1, Math.min(maxMessages, 1_000)));
        return objectValue("session.history", payload);
    }

    public JsonObject createSession(String cwd, String workspaceId, String agentPreset) throws DshRpcException {
        JsonObject payload = new JsonObject();
        if (workspaceId == null || workspaceId.isBlank()) {
            if (cwd != null && !cwd.isBlank()) payload.addProperty("cwd", cwd);
        } else {
            payload.addProperty("workspaceId", workspaceId);
        }
        if (agentPreset != null && !agentPreset.isBlank()) payload.addProperty("agentPreset", agentPreset);
        return objectValue("session.create", payload);
    }

    public void prompt(String sessionId, String text, String mode, JsonArray images) throws DshRpcException {
        JsonObject payload = new JsonObject();
        payload.addProperty("sessionId", sessionId);
        payload.addProperty("mode", "steer".equals(mode) ? "steer" : "queue");
        JsonArray content = new JsonArray();
        if (images != null) {
            for (JsonElement image : images) {
                JsonObject part = new JsonObject();
                part.addProperty("type", "image");
                if (image.isJsonObject()) {
                    JsonObject imageObject = image.getAsJsonObject();
                    if (imageObject.has("mediaType")) part.add("mediaType", imageObject.get("mediaType").deepCopy());
                    if (imageObject.has("data")) part.add("data", imageObject.get("data").deepCopy());
                    if (imageObject.has("name")) part.add("name", imageObject.get("name").deepCopy());
                }
                content.add(part);
            }
        }
        if (text != null && !text.isEmpty()) {
            JsonObject part = new JsonObject();
            part.addProperty("type", "text");
            part.addProperty("text", text);
            content.add(part);
        }
        payload.add("content", content);
        call("session.prompt", payload);
    }

    public void cancel(String sessionId) throws DshRpcException {
        JsonObject payload = new JsonObject();
        payload.addProperty("sessionId", sessionId);
        call("session.cancel", payload);
    }

    public void updateQueue(String sessionId, String itemId, String action, String text) throws DshRpcException {
        JsonObject queueAction = new JsonObject();
        String normalized = action == null ? "" : action.trim();
        if ("edit".equals(normalized)) {
            queueAction.addProperty("kind", "edit");
            JsonArray content = new JsonArray();
            JsonObject part = new JsonObject();
            part.addProperty("type", "text");
            part.addProperty("text", text == null ? "" : text);
            content.add(part);
            queueAction.add("content", content);
        } else if ("remove".equals(normalized) || "steer".equals(normalized)) {
            queueAction.addProperty("kind", normalized);
        } else {
            throw new DshRpcException("session.updateQueue", "invalid-action", "Unsupported queue action");
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("sessionId", sessionId);
        payload.addProperty("itemId", itemId);
        payload.add("action", queueAction);
        call("session.updateQueue", payload);
    }

    public JsonObject attachment(String sessionId, String attachmentId) throws DshRpcException {
        JsonObject payload = new JsonObject();
        payload.addProperty("sessionId", sessionId);
        payload.addProperty("attachmentId", attachmentId);
        return objectValue("session.attachment", payload);
    }

    public String renameSession(String sessionId, String title) throws DshRpcException {
        JsonObject payload = new JsonObject();
        payload.addProperty("sessionId", sessionId);
        payload.addProperty("title", title);
        JsonObject value = objectValue("session.rename", payload);
        return stringOr(value, "title", title);
    }

    public String forkSession(String sessionId) throws DshRpcException {
        JsonObject payload = new JsonObject();
        payload.addProperty("sessionId", sessionId);
        JsonObject value = objectValue("session.fork", payload);
        return stringOr(value, "sessionId", "");
    }

    public void archiveSession(String sessionId) throws DshRpcException {
        JsonObject payload = new JsonObject();
        payload.addProperty("sessionId", sessionId);
        call("workspace.archiveSession", payload);
    }

    public JsonArray models(String sessionId) throws DshRpcException {
        JsonObject payload = new JsonObject();
        payload.addProperty("sessionId", sessionId);
        JsonObject value = objectValue("session.models", payload);
        return value.has("groups") && value.get("groups").isJsonArray()
                ? value.getAsJsonArray("groups") : new JsonArray();
    }

    public void selectModel(String sessionId, String provider, String model, String reasoningEffort)
            throws DshRpcException {
        JsonObject payload = new JsonObject();
        payload.addProperty("sessionId", sessionId);
        payload.addProperty("provider", provider);
        payload.addProperty("model", model);
        if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            payload.addProperty("reasoningEffort", reasoningEffort);
        }
        call("session.selectModel", payload);
    }

    public void selectAgentPreset(String sessionId, String agentPreset) throws DshRpcException {
        JsonObject payload = new JsonObject();
        payload.addProperty("sessionId", sessionId);
        payload.addProperty("agentPreset", agentPreset);
        call("agentPreset.select", payload);
    }

    public JsonArray agentPresets() throws DshRpcException {
        JsonObject value = objectValue("agentPreset.list", new JsonObject());
        if (value.has("presets") && value.get("presets").isJsonArray()) {
            return value.getAsJsonArray("presets");
        }
        // Older Harness builds used the generic `items` field.
        return value.has("items") && value.get("items").isJsonArray()
                ? value.getAsJsonArray("items") : new JsonArray();
    }

    public void respond(String rpcId, boolean accepted, JsonElement value) throws DshRpcException {
        String configured = normalizeUrl(baseUrl.get());
        if (configured == null) throw new DshRpcException("respond", "not-connected", "DSH Runtime is not connected");
        JsonObject result = new JsonObject();
        result.addProperty("ok", accepted);
        if (accepted && value != null) result.add("value", value.deepCopy());
        if (!accepted) {
            JsonObject error = new JsonObject();
            error.addProperty("code", "rejected");
            error.addProperty("message", "Rejected by IntelliJ Platform host");
            result.add("error", error);
        }
        JsonObject body = new JsonObject();
        body.addProperty("type", "client-response");
        body.addProperty("rpcId", rpcId);
        body.add("result", result);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(configured + "/api/respond"))
                .timeout(Duration.ofMillis(clampedTimeout()))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new DshRpcException("respond", "http-" + response.statusCode(), "Harness respond returned HTTP " + response.statusCode());
            }
        } catch (DshRpcException error) {
            throw error;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new DshRpcException("respond", "interrupted", "Harness response was interrupted", error);
        } catch (Exception error) {
            throw new DshRpcException("respond", "transport-error", error.getMessage(), error);
        }
    }

    /** Web readiness check used after spawning, matching dsh-ide's waitForReady. */
    public boolean isWebHealthy(String url) {
        String normalized = normalizeUrl(url);
        if (normalized == null) return false;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(normalized + "/"))
                .timeout(Duration.ofMillis(Math.min(clampedTimeout(), 1_500)))
                .GET()
                .build();
        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Harness-specific probe used before attaching to an existing local port. */
    public boolean isHarnessHealthy(String url) {
        String normalized = normalizeUrl(url);
        if (normalized == null) return false;
        JsonObject body = new JsonObject();
        body.addProperty("type", "client-request");
        body.addProperty("rpcId", "dsh-intellij-probe-" + UUID.randomUUID());
        body.addProperty("method", "host.describe");
        body.add("payload", new JsonObject());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(normalized + "/api/host.describe"))
                .timeout(Duration.ofMillis(Math.min(clampedTimeout(), 1_500)))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) return false;
            JsonElement value = JsonParser.parseString(response.body());
            if (!value.isJsonObject()) return false;
            JsonObject envelope = value.getAsJsonObject();
            return "server-response".equals(string(envelope, "type"));
        } catch (Exception ignored) {
            return false;
        }
    }

    public boolean isHealthy(String url) {
        return isHarnessHealthy(url);
    }

    private JsonObject objectValue(String method, JsonObject payload) throws DshRpcException {
        JsonElement value = call(method, payload);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
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

    public static String normalizeUrl(String value) {
        if (value == null || value.isBlank()) return null;
        String candidate = value.trim().replaceAll("/+\\z", "");
        try {
            URI uri = URI.create(candidate);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || uri.getHost() == null || uri.getPort() <= 0) return null;
            return candidate;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    static JsonObject object(JsonObject parent, String name) {
        return parent.has(name) && parent.get(name).isJsonObject() ? parent.getAsJsonObject(name) : null;
    }

    static String string(JsonObject parent, String name) {
        return parent.has(name) && parent.get(name).isJsonPrimitive() ? parent.get(name).getAsString() : null;
    }

    static String stringOr(JsonObject parent, String name, String fallback) {
        String value = string(parent, name);
        return value == null ? fallback : value;
    }

    public static final class DshRpcException extends Exception {
        private final String method;
        private final String code;

        public DshRpcException(String method, String code, String message) {
            super(message);
            this.method = method;
            this.code = code;
        }

        public DshRpcException(String method, String code, String message, Throwable cause) {
            super(message, cause);
            this.method = method;
            this.code = code;
        }

        public String getMethod() {
            return method;
        }

        public String getCode() {
            return code;
        }
    }
}
