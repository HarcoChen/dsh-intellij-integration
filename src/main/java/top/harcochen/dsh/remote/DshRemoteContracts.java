package top.harcochen.dsh.remote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.UUID;

/**
 * Wire contracts for the RC Remote API, fixed against {@code deepseek-harness} tag {@code
 * dsh-v0.1.2-rc.1}, commit {@code a66e4702047846cdaa10c66c9d3df3951f5ea70d}.
 *
 * <p>Every endpoint's {@code args} field names were taken from the Host method parameters (the
 * descriptor {@code wire} names), not from flattened DTOs. Zero-argument endpoints send an {@code
 * args} object that is strictly empty, because the Gateway rejects any other shape with {@code
 * gateway/arguments-invalid}. Upgrading the managed Runtime requires an endpoint and descriptor
 * audit before changing anything in this file.
 */
public final class DshRemoteContracts {
    /** Harness tag this contract was audited against. */
    public static final String TARGET_TAG = "dsh-v0.1.2-rc.1";

    /** Harness commit this contract was audited against. */
    public static final String TARGET_COMMIT = "a66e4702047846cdaa10c66c9d3df3951f5ea70d";

    public static final String MUX_PATH = "/api/remote.mux";
    public static final String EVENT_STREAM_ENDPOINT = "$events";
    public static final String EVENT_RESULT_ENDPOINT = "$events/result";

    // Unary endpoints (namespace/method on /api/).
    public static final String SESSION_LIST = "session/list";
    public static final String SESSION_SEARCH = "session/search";
    public static final String SESSION_CREATE = "session/create";
    public static final String SESSION_RENAME = "session/rename";
    public static final String SESSION_FORK = "session/fork";
    public static final String SESSION_PROMPT = "session/prompt";
    public static final String SESSION_CANCEL = "session/cancel";
    public static final String SESSION_UPDATE_QUEUE = "session/updateQueue";
    public static final String SESSION_ATTACHMENT = "session/attachment";
    public static final String SESSION_MODEL_CATALOG = "session/modelCatalog";
    public static final String SESSION_SELECT_MODEL = "session/selectModel";
    public static final String SESSION_PAGE = "session/page";
    public static final String SESSION_CAN_OPEN_WORKSPACE_PATH = "session/canOpenWorkspacePath";
    public static final String SESSION_OPEN_WORKSPACE_PATH = "session/openWorkspacePath";
    public static final String WORKSPACE_CREATE = "workspace/create";
    public static final String WORKSPACE_RENAME = "workspace/rename";
    public static final String WORKSPACE_DELETE = "workspace/delete";
    public static final String WORKSPACE_ARCHIVE_SESSION = "workspace/archiveSession";
    public static final String AGENT_PRESETS_LIST = "agentPresets/list";
    public static final String AGENT_PRESETS_SELECT = "agentPresets/select";
    public static final String AGENT_PRESETS_READ = "agentPresets/read";
    public static final String AGENT_PRESETS_COPY = "agentPresets/copy";
    public static final String AGENT_PRESETS_DELETE = "agentPresets/deletePreset";
    public static final String SETTINGS_DESCRIBE = "settings/describe";
    public static final String SETTINGS_MUTATE = "settings/mutate";
    public static final String SETTINGS_OPEN_DOCUMENT = "settings/openSettingsDocument";
    public static final String SETTINGS_OPEN_PRESET_DIRECTORY = "settings/openAgentPresetDirectory";
    public static final String GOALS_CREATE = "goals/create";
    public static final String GOALS_EDIT = "goals/edit";
    public static final String GOALS_PAUSE = "goals/pause";
    public static final String GOALS_RESUME = "goals/resume";
    public static final String GOALS_COMPLETE = "goals/complete";
    public static final String GOALS_CLEAR = "goals/clear";
    public static final String SUBAGENTS_LIST = "subagents/list";
    public static final String SUBAGENTS_PROMPT = "subagents/prompt";
    public static final String SUBAGENTS_INTERRUPT = "subagents/interruptByParent";
    public static final String SKILLS_LIST = "skills/list";
    public static final String COMMANDS_LIST = "commands/list";
    public static final String COMMANDS_EXECUTE = "commands/execute";
    public static final String LLM_PROVIDERS = "llm/listConfigurableProviders";
    public static final String LLM_DISCOVER_MODELS = "llm/discoverModels";
    public static final String CREDENTIALS_DESCRIBE = "credentials/describe";
    public static final String CREDENTIALS_SET = "credentials/set";
    public static final String CREDENTIALS_UNSET = "credentials/unset";

