package top.harcochen.dsh.remote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import org.jetbrains.annotations.NotNull;
import top.harcochen.dsh.DshRuntimeService;
import top.harcochen.dsh.DshSettingsState;

/**
 * Stable domain facade over the RC Remote protocol.
 *
 * <p>UI surfaces (Tool Window, Trace dialog, actions, settings) call domain methods and consume
 * immutable snapshots published by this service; they never touch endpoints, {@code args},
 * streamIds, generations, or RemoteError wire details. The lifecycle is bound to the IntelliJ
 * {@link Project}: disposing the project cancels logical streams and closes the socket and
 * executors, while Tool Window rebuilds only add and remove address subscriptions.
 */
public final class DshRemoteService implements Disposable {
    private static final Logger LOG = Logger.getInstance(DshRemoteService.class);

    public static final String PHASE_STOPPED = "stopped";
    public static final String PHASE_CONNECTING = "connecting";
    public static final String PHASE_CONNECTED = "connected";
    public static final String PHASE_RECONNECTING = "reconnecting";

    private final Project project;
    private final DshRuntimeService runtime;
    private final Consumer<DshRuntimeService.RuntimeStatus> statusListener = this::onRuntimeStatus;
    private final DshRemoteAuth auth;
    private final DshRemoteUnaryClient unary;
    private final DshRemoteConnection connection;
    private final ExecutorService operations;
    private final List<Consumer<DshRemoteState.Snapshot>> listeners = new ArrayList<>();
    private final Object listenerLock = new Object();

    private volatile DshRemoteState.Snapshot snapshot =
            new DshRemoteState.Snapshot(
                    PHASE_STOPPED,
                    null,
                    0,
                    new JsonArray(),
                    Map.of(),
                    Map.of(),
                    List.of(),
                    java.util.Set.of());

    public DshRemoteService(@NotNull Project project) {
        this.project = project;
        this.runtime = DshRuntimeService.getInstance(project);
        this.operations =
                AppExecutorUtil.createBoundedApplicationPoolExecutor(
                        "dsh-remote-ops",
                        Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
        this.auth = runtime.auth();
        this.unary =
                new DshRemoteUnaryClient(
                        runtime::getUrl,
                        () -> DshSettingsState.getInstance(project).requestTimeoutMs,
                        auth,
                        error -> LOG.warn("DSH Runtime authentication failed: " + error.display()));
        this.connection =
                new DshRemoteConnection(
                        runtime::getUrl,
                        unary,
                        auth::headers,
                        new ConnectionCallbacks(),
                        message -> LOG.debug(message));
        runtime.addStatusListener(statusListener);
        if (runtime.getStatus().state == DshRuntimeService.RuntimeState.RUNNING
                && runtime.getUrl() != null) {
            connection.start();
        }
    }

    public static DshRemoteService getInstance(@NotNull Project project) {
        return project.getService(DshRemoteService.class);
    }

    /** The empty pre-connection snapshot. */
    public static DshRemoteState.Snapshot emptySnapshot() {
        return new DshRemoteState.Snapshot(
                PHASE_STOPPED,
                null,
                0,
                new JsonArray(),
                Map.of(),
                Map.of(),
                List.of(),
                java.util.Set.of());
    }

    // ---------------------------------------------------------------------------
    // subscription API
    // ---------------------------------------------------------------------------

    /** Subscribe to immutable snapshots; invoked on the connection executor, never on the EDT. */
    public void addListener(Consumer<DshRemoteState.Snapshot> listener) {
        synchronized (listenerLock) {
            listeners.add(listener);
        }
    }

    public void removeListener(Consumer<DshRemoteState.Snapshot> listener) {
        synchronized (listenerLock) {
            listeners.remove(listener);
        }
    }

    /** Latest published snapshot; always non-null. */
    public DshRemoteState.Snapshot snapshot() {
        return snapshot;
    }

    public String phase() {
        return snapshot.phase;
    }

    /** Extend the environment diagnostics with Remote-specific facts. */
    public String diagnostics() {
        DshRemoteState.Snapshot current = snapshot;
        StringBuilder report = new StringBuilder();
        report.append("Remote protocol target: ")
                .append(DshRemoteContracts.TARGET_TAG)
                .append(" (")
                .append(DshRemoteContracts.TARGET_COMMIT)
                .append(")\n");
        report.append("Remote phase: ").append(current.phase);
        if (current.message != null && !current.message.isBlank()) {
            report.append(" — ").append(current.message);
        }
        report.append('\n');
        report.append("Remote generation: ").append(current.generation).append('\n');
        report.append("Remote sessions tracked: ").append(current.catalog.size()).append('\n');
        report.append("Remote followed addresses: ").append(current.follows.size()).append('\n');
        report.append(connection.diagnostics()).append('\n');
        return report.toString();
    }

    // ---------------------------------------------------------------------------
    // address subscriptions
    // ---------------------------------------------------------------------------

    /** Follow one session's live history (reference-counted). */
    public void retainSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        connection.retainFollow(DshRemoteContracts.sessionAddress(sessionId));
    }

