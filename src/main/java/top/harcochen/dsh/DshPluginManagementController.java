package top.harcochen.dsh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jetbrains.annotations.NotNull;
import top.harcochen.dsh.remote.DshRemoteException;
import top.harcochen.dsh.remote.DshRemoteService;

/** Official Host plugin manager, including inspection, build approval and cancellation. */
final class DshPluginManagementController {
    private final Project project;
    private final DshRuntimeService runtime;
    private final DshRemoteService remote;
    private final ExecutorService operations;
    private final Runnable changed;
    private final Consumer<String> notify;
    private final AtomicBoolean pending = new AtomicBoolean();

    DshPluginManagementController(
            Project project,
            DshRuntimeService runtime,
            DshRemoteService remote,
            ExecutorService operations,
            Runnable changed,
            Consumer<String> notify) {
        this.project = project;
        this.runtime = runtime;
        this.remote = remote;
        this.operations = operations;
        this.changed = changed;
        this.notify = notify;
    }

    void manage() {
        if (!pending.compareAndSet(false, true)) return;
        operations.execute(
                () -> {
                    String endpoint = runtime.getUrl();
                    try {
                        JsonArray bundles = remote.pluginBundles();
                        assertEndpoint(endpoint);
                        List<String> labels = new ArrayList<>();
                        labels.add(DshBundle.message("dsh.plugins.install"));
                        List<String> names = new ArrayList<>();
                        for (JsonElement raw : bundles) {
                            JsonObject bundle = raw.getAsJsonObject();
                            if (!DshJson.bool(bundle, "installed", false)
                                    || !DshJson.bool(bundle, "removable", false)
                                    || bundle.has("readOnlyReason")) continue;
                            names.add(DshJson.string(bundle, "name"));
                            labels.add(
                                    DshBundle.message("dsh.plugins.remove")
                                            + " · "
                                            + DshJson.string(bundle, "name")
                                            + " "
                                            + DshJson.stringOr(bundle, "version", ""));
                        }
                        int selected =
                                ui(
                                        () ->
                                                DshChoiceDialog.show(
                                                        project,
                                                        DshBundle.message("dsh.plugins.choose"),
                                                        title(),
                                                        Messages.getQuestionIcon(),
                                                        labels.toArray(new String[0]),
                                                        labels.get(0)));
                        if (selected < 0) return;
                        assertEndpoint(endpoint);
                        if (selected == 0) install(endpoint);
                        else {
                            String name = names.get(selected - 1);
                            if (!confirm(DshBundle.message("dsh.plugins.remove.confirm", name)))
                                return;
                            assertEndpoint(endpoint);
                            JsonObject args = new JsonObject();
                            args.addProperty("name", name);
                            finish(
                                    remote.pluginChange("pluginManager/removeBundle", args),
                                    endpoint);
                        }
                    } catch (Exception error) {
                        notify.accept(
                                error instanceof DshRemoteException missing
                                                && missing.isCapabilityMissing()
                                        ? DshBundle.message("dsh.plugins.unavailable")
                                        : DshJson.message(error));
                    } finally {
                        pending.set(false);
                    }
                });
    }