    /** Recorded for capability documentation; no IntelliJ UI exposes it yet. */
    public static final String MESSAGE_FEEDBACK_LIST = "messageFeedback/list";

    // Stream endpoints opened through /api/remote.mux.
    public static final String STREAM_WORKSPACE_FOLLOW = "workspace/follow";
    public static final String STREAM_SESSION_CONTROL = "session/control";
    public static final String STREAM_SESSION_FOLLOW = "session/follow";

    private DshRemoteContracts() {}

    /** RC Remote methods are exactly {@code <namespace>/<method>}, plus the two event endpoints. */
    public static void assertEndpoint(String endpoint) {
        if (EVENT_STREAM_ENDPOINT.equals(endpoint) || EVENT_RESULT_ENDPOINT.equals(endpoint)) {
            return;
        }
        String[] segments = endpoint.split("/", -1);
        boolean shaped = segments.length == 2 && !segments[0].isEmpty() && !segments[1].isEmpty();
        if (shaped) {
            for (int index = 0; index < endpoint.length() && shaped; index++) {
                char c = endpoint.charAt(index);
                shaped =
                        Character.isLetterOrDigit(c)
                                || c == '/'
                                || c == '_'
                                || c == '-'
                                || c == '.'
                                || c == '$';
            }
        }
        if (!shaped) {
            throw new IllegalArgumentException("Remote endpoint is invalid: " + endpoint);
        }
    }

    /** Build the Connection unary envelope. {@code args} is copied verbatim. */
    public static JsonObject requestEnvelope(String endpoint, JsonObject args) {
        assertEndpoint(endpoint);
        JsonObject payload = new JsonObject();
        payload.add("args", args == null ? new JsonObject() : args.deepCopy());
        JsonObject request = new JsonObject();
        request.addProperty("type", "client-request");
        request.addProperty("rpcId", UUID.randomUUID().toString());
        request.addProperty("method", endpoint);
        request.add("payload", payload);
        return request;
    }

    /** Opening frame for one logical mux stream. */
    public static JsonObject streamOpen(String streamId, String endpoint, JsonObject args) {
        assertEndpoint(endpoint);
        JsonObject payload = new JsonObject();
        payload.add("args", args == null ? new JsonObject() : args.deepCopy());
        JsonObject open = new JsonObject();
        open.addProperty("type", "open");
        open.addProperty("streamId", streamId);
        open.addProperty("endpoint", endpoint);
        open.add("payload", payload);
        return open;
    }

    /** Cancellation frame for one logical mux stream. */
    public static JsonObject streamCancel(String streamId) {
        JsonObject cancel = new JsonObject();
        cancel.addProperty("type", "cancel");
        cancel.addProperty("streamId", streamId);
        return cancel;
    }

