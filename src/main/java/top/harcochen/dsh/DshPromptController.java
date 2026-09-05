package top.harcochen.dsh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import top.harcochen.dsh.remote.DshRemoteException;
import top.harcochen.dsh.remote.DshRemoteService;

/**
 * Owns prompt submission, cancellation, attachments, and command/skill catalogs.
 *
 * <p>Every submission carries a client-minted {@code requestId} that the Runtime persists on the
 * exact accepted user message. The request is reused when the user retries the same submission
 * after a failure or timeout, and retired once the durable echo (a user message whose source
 * carries that requestId) appears in the followed history; a fresh submission mints a new one. The
 * carrier never retries a prompt on its own.
 */
final class DshPromptController {
    private static final Logger LOG = Logger.getInstance(DshPromptController.class);
    private static final Pattern COMMAND_LINE =
            Pattern.compile("^/([a-z][a-z0-9_-]*)(?:$|[\t\n\r ])");

    private final DshRuntimeService runtime;
    private final DshRemoteService remote;
    private final DshIdeContextController ideContext;
    private final DshSessionStateStore sessionState;
    private final ExecutorService operations;
    private final SessionProvider sessionProvider;
    private final Supplier<String> sessionId;
    private final Supplier<JsonArray> messages;
    private final Runnable refreshState;
    private final Runnable stateChanged;
    private final Consumer<String> notifier;
    private final Consumer<String> errorSink;
    private final Set<String> catalogRequests = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** One unretired submission: requestId kept until the durable echo reconciles it. */
    private record PendingPrompt(String requestId, String text) {}

    private final Object pendingLock = new Object();
    private final java.util.Map<String, PendingPrompt> pendingBySession =
            new java.util.LinkedHashMap<>();

    private volatile boolean commandRegistryUnavailable;
    private volatile boolean submitting;
    private volatile boolean cancelling;

    DshPromptController(
            DshRuntimeService runtime,
            DshRemoteService remote,
            DshIdeContextController ideContext,
            DshSessionStateStore sessionState,
            ExecutorService operations,
            SessionProvider sessionProvider,
            Supplier<String> sessionId,
            Supplier<JsonArray> messages,
            Runnable refreshState,
            Runnable stateChanged,
            Consumer<String> notifier,
            Consumer<String> errorSink) {
        this.runtime = runtime;
        this.remote = remote;
        this.ideContext = ideContext;
        this.sessionState = sessionState;
        this.operations = operations;
        this.sessionProvider = sessionProvider;
        this.sessionId = sessionId;
        this.messages = messages;
        this.refreshState = refreshState;
        this.stateChanged = stateChanged;
        this.notifier = notifier;
        this.errorSink = errorSink;
    }

    boolean isSubmitting() {
        return submitting;
    }

    boolean isCancelling() {
        return cancelling;
    }

    void send(JsonObject action) {
        String text = DshJson.stringOr(action, "text", "");
        JsonArray images =
                action.has("images") && action.get("images").isJsonArray()
                        ? action.getAsJsonArray("images")
                        : new JsonArray();
        if (text.isBlank() && images.isEmpty()) {
            return;
        }
        String mode = "steer".equals(DshJson.string(action, "mode")) ? "steer" : "queue";
        submitting = true;
        cancelling = false;
        errorSink.accept(null);
        String editorContext = ideContext.captureEditorContext();
        List<String> capturedContextIds = ideContext.contextItemIds();
        stateChanged.run();
        operations.execute(() -> send(text, images, mode, editorContext, capturedContextIds));
    }

    private void send(
            String text,
            JsonArray images,
            String mode,
            String editorContext,
            List<String> capturedContextIds) {
        try {
            runtime.startAsync().join();
            String current = sessionProvider.ensure();
            String commandName = images.isEmpty() ? commandName(text) : null;
            if (commandName != null) {
                ensureCommandCatalog(current);
                if (sessionState.isRegisteredCommand(current, commandName)) {
                    runHostCommand(current, text);
                    refreshState.run();
                    return;
                }
            }
            String prompt = editorContext.isBlank() ? text : text + "\n\n" + editorContext;
            String requestId = requestIdFor(current, prompt);
            remote.prompt(
                    requestId,
                    current,
                    mode,
                    contentOf(prompt, images),
                    java.util.TimeZone.getDefault().getID());
            ideContext.removeCapturedContext(capturedContextIds);
            refreshState.run();
        } catch (Exception error) {
            String failureMessage = DshJson.message(error);
            errorSink.accept(failureMessage);
            LOG.warn("DSH prompt failed", error);
        } finally {
            submitting = false;
            cancelling = false;
            stateChanged.run();
        }
    }

