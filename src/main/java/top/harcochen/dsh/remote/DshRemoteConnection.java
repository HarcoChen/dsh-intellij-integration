package top.harcochen.dsh.remote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Generation lifecycle for one Remote connection.
 *
 * <p>Every generation establishes the physical mux socket, then the {@code $events} stream whose
 * first {@code ready} item is the readiness barrier, then the {@code workspace/follow} and {@code
 * session/control} baselines, then the {@code session/list} catalog baseline, and finally rebuilds
 * the {@code session/follow} streams whose reference count is above zero. Only after all opening
 * state has arrived is CONNECTED published together with the first complete snapshot.
 *
 * <p>Any failure inside that sequence — illegal first frame, stream error, socket loss, timeout —
 * tears the whole generation down and reconnects with jittered exponential backoff. Completions
 * from an older generation are refused by comparing generation numbers before any state commit. All
 * state mutations and frame handling run on one single-thread executor, in wire order.
 */
public final class DshRemoteConnection implements AutoCloseable {
    /** Callbacks invoked on the connection executor. */
    interface Callbacks {
        void onSnapshot(DshRemoteState.Snapshot snapshot);

        void onDropped(String message);

        void onAuthFailure(DshRemoteException error);
    }

    static final int FOLLOW_MAX_MESSAGES = 250;
    private static final long OPENING_TIMEOUT_MS = 15_000;
    private static final long MAX_BACKOFF_MS = 8_000;

    private final DshRemoteMuxClient mux;
    private final DshRemoteUnaryClient unary;
    private final DshRemoteState state = new DshRemoteState();
    private final Callbacks callbacks;
    private final ExecutorService executor;
    private final ScheduledExecutorService timer;
    private final AtomicBoolean publishPending = new AtomicBoolean();
    private final AtomicLong pendingGeneration = new AtomicLong();

    /** Connection-executor-confined fields below. */
    private boolean started;

    private boolean stopped;
    private boolean generationConnected;
    private long generation;
    private Opening opening;
    private DshRemoteEventClient events;
    private final Map<String, StreamLease> coreStreams = new HashMap<>();
    private final Map<String, FollowLease> follows = new HashMap<>();
    private int backoffAttempt;