    public void releaseSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        connection.releaseFollow(DshRemoteContracts.sessionAddress(sessionId));
    }

    /** Follow one subagent address for the duration of a preview. */
    public void retainSubagent(String parentSessionId, String childSessionId, String mode) {
        connection.retainFollow(
                DshRemoteContracts.subagentAddress(parentSessionId, childSessionId, mode));
    }

    public void releaseSubagent(String parentSessionId, String childSessionId, String mode) {
        connection.releaseFollow(
                DshRemoteContracts.subagentAddress(parentSessionId, childSessionId, mode));
    }

    // ---------------------------------------------------------------------------
    // interactive events
    // ---------------------------------------------------------------------------

    /**
     * Submit one waterfall outcome. The result is retired locally on success and marked failed
     * otherwise; the returned message is null when the answer reached the Runtime.
     */
    public String answerInteraction(String sessionId, String key, JsonElement outcomeValue) {
        String eventId =
                key != null && key.length() > 2 && (key.startsWith("a:") || key.startsWith("q:"))
                        ? key.substring(2)
                        : key;
        if (eventId == null || eventId.isBlank() || sessionId == null || sessionId.isBlank()) {
            return "The interaction is no longer available.";
        }
        connection.interactionStatus(sessionId, key, "submitting", null, false);
        JsonObject outcome = DshRemoteEventClient.resultOutcome(outcomeValue);
        DshRemoteException failure = connection.answer(eventId, outcome);
        if (failure == null) {
            connection.interactionStatus(sessionId, key, "resolved", null, true);
            return null;
        }
        connection.interactionStatus(sessionId, key, "failed", failure.display(), false);
        return failure.display();
    }

    /** True when the durable echo of one prompt requestId has been observed. */
    public boolean hasDurableEcho(String sessionId, String requestId) {
        DshRemoteState.Snapshot current = snapshot;
        DshRemoteState.SessionView view = current.sessions.get(sessionId);
        return view != null && view.durableRequestIds.contains(requestId);
    }

    // ---------------------------------------------------------------------------
    // unary domain API (blocking; only call from background executors)
    // ---------------------------------------------------------------------------

    public JsonObject createSession(String cwd, String workspaceId, String agentPreset)
            throws DshRemoteException {
        return objectValue(
                unary.call(
                        DshRemoteContracts.SESSION_CREATE,
                        DshRemoteContracts.argsSessionCreate(cwd, workspaceId, agentPreset)));
    }

    public JsonArray searchSessions(String query) throws DshRemoteException {
        JsonObject value =
                objectValue(
                        unary.call(
                                DshRemoteContracts.SESSION_SEARCH,
                                DshRemoteContracts.argsSessionSearch(query)));
        return arrayOrEmpty(value, "items");
    }

    public String renameSession(String sessionId, String title) throws DshRemoteException {
        JsonObject value =
                objectValue(
                        unary.call(
                                DshRemoteContracts.SESSION_RENAME,
                                DshRemoteContracts.argsSessionRename(sessionId, title)));
        return value.has("title") && !stringOf(value, "title").isBlank()
                ? stringOf(value, "title")
                : title;
    }

    public String forkSession(String sessionId, Long atSeq) throws DshRemoteException {
        JsonObject value =
                objectValue(
                        unary.call(
                                DshRemoteContracts.SESSION_FORK,
                                DshRemoteContracts.argsSessionFork(sessionId, atSeq)));
        return stringOf(value, "sessionId");
    }

    public void archiveSession(String sessionId) throws DshRemoteException {
        unary.call(
                DshRemoteContracts.WORKSPACE_ARCHIVE_SESSION,
                DshRemoteContracts.argsWorkspaceArchiveSession(sessionId));
    }

    public JsonObject prompt(
            String requestId,
            String sessionId,
            String mode,
            JsonArray content,
            String clientTimeZone)
            throws DshRemoteException {
        return objectValue(
                unary.call(
                        DshRemoteContracts.SESSION_PROMPT,
                        DshRemoteContracts.argsSessionPrompt(
                                requestId, sessionId, mode, content, clientTimeZone)));
    }

    public void cancel(String sessionId) throws DshRemoteException {
        unary.call(
                DshRemoteContracts.SESSION_CANCEL, DshRemoteContracts.argsSessionCancel(sessionId));
    }

    public void updateQueue(String sessionId, String itemId, JsonObject action)
            throws DshRemoteException {
        unary.call(
                DshRemoteContracts.SESSION_UPDATE_QUEUE,
                DshRemoteContracts.argsSessionUpdateQueue(sessionId, itemId, action));
    }

    public JsonObject attachment(String sessionId, String attachmentId) throws DshRemoteException {
        return objectValue(
                unary.call(
                        DshRemoteContracts.SESSION_ATTACHMENT,
                        DshRemoteContracts.argsSessionAttachment(sessionId, attachmentId)));
    }

    public JsonObject modelCatalog() throws DshRemoteException {
        return objectValue(
                unary.call(
                        DshRemoteContracts.SESSION_MODEL_CATALOG, DshRemoteContracts.argsEmpty()));
    }

    public void selectModel(String sessionId, String provider, String model, String reasoningEffort)
            throws DshRemoteException {
        unary.call(
                DshRemoteContracts.SESSION_SELECT_MODEL,
                DshRemoteContracts.argsSessionSelectModel(
                        sessionId, provider, model, reasoningEffort));
    }

    public JsonArray listCommands(String sessionId) throws DshRemoteException {
        JsonElement value =
                unary.call(
                        DshRemoteContracts.COMMANDS_LIST,
                        DshRemoteContracts.argsCommandsList(sessionId));
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    public JsonElement executeCommand(String sessionId, String line) throws DshRemoteException {
        return unary.call(
                DshRemoteContracts.COMMANDS_EXECUTE,
                DshRemoteContracts.argsCommandsExecute(sessionId, line, new JsonArray()));
    }

    public JsonArray listSkills(String sessionId) throws DshRemoteException {
        JsonObject value =
                objectValue(
                        unary.call(
                                DshRemoteContracts.SKILLS_LIST,
                                DshRemoteContracts.argsSkillsList(sessionId)));
        return arrayOrEmpty(value, "skills");
    }

    public JsonObject createWorkspace(String path) throws DshRemoteException {
        return objectValue(
                unary.call(
                        DshRemoteContracts.WORKSPACE_CREATE,
                        DshRemoteContracts.argsWorkspaceCreate(path)));
    }

    public JsonObject renameWorkspace(String workspaceId, String title) throws DshRemoteException {
        return objectValue(
                unary.call(
                        DshRemoteContracts.WORKSPACE_RENAME,
                        DshRemoteContracts.argsWorkspaceRename(workspaceId, title)));
    }

    public void deleteWorkspace(String workspaceId) throws DshRemoteException {
        unary.call(
                DshRemoteContracts.WORKSPACE_DELETE,
                DshRemoteContracts.argsWorkspaceDelete(workspaceId));
    }

    public JsonObject agentPresetCatalog() throws DshRemoteException {
        return objectValue(
                unary.call(DshRemoteContracts.AGENT_PRESETS_LIST, DshRemoteContracts.argsEmpty()));
    }

    public void selectAgentPreset(String agentId, String agentPreset) throws DshRemoteException {
        unary.call(
                DshRemoteContracts.AGENT_PRESETS_SELECT,
                DshRemoteContracts.argsAgentPresetSelect(agentId, agentPreset));
    }

    public JsonObject readAgentPreset(String agentPreset) throws DshRemoteException {
        return objectValue(
                unary.call(
                        DshRemoteContracts.AGENT_PRESETS_READ,
                        DshRemoteContracts.argsAgentPresetRead(agentPreset)));
    }

    public void copyAgentPreset(String from, String agentPreset, String name)
            throws DshRemoteException {
        unary.call(
                DshRemoteContracts.AGENT_PRESETS_COPY,
                DshRemoteContracts.argsAgentPresetCopy(from, agentPreset, name));
    }

    public void removeAgentPreset(String agentPreset) throws DshRemoteException {
        unary.call(
                DshRemoteContracts.AGENT_PRESETS_DELETE,
                DshRemoteContracts.argsAgentPresetDelete(agentPreset));
    }

    public void openAgentPresetDirectory(String agentPreset) throws DshRemoteException {
        unary.call(
                DshRemoteContracts.SETTINGS_OPEN_PRESET_DIRECTORY,
                DshRemoteContracts.argsSettingsOpenPresetDirectory(agentPreset));
    }

    public JsonObject describeSettings() throws DshRemoteException {
        return objectValue(
                unary.call(DshRemoteContracts.SETTINGS_DESCRIBE, DshRemoteContracts.argsEmpty()));
    }

    public JsonObject mutateSettings(String ns, JsonArray ops, Long expectedRevision)
            throws DshRemoteException {
        return objectValue(
                unary.call(
                        DshRemoteContracts.SETTINGS_MUTATE,
                        DshRemoteContracts.argsSettingsMutate(ns, ops, expectedRevision)));
    }

    public void openSettingsDocument() throws DshRemoteException {
        unary.call(DshRemoteContracts.SETTINGS_OPEN_DOCUMENT, DshRemoteContracts.argsEmpty());
    }

    public JsonObject createGoal(String agentId, String objective, Integer maxGoalRounds)
            throws DshRemoteException {
        return objectValue(
                unary.call(
                        DshRemoteContracts.GOALS_CREATE,
                        DshRemoteContracts.argsGoalCreate(agentId, objective, maxGoalRounds)));
    }

    public JsonObject editGoal(
            String agentId, JsonObject ref, String objective, Integer maxGoalRounds)
            throws DshRemoteException {
        return objectValue(
                unary.call(
                        DshRemoteContracts.GOALS_EDIT,
                        DshRemoteContracts.argsGoalEdit(agentId, ref, objective, maxGoalRounds)));
    }

    public JsonObject mutateGoal(String method, String agentId, JsonObject ref)
            throws DshRemoteException {
        return objectValue(unary.call(method, DshRemoteContracts.argsGoalRef(agentId, ref)));
    }

    public JsonObject subagents(String parentSessionId) throws DshRemoteException {
        return objectValue(
                unary.call(
                        DshRemoteContracts.SUBAGENTS_LIST,
                        DshRemoteContracts.argsSubagentsList(parentSessionId)));
    }

    public JsonObject promptSubagent(String parentSessionId, String childSessionId, String text)
            throws DshRemoteException {
        JsonArray content = new JsonArray();
        JsonObject part = new JsonObject();
        part.addProperty("type", "text");
        part.addProperty("text", text);
        content.add(part);
        return objectValue(
                unary.call(
                        DshRemoteContracts.SUBAGENTS_PROMPT,
                        DshRemoteContracts.argsSubagentPrompt(
                                parentSessionId,
                                childSessionId,
                                "continuable",
                                java.util.UUID.randomUUID().toString(),
                                content,
                                java.util.TimeZone.getDefault().getID())));
    }

    public void interruptSubagent(String parentSessionId, String childSessionId)
            throws DshRemoteException {
        unary.call(
                DshRemoteContracts.SUBAGENTS_INTERRUPT,
                DshRemoteContracts.argsSubagentInterrupt(
                        parentSessionId, childSessionId, "continuable"));
    }

    public JsonArray providers() throws DshRemoteException {
        JsonElement value =
                unary.call(DshRemoteContracts.LLM_PROVIDERS, DshRemoteContracts.argsEmpty());
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    public JsonObject discoverLlmModels(String settingsNs, JsonObject draft)
            throws DshRemoteException {
        return objectValue(
                unary.call(
                        DshRemoteContracts.LLM_DISCOVER_MODELS,
                        DshRemoteContracts.argsLlmDiscoverModels(settingsNs, draft)));
    }

    public JsonObject describeCredentials(JsonArray refs) throws DshRemoteException {
        return objectValue(
                unary.call(
                        DshRemoteContracts.CREDENTIALS_DESCRIBE,
                        DshRemoteContracts.argsCredentialsDescribe(refs)));
    }

    public void setCredential(String ref, String value) throws DshRemoteException {
        unary.call(
                DshRemoteContracts.CREDENTIALS_SET,
                DshRemoteContracts.argsCredentialsSet(ref, value));
    }

    public void unsetCredential(String ref) throws DshRemoteException {
        unary.call(
                DshRemoteContracts.CREDENTIALS_UNSET, DshRemoteContracts.argsCredentialsUnset(ref));
    }

    /** One message-aligned backwards history page for any session address. */
    public JsonObject pageHistory(
            JsonObject address, long throughSeq, Long beforeSeq, int maxMessages)
            throws DshRemoteException {
        return objectValue(
                unary.call(
                        DshRemoteContracts.SESSION_PAGE,
                        DshRemoteContracts.argsSessionPage(
                                address, throughSeq, beforeSeq, maxMessages)));
    }

    /**
     * The complete bounded event ledger used by the Trace dialog: one opening follow snapshot plus
     * backwards pages merged by sequence.
     */
    public JsonObject traceHistory(String sessionId, int pageSize) throws DshRemoteException {
        JsonObject address = DshRemoteContracts.sessionAddress(sessionId);
        JsonObject tail = followSnapshotOnce(address, pageSize);
        long throughSeq = longOf(tail, "cursor", -1L);
        TreeMap<Long, JsonElement> merged = new TreeMap<>();
        addHistoryRecords(merged, tail);
        boolean hasMore = boolOf(tail, "hasMore");
        int pages = 0;
        while (hasMore && pages < 400 && merged.size() < 100_000) {
            Long beforeSeq = merged.isEmpty() ? null : merged.firstKey();
            if (beforeSeq == null || beforeSeq <= 0 || throughSeq < 0) break;
            JsonObject page = pageHistory(address, throughSeq, beforeSeq, pageSize);
            int previous = merged.size();
            addHistoryRecords(merged, page);
            hasMore = boolOf(page, "hasMore");
            Long next = merged.isEmpty() ? null : merged.firstKey();
            if (merged.size() == previous || (next != null && next >= beforeSeq)) break;
            pages++;
        }
        JsonObject result = new JsonObject();
        JsonArray events = new JsonArray();
        for (JsonElement record : merged.values()) events.add(record);
        result.add("events", events);
        result.addProperty("hasMore", false);
        JsonObject projections =
                tail.has("projections") && tail.get("projections").isJsonObject()
                        ? tail.getAsJsonObject("projections")
                        : null;
        if (projections != null) result.add("projections", projections);
        return result;
    }

    /**
     * One-shot follow snapshot for any address: opens the stream, waits for the opening snapshot,
     * cancels, and returns the raw frame. Runs on the calling (background) thread.
     */
    public JsonObject followSnapshotOnce(JsonObject address, int maxMessages)
            throws DshRemoteException {
        CompletableFuture<JsonObject> opened = new CompletableFuture<>();
        CompletableFuture<DshRemoteMuxClient.StreamHandle> handleFuture = new CompletableFuture<>();
        connection
                .executor()
                .execute(
                        () -> {
                            DshRemoteMuxClient.StreamHandle handle =
                                    connection.openOneShot(
                                            address,
                                            maxMessages,
                                            item -> {
                                                if (item != null
                                                        && "snapshot".equals(stringOf(item, "type"))
                                                        && !opened.isDone()) {
                                                    opened.complete(item.deepCopy());
                                                }
                                            },
                                            error -> {
                                                if (opened.isDone()) return;
                                                if (error == null) {
                                                    opened.completeExceptionally(
                                                            DshRemoteException.carrier(
                                                                    DshRemoteContracts
                                                                            .STREAM_SESSION_FOLLOW,
                                                                    "Remote history stream ended without a snapshot",
                                                                    null));
                                                } else {
                                                    opened.completeExceptionally(
                                                            DshRemoteException.fromStreamError(
                                                                    error));
                                                }
                                            });
                            handleFuture.complete(handle);
                        });
        try {
            JsonObject frame = opened.get(30, java.util.concurrent.TimeUnit.SECONDS);
            DshRemoteMuxClient.StreamHandle handle =
                    handleFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);
            connection.executor().execute(handle::cancel);
            return frame;
        } catch (java.util.concurrent.TimeoutException error) {
            opened.cancel(true);
            cancelOneShot(handleFuture);
            throw DshRemoteException.carrier(
                    DshRemoteContracts.STREAM_SESSION_FOLLOW,
                    "Remote history snapshot timed out",
                    error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            cancelOneShot(handleFuture);
            throw DshRemoteException.carrier(
                    DshRemoteContracts.STREAM_SESSION_FOLLOW,
                    "Remote history snapshot was interrupted",
                    error);
        } catch (java.util.concurrent.ExecutionException error) {
            cancelOneShot(handleFuture);
            Throwable cause = error.getCause() == null ? error : error.getCause();
            if (cause instanceof DshRemoteException remote) throw remote;
            throw DshRemoteException.carrier(
                    DshRemoteContracts.STREAM_SESSION_FOLLOW, String.valueOf(cause), cause);
        }
    }

    private void cancelOneShot(CompletableFuture<DshRemoteMuxClient.StreamHandle> handleFuture) {
        if (!handleFuture.isDone() || handleFuture.isCompletedExceptionally()) return;
        DshRemoteMuxClient.StreamHandle handle = handleFuture.join();
        if (handle == null) return;
        // Keep every frame send sequenced on the connection executor.
        connection.executor().execute(handle::cancel);
    }

    // ---------------------------------------------------------------------------
    // runtime status bridge
    // ---------------------------------------------------------------------------

    private void onRuntimeStatus(DshRuntimeService.RuntimeStatus status) {
        if (status.state == DshRuntimeService.RuntimeState.RUNNING && status.url != null) {
            connection.start();
        } else if (status.state == DshRuntimeService.RuntimeState.STOPPED
                || status.state == DshRuntimeService.RuntimeState.ERROR) {
            connection.stop();
        }
        // STARTING keeps the current Remote phase; the connection reconnects
        // on its own if the endpoint underneath it changed.
    }

    private final class ConnectionCallbacks implements DshRemoteConnection.Callbacks {
        @Override
        public void onSnapshot(DshRemoteState.Snapshot next) {
            snapshot = next;
            List<Consumer<DshRemoteState.Snapshot>> current;
            synchronized (listenerLock) {
                current = new ArrayList<>(listeners);
            }
            for (Consumer<DshRemoteState.Snapshot> listener : current) {
                try {
                    listener.accept(next);
                } catch (RuntimeException error) {
                    LOG.warn("DSH remote snapshot listener failed", error);
                }
            }
        }

        @Override
        public void onDropped(String message) {
            LOG.debug(message);
        }

        @Override
        public void onAuthFailure(DshRemoteException error) {
            LOG.warn("DSH Runtime authentication failed: " + error.display());
        }
    }

    @Override
    public void dispose() {
        runtime.removeStatusListener(statusListener);
        connection.close();
        operations.shutdownNow();
    }

    // ---------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------

    private static JsonObject objectValue(JsonElement value) {
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    private static JsonArray arrayOrEmpty(JsonObject value, String key) {
        return value.has(key) && value.get(key).isJsonArray()
                ? value.getAsJsonArray(key)
                : new JsonArray();
    }

    private static String stringOf(JsonObject object, String key) {
        return object != null
                        && object.has(key)
                        && object.get(key).isJsonPrimitive()
                        && object.get(key).getAsJsonPrimitive().isString()
                ? object.get(key).getAsString()
                : "";
    }

    private static boolean boolOf(JsonObject object, String key) {
        return object != null
                && object.has(key)
                && object.get(key).isJsonPrimitive()
                && object.get(key).getAsBoolean();
    }

    private static long longOf(JsonObject object, String key, long fallback) {
        return object != null
                        && object.has(key)
                        && object.get(key).isJsonPrimitive()
                        && object.get(key).getAsJsonPrimitive().isNumber()
                ? object.get(key).getAsLong()
                : fallback;
    }

    private static void addHistoryRecords(Map<Long, JsonElement> target, JsonObject page) {
        if (page == null || !page.has("records") || !page.get("records").isJsonArray()) return;
        for (JsonElement candidate : page.getAsJsonArray("records")) {
            if (!candidate.isJsonObject()) continue;
            JsonObject wrapper = candidate.getAsJsonObject();
            JsonObject event =
                    wrapper.has("event") && wrapper.get("event").isJsonObject()
                            ? wrapper.getAsJsonObject("event")
                            : null;
            if (event == null || !event.has("seq")) continue;
            try {
                long seq = event.get("seq").getAsLong();
                if (seq >= 0) target.putIfAbsent(seq, wrapper.deepCopy());
            } catch (RuntimeException ignored) {
                // Skip malformed history entries at this typed boundary.
            }
        }
    }
}