    /**
     * Reuse the pending requestId for the same submission text until its durable echo appears; this
     * makes a timeout recovery or a user retry idempotent at the Runtime.
     */
    private String requestIdFor(String session, String text) {
        synchronized (pendingLock) {
            PendingPrompt pending = pendingBySession.get(session);
            if (pending != null
                    && pending.text().equals(text)
                    && !remote.hasDurableEcho(session, pending.requestId())) {
                return pending.requestId();
            }
            PendingPrompt fresh = new PendingPrompt(UUID.randomUUID().toString(), text);
            pendingBySession.put(session, fresh);
            return fresh.requestId();
        }
    }

    void cancel() {
        String current = sessionId.get();
        if (current == null || current.isBlank()) {
            return;
        }
        cancelling = true;
        stateChanged.run();
        operations.execute(
                () -> {
                    try {
                        remote.cancel(current);
                    } catch (Exception error) {
                        errorSink.accept(DshJson.message(error));
                    } finally {
                        cancelling = false;
                        refreshState.run();
                    }
                });
    }

    void updateQueue(JsonObject action) {
        String current = sessionId.get();
        String itemId = DshJson.string(action, "itemId");
        String queueAction = DshJson.string(action, "action");
        if (current == null || current.isBlank() || itemId == null || queueAction == null) {
            return;
        }
        operations.execute(
                () -> {
                    try {
                        remote.updateQueue(
                                current,
                                itemId,
                                queueActionOf(queueAction, DshJson.string(action, "text")));
                        refreshState.run();
                    } catch (Exception error) {
                        errorSink.accept(DshJson.message(error));
                        stateChanged.run();
                    }
                });
    }