    DshRemoteConnection(
            Supplier<String> baseUrl,
            DshRemoteUnaryClient unary,
            java.util.function.Supplier<java.util.Map<String, String>> headers,
            Callbacks callbacks,
            Consumer<String> diagnostic) {
        this.unary = unary;
        this.callbacks = callbacks;
        this.executor =
                Executors.newSingleThreadExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, "dsh-remote-connection");
                            thread.setDaemon(true);
                            return thread;
                        });
        this.timer =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, "dsh-remote-timer");
                            thread.setDaemon(true);
                            return thread;
                        });
        this.mux =
                new DshRemoteMuxClient(
                        baseUrl,
                        headers,
                        executor,
                        diagnostic,
                        generation -> onSocketOpen(generation),
                        (gen, error) -> execute(() -> onSocketLost(gen, error)));
    }

    // ---------------------------------------------------------------------------
    // lifecycle control
    // ---------------------------------------------------------------------------

    void start() {
        execute(
                () -> {
                    if (stopped || started) return;
                    started = true;
                    connectNow();
                });
    }

    /** Force a fresh generation (endpoint changed, or the user requested a reconnect). */
    void reconnect() {
        execute(
                () -> {
                    if (stopped || !started) return;
                    teardown("Remote connection was reset", false);
                    connectNow();
                });
    }

    void stop() {
        execute(
                () -> {
                    stopped = true;
                    started = false;
                    teardown("Remote connection stopped", false);
                    state.setPhase("stopped", null);
                    publish();
                });
    }

    @Override
    public void close() {
        execute(
                () -> {
                    stopped = true;
                    started = false;
                    teardown("Remote connection disposed", false);
                    state.setPhase("stopped", null);
                    publish();
                });
        mux.shutdown();
        executor.shutdown();
        timer.shutdownNow();
    }

    /** Reference-count one followed address. Must run off-EDT. */
    void retainFollow(JsonObject address) {
        execute(
                () -> {
                    String key = DshRemoteState.addressKey(address);
                    FollowLease lease = follows.get(key);
                    if (lease != null) {
                        lease.refCount++;
                        return;
                    }
                    follows.put(key, new FollowLease(address.deepCopy()));
                    if (opening == null && generation > 0) {
                        openFollowStream(key);
                    }
                });
    }

    void releaseFollow(JsonObject address) {
        execute(
                () -> {
                    String key = DshRemoteState.addressKey(address);
                    FollowLease lease = follows.get(key);
                    if (lease == null) return;
                    if (--lease.refCount > 0) return;
                    follows.remove(key);
                    StreamLease stream = coreStreams.remove(key);
                    if (stream != null) stream.handle.cancel();
                    state.closeFollow(key);
                    publish();
                });
    }

    String diagnostics() {
        return "generation="
                + pendingGeneration.get()
                + ", mux: "
                + mux.diagnostics()
                + ", follows="
                + follows.size();
    }

    // ---------------------------------------------------------------------------
    // generation sequencing (connection executor)
    // ---------------------------------------------------------------------------

    private void connectNow() {
        if (stopped || !started) return;
        generation = pendingGeneration.incrementAndGet();
        backoffAttempt = 0;
        opening = new Opening();
        state.beginGeneration(generation, "connecting");
        publish();
        boolean authenticated = false;
        try {
            // The exchange runs at most once per authority; the cookie is
            // normally already established from earlier unary calls.
            authenticated = unary.isAuthEstablished();
        } catch (RuntimeException ignored) {
            authenticated = false;
        }
        if (!authenticated) {
            DshRemoteException failure = unary.tryAuthenticate();
            if (failure != null) {
                callbacks.onAuthFailure(failure);
                scheduleReconnect();
                return;
            }
        }
        mux.connect((int) generation);
        timer.schedule(this::openingWatchdog, OPENING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private void openingWatchdog() {
        executor.execute(
                () -> {
                    if (opening != null) {
                        teardown("Remote opening baseline timed out", true);
                    }
                });
    }

    private void onSocketOpen(int gen) {
        if (gen != generation || opening == null) return;
        events = new DshRemoteEventClient(mux, unary, new EventListener(), gen);
        events.open();
    }

    private void onSocketLost(int gen, Throwable error) {
        if (gen != generation) return;
        String reason = error.getMessage() == null ? "Remote socket failed" : error.getMessage();
        teardown(reason, true);
    }

    private void onEventsReady(String clientId, String hostHome, int gen) {
        if (gen != generation || opening == null) return;
        opening.clientId = clientId;
        opening.hostHome = hostHome;
        opening.eventsReady = true;
        openCoreStream(
                DshRemoteContracts.STREAM_WORKSPACE_FOLLOW,
                DshRemoteContracts.argsEmpty(),
                this::onWorkspaceFrame);
        openCoreStream(
                DshRemoteContracts.STREAM_SESSION_CONTROL,
                DshRemoteContracts.argsEmpty(),
                this::onControlFrame);
    }

    private void onWorkspaceFrame(JsonObject item) {
        if (item == null) {
            teardown("Remote workspace stream carried an empty item", true);
            return;
        }
        state.applyWorkspaceFrame(item);
        if (opening != null && "baseline".equals(string(item, "type")) && !opening.workspaceReady) {
            opening.workspaceReady = true;
            checkOpeningComplete();
        }
        publish();
    }

    private void onControlFrame(JsonObject item) {
        if (item == null) {
            teardown("Remote control stream carried an empty item", true);
            return;
        }
        state.applyControlFrame(item);
        if (opening != null && "baseline".equals(string(item, "type")) && !opening.controlReady) {
            opening.controlReady = true;
            checkOpeningComplete();
        }
        publish();
    }

    private void checkOpeningComplete() {
        if (opening == null
                || !opening.eventsReady
                || !opening.workspaceReady
                || !opening.controlReady) {
            return;
        }
        String endpoint = DshRemoteContracts.SESSION_LIST;
        unary.callAsync(endpoint, DshRemoteContracts.argsSessionList())
                .whenComplete(
                        (value, error) ->
                                executor.execute(
                                        () -> {
                                            if (error != null) {
                                                Throwable cause =
                                                        error.getCause() == null
                                                                ? error
                                                                : error.getCause();
                                                teardown(
                                                        "Remote session baseline failed: "
                                                                + cause.getMessage(),
                                                        true);
                                                return;
                                            }
                                            onSessionBaseline(value);
                                        }));
    }

    private void onSessionBaseline(JsonElement value) {
        if (opening == null) return;
        JsonArray items =
                value != null
                                && value.isJsonObject()
                                && value.getAsJsonObject().has("items")
                                && value.getAsJsonObject().get("items").isJsonArray()
                        ? value.getAsJsonObject().getAsJsonArray("items")
                        : new JsonArray();
        state.applySessionList(items);
        // Rebuild the follows whose reference count is still above zero.
        for (Map.Entry<String, FollowLease> entry : follows.entrySet()) {
            openFollowStream(entry.getKey());
        }
        state.setPhase("connected", null);
        Opening settled = opening;
        opening = null;
        generationConnected = true;
        publish();
        backoffAttempt = 0;
        Objects.requireNonNull(settled);
    }

    private void openCoreStream(String endpoint, JsonObject args, Consumer<JsonObject> sink) {
        StreamLease lease =
                new StreamLease(mux.open(endpoint, args, new CoreStreamHandler(endpoint, sink)));
        coreStreams.put(endpoint, lease);
    }

    private void openFollowStream(String key) {
        FollowLease lease = follows.get(key);
        if (lease == null || lease.stream != null) return;
        // Register the state-side follow first: applyFollowFrame drops frames
        // for addresses it does not know about.
        state.openFollow(lease.address, FOLLOW_MAX_MESSAGES);
        StreamLease stream =
                new StreamLease(
                        mux.open(
                                DshRemoteContracts.STREAM_SESSION_FOLLOW,
                                DshRemoteContracts.argsSessionFollow(
                                        lease.address, FOLLOW_MAX_MESSAGES),
                                new FollowStreamHandler(key)));
        lease.stream = stream;
        coreStreams.put(key, stream);
    }

    private void onSessionFollowFrame(String key, JsonObject item) {
        if (item == null) return;
        boolean ordered = state.applyFollowFrame(key, item);
        if (!ordered) {
            callbacks.onDropped(
                    "Remote session history detected a sequence gap; reopening the stream");
            reopenFollow(key);
        }
        publish();
    }

    private void reopenFollow(String key) {
        FollowLease lease = follows.get(key);
        if (lease == null) return;
        StreamLease stream = lease.stream;
        lease.stream = null;
        coreStreams.remove(key);
        if (stream != null) stream.handle.cancel();
        state.closeFollow(key);
        openFollowStream(key);
    }

    // ---------------------------------------------------------------------------
    // teardown and backoff
    // ---------------------------------------------------------------------------

    private void teardown(String reason, boolean reconnect) {
        if (opening != null) {
            opening = null;
        }
        generationConnected = false;
        events = null;
        for (StreamLease lease : coreStreams.values()) lease.handle.cancel();
        coreStreams.clear();
        for (FollowLease lease : follows.values()) lease.stream = null;
        state.setPhase("reconnecting", reason);
        publish();
        if (reconnect && !stopped) scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (stopped || !started) return;
        backoffAttempt++;
        long base = Math.min(MAX_BACKOFF_MS, 250L * (1L << Math.min(backoffAttempt, 6)));
        long jitter = (long) (Math.random() * 200);
        timer.schedule(this::connectNow, base + jitter, TimeUnit.MILLISECONDS);
    }

    private void publish() {
        if (publishPending.compareAndSet(false, true)) {
            execute(
                    () -> {
                        publishPending.set(false);
                        callbacks.onSnapshot(state.snapshot());
                    });
        }
    }

    /**
     * Run on the connection executor, ignoring tasks submitted after dispose. Callbacks racing
     * {@link #close()} must never surface a {@link
     * java.util.concurrent.RejectedExecutionException}.
     */
    private void execute(Runnable task) {
        try {
            executor.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // The executor was shut down while a late callback was in flight.
        }
    }

    // ---------------------------------------------------------------------------
    // event plumbing
    // ---------------------------------------------------------------------------

    /** Answer one pending waterfall; may block on HTTP. Returns null on success. */
    DshRemoteException answer(String eventId, JsonObject outcome) {
        DshRemoteEventClient current = events;
        if (current == null) {
            return DshRemoteException.carrier(
                    DshRemoteContracts.EVENT_RESULT_ENDPOINT,
                    "The Remote event stream is not ready; the answer was not sent",
                    null);
        }
        try {
            current.answer(eventId, outcome);
            return null;
        } catch (DshRemoteException error) {
            return error;
        }
    }

    private final class EventListener implements DshRemoteEventClient.Listener {
        @Override
        public void onReady(String clientId, String hostHome, int gen) {
            executor.execute(() -> onEventsReady(clientId, hostHome, gen));
        }

        @Override
        public void onEmit(String event, com.google.gson.JsonArray args, int gen) {
            executor.execute(
                    () -> {
                        if (gen != generation) return;
                        if (event.startsWith("api-session/")) {
                            if (state.applyCatalogEmit(event, args)) publish();
                            return;
                        }
                        // Correctness never depends on emit-only events; the
                        // baselines and unary queries are authoritative.
                        callbacks.onDropped("Ignoring unconsumed remote event: " + event);
                    });
        }

        @Override
        public void onWaterfall(
                String event, String eventId, String agentId, JsonObject request, int gen) {
            executor.execute(
                    () -> {
                        if (gen != generation) return;
                        switch (event) {
                            case "approval/request" ->
                                    state.requestApproval(agentId, eventId, request);
                            case "user-questions/request" ->
                                    state.requestQuestion(agentId, eventId, request);
                            default -> {
                                callbacks.onDropped("Ignoring unknown waterfall event: " + event);
                                return;
                            }
                        }
                        publish();
                    });
        }

        @Override
        public void onCancel(String eventId, int gen) {
            executor.execute(
                    () -> {
                        if (gen != generation) return;
                        state.cancelInteraction(eventId);
                        publish();
                    });
        }

        @Override
        public void onDropped(String reason) {
            callbacks.onDropped(reason);
        }

        @Override
        public void onFailure(String message) {
            executor.execute(
                    () -> {
                        teardown(message, true);
                    });
        }
    }

    private final class CoreStreamHandler implements DshRemoteMuxClient.StreamHandler {
        private final String endpoint;
        private final Consumer<JsonObject> sink;

        CoreStreamHandler(String endpoint, Consumer<JsonObject> sink) {
            this.endpoint = endpoint;
            this.sink = sink;
        }

        @Override
        public void onItem(JsonObject value) {
            sink.accept(value);
        }

        @Override
        public void onTerminal(JsonObject error) {
            executor.execute(
                    () -> {
                        if (error != null) {
                            teardown(
                                    "Remote "
                                            + endpoint
                                            + " stream failed: "
                                            + string(error, "message"),
                                    true);
                        }
                    });
        }
    }

    private final class FollowStreamHandler implements DshRemoteMuxClient.StreamHandler {
        private final String key;

        FollowStreamHandler(String key) {
            this.key = key;
        }

        @Override
        public void onItem(JsonObject value) {
            if (value != null && "snapshot".equals(string(value, "type"))) {
                FollowLease lease = follows.get(key);
                if (lease != null) lease.reopenAttempts = 0;
            }
            onSessionFollowFrame(key, value);
        }

        @Override
        public void onTerminal(JsonObject error) {
            executor.execute(
                    () -> {
                        FollowLease lease = follows.get(key);
                        if (lease != null) lease.stream = null;
                        coreStreams.remove(key);
                        if (!generationConnected) {
                            // A terminal that races a generation teardown; the
                            // rebuild below reopens every live subscription.
                            return;
                        }
                        if (error == null) {
                            // The Host ended the stream; drop the subscription.
                            if (lease != null) {
                                follows.remove(key);
                                state.closeFollow(key);
                                publish();
                            }
                            return;
                        }
                        // Session not found (or equivalent): retire the follow instead of
                        // tearing down the whole generation.
                        String code = string(error, "code");
                        if ("session/not-found".equals(code)) {
                            if (lease != null) {
                                follows.remove(key);
                                state.closeFollow(key);
                                publish();
                            }
                            return;
                        }
                        if (lease == null) return;
                        callbacks.onDropped(
                                "Remote session stream failed: " + string(error, "message"));
                        lease.reopenAttempts++;
                        if (lease.reopenAttempts > 3) {
                            // The Runtime keeps failing this address; retire it and
                            // let the next explicit subscription retry.
                            follows.remove(key);
                            state.closeFollow(key);
                            publish();
                            return;
                        }
                        state.closeFollow(key);
                        timer.schedule(
                                () ->
                                        executor.execute(
                                                () -> {
                                                    if (follows.get(key) == lease
                                                            && lease.stream == null) {
                                                        openFollowStream(key);
                                                    }
                                                }),
                                1_000L * lease.reopenAttempts,
                                TimeUnit.MILLISECONDS);
                    });
        }
    }

    // ---------------------------------------------------------------------------
    // shared plumbing used by the socket callback
    // ---------------------------------------------------------------------------

    /** The connection executor, for callers that must sequence work behind frames. */
    ExecutorService executor() {
        return executor;
    }

    /**
     * Open one untracked stream for a one-shot read (snapshot plus cancel). The open is sequenced
     * behind pending frames on the connection executor; items and the terminal arrive on it too.
     */
    DshRemoteMuxClient.StreamHandle openOneShot(
            JsonObject address,
            int maxMessages,
            Consumer<JsonObject> items,
            Consumer<JsonObject> terminalError) {
        return mux.open(
                DshRemoteContracts.STREAM_SESSION_FOLLOW,
                DshRemoteContracts.argsSessionFollow(address, maxMessages),
                new DshRemoteMuxClient.StreamHandler() {
                    @Override
                    public void onItem(JsonObject value) {
                        items.accept(value);
                    }

                    @Override
                    public void onTerminal(JsonObject error) {
                        terminalError.accept(error);
                    }
                });
    }

    /** Update one interaction's submission status and republish. */
    void interactionStatus(
            String sessionId, String key, String status, String error, boolean resolved) {
        execute(
                () -> {
                    if (resolved) state.resolveInteraction(sessionId, key);
                    else state.setInteractionStatus(sessionId, key, status, error);
                    publish();
                });
    }

    private static String string(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsString()
                : null;
    }

    private static final class Opening {
        boolean eventsReady;
        boolean workspaceReady;
        boolean controlReady;
        String clientId;
        String hostHome;
    }

    private static final class StreamLease {
        final DshRemoteMuxClient.StreamHandle handle;

        StreamLease(DshRemoteMuxClient.StreamHandle handle) {
            this.handle = handle;
        }
    }

    private static final class FollowLease {
        final JsonObject address;
        int refCount = 1;
        int reopenAttempts;
        StreamLease stream;

        FollowLease(JsonObject address) {
            this.address = address;
        }
    }
}