    private void install(String endpoint) {
        String input =
                ui(
                        () ->
                                Messages.showInputDialog(
                                        project,
                                        DshBundle.message("dsh.plugins.spec.prompt"),
                                        title(),
                                        Messages.getQuestionIcon()));
        if (input == null) return;
        String spec = input.trim();
        if (spec.isEmpty() || spec.startsWith("-"))
            throw new IllegalArgumentException(DshBundle.message("dsh.plugins.spec.invalid"));
        assertEndpoint(endpoint);
        JsonObject args = new JsonObject();
        args.addProperty("spec", spec);
        JsonObject inspection = remote.inspectPlugin(args);
        assertEndpoint(endpoint);
        if ("refused".equals(DshJson.string(inspection, "status")))
            throw new IllegalStateException(
                    DshJson.string(inspection, "problem")
                            + ": "
                            + DshJson.string(inspection, "reason"));
        if (inspection.has("bundle")
                && !inspection.get("bundle").isJsonNull()
                && !inspection.get("bundle").getAsBoolean())
            throw new IllegalStateException(DshBundle.message("dsh.plugins.not.bundle"));
        String name =
                DshJson.stringOr(inspection, "name", spec)
                        + (inspection.has("version")
                                ? "@" + DshJson.string(inspection, "version")
                                : "");
        String source =
                DshJson.stringOr(
                        inspection,
                        "host",
                        DshJson.stringOr(inspection, "registry", "Runtime default registry"));
        if (!confirm(
                DshBundle.message("dsh.plugins.install.confirm", name)
                        + "\n\n"
                        + source
                        + "\n"
                        + DshJson.stringOr(inspection, "description", ""))) return;
        JsonArray builds = null;
        for (; ; ) {
            assertEndpoint(endpoint);
            JsonObject result = runInstall(endpoint, spec, builds);
            if ("failed".equals(DshJson.string(result, "application"))
                    && result.has("pendingBuilds")
                    && !result.getAsJsonArray("pendingBuilds").isEmpty()) {
                if (confirm(
                        DshBundle.message(
                                "dsh.plugins.builds.confirm",
                                result.getAsJsonArray("pendingBuilds").toString()))) {
                    builds = result.getAsJsonArray("pendingBuilds").deepCopy();
                    continue;
                }
            }
            finish(result, endpoint);
            return;
        }
    }

    private JsonObject runInstall(String endpoint, String spec, JsonArray approvedBuilds) {
        String requestId = java.util.UUID.randomUUID().toString();
        JsonObject options = new JsonObject();
        options.addProperty("requestId", requestId);
        options.addProperty("enabled", true);
        options.addProperty("saveExact", true);
        if (approvedBuilds != null) options.add("approvedBuilds", approvedBuilds);
        JsonObject args = new JsonObject();
        args.addProperty("spec", spec);
        args.add("options", options);
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        ui(
                () -> {
                    ProgressManager.getInstance()
                            .run(
                                    new Task.Backgroundable(
                                            project,
                                            DshBundle.message("dsh.plugins.install"),
                                            true) {
                                        @Override
                                        public void run(@NotNull ProgressIndicator indicator) {
                                            AtomicBoolean settled = new AtomicBoolean();
                                            AtomicBoolean cancellation = new AtomicBoolean();
                                            CompletableFuture<JsonObject> work =
                                                    CompletableFuture.supplyAsync(
                                                            () -> {
                                                                try {
                                                                    assertEndpoint(endpoint);
                                                                    return remote.pluginChange(
                                                                            "pluginManager/installBundle",
                                                                            args);
                                                                } catch (RuntimeException error) {
                                                                    assertEndpoint(endpoint);
                                                                    JsonObject wait =
                                                                            new JsonObject();
                                                                    wait.addProperty(
                                                                            "requestId", requestId);
                                                                    JsonElement recovered =
                                                                            remote.callFeature(
                                                                                    "pluginManager/waitForInstall",
                                                                                    wait);
                                                                    if (recovered == null
                                                                            || recovered
                                                                                    .isJsonNull())
                                                                        throw error;
                                                                    return remote
                                                                            .normalizePluginChange(
                                                                                    recovered);
                                                                }
                                                            },
                                                            operations);
                                            try (AutoCloseable events =
                                                    remote.onFeatureEvent(
                                                            (event, frames) -> {
                                                                if (!endpoint.equals(
                                                                                runtime.getUrl())
                                                                        || frames.isEmpty()
                                                                        || !frames.get(0)
                                                                                .isJsonObject())
                                                                    return;
                                                                JsonObject frame =
                                                                        frames.get(0)
                                                                                .getAsJsonObject();
                                                                if (!requestId.equals(
                                                                        DshJson.string(
                                                                                frame,
                                                                                "requestId")))
                                                                    return;
                                                                if (event.equals(
                                                                        "plugin-manager/install-state"))
                                                                    indicator.setText2(
                                                                            DshJson.stringOr(
                                                                                    frame, "phase",
                                                                                    ""));
                                                                if (event.equals(
                                                                                "plugin-manager/install-log")
                                                                        && DshJson.strictString(
                                                                                        frame,
                                                                                        "text")
                                                                                != null)
                                                                    runtime.logIntegration(
                                                                            DshJson.string(
                                                                                    frame, "text"));
                                                            })) {
                                                while (!work.isDone()) {
                                                    if (indicator.isCanceled()
                                                            && cancellation.compareAndSet(
                                                                    false, true))
                                                        operations.execute(
                                                                () ->
                                                                        cancelInstallation(
                                                                                endpoint, requestId,
                                                                                settled));
                                                    Thread.sleep(100);
                                                }
                                                result.complete(work.join());
                                            } catch (Exception error) {
                                                result.completeExceptionally(error);
                                            } finally {
                                                settled.set(true);
                                            }
                                        }
                                    });
                    return null;
                });
        return result.join();
    }