    /**
     * Parse one unary HTTP response body into the endpoint value, or throw the structured error. A
     * successful envelope without {@code value} yields {@code JsonNull} (void methods).
     */
    public static JsonElement parseUnaryResponse(
            String endpoint, String body, String expectedRpcId, int httpStatus)
            throws DshRemoteException {
        if (httpStatus == 401 || httpStatus == 403) {
            throw DshRemoteException.auth(endpoint, httpStatus);
        }
        if (httpStatus == 404) {
            throw DshRemoteException.capability(endpoint);
        }
        if (httpStatus < 200 || httpStatus >= 300) {
            throw DshRemoteException.http(endpoint, httpStatus);
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(body);
        } catch (RuntimeException error) {
            throw DshRemoteException.protocol(endpoint, "Remote RPC returned invalid JSON", error);
        }
        if (!parsed.isJsonObject()) {
            throw DshRemoteException.protocol(
                    endpoint, "Remote RPC returned a non-object envelope", null);
        }
        JsonObject envelope = parsed.getAsJsonObject();
        if (!jsonString(envelope, "type").equals("server-response")
                || !envelope.has("rpcId")
                || !jsonString(envelope, "rpcId").equals(expectedRpcId)) {
            throw DshRemoteException.protocol(
                    endpoint, "Remote RPC response envelope did not match the request", null);
        }
        if (!envelope.has("result") || !envelope.get("result").isJsonObject()) {
            throw DshRemoteException.protocol(
                    endpoint, "Remote RPC response has no result envelope", null);
        }
        JsonObject result = envelope.getAsJsonObject("result");
        Boolean ok = jsonBoolean(result, "ok");
        if (ok == null) {
            throw DshRemoteException.protocol(endpoint, "Remote RPC result has no ok flag", null);
        }
        if (!ok) {
            if (!result.has("error") || !result.get("error").isJsonObject()) {
                throw DshRemoteException.protocol(
                        endpoint, "Remote RPC failure has no error object", null);
            }
            JsonObject error = result.getAsJsonObject("error");
            String code = jsonString(error, "code");
            String message = jsonString(error, "message");
            if (code == null || code.isBlank()) {
                throw DshRemoteException.protocol(
                        endpoint, "Remote RPC failure has no error code", null);
            }
            JsonObject details =
                    error.has("details") && error.get("details").isJsonObject()
                            ? error.getAsJsonObject("details")
                            : new JsonObject();
            throw DshRemoteException.remote(
                    endpoint, code, message == null ? "" : message, details);
        }
        if (result.has("value") && !isRemoteJson(result.get("value"))) {
            throw DshRemoteException.protocol(endpoint, "Remote RPC value is not JSON-safe", null);
        }
        return result.has("value")
                ? result.get("value").deepCopy()
                : com.google.gson.JsonNull.INSTANCE;
    }

    /** Minimal JSON-safety check mirroring the Gateway's lossless-JSON boundary. */
    public static boolean isRemoteJson(JsonElement value) {
        if (value == null || value.isJsonNull()) return true;
        if (value.isJsonPrimitive()) {
            try {
                if (value.getAsJsonPrimitive().isNumber()) {
                    double numeric = value.getAsDouble();
                    return Double.isFinite(numeric);
                }
                return true;
            } catch (RuntimeException ignored) {
                return false;
            }
        }
        if (value.isJsonArray()) {
            for (JsonElement item : value.getAsJsonArray()) {
                if (!isRemoteJson(item)) return false;
            }
            return true;
        }
        if (value.isJsonObject()) {
            for (var entry : value.getAsJsonObject().entrySet()) {
                if (!isRemoteJson(entry.getValue())) return false;
            }
            return true;
        }
        return false;
    }

    /** Session address for an ordinary session. */
    public static JsonObject sessionAddress(String sessionId) {
        JsonObject address = new JsonObject();
        address.addProperty("kind", "session");
        address.addProperty("sessionId", sessionId);
        return address;
    }

    /** Session address for one direct subagent child. */
    public static JsonObject subagentAddress(
            String parentSessionId, String childSessionId, String mode) {
        JsonObject address = new JsonObject();
        address.addProperty("kind", "subagent");
        address.addProperty("parentSessionId", parentSessionId);
        address.addProperty("childSessionId", childSessionId);
        address.addProperty("mode", mode);
        return address;
    }

    // ---------------------------------------------------------------------------
    // args builders, one per endpoint; each mirrors the Host method's parameters.
    // ---------------------------------------------------------------------------

    /**
     * `session/list(_request)`; the baseline request is an empty object. The Host's descriptor
     * names this parameter {@code _request} — the one wire name that differs from the method
     * signature — verified against the live `dsh-v0.1.2-rc.1` Runtime.
     */
    public static JsonObject argsSessionList() {
        JsonObject args = new JsonObject();
        JsonObject request = new JsonObject();
        args.add("_request", request);
        return args;
    }

    /** `session/search(request)` with `{request:{query}}`. */
    public static JsonObject argsSessionSearch(String query) {
        JsonObject request = new JsonObject();
        request.addProperty("query", query == null ? "" : query);
        return withRequest(request);
    }

    /** `session/create(request)` with workspace or cwd plus an optional preset. */
    public static JsonObject argsSessionCreate(String cwd, String workspaceId, String agentPreset) {
        JsonObject request = new JsonObject();
        if (workspaceId != null && !workspaceId.isBlank()) {
            request.addProperty("workspaceId", workspaceId);
        } else if (cwd != null && !cwd.isBlank()) {
            request.addProperty("cwd", cwd);
        }
        if (agentPreset != null && !agentPreset.isBlank()) {
            request.addProperty("agentPreset", agentPreset);
        }
        return withRequest(request);
    }

