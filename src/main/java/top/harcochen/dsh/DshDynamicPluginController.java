package top.harcochen.dsh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.Disposable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import top.harcochen.dsh.remote.DshRemoteException;
import top.harcochen.dsh.remote.DshRemoteService;

/** Owns the optional read-only Cordis dynamic-plugin panel and its safe host mutations. */
final class DshDynamicPluginController implements Disposable {
    private final DshRuntimeService runtime;
    private final DshRemoteService remote;
    private final ExecutorService operations;
    private final Runnable stateChanged;
    private final Consumer<String> notifier;
    private final Consumer<String> errorSink;
    private final AtomicLong generation = new AtomicLong();

    private volatile JsonObject view;
    private volatile boolean disposed;

    DshDynamicPluginController(
            DshRuntimeService runtime,
            DshRemoteService remote,
            ExecutorService operations,
            Runnable stateChanged,
            Consumer<String> notifier,
            Consumer<String> errorSink) {
        this.runtime = runtime;
        this.remote = remote;
        this.operations = operations;
        this.stateChanged = stateChanged;
        this.notifier = notifier;
        this.errorSink = errorSink;
    }

    JsonObject view() {
        JsonObject current = view;
        return current == null ? null : current.deepCopy();
    }

    void refresh() {
        long requested = generation.incrementAndGet();
        JsonObject previous = view;
        JsonObject loading = new JsonObject();
        loading.add("rows", rows(previous));
        loading.addProperty("loading", true);
        view = loading;
        stateChanged.run();
        operations.execute(
                () -> {
                    try {
                        runtime.startAsync().join();
                        JsonArray rows = remote.dynamicPluginInventory();
                        if (generation.get() != requested || disposed) return;
                        JsonObject result = new JsonObject();
                        result.add("rows", rows);
                        view = result;
                    } catch (Exception error) {
                        if (generation.get() != requested || disposed) return;
                        Throwable cause = unwrap(error);
                        if (cause instanceof DshRemoteException remoteError
                                && remoteError.isCapabilityMissing()) {
                            // The namespace is optional. Hiding the tab is the safe fallback.
                            view = null;
                        } else {
                            JsonObject result = new JsonObject();
                            result.add("rows", rows(previous));
                            result.addProperty(
                                    "error", DshBundle.message("dsh.dynamic.plugins.failed"));
                            view = result;
                            errorSink.accept(DshJson.message(cause));
                        }
                    } finally {
                        if (generation.get() == requested && !disposed) stateChanged.run();
                    }
                });
    }

    void refreshOnRuntimeStart(DshRuntimeService.RuntimeStatus status) {
        if (status == null || disposed) return;
        if (status.state != DshRuntimeService.RuntimeState.RUNNING) {
            generation.incrementAndGet();
            view = null;
            return;
        }
        if (view == null) refresh();
    }

    /**
     * Refresh only when the Runtime is already attached; opening the tool window must not
     * auto-start it.
     */
    void refreshIfRunning() {
        if (!disposed && runtime.getStatus().state == DshRuntimeService.RuntimeState.RUNNING) {
            refresh();
        }
    }

    void stop(String agentId, String pluginId) {
        if (!present(agentId, pluginId)) {
            notifyUser(DshBundle.message("dsh.dynamic.plugins.stale"));
            return;
        }
        operations.execute(
                () -> {
                    try {
                        JsonObject result = remote.stopDynamicPlugin(agentId, pluginId);
                        if (!DshJson.bool(result, "ok", false)) {
                            notifyUser(
                                    DshJson.stringOr(
                                            result,
                                            "message",
                                            DshBundle.message("dsh.dynamic.plugins.rejected")));
                            return;
                        }
                        refresh();
                    } catch (Exception error) {
                        report(error);
                    }
                });
    }

    void remove(String agentId, String pluginId) {
        if (!present(agentId, pluginId)) {
            notifyUser(DshBundle.message("dsh.dynamic.plugins.stale"));
            return;
        }
        operations.execute(
                () -> {
                    try {
                        JsonObject result = remote.removeDynamicPlugin(agentId, pluginId);
                        if (!DshJson.bool(result, "ok", false)) {
                            notifyUser(
                                    DshJson.stringOr(
                                            result,
                                            "message",
                                            DshBundle.message("dsh.dynamic.plugins.rejected")));
                            return;
                        }
                        refresh();
                    } catch (Exception error) {
                        report(error);
                    }
                });
    }

    void decline(String pluginId, String requestId) {
        JsonObject latest = latest(pluginId);
        if (latest == null
                || !"awaiting-approval".equals(DshJson.string(latest, "status"))
                || !requestId.equals(DshJson.string(latest, "approvalRequestId"))) {
            notifyUser(DshBundle.message("dsh.dynamic.plugins.approval.stale"));
            return;
        }
        String pluginRunId = DshJson.string(latest, "pluginRunId");
        operations.execute(
                () -> {
                    try {
                        JsonObject result = remote.declineDynamicPlugin(requestId, pluginRunId);
                        if (!DshJson.bool(result, "accepted", false)) {
                            notifyUser(DshBundle.message("dsh.dynamic.plugins.approval.resolved"));
                            return;
                        }
                        refresh();
                    } catch (Exception error) {
                        report(error);
                    }
                });
    }

    private boolean present(String agentId, String pluginId) {
        if (agentId == null || agentId.isBlank() || pluginId == null || pluginId.isBlank())
            return false;
        JsonObject current = view;
        if (current == null || !current.has("rows") || !current.get("rows").isJsonArray())
            return false;
        for (JsonElement candidate : current.getAsJsonArray("rows")) {
            if (candidate.isJsonObject()
                    && agentId.equals(DshJson.string(candidate.getAsJsonObject(), "agentId"))
                    && pluginId.equals(DshJson.string(candidate.getAsJsonObject(), "pluginId")))
                return true;
        }
        return false;
    }

    private JsonObject latest(String pluginId) {
        JsonObject current = view;
        if (current == null || !current.has("rows") || !current.get("rows").isJsonArray())
            return null;
        for (JsonElement candidate : current.getAsJsonArray("rows")) {
            if (candidate.isJsonObject()
                    && pluginId.equals(DshJson.string(candidate.getAsJsonObject(), "pluginId"))) {
                JsonElement latest = candidate.getAsJsonObject().get("latestRun");
                return latest != null && latest.isJsonObject() ? latest.getAsJsonObject() : null;
            }
        }
        return null;
    }

    private static JsonArray rows(JsonObject source) {
        return source != null && source.has("rows") && source.get("rows").isJsonArray()
                ? source.getAsJsonArray("rows").deepCopy()
                : new JsonArray();
    }

    private void report(Exception error) {
        Throwable cause = unwrap(error);
        errorSink.accept(DshJson.message(cause));
        notifyUser(DshBundle.message("dsh.dynamic.plugins.failed"));
        stateChanged.run();
    }

    private void notifyUser(String message) {
        if (message != null && !message.isBlank()) notifier.accept(message);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.CompletionException
                        || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) current = current.getCause();
        return current;
    }

    @Override
    public void dispose() {
        disposed = true;
        generation.incrementAndGet();
        view = null;
    }
}