    private void cancelInstallation(String endpoint, String requestId, AtomicBoolean settled) {
        try {
            JsonObject cancel = new JsonObject();
            cancel.addProperty("requestId", requestId);
            while (!settled.get()) {
                assertEndpoint(endpoint);
                JsonElement raw = remote.callFeature("pluginManager/cancelInstall", cancel);
                if (!raw.isJsonObject())
                    throw new IllegalStateException("Invalid plugin cancellation response");
                String status = DshJson.strictString(raw.getAsJsonObject(), "status");
                if (!List.of("not-running", "cancelled", "too-late")
                        .contains(status == null ? "" : status))
                    throw new IllegalStateException("Invalid plugin cancellation response");
                if (!"not-running".equals(status)) return;
                Thread.sleep(150);
            }
        } catch (Exception failure) {
            runtime.logIntegration("Plugin cancellation: " + DshJson.message(failure));
        }
    }

    private void finish(JsonObject result, String endpoint) {
        assertEndpoint(endpoint);
        changed.run();
        String state = DshJson.string(result, "application");
        String message =
                DshBundle.message("dsh.plugin.change." + state)
                        + " · "
                        + DshJson.string(result, "target")
                        + "\n"
                        + details(result);
        if (result.has("packageResult")) {
            JsonObject output = result.getAsJsonObject("packageResult");
            runtime.logIntegration(
                    DshJson.stringOr(output, "output", "")
                            + "\n"
                            + DshJson.stringOr(output, "logPath", ""));
        }
        notify.accept(message);
    }

    static String details(JsonObject result) {
        List<String> details = new ArrayList<>();
        String reason = DshJson.string(result, "restartReason");
        if (reason != null) details.add(DshBundle.message("dsh.plugins.restart." + reason));
        if (result.has("error") && result.get("error").isJsonObject()) {
            JsonObject error = result.getAsJsonObject("error");
            details.add(DshJson.stringOr(error, "diagnostic", DshJson.stringOr(error, "code", "")));
            if (error.has("incompatible")) details.add(error.get("incompatible").toString());
        }
        if (result.has("warnings"))
            for (JsonElement warning : result.getAsJsonArray("warnings"))
                details.add(warning.getAsString());
        return String.join("\n", details);
    }

    private boolean confirm(String text) {
        return ui(() -> Messages.showYesNoDialog(project, text, title(), Messages.getWarningIcon()))
                == Messages.YES;
    }

    private String title() {
        return DshBundle.message("dsh.plugins.title");
    }

    private void assertEndpoint(String endpoint) {
        if (endpoint == null || !endpoint.equals(runtime.getUrl()))
            throw new IllegalStateException(DshBundle.message("dsh.runtime.changed"));
    }

    static <T> T ui(Supplier<T> action) {
        if (ApplicationManager.getApplication().isDispatchThread()) return action.get();
        java.util.concurrent.atomic.AtomicReference<T> result =
                new java.util.concurrent.atomic.AtomicReference<>();
        ApplicationManager.getApplication().invokeAndWait(() -> result.set(action.get()));
        return result.get();
    }
}