    /** `session/rename(request)` with `{request:{sessionId,title}}`. */
    public static JsonObject argsSessionRename(String sessionId, String title) {
        JsonObject request = new JsonObject();
        request.addProperty("sessionId", sessionId);
        request.addProperty("title", title == null ? "" : title);
        return withRequest(request);
    }

    /** `session/fork(request)` with `{request:{sessionId,atSeq?}}`. */
    public static JsonObject argsSessionFork(String sessionId, Long atSeq) {
        JsonObject request = new JsonObject();
        request.addProperty("sessionId", sessionId);
        if (atSeq != null) request.addProperty("atSeq", atSeq);
        return withRequest(request);
    }

    /** `session/prompt(request)`; every field lives inside `args.request`. */
    public static JsonObject argsSessionPrompt(
            String requestId,
            String sessionId,
            String mode,
            JsonArray content,
            String clientTimeZone) {
        JsonObject request = new JsonObject();
        request.addProperty("requestId", requestId);
        request.addProperty("sessionId", sessionId);
        request.addProperty("mode", mode);
        request.add("content", content == null ? new JsonArray() : content.deepCopy());
        if (clientTimeZone != null && !clientTimeZone.isBlank()) {
            request.addProperty("clientTimeZone", clientTimeZone);
        }
        return withRequest(request);
    }

    /** `session/cancel(request)` with `{request:{sessionId}}`. */
    public static JsonObject argsSessionCancel(String sessionId) {
        JsonObject request = new JsonObject();
        request.addProperty("sessionId", sessionId);
        return withRequest(request);
    }

    /** `session/updateQueue(request)` with `{request:{sessionId,itemId,action}}`. */
    public static JsonObject argsSessionUpdateQueue(
            String sessionId, String itemId, JsonObject action) {
        JsonObject request = new JsonObject();
        request.addProperty("sessionId", sessionId);
        request.addProperty("itemId", itemId);
        request.add("action", action);
        return withRequest(request);
    }

    /** `session/attachment(request)` with `{request:{sessionId,attachmentId}}`. */
    public static JsonObject argsSessionAttachment(String sessionId, String attachmentId) {
        JsonObject request = new JsonObject();
        request.addProperty("sessionId", sessionId);
        request.addProperty("attachmentId", attachmentId);
        return withRequest(request);
    }

    /** `session/modelCatalog()` takes no arguments at all. */
    public static JsonObject argsEmpty() {
        return new JsonObject();
    }