    private static JsonObject queueActionOf(String action, String text) {
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
        }
        return queueAction;
    }

    void loadImage(String attachmentId) {
        String current = sessionId.get();
        if (current == null
                || current.isBlank()
                || attachmentId == null
                || attachmentId.isBlank()) {
            return;
        }
        operations.execute(
                () -> {
                    try {
                        JsonObject value = remote.attachment(current, attachmentId);
                        JsonObject attachment =
                                value.has("attachment") && value.get("attachment").isJsonObject()
                                        ? value.getAsJsonObject("attachment")
                                        : new JsonObject();
                        String mediaType = DshJson.string(attachment, "mediaType");
                        String data = DshJson.string(value, "data");
                        validateImage(attachment, mediaType, data);
                        String source = "data:" + mediaType + ";base64," + data;
                        updateImageSources(messages.get(), attachmentId, source);
                        stateChanged.run();
                    } catch (Exception error) {
                        errorSink.accept(DshJson.message(error));
                        stateChanged.run();
                    }
                });
    }

    private static void validateImage(JsonObject attachment, String mediaType, String data) {
        if (!isImageMediaType(mediaType)
                || data == null
                || data.isBlank()
                || data.length() > 22_000_000) {
            throw new IllegalStateException("Harness returned an invalid image attachment");
        }
        byte[] decoded = java.util.Base64.getDecoder().decode(data);
        if (decoded.length > 16 * 1024 * 1024) {
            throw new IllegalStateException("Image attachment is too large");
        }
        if (attachment.has("bytes")
                && attachment.get("bytes").isJsonPrimitive()
                && attachment.get("bytes").getAsLong() != decoded.length) {
            throw new IllegalStateException(
                    "Harness returned an image with an invalid byte length");
        }
    }

    private static void updateImageSources(JsonArray messages, String attachmentId, String source) {
        for (JsonElement message : messages) {
            if (!message.isJsonObject()) {
                continue;
            }
            JsonObject row = message.getAsJsonObject();
            updateImageSource(row.get("images"), attachmentId, source);
            JsonObject tool =
                    row.has("tool") && row.get("tool").isJsonObject()
                            ? row.getAsJsonObject("tool")
                            : null;
            if (tool != null) {
                updateImageSource(tool.get("images"), attachmentId, source);
            }
        }
    }

    private static void updateImageSource(JsonElement value, String attachmentId, String source) {
        if (value == null || !value.isJsonArray()) {
            return;
        }
        for (JsonElement candidate : value.getAsJsonArray()) {
            if (candidate.isJsonObject()
                    && attachmentId.equals(
                            DshJson.string(candidate.getAsJsonObject(), "attachmentId"))) {
                candidate.getAsJsonObject().addProperty("src", source);
                candidate.getAsJsonObject().addProperty("loadState", "idle");
            }
        }
    }

    void retry(String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        JsonObject found = null;
        for (JsonElement candidate : messages.get()) {
            if (candidate.isJsonObject()
                    && id.equals(DshJson.string(candidate.getAsJsonObject(), "id"))) {
                found = candidate.getAsJsonObject();
                break;
            }
        }
        if (found == null) {
            return;
        }
        JsonObject action = new JsonObject();
        action.addProperty("type", "sendPrompt");
        action.addProperty("text", DshJson.stringOr(found, "text", ""));
        action.addProperty("mode", "queue");
        send(action);
    }

    void refreshCatalogs(String session) {
        refreshCommandCatalog(session);
        refreshSkillCatalog(session);
    }

    private void refreshCommandCatalog(String session) {
        if (session == null
                || commandRegistryUnavailable
                || sessionState.hasCommandCatalog(session)
                || !catalogRequests.add("commands:" + session)) {
            return;
        }
        operations.execute(
                () -> {
                    try {
                        sessionState.putCommandCatalog(session, remote.listCommands(session));
                        stateChanged.run();
                    } catch (DshRemoteException error) {
                        if (error.isCapabilityMissing()) {
                            commandRegistryUnavailable = true;
                            LOG.info(
                                    "The connected Harness serves no command registry; using IDE commands only");
                        } else {
                            LOG.debug("DSH command catalog refresh failed", error);
                        }
                    } catch (Exception error) {
                        LOG.debug("DSH command catalog refresh failed", error);
                    } finally {
                        catalogRequests.remove("commands:" + session);
                    }
                });
    }

    private void refreshSkillCatalog(String session) {
        if (session == null
                || sessionState.hasSkillCatalog(session)
                || !catalogRequests.add("skills:" + session)) {
            return;
        }
        operations.execute(
                () -> {
                    try {
                        sessionState.putSkillCatalog(session, remote.listSkills(session));
                        stateChanged.run();
                    } catch (Exception error) {
                        LOG.debug("DSH skill catalog refresh failed", error);
                    } finally {
                        catalogRequests.remove("skills:" + session);
                    }
                });
    }

    private void ensureCommandCatalog(String session) {
        if (session == null
                || commandRegistryUnavailable
                || sessionState.hasCommandCatalog(session)) {
            return;
        }
        refreshCommandCatalog(session);
        for (int wait = 0;
                wait < 100
                        && !commandRegistryUnavailable
                        && !sessionState.hasCommandCatalog(session)
                        && catalogRequests.contains("commands:" + session);
                wait++) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void runHostCommand(String session, String line) throws Exception {
        JsonElement execution = remote.executeCommand(session, line);
        if (execution == null || execution.isJsonNull() || !execution.isJsonObject()) {
            throw new IllegalStateException(DshBundle.message("dsh.command.not.resolved", line));
        }
        JsonObject result =
                execution.getAsJsonObject().has("result")
                                && execution.getAsJsonObject().get("result").isJsonObject()
                        ? execution.getAsJsonObject().getAsJsonObject("result")
                        : new JsonObject();
        String text = DshJson.string(result, "text");
        if ("error".equals(DshJson.string(result, "kind"))) {
            throw new IllegalStateException(
                    text == null || text.isBlank()
                            ? DshBundle.message("dsh.command.rejected")
                            : text.trim());
        }
        if (text != null && !text.isBlank()) {
            notifier.accept(text.trim());
        }
    }

    private static JsonArray contentOf(String text, JsonArray images) {
        JsonArray content = new JsonArray();
        for (JsonElement image : images) {
            JsonObject part = new JsonObject();
            part.addProperty("type", "image");
            if (image.isJsonObject()) {
                JsonObject imageObject = image.getAsJsonObject();
                if (imageObject.has("mediaType"))
                    part.add("mediaType", imageObject.get("mediaType").deepCopy());
                if (imageObject.has("data")) part.add("data", imageObject.get("data").deepCopy());
                if (imageObject.has("name")) part.add("name", imageObject.get("name").deepCopy());
            }
            content.add(part);
        }
        if (text != null && !text.isEmpty()) {
            JsonObject part = new JsonObject();
            part.addProperty("type", "text");
            part.addProperty("text", text);
            content.add(part);
        }
        return content;
    }

    private static String commandName(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = COMMAND_LINE.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static boolean isImageMediaType(String value) {
        return "image/png".equals(value)
                || "image/jpeg".equals(value)
                || "image/webp".equals(value)
                || "image/gif".equals(value);
    }

    @FunctionalInterface
    interface SessionProvider {
        String ensure() throws DshRemoteException;
    }
}