    /**
     * `session/selectModel(request)` with `{request:{sessionId,provider,model,reasoningEffort?}}`.
     */
    public static JsonObject argsSessionSelectModel(
            String sessionId, String provider, String model, String reasoningEffort) {
        JsonObject request = new JsonObject();
        request.addProperty("sessionId", sessionId);
        request.addProperty("provider", provider);
        request.addProperty("model", model);
        if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            request.addProperty("reasoningEffort", reasoningEffort);
        }
        return withRequest(request);
    }

    /** `session/page(request)` for one backwards history page. */
    public static JsonObject argsSessionPage(
            JsonObject address, long throughSeq, Long beforeSeq, int maxMessages) {
        JsonObject request = new JsonObject();
        request.add("address", address.deepCopy());
        request.addProperty("throughSeq", throughSeq);
        if (beforeSeq != null) request.addProperty("beforeSeq", beforeSeq);
        request.addProperty("maxMessages", Math.max(1, maxMessages));
        return withRequest(request);
    }

    /** `session/follow(request)` for one live history stream. */
    public static JsonObject argsSessionFollow(JsonObject address, int maxMessages) {
        JsonObject request = new JsonObject();
        request.add("address", address.deepCopy());
        if (maxMessages > 0) request.addProperty("maxMessages", maxMessages);
        return withRequest(request);
    }

    /** `workspace/create(request)` with `{request:{path}}`. */
    public static JsonObject argsWorkspaceCreate(String path) {
        JsonObject request = new JsonObject();
        request.addProperty("path", path == null ? "" : path);
        return withRequest(request);
    }

    /** `workspace/rename(request)` with `{request:{workspaceId,title}}`. */
    public static JsonObject argsWorkspaceRename(String workspaceId, String title) {
        JsonObject request = new JsonObject();
        request.addProperty("workspaceId", workspaceId);
        request.addProperty("title", title == null ? "" : title);
        return withRequest(request);
    }

    /** `workspace/delete(request)` with `{request:{workspaceId}}`. */
    public static JsonObject argsWorkspaceDelete(String workspaceId) {
        JsonObject request = new JsonObject();
        request.addProperty("workspaceId", workspaceId);
        return withRequest(request);
    }

    /** `workspace/archiveSession(request)` with `{request:{sessionId}}`. */
    public static JsonObject argsWorkspaceArchiveSession(String sessionId) {
        JsonObject request = new JsonObject();
        request.addProperty("sessionId", sessionId);
        return withRequest(request);
    }

    /** `agentPresets/select(agentId, agentPreset)`. */
    public static JsonObject argsAgentPresetSelect(String agentId, String agentPreset) {
        JsonObject args = new JsonObject();
        args.addProperty("agentId", agentId);
        args.addProperty("agentPreset", agentPreset);
        return args;
    }

    /** `agentPresets/read(agentPreset)`. */
    public static JsonObject argsAgentPresetRead(String agentPreset) {
        JsonObject args = new JsonObject();
        args.addProperty("agentPreset", agentPreset);
        return args;
    }

    /** `agentPresets/copy(from, id, name?)`. */
    public static JsonObject argsAgentPresetCopy(String from, String id, String name) {
        JsonObject args = new JsonObject();
        args.addProperty("from", from);
        args.addProperty("id", id);
        if (name != null && !name.isBlank()) args.addProperty("name", name);
        return args;
    }

    /** `agentPresets/deletePreset(id)`. */
    public static JsonObject argsAgentPresetDelete(String id) {
        JsonObject args = new JsonObject();
        args.addProperty("id", id);
        return args;
    }

    /** `settings/openAgentPresetDirectory(agentPreset)`. */
    public static JsonObject argsSettingsOpenPresetDirectory(String agentPreset) {
        JsonObject args = new JsonObject();
        args.addProperty("agentPreset", agentPreset);
        return args;
    }

    /** `settings/mutate(ns, ops, expectedRevision?)`; the revision is omitted when unknown. */
    public static JsonObject argsSettingsMutate(String ns, JsonArray ops, Long expectedRevision) {
        JsonObject args = new JsonObject();
        args.addProperty("ns", ns);
        args.add("ops", ops == null ? new JsonArray() : ops.deepCopy());
        if (expectedRevision != null) args.addProperty("expectedRevision", expectedRevision);
        return args;
    }

    /** `goals/create(agentId, request)` with `{request:{objective,maxGoalRounds?}}`. */
    public static JsonObject argsGoalCreate(
            String agentId, String objective, Integer maxGoalRounds) {
        JsonObject args = new JsonObject();
        args.addProperty("agentId", agentId);
        JsonObject request = new JsonObject();
        request.addProperty("objective", objective == null ? "" : objective);
        if (maxGoalRounds != null) request.addProperty("maxGoalRounds", maxGoalRounds);
        args.add("request", request);
        return args;
    }

    /** `goals/edit(agentId, ref, request)`. */
    public static JsonObject argsGoalEdit(
            String agentId, JsonObject ref, String objective, Integer maxGoalRounds) {
        JsonObject args = new JsonObject();
        args.addProperty("agentId", agentId);
        args.add("ref", ref.deepCopy());
        JsonObject request = new JsonObject();
        if (objective != null) request.addProperty("objective", objective);
        if (maxGoalRounds != null) request.addProperty("maxGoalRounds", maxGoalRounds);
        args.add("request", request);
        return args;
    }

    /** `goals/pause|resume|complete|clear(agentId, ref)`. */
    public static JsonObject argsGoalRef(String agentId, JsonObject ref) {
        JsonObject args = new JsonObject();
        args.addProperty("agentId", agentId);
        args.add("ref", ref.deepCopy());
        return args;
    }

    /** `subagents/list(parentSessionId)`. */
    public static JsonObject argsSubagentsList(String parentSessionId) {
        JsonObject args = new JsonObject();
        args.addProperty("parentSessionId", parentSessionId);
        return args;
    }

    /**
     * `subagents/prompt(parentSessionId, childSessionId, mode, requestId, content,
     * clientTimeZone?)`.
     */
    public static JsonObject argsSubagentPrompt(
            String parentSessionId,
            String childSessionId,
            String mode,
            String requestId,
            JsonArray content,
            String clientTimeZone) {
        JsonObject args = new JsonObject();
        args.addProperty("parentSessionId", parentSessionId);
        args.addProperty("childSessionId", childSessionId);
        args.addProperty("mode", mode);
        args.addProperty("requestId", requestId);
        args.add("content", content == null ? new JsonArray() : content.deepCopy());
        if (clientTimeZone != null && !clientTimeZone.isBlank()) {
            args.addProperty("clientTimeZone", clientTimeZone);
        }
        return args;
    }

    /** `subagents/interruptByParent(parentSessionId, childSessionId, mode)`. */
    public static JsonObject argsSubagentInterrupt(
            String parentSessionId, String childSessionId, String mode) {
        JsonObject args = new JsonObject();
        args.addProperty("parentSessionId", parentSessionId);
        args.addProperty("childSessionId", childSessionId);
        args.addProperty("mode", mode);
        return args;
    }

    /** `skills/list(request)` with `{request:{sessionId}}`. */
    public static JsonObject argsSkillsList(String sessionId) {
        JsonObject request = new JsonObject();
        request.addProperty("sessionId", sessionId);
        return withRequest(request);
    }

    /** `commands/list(agentId)`. */
    public static JsonObject argsCommandsList(String agentId) {
        JsonObject args = new JsonObject();
        args.addProperty("agentId", agentId);
        return args;
    }

    /** `commands/execute(agentId, line, images)`. */
    public static JsonObject argsCommandsExecute(String agentId, String line, JsonArray images) {
        JsonObject args = new JsonObject();
        args.addProperty("agentId", agentId);
        args.addProperty("line", line == null ? "" : line);
        args.add("images", images == null ? new JsonArray() : images.deepCopy());
        return args;
    }

    /** `llm/discoverModels(settingsNs, request)`. */
    public static JsonObject argsLlmDiscoverModels(String settingsNs, JsonObject draft) {
        JsonObject args = new JsonObject();
        args.addProperty("settingsNs", settingsNs);
        JsonObject request = draft == null ? new JsonObject() : draft.deepCopy();
        if (!request.has("settingsNs")) request.addProperty("settingsNs", settingsNs);
        args.add("request", request);
        return args;
    }

    /** `credentials/describe(refs)`. */
    public static JsonObject argsCredentialsDescribe(JsonArray refs) {
        JsonObject args = new JsonObject();
        args.add("refs", refs == null ? new JsonArray() : refs.deepCopy());
        return args;
    }

    /** `credentials/set(ref, value)`. */
    public static JsonObject argsCredentialsSet(String ref, String value) {
        JsonObject args = new JsonObject();
        args.addProperty("ref", ref);
        args.addProperty("value", value);
        return args;
    }

    /** `credentials/unset(ref)`. */
    public static JsonObject argsCredentialsUnset(String ref) {
        JsonObject args = new JsonObject();
        args.addProperty("ref", ref);
        return args;
    }

    /** `session/openWorkspacePath(request)` with `{request:{path}}`. */
    public static JsonObject argsOpenWorkspacePath(String path) {
        JsonObject request = new JsonObject();
        request.addProperty("path", path == null ? "" : path);
        return withRequest(request);
    }

    /** `$events/result(clientId, eventId, outcome)`; the sole reserved unary endpoint. */
    public static JsonObject argsEventResult(String clientId, String eventId, JsonObject outcome) {
        JsonObject args = new JsonObject();
        args.addProperty("clientId", clientId);
        args.addProperty("eventId", eventId);
        args.add("outcome", outcome.deepCopy());
        return args;
    }

    private static JsonObject withRequest(JsonObject request) {
        JsonObject args = new JsonObject();
        args.add("request", request);
        return args;
    }

    private static String jsonString(JsonObject object, String key) {
        return object != null
                        && object.has(key)
                        && object.get(key).isJsonPrimitive()
                        && object.get(key).getAsJsonPrimitive().isString()
                ? object.get(key).getAsString()
                : null;
    }

    private static Boolean jsonBoolean(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) return null;
        try {
            return object.get(key).getAsBoolean();
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
