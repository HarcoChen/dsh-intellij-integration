package top.harcochen.dsh;

import static top.harcochen.dsh.DshJson.bool;
import static top.harcochen.dsh.DshJson.integer;
import static top.harcochen.dsh.DshJson.message;
import static top.harcochen.dsh.DshJson.string;
import static top.harcochen.dsh.DshJson.stringOr;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBLabel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.datatransfer.StringSelection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.jetbrains.annotations.NotNull;
import top.harcochen.dsh.remote.DshRemoteException;
import top.harcochen.dsh.remote.DshRemoteService;
import top.harcochen.dsh.remote.DshRemoteState;

/**
 * Host-side controller for the reused React chat bundle.
 *
 * <p>The panel intentionally owns no Swing chat widgets and no wire state. Its job is the same as
 * dsh-ide's {@code ChatViewProvider}: validate/dispatch actions, project the Remote domain snapshot
 * to ChatViewState, and resolve editor-aware actions through IntelliJ APIs. Live state (sessions,
 * history, queue, jobs, projections, interactions, workspaces) arrives as immutable snapshots from
 * {@link DshRemoteService}; the panel only derives presentation caches from them.
 */
public final class DshToolWindowPanel extends JPanel implements com.intellij.openapi.Disposable {
    private static final Logger LOG = Logger.getInstance(DshToolWindowPanel.class);
    private static final int WEBVIEW_PROTOCOL_VERSION = 1;

    private final Project project;
    private final DshRuntimeService runtime;
    private final DshRemoteService remote;
    private final ExecutorService operations;
    private final AtomicBoolean reprojectInFlight = new AtomicBoolean();

    /** Coalesces bursts of snapshot updates into one EDT state publish. */
    private final AtomicBoolean statePostPending = new AtomicBoolean();

    private final DshDynamicPluginController dynamicPlugins;
    private final Consumer<DshRemoteState.Snapshot> snapshotListener = this::onSnapshot;
    private final Consumer<DshRuntimeService.RuntimeStatus> runtimeStatusListener =
            this::onRuntimeStatus;
    private final DshMarkdownRenderCache markdownRenderCache = new DshMarkdownRenderCache();
    private final DshIdeContextController ideContext;
    private final DshDebugContextController debugContext;
    private final DshCodeActionController codeActions;
    private final DshSubagentController subagents;
    private final DshSessionStateStore sessionState;
    private final DshGoalController goals;
    private final DshSettingsController runtimeSettings;
    private final DshWorkspaceController workspaces;
    private final DshAgentPresetController agentPresets;
    private final DshDiffController diffs;
    private final DshSessionActionsController sessionActions;
    private final DshPromptController prompts;
    private final DshChangeReviewStore changeReviews;
    private final DshFeedbackController feedback;

    private DshBridge bridge;
    private JPanel fallbackPanel;
    private JLabel fallbackLabel;
    private volatile boolean disposed;
    private volatile boolean webviewReady;
    private volatile boolean focusMode;
    private volatile String sessionId;
    private volatile boolean newSessionDraft;
    private volatile String pendingAgentPreset;
    private volatile String followedSession;
    private volatile long projectedCursor = Long.MIN_VALUE;
    private volatile JsonArray projectedEvents;
    private volatile JsonObject projectedAssistantStream;
    private volatile JsonObject currentWorkspaceRegistration;
    private volatile String canonicalBasePath;
    private volatile DshRemoteState.Snapshot snapshot = DshRemoteService.emptySnapshot();
    private volatile JsonArray messages = new JsonArray();
    private volatile DshMessageProjector.Projection projection;

    /** Keeps visible text briefly so copy actions survive a stream-to-history projection race. */
    private final Map<String, String> copyableMessageTexts = new LinkedHashMap<>();

    private volatile String lastError;
    private volatile JsonObject pendingComposerUpdate;

    public DshToolWindowPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;
        this.runtime = DshRuntimeService.getInstance(project);
        this.remote = DshRemoteService.getInstance(project);
        this.operations =
                Executors.newCachedThreadPool(
                        runnable -> {
                            Thread thread = new Thread(runnable, "dsh-intellij-chat");
                            thread.setDaemon(true);
                            return thread;
                        });
        this.sessionState = new DshSessionStateStore(markdownRenderCache);
        this.changeReviews = new DshChangeReviewStore(operations, this::postStateLater);
        this.ideContext =
                new DshIdeContextController(
                        project,
                        operations,
                        this::postStateLater,
                        this::notify,
                        this::postToWebview);
        this.debugContext =
                new DshDebugContextController(
                        project,
                        item -> ideContext.attachCustomItem(item),
                        this::postToWebview,
                        this::notify);
        this.codeActions = new DshCodeActionController(project, markdownRenderCache, this::notify);
        this.subagents =
                new DshSubagentController(
                        remote,
                        operations,
                        markdownRenderCache,
                        this::catalogRows,
                        this::postStateLater,
                        this::notify,
                        error -> lastError = error);
        this.goals =
                new DshGoalController(
                        sessionState,
                        this::projectionCell,
                        remote,
                        operations,
                        this::refreshAfterMutation,
                        this::postStateLater,
                        this::notify,
                        error -> lastError = error);
        this.runtimeSettings =
                new DshSettingsController(
                        project,
                        runtime,
                        remote,
                        operations,
                        this::postStateLater,
                        this::openBrowser,
                        this::notify,
                        error -> lastError = error);
        this.workspaces =
                new DshWorkspaceController(
                        project,
                        remote,
                        operations,
                        this::refreshAfterMutation,
                        this::postStateLater,
                        this::notify,
                        error -> lastError = error);
        this.agentPresets =
                new DshAgentPresetController(
                        project,
                        remote,
                        operations,
                        () -> sessionId,
                        preset -> pendingAgentPreset = preset,
                        this::refreshAfterMutation,
                        this::postStateLater,
                        this::notify,
                        error -> lastError = error);
        this.diffs =
                new DshDiffController(
                        project,
                        remote,
                        changeReviews,
                        operations,
                        () -> sessionId,
                        this::catalogRows,
                        this::postStateLater,
                        this::notify,
                        error -> lastError = error);
        this.sessionActions =
                new DshSessionActionsController(
                        project,
                        remote,
                        operations,
                        () -> sessionId,
                        () -> sessionView(sessionId),
                        this::catalogRows,
                        id -> {
                            sessionId = id;
                            newSessionDraft = false;
                        },
                        () -> {
                            sessionId = null;
                            pendingAgentPreset = null;
                            DshSettingsState.getInstance(project).lastSessionId = "";
                        },
                        this::refreshAfterMutation,
                        this::postStateLater,
                        this::notify,
                        error -> lastError = error);
        this.prompts =
                new DshPromptController(
                        runtime,
                        remote,
                        ideContext,
                        sessionState,
                        operations,
                        this::ensureSession,
                        () -> sessionId,
                        () -> messages,
                        this::refreshAfterMutation,
                        this::postStateLater,
                        this::notify,
                        error -> lastError = error);
        this.feedback =
                new DshFeedbackController(
                        remote,
                        operations,
                        () -> sessionId,
                        this::postStateLater,
                        this::notify,
                        error -> lastError = error);
        this.dynamicPlugins =
                new DshDynamicPluginController(
                        runtime,
                        remote,
                        operations,
                        this::postStateLater,
                        this::notify,
                        error -> lastError = error);
        runtime.addStatusListener(runtimeStatusListener);
        remote.addListener(snapshotListener);
        setBorder(BorderFactory.createEmptyBorder());
        createWebview();
        if (DshSettingsState.getInstance(project).autoStart && project.getBasePath() != null) {
            operations.execute(
                    () -> runtime.startAsync().whenComplete((ignored, error) -> postStateLater()));
        }
    }

    // ---------------------------------------------------------------------------
    // snapshot intake
    // ---------------------------------------------------------------------------

    /** Runs on the Remote connection executor. */
    private void onSnapshot(DshRemoteState.Snapshot next) {
        if (disposed) return;
        snapshot = next;
        if (reprojectInFlight.compareAndSet(false, true)) {
            operations.execute(
                    () -> {
                        try {
                            reproject();
                        } finally {
                            reprojectInFlight.set(false);
                        }
                    });
        }
    }

    private void onRuntimeStatus(DshRuntimeService.RuntimeStatus status) {
        dynamicPlugins.refreshOnRuntimeStart(status);
        postStateLater();
    }

    /** Derive presentation caches from the latest snapshot. Runs off the EDT. */
    private void reproject() {
        if (disposed) return;
        DshRemoteState.Snapshot current = snapshot;
        if (current == null) return;
        if (DshRemoteService.PHASE_STOPPED.equals(current.phase)) {
            releaseFollowedSession();
            messages = new JsonArray();
            projection = null;
            postStateLater();
            return;
        }
        chooseSessionIfNecessary(current);
        String selected = sessionId;
        if (selected != null && !selected.isBlank()) {
            persistSelectedSession();
        }
        if (selected != null && !selected.isBlank() && !selected.equals(followedSession)) {
            releaseFollowedSession();
            remote.retainSession(selected);
            followedSession = selected;
        }

        Set<String> live = new HashSet<>();
        for (JsonElement candidate : current.catalog) {
            if (candidate.isJsonObject()) {
                String id = string(candidate.getAsJsonObject(), "sessionId");
                if (id != null) live.add(id);
            }
        }
        if (selected != null) live.add(selected);
        sessionState.prune(live);
        changeReviews.retain(live);
        feedback.prune(live);

        if (selected != null) {
            String followKey = "session:" + selected;
            DshRemoteState.FollowView view = current.follows.get(followKey);
            long cursor = view == null ? Long.MIN_VALUE : view.cursor;
            boolean replaced =
                    view != null
                            && (view.events != projectedEvents
                                    || !java.util.Objects.equals(
                                            view.assistantStream, projectedAssistantStream));
            if (view != null && (cursor != projectedCursor || replaced)) {
                JsonObject history = new JsonObject();
                history.add("events", view.events);
                if (view.assistantStream != null)
                    history.add("assistantStream", view.assistantStream);
                DshSettingsState settings = DshSettingsState.getInstance(project);
                String statusLabel =
                        settings.agentStatusLabel == null || settings.agentStatusLabel.isBlank()
                                ? DshBundle.message("dsh.status.thinking")
                                : settings.agentStatusLabel.trim();
                DshSessionStateStore.HistoryProjection cached =
                        sessionState.projectHistory(selected, history, statusLabel);
                projection = cached.projection();
                messages = cached.messages();
                feedback.refresh(selected, false);
                rememberCopyableMessages(selected, messages);
                changeReviews.observe(selected, project.getBasePath(), history);
                prompts.refreshCatalogs(selected);
                sessionActions.refreshModelCatalog(selected);
                projectedCursor = cursor;
                projectedEvents = view.events;
                projectedAssistantStream = view.assistantStream;
            }
            agentPresets.refreshCatalogIfNecessary();
        } else {
            projection = DshMessageProjector.Projection.empty();
            messages = new JsonArray();
        }
        resolveCurrentWorkspace(current);
        postStateLater();
    }

    private void resolveCurrentWorkspace(DshRemoteState.Snapshot current) {
        String base = project.getBasePath();
        if (base == null) {
            currentWorkspaceRegistration = null;
            return;
        }
        String canonical = canonicalBasePath;
        if (canonical == null) {
            canonical = canonicalPath(base);
            canonicalBasePath = canonical;
        }
        for (JsonObject workspace : current.workspaces) {
            if (canonical != null && canonical.equals(canonicalPath(string(workspace, "path")))) {
                currentWorkspaceRegistration = workspace.deepCopy();
                return;
            }
        }
        currentWorkspaceRegistration = null;
    }

    private void releaseFollowedSession() {
        String previous = followedSession;
        if (previous != null) {
            remote.releaseSession(previous);
            followedSession = null;
            projectedCursor = Long.MIN_VALUE;
            projectedEvents = null;
        }
    }

    /** Move the live history subscription when the user switches sessions. */
    private void switchFollowedSession() {
        String target = sessionId;
        String previous = followedSession;
        if (target != null && target.equals(previous)) return;
        releaseFollowedSession();
        projectedCursor = Long.MIN_VALUE;
        projectedEvents = null;
        if (target != null && !target.isBlank()) {
            remote.retainSession(target);
            followedSession = target;
        }
        messages = new JsonArray();
        projection = DshMessageProjector.Projection.empty();
        postStateLater();
    }

    private static String canonicalPath(String path) {
        if (path == null || path.isBlank()) return null;
        try {
            return Path.of(path).toRealPath().toString();
        } catch (Exception ignored) {
            return Path.of(path).toAbsolutePath().normalize().toString();
        }
    }

    // ---------------------------------------------------------------------------
    // webview lifecycle
    // ---------------------------------------------------------------------------

    private void createWebview() {
        if (!SwingUtilities.isEventDispatchThread()) {
            ApplicationManager.getApplication().invokeLater(this::createWebview);
            return;
        }
        if (disposed || bridge != null) return;
        if (!DshBridge.isAvailable()) {
            LOG.warn("JCEF is not available; showing the DSH fallback panel");
            createFallback(DshBundle.message("dsh.fallback.jcef.unavailable"));
            return;
        }
        DshBridge candidate = null;
        try {
            candidate = new DshBridge(this::receiveAction);
            candidate.load();
            if (fallbackPanel != null) {
                remove(fallbackPanel);
                fallbackPanel = null;
                fallbackLabel = null;
            }
            bridge = candidate;
            add(candidate.getComponent(), BorderLayout.CENTER);
            revalidate();
            repaint();
        } catch (Throwable error) {
            if (candidate != null) candidate.dispose();
            LOG.warn("JCEF failed to initialize; showing the DSH fallback panel", error);
            createFallback(DshBundle.message("dsh.fallback.jcef.init.failed", error.getMessage()));
        }
    }

    private void createFallback(String reason) {
        if (fallbackPanel != null) remove(fallbackPanel);
        JPanel fallback = new JPanel(new BorderLayout(8, 8));
        fallbackPanel = fallback;
        fallback.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        JPanel info = new JPanel();
        info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
        info.add(
                new JBLabel("<html><b>" + DshBundle.message("dsh.fallback.title") + "</b></html>"));
        info.add(Box.createVerticalStrut(8));
        fallbackLabel =
                new JBLabel(
                        "<html>"
                                + reason
                                + "<br><br>"
                                + DshBundle.message("dsh.fallback.explanation")
                                + "</html>");
        info.add(fallbackLabel);
        fallback.add(info, BorderLayout.NORTH);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton start = new JButton(DshBundle.message("dsh.fallback.button.start.runtime"));
        start.addActionListener(event -> runAction("start", runtime::startAsync));
        JButton openBrowser = new JButton(DshBundle.message("dsh.fallback.button.open.browser"));
        openBrowser.addActionListener(
                event -> {
                    String url = runtime.getBrowserUrl();
                    if (url != null) BrowserUtil.browse(url);
                    else notify(DshBundle.message("dsh.fallback.runtime.not.started"));
                });
        JButton settings = new JButton(DshBundle.message("dsh.fallback.button.settings"));
        settings.addActionListener(event -> DshActions.openSettings(project));
        JButton diagnose = new JButton(DshBundle.message("dsh.fallback.button.diagnose"));
        diagnose.addActionListener(event -> showDiagnostics());
        JButton retryJcef = new JButton(DshBundle.message("dsh.fallback.button.retry.jcef"));
        retryJcef.addActionListener(event -> createWebview());
        actions.add(start);
        actions.add(openBrowser);
        actions.add(retryJcef);
        actions.add(settings);
        actions.add(diagnose);
        fallback.add(actions, BorderLayout.CENTER);
        add(fallback, BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    // ---------------------------------------------------------------------------
    // webview actions
    // ---------------------------------------------------------------------------

    private void receiveAction(JsonElement value) {
        if (disposed || value == null || !value.isJsonObject()) return;
        JsonObject action = DshWebviewActionSanitizer.sanitize(value.getAsJsonObject());
        if (action == null) return;
        String type = string(action, "type");
        if (type == null || type.isBlank()) return;
        if ("ready".equals(type)) {
            webviewReady = true;
            postStateLater();
            flushPendingComposerUpdate();
            subagents.refresh(sessionId);
            dynamicPlugins.refreshIfRunning();
            return;
        }
        switch (type) {
            case "start" -> runAction("start", runtime::startAsync);
            case "stop" -> runAction("stop", runtime::stopAsync);
            case "restart" -> runAction("restart", runtime::restartAsync);
            case "sendPrompt" -> prompts.send(action);
            case "cancel" -> prompts.cancel();
            case "updateQueue" -> prompts.updateQueue(action);
            case "newSession", "newSessionInCurrentWorkspace" -> {
                sessionId = null;
                newSessionDraft = true;
                pendingAgentPreset = null;
                lastError = null;
                DshSettingsState.getInstance(project).lastSessionId = "";
                subagents.reset();
                postStateLater();
            }
            case "switchSession" -> {
                sessionId = string(action, "sessionId");
                newSessionDraft = false;
                lastError = null;
                subagents.clearPreview();
                switchFollowedSession();
                subagents.refresh(sessionId);
            }
            case "searchSession" -> sessionActions.search();
            case "renameSession" -> sessionActions.rename();
            case "forkSession" -> sessionActions.fork();
            case "forkFromMessage" -> checkpointFork(integer(action, "seq", -1));
            case "restoreCodeToMessage" -> checkpointRestore(integer(action, "seq", -1));
            case "forkAndRestoreCodeToMessage" ->
                    checkpointForkAndRestore(integer(action, "seq", -1));
            case "copyMessage" -> copyMessage(string(action, "messageId"));
            case "toggleMessageFeedback" ->
                    feedback.toggle(string(action, "messageId"), string(action, "rating"));
            case "submitMessageFeedback" ->
                    feedback.submit(
                            string(action, "messageId"),
                            string(action, "rating"),
                            string(action, "note"),
                            string(action, "category"));
            case "saveMessageFeedbackNote" ->
                    feedback.saveNote(string(action, "messageId"), stringOr(action, "note", ""));
            case "openSessionFeedback" -> feedback.openSessionFeedback();
            case "dismissSessionFeedback" -> feedback.dismissSessionFeedback();
            case "recordSessionFeedback" ->
                    feedback.recordSessionFeedback(
                            stringOr(action, "text", ""), string(action, "category"));
            case "archiveSession" -> sessionActions.archive();
            case "openTrace" -> openTrace(action);
            case "openConversationOutline" -> openConversationOutline();
            case "openPromptTemplatePicker" -> openPromptTemplatePicker();
            case "openTerminalCommandPicker" ->
                    notify(DshBundle.message("dsh.terminal.context.unavailable"));
            case "openBrowser" -> openBrowser();
            case "openExternalLink" -> openExternalLink(action);
            case "openLogs" -> showLogs();
            case "cancelRecovery" -> runtime.cancelRecovery();
            case "restoreRecovery", "exportRecoveryDiagnostics" ->
                    runtime.recoveryAction("restoreRecovery".equals(type) ? "restore" : "export")
                            .whenComplete(
                                    (result, error) -> {
                                        if (error != null) notify(DshJson.message(error));
                                        else {
                                            String display =
                                                    DshBundle.message("dsh.recovery.restored");
                                            for (String line : result.split("\\R")) {
                                                if (line.startsWith("DSH_INTELLIJ_HELPER ")) {
                                                    JsonObject message =
                                                            com.google.gson.JsonParser.parseString(
                                                                            line.substring(
                                                                                    "DSH_INTELLIJ_HELPER "
                                                                                            .length()))
                                                                    .getAsJsonObject();
                                                    if ("diagnostics"
                                                            .equals(string(message, "event")))
                                                        display =
                                                                DshBundle.message(
                                                                        "dsh.recovery.exported",
                                                                        string(
                                                                                message
                                                                                        .getAsJsonObject(
                                                                                                "value"),
                                                                                "path"));
                                                }
                                            }
                                            notify(display);
                                        }
                                        postStateLater();
                                    });
            case "manageSettings" -> runtimeSettings.togglePanel();
            case "refreshPluginInventory" -> runtimeSettings.refreshPluginInventory();
            case "mutateSettings" -> runtimeSettings.mutate(action);
            case "configureApiKey" -> runtimeSettings.configureApiKey();
            case "manageProviders" -> runtimeSettings.manageProviders();
            case "manageAgentPresets" -> agentPresets.manage();
            case "selectAgentPreset" -> agentPresets.select(string(action, "agentPreset"));
            case "manageWorkspaces" -> workspaces.manage();
            case "openSettingsDocument" -> runtimeSettings.openDocument();
            case "openIdeContextPicker" -> ideContext.openPicker();
            case "toggleSelection" -> ideContext.toggleSelection();
            case "loadImage" -> prompts.loadImage(string(action, "attachmentId"));
            case "captureAppShot" -> ideContext.captureAppShot();
            case "toggleFocus" -> {
                focusMode = !focusMode;
                postStateLater();
            }
            case "fileReferenceQuery" ->
                    ideContext.fileReferenceQuery(stringOr(action, "query", ""));
            case "removeContext" -> ideContext.removeContext(string(action, "id"));
            case "openFileLocation" -> ideContext.openFileLocation(action);
            case "retryPrompt" -> prompts.retry(stringOr(action, "id", ""));
            case "selectModel" -> sessionActions.selectModel();
            case "selectReasoningEffort" ->
                    sessionActions.selectReasoningEffort(string(action, "effort"));
            case "openReasoningEffort" -> sessionActions.openReasoningEffort();
            case "setPermissionPreset" ->
                    sessionActions.setPermissionPreset(string(action, "value"));
            case "setPlanMode" -> prompts.setPlanMode(bool(action, "active", false));
            case "prefillGitDiff" -> prefillGitDiffTask(string(action, "kind"));
            case "askAboutResource" ->
                    askAboutResource(string(action, "path"), bool(action, "isDirectory", false));
            case "insertEditorReference" -> ideContext.insertCurrentFileReference();
            case "explainDebugState" -> debugContext.explainDebugState();
            case "goalCreate", "goalEdit", "goalPause", "goalResume", "goalComplete", "goalClear" ->
                    goals.mutate(sessionId, action);
            case "refreshSubagents" -> subagents.refresh(sessionId);
            case "openSubagent" -> subagents.open(string(action, "childSessionId"));
            case "closeSubagent" -> subagents.clearPreview();
            case "followUpSubagent" ->
                    subagents.followUp(string(action, "childSessionId"), string(action, "text"));
            case "interruptSubagent" -> subagents.interrupt(string(action, "childSessionId"));
            case "refreshDynamicPlugins" -> dynamicPlugins.refresh();
            case "stopDynamicPlugin" ->
                    dynamicPlugins.stop(string(action, "sessionId"), string(action, "pluginId"));
            case "removeDynamicPlugin" ->
                    dynamicPlugins.remove(string(action, "sessionId"), string(action, "pluginId"));
            case "declineDynamicPlugin" ->
                    dynamicPlugins.decline(string(action, "pluginId"), string(action, "requestId"));
            case "answerApproval", "answerQuestion" -> answerInteraction(action);
            case "copyCode", "insertCode", "openCode", "applyCode" -> codeActions.handle(action);
            case "openToolDiff" ->
                    diffs.openToolDiff(string(action, "callId"), string(action, "path"));
            case "openChangeDiff" ->
                    diffs.openChangeDiff(integer(action, "turn", 0), string(action, "fileId"));
            case "restoreTurnChanges" -> diffs.restoreTurnChanges(integer(action, "turn", 0));
            default -> LOG.debug("Ignoring unsupported DSH webview action: " + type);
        }
    }

    private void checkpointFork(int sequence) {
        Integer turn = checkpointTurn(sequence);
        if (turn == null) return;
        sessionActions.forkAt((long) sequence);
    }

    private void checkpointRestore(int sequence) {
        Integer turn = checkpointTurn(sequence);
        if (turn == null) return;
        diffs.restoreTurnChanges(turn);
    }

    private void checkpointForkAndRestore(int sequence) {
        Integer turn = checkpointTurn(sequence);
        if (turn == null) return;
        String current = sessionId;
        if (current == null || current.isBlank()) return;
        diffs.restoreTurnChanges(turn, () -> sessionActions.forkAt((long) sequence));
    }

    private Integer checkpointTurn(int sequence) {
        String current = sessionId;
        if (current == null || current.isBlank() || sequence < 0) return null;
        Integer turn = sessionState.checkpointTurn(current, sequence);
        if (turn == null) {
            notify("This message is no longer available for a checkpoint action.");
        }
        return turn;
    }

    /** Copy the host-projected message text rather than trusting text supplied by the WebView. */
    private void copyMessage(String messageId) {
        if (messageId == null || messageId.isBlank()) return;
        String scope = sessionId == null ? "none" : sessionId;
        String cacheKey = scope + ":" + messageId;
        String text = null;
        synchronized (copyableMessageTexts) {
            text = copyableMessageTexts.get(cacheKey);
        }
        JsonArray current = messages;
        if (text == null) {
            for (JsonElement candidate : current) {
                if (!candidate.isJsonObject()) continue;
                JsonObject row = candidate.getAsJsonObject();
                if (!messageId.equals(string(row, "id"))) continue;
                String role = string(row, "role");
                if (!"user".equals(role) && !"assistant".equals(role)) break;
                text = stringOr(row, "text", "");
                String skill = string(row, "skillInvocation");
                if ("user".equals(role) && skill != null && !skill.isBlank()) {
                    text = "/" + skill + (text.isBlank() ? "" : " " + text);
                }
                if (!text.isBlank()) rememberCopyableMessage(cacheKey, text);
                break;
            }
        }
        if (text == null || text.isBlank()) {
            notify(DshBundle.message("dsh.message.copy.unavailable"));
            return;
        }
        String copied = text;
        ApplicationManager.getApplication()
                .invokeLater(
                        () ->
                                CopyPasteManager.getInstance()
                                        .setContents(new StringSelection(copied)));
    }

    private void rememberCopyableMessages(String session, JsonArray rows) {
        if (session == null || session.isBlank() || rows == null) return;
        for (JsonElement candidate : rows) {
            if (!candidate.isJsonObject()) continue;
            JsonObject row = candidate.getAsJsonObject();
            String role = string(row, "role");
            if (!"user".equals(role) && !"assistant".equals(role)) continue;
            String text = stringOr(row, "text", "");
            String skill = string(row, "skillInvocation");
            if ("user".equals(role) && skill != null && !skill.isBlank()) {
                text = "/" + skill + (text.isBlank() ? "" : " " + text);
            }
            if (!text.isBlank()) {
                rememberCopyableMessage(session + ":" + string(row, "id"), text);
            }
        }
    }

    private void rememberCopyableMessage(String key, String text) {
        if (key == null || key.endsWith(":null") || text == null || text.isBlank()) return;
        synchronized (copyableMessageTexts) {
            copyableMessageTexts.put(key, text);
            while (copyableMessageTexts.size() > 2_000) {
                copyableMessageTexts.remove(copyableMessageTexts.keySet().iterator().next());
            }
        }
    }

    /** Dispatch a structured action from IDE menus, as if it came from the webview. */
    public void runAction(JsonElement value) {
        receiveAction(value);
    }

    /** Replace the composer text; queued until the webview is ready, like dsh-ide. */
    public void setComposerText(String text) {
        queueComposerUpdate("setText", text);
    }

    /** Insert text at the composer caret; queued until the webview is ready. */
    public void insertComposerText(String text) {
        queueComposerUpdate("insertText", text + " ");
    }

    private void queueComposerUpdate(String type, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        JsonObject update = new JsonObject();
        update.addProperty("type", type);
        update.addProperty("text", text);
        pendingComposerUpdate = update;
        DshActions.openToolWindow(project);
        flushPendingComposerUpdate();
    }

    private void flushPendingComposerUpdate() {
        if (!webviewReady) {
            return;
        }
        JsonObject update = pendingComposerUpdate;
        pendingComposerUpdate = null;
        if (update != null) {
            postToWebview(update);
        }
    }

    /**
     * Attach the working-tree Git diff as a one-shot context chip and prefill the composer with the
     * requested task prompt, mirroring dsh-ide's git diff quick tasks.
     */
    private void prefillGitDiffTask(String kind) {
        operations.execute(
                () -> {
                    String base = project.getBasePath();
                    if (base == null) {
                        notify(DshBundle.message("dsh.git.diff.no.project"));
                        return;
                    }
                    String content;
                    boolean failed = false;
                    try {
                        ProcessBuilder builder =
                                new ProcessBuilder("git", "diff", "--no-ext-diff", "--unified=3");
                        builder.directory(Path.of(base).toFile());
                        builder.redirectErrorStream(false);
                        Process process = builder.start();
                        String output;
                        try (var reader =
                                new java.io.BufferedReader(
                                        new java.io.InputStreamReader(
                                                process.getInputStream(),
                                                java.nio.charset.StandardCharsets.UTF_8))) {
                            StringBuilder collected = new StringBuilder();
                            char[] buffer = new char[8192];
                            int read;
                            while ((read = reader.read(buffer)) >= 0)
                                collected.append(buffer, 0, read);
                            output = collected.toString();
                        }
                        if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                            process.destroyForcibly();
                            failed = true;
                        }
                        if (failed || process.exitValue() != 0) failed = true;
                        if (failed) {
                            notify(
                                    DshBundle.message(
                                            "dsh.git.diff.failed", "git diff exited non-zero"));
                            return;
                        }
                        content =
                                output.isBlank() ? DshBundle.message("dsh.git.diff.empty") : output;
                    } catch (java.io.IOException | InterruptedException error) {
                        if (error instanceof InterruptedException)
                            Thread.currentThread().interrupt();
                        notify(
                                DshBundle.message(
                                        "dsh.git.diff.failed",
                                        error.getMessage() == null
                                                ? "git diff"
                                                : error.getMessage()));
                        return;
                    }
                    int cap =
                            Math.min(
                                    DshSettingsState.getInstance(project).maxContextBytes, 300_000);
                    String[] limited = truncateUtf8(content, cap);
                    String finalKind = kind == null ? "explain" : kind;
                    ApplicationManager.getApplication()
                            .invokeLater(
                                    () -> {
                                        if (disposed) return;
                                        ideContext.attachGitDiff(
                                                limited[0], Boolean.parseBoolean(limited[1]));
                                        setComposerText(
                                                DshBundle.message(
                                                        "dsh.git.diff.task." + finalKind));
                                    });
                });
    }

    /** UTF-8-safe truncation; returns {text, truncated}. */
    private static String[] truncateUtf8(String value, int maxBytes) {
        if (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= maxBytes) {
            return new String[] {value, "false"};
        }
        StringBuilder builder = new StringBuilder();
        int bytes = 0;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            int width = utf8Width(codePoint);
            if (bytes + width > maxBytes) break;
            builder.appendCodePoint(codePoint);
            bytes += width;
            offset += Character.charCount(codePoint);
        }
        return new String[] {builder.toString(), "true"};
    }

    private static int utf8Width(int codePoint) {
        if (codePoint < 0x80) return 1;
        if (codePoint < 0x800) return 2;
        if (codePoint < 0x10000) return 3;
        return 4;
    }

    /** Prefill a workspace-scoped prompt about one project resource, like dsh-ide. */
    private void askAboutResource(String relativePath, boolean directory) {
        String cleaned = relativePath == null ? "" : relativePath.replace('\\', '/');
        if (cleaned.isBlank() || cleaned.contains("..")) {
            notify(DshBundle.message("dsh.ask.resource.outside"));
            return;
        }
        String type =
                DshBundle.message(
                        directory ? "dsh.ask.resource.directory" : "dsh.ask.resource.file");
        setComposerText(
                String.join(
                        "\n",
                        DshBundle.message("dsh.ask.resource.prompt"),
                        DshBundle.message("dsh.ask.resource.root") + ": " + project.getBasePath(),
                        DshBundle.message("dsh.ask.resource.target") + ": " + cleaned,
                        DshBundle.message("dsh.ask.resource.type") + ": " + type));
    }

    /** Keep the JCEF page as an untrusted action sender, matching dsh-ide's boundary. */
    public void submitPromptFromIde(String instruction) {
        JsonObject action = new JsonObject();
        action.addProperty("type", "sendPrompt");
        action.addProperty("text", instruction == null ? "" : instruction);
        action.addProperty("mode", "queue");
        if (instruction != null && !instruction.isBlank()) prompts.send(action);
    }

    /** Reset the selected session without creating a remote session eagerly. */
    public void newSession() {
        sessionId = null;
        newSessionDraft = true;
        pendingAgentPreset = null;
        DshSettingsState.getInstance(project).lastSessionId = "";
        lastError = null;
        projection = DshMessageProjector.Projection.empty();
        messages = new JsonArray();
        postStateLater();
    }

    /** Dispatch a command registered in plugin.xml as if it came from the webview menu. */
    public void runCommand(String type) {
        JsonObject action = new JsonObject();
        action.addProperty("type", type);
        receiveAction(action);
    }

    public void showDiagnostics() {
        operations.execute(
                () -> {
                    String report = runtime.diagnoseEnvironment() + "\n" + remote.diagnostics();
                    ApplicationManager.getApplication()
                            .invokeLater(
                                    () -> {
                                        if (!disposed) {
                                            DshTextDialog.show(
                                                    project,
                                                    DshBundle.message("dsh.diagnose.dialog.title"),
                                                    report);
                                        }
                                    });
                });
    }

    // ---------------------------------------------------------------------------
    // session and workspace helpers
    // ---------------------------------------------------------------------------

    private String ensureSession() throws DshRemoteException {
        agentPresets.prepareForSend(sessionId);
        if (sessionId != null && !sessionId.isBlank()) return sessionId;
        String cwd = project.getBasePath();
        String workspaceId = resolveWorkspaceId(cwd);
        JsonObject created =
                remote.createSession(
                        workspaceId != null ? null : cwd, workspaceId, pendingAgentPreset);
        String createdId = string(created, "sessionId");
        if (createdId == null || createdId.isBlank())
            throw DshRemoteException.remote(
                    "session/create",
                    "invalid-result",
                    "Harness did not return a sessionId",
                    new JsonObject());
        sessionId = createdId;
        newSessionDraft = false;
        pendingAgentPreset = null;
        return createdId;
    }

    private String resolveWorkspaceId(String cwd) {
        if (cwd == null || cwd.isBlank()) return null;
        JsonObject registration = currentWorkspaceRegistration;
        if (registration != null) {
            String id = string(registration, "workspaceId");
            if (id != null && !id.isBlank()) return id;
        }
        try {
            JsonObject result = remote.createWorkspace(cwd);
            JsonObject workspace =
                    result.has("workspace") && result.get("workspace").isJsonObject()
                            ? result.getAsJsonObject("workspace")
                            : new JsonObject();
            String id = string(workspace, "workspaceId");
            currentWorkspaceRegistration = workspace;
            return id;
        } catch (Exception error) {
            LOG.debug("Unable to resolve workspace for session creation", error);
        }
        return null;
    }

    private void chooseSessionIfNecessary(DshRemoteState.Snapshot current) {
        if (newSessionDraft) return;
        if (sessionId != null && containsSession(current, sessionId)) return;
        // Restore the persisted session first, mirroring dsh-ide's
        // persistSession: what you had open is what you get back, even when it
        // is a blank draft. The follow stream dies quietly if the session was
        // deleted elsewhere, and the catalog fallback below takes over.
        if (DshSettingsState.getInstance(project).persistSession) {
            String persisted = DshSettingsState.getInstance(project).lastSessionId;
            if (persisted != null && !persisted.isBlank() && containsSession(current, persisted)) {
                sessionId = persisted;
                return;
            }
        }
        // Do not auto-select a blank session. dsh-ide creates the session only
        // when the first prompt is sent, which keeps an opened tool window quiet.
        for (JsonElement candidate : current.catalog) {
            if (!candidate.isJsonObject()) continue;
            JsonObject item = candidate.getAsJsonObject();
            if (!item.has("blank") || !item.get("blank").getAsBoolean()) {
                String id = string(item, "sessionId");
                if (id != null && !id.isBlank()) {
                    sessionId = id;
                    return;
                }
            }
        }
    }

    /** Persist the selected session so the next project open can restore it. */
    private void persistSelectedSession() {
        String current = sessionId;
        DshSettingsState settings = DshSettingsState.getInstance(project);
        if (!settings.persistSession) return;
        String saved = settings.lastSessionId;
        if (current != null && !current.isBlank() && !current.equals(saved)) {
            settings.lastSessionId = current;
        }
    }

    private static boolean containsSession(DshRemoteState.Snapshot current, String id) {
        for (JsonElement candidate : current.catalog) {
            if (candidate.isJsonObject()
                    && id.equals(string(candidate.getAsJsonObject(), "sessionId"))) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------------------
    // snapshot projections used by buildState and controllers
    // ---------------------------------------------------------------------------

    private JsonArray catalogRows() {
        return snapshot.catalog;
    }

    private DshRemoteState.SessionView sessionView(String session) {
        DshRemoteState.Snapshot current = snapshot;
        return session == null ? null : current.sessions.get(session);
    }

    private DshRemoteState.ProjectionCell projectionCell(String session, String key) {
        DshRemoteState.SessionView view = sessionView(session);
        return view == null ? null : view.projections.get(key);
    }

    private JsonObject sessionRow(String session) {
        if (session == null) return null;
        for (JsonElement candidate : snapshot.catalog) {
            if (candidate.isJsonObject()
                    && session.equals(string(candidate.getAsJsonObject(), "sessionId"))) {
                return candidate.getAsJsonObject();
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------
    // EDT state publish
    // ---------------------------------------------------------------------------

    private void refreshAfterMutation() {
        // Mutations stream back through the Remote connections; only the
        // transient local error marker needs clearing here.
        lastError = null;
        postStateLater();
    }

    private void postStateLater() {
        if (disposed || !statePostPending.compareAndSet(false, true)) return;
        SwingUtilities.invokeLater(
                () -> {
                    statePostPending.set(false);
                    if (!disposed) postState();
                });
    }

    private void postState() {
        if (!SwingUtilities.isEventDispatchThread()) {
            postStateLater();
            return;
        }
        if (disposed || bridge == null || !webviewReady) return;
        JsonObject envelope = new JsonObject();
        envelope.addProperty("type", "state");
        envelope.addProperty("protocol", WEBVIEW_PROTOCOL_VERSION);
        envelope.add("state", ReadAction.compute(this::buildState));
        bridge.postMessage(envelope);
    }

    private JsonObject buildState() {
        DshRuntimeService.RuntimeStatus runtimeStatus = runtime.getStatus();
        DshRemoteState.Snapshot current = snapshot;
        JsonObject state = new JsonObject();
        JsonArray visibleMessages =
                feedback.decorate(
                        sessionId, messages == null ? new JsonArray() : messages.deepCopy());
        state.add("messages", visibleMessages);
        state.add("context", ideContext.contextMetadata());
        JsonObject selection = ideContext.currentSelection(false);
        if (selection != null) state.add("selection", selection);
        state.add("fileReferenceCandidates", ideContext.fileReferenceCandidates());
        JsonObject panel = runtimeSettings.panel();
        if (panel != null) state.add("settings", panel.deepCopy());
        state.addProperty("selectionEnabled", ideContext.isSelectionEnabled());
        state.addProperty("modeSelectionEnabled", agentPresets.modeSelectionEnabled());
        JsonObject status = new JsonObject();
        status.addProperty(
                "state",
                runtimeStatus.state == DshRuntimeService.RuntimeState.RUNNING
                                && "error".equals(current.phase)
                        ? "error"
                        : runtimeState(runtimeStatus.state));
        if (runtimeStatus.url != null) status.addProperty("url", runtimeStatus.url);
        String statusMessage = statusMessage(runtimeStatus, current);
        if (statusMessage != null && !statusMessage.isBlank())
            status.addProperty("message", statusMessage);
        JsonObject recovery = runtime.recoveryStatus();
        if (recovery != null) status.add("recovery", recovery);
        state.add("status", status);
        boolean running = projection != null && projection.running;
        JsonObject currentRow = sessionRow(sessionId);
        if (currentRow != null) {
            running = bool(currentRow, "running", running);
        }
        state.addProperty("busy", running);
        state.addProperty("submitting", prompts.isSubmitting());
        state.addProperty("cancelling", prompts.isCancelling());
        state.addProperty("focusMode", focusMode);
        state.addProperty("workspaceName", project.getName());
        JsonObject currentWorkspace = new JsonObject();
        JsonObject registration = currentWorkspaceRegistration;
        if (registration != null) {
            DshJson.copyString(registration, currentWorkspace, "workspaceId");
            currentWorkspace.addProperty(
                    "title", stringOr(registration, "title", project.getName()));
        } else {
            currentWorkspace.addProperty("title", project.getName());
        }
        state.add("currentWorkspace", currentWorkspace);
        if (sessionId != null) state.addProperty("sessionId", sessionId);
        state.add("sessions", current.catalog.deepCopy());
        if (pendingAgentPreset != null && !pendingAgentPreset.isBlank()) {
            state.addProperty("agentPreset", pendingAgentPreset);
        }
        if (sessionId != null) {
            JsonObject sessionStatus = new JsonObject();
            sessionStatus.addProperty("running", running);
            sessionStatus.addProperty("attention", bool(currentRow, "attention", false));
            JsonObject turn = new JsonObject();
            DshMessageProjector.Projection currentProjection = projection;
            turn.addProperty(
                    "phase",
                    currentProjection == null
                            ? (running ? "running" : "completed")
                            : currentProjection.phase);
            if (currentProjection != null && currentProjection.turn > 0)
                turn.addProperty("turn", currentProjection.turn);
            if (currentProjection != null && currentProjection.detail != null)
                turn.addProperty("detail", currentProjection.detail);
            sessionStatus.add("turn", turn);
            state.add("sessionStatus", sessionStatus);
            if (currentRow != null) {
                String preset = string(currentRow, "agentPreset");
                if (preset != null && !preset.isBlank()) {
                    state.addProperty("agentPreset", preset);
                    String label = agentPresets.label(preset);
                    if (label != null) state.addProperty("agentPresetLabel", label);
                }
            }
        }
        DshRemoteState.ProjectionCell permissionsCell = projectionCell(sessionId, "permissions");
        JsonObject permissions =
                DshSessionStateStore.permissions(
                        permissionsCell == null ? null : permissionsCell.value());
        if (permissions != null) state.add("permissions", permissions);
        DshRemoteState.SessionView view = sessionView(sessionId);
        state.add(
                "interactions",
                DshInteractionProjector.present(
                        view == null ? new JsonArray() : view.interactions));
        state.add("queue", view == null ? new JsonArray() : view.queue);
        state.add("jobs", view == null ? new JsonArray() : view.jobs);
        state.add("changeReviews", changeReviews.view(sessionId));
        state.add("skills", sessionState.skillCatalog(sessionId));
        state.add("commands", sessionState.commandCatalog(sessionId));
        JsonArray todos = DshSessionStateStore.todos(cellValue(sessionId, "todos"));
        if (todos != null) state.add("todos", todos);
        JsonArray schedule = DshSessionStateStore.schedule(cellValue(sessionId, "schedule"));
        if (schedule != null) state.add("schedule", schedule);
        JsonObject imageLimits =
                DshSessionStateStore.imageLimits(cellValue(sessionId, "imageLimits"));
        if (imageLimits != null) state.add("imageLimits", imageLimits);
        JsonObject sessionStats =
                DshSessionStateStore.sessionStats(cellValue(sessionId, "sessionStats"));
        if (sessionStats != null) state.add("sessionStats", sessionStats);
        JsonObject plan = DshSessionStateStore.plan(cellValue(sessionId, "plan"));
        if (plan != null) state.add("plan", plan);
        JsonObject messageFeedback = feedback.view(sessionId);
        if (messageFeedback != null) state.add("messageFeedback", messageFeedback);
        JsonObject sessionFeedback = feedback.sessionView(sessionId);
        if (sessionFeedback != null) state.add("sessionFeedback", sessionFeedback);
        JsonObject tokenUsage = tokenUsageView(view);
        if (tokenUsage != null) state.add("tokenUsage", tokenUsage);
        JsonObject goal = goals.view(sessionId);
        if (goal != null) state.add("goal", goal);
        state.add("subagents", subagents.treeView(sessionId));
        JsonObject subagentPreviewState = subagents.previewView(sessionId);
        if (subagentPreviewState != null) state.add("subagentPreview", subagentPreviewState);
        JsonObject dynamicPluginView = dynamicPlugins.view();
        if (dynamicPluginView != null) state.add("dynamicPlugins", dynamicPluginView);
        String statusLabel = agentStatusLabelFor(sessionId, running);
        if (statusLabel != null) {
            state.addProperty("agentStatusLabel", statusLabel);
        }
        state.add("reasoningEffort", sessionActions.reasoningEffort(sessionId, view));
        return state;
    }

    /**
     * Resolve the streaming status label: the fixed override first, then a per-session random pick
     * from the candidate list while the agent is running, matching dsh-ide's agentStatusLabels.
     */
    private String agentStatusLabelFor(String session, boolean busy) {
        DshSettingsState settings = DshSettingsState.getInstance(project);
        String fixed = settings.agentStatusLabel;
        if (fixed != null && !fixed.isBlank()) {
            return fixed.trim();
        }
        java.util.List<String> candidates =
                settings.agentStatusLabels == null
                        ? java.util.List.of()
                        : settings.agentStatusLabels;
        if (!busy || session == null || session.isBlank() || candidates.isEmpty()) {
            return null;
        }
        StringBuilder key = new StringBuilder();
        for (String candidate : candidates) key.append(candidate).append('\0');
        int seed = (session + "\0" + key).hashCode();
        return candidates.get(Math.floorMod(seed, candidates.size()));
    }

    private static String statusMessage(
            DshRuntimeService.RuntimeStatus runtimeStatus, DshRemoteState.Snapshot current) {
        if (runtimeStatus.state != DshRuntimeService.RuntimeState.RUNNING) {
            return runtimeStatus.message;
        }
        if ((DshRemoteService.PHASE_RECONNECTING.equals(current.phase)
                        || "error".equals(current.phase))
                && current.message != null) {
            return current.message;
        }
        return null;
    }

    private JsonElement cellValue(String session, String key) {
        DshRemoteState.ProjectionCell cell = projectionCell(session, key);
        return cell == null ? null : cell.value();
    }

    private JsonObject tokenUsageView(DshRemoteState.SessionView view) {
        String session = sessionId;
        JsonObject catalog = sessionActions.modelCatalog(session);
        JsonObject route =
                DshSessionStateStore.currentModelRoute(
                        view == null ? null : cellValueOf(view, "modelSelection"), catalog);
        if (view == null) return null;
        return DshSessionStateStore.tokenUsage(
                route,
                cellValueOf(view, "tokenUsage"),
                cellValueOf(view, "contextPressure"),
                cellValueOf(view, "contextBreakdown"));
    }

    private static JsonElement cellValueOf(DshRemoteState.SessionView view, String key) {
        DshRemoteState.ProjectionCell cell = view.projections.get(key);
        return cell == null ? null : cell.value();
    }

    private void postToWebview(JsonObject envelope) {
        SwingUtilities.invokeLater(
                () -> {
                    if (disposed || bridge == null || !webviewReady) return;
                    bridge.postMessage(envelope);
                });
    }

    private void answerInteraction(JsonObject action) {
        String key = string(action, "key");
        String current = sessionId;
        if (key == null || key.isBlank() || current == null || current.isBlank()) return;
        // The Runtime expects the raw outcome value: an approval decision string,
        // or the question answer envelope.
        JsonElement outcomeValue;
        if (action.has("outcome")) {
            outcomeValue = action.get("outcome").deepCopy();
        } else {
            JsonObject answers = new JsonObject();
            answers.add(
                    "answers",
                    action.has("answers") ? action.get("answers").deepCopy() : new JsonArray());
            outcomeValue = answers;
        }
        operations.execute(
                () -> {
                    DshRemoteState.SessionView view = sessionView(current);
                    boolean pending = false;
                    if (view != null) {
                        for (JsonElement candidate : view.interactions) {
                            if (!candidate.isJsonObject()) continue;
                            JsonObject item = candidate.getAsJsonObject();
                            String expectedKind = action.has("outcome") ? "approval" : "question";
                            if (key.equals(string(item, "key"))
                                    && expectedKind.equals(string(item, "kind"))
                                    && "pending".equals(string(item, "status"))) {
                                pending = true;
                                break;
                            }
                        }
                    }
                    if (!pending) {
                        lastError = DshBundle.message("dsh.interaction.unavailable");
                        postStateLater();
                        return;
                    }
                    String failure = remote.answerInteraction(current, key, outcomeValue);
                    if (failure != null) {
                        lastError = failure;
                        postStateLater();
                    }
                });
    }

    private void openBrowser() {
        String url = runtime.getBrowserUrl();
        if (url == null) {
            notify(DshBundle.message("dsh.runtime.not.running"));
            return;
        }
        BrowserUtil.browse(url);
    }

    private void openTrace(JsonObject action) {
        String current = sessionId;
        if (current == null || current.isBlank()) {
            notify(DshBundle.message("dsh.trace.no.active.session"));
            return;
        }
        int selectedSeq = integer(action, "seq", -1);
        JsonObject row = sessionRow(current);
        String title = row == null ? current : stringOr(row, "title", current);
        String traceTitle = title;
        ApplicationManager.getApplication()
                .invokeLater(
                        () -> new DshTraceDialog(project, current, traceTitle, selectedSeq).show());
    }

    /** Show a native navigator for the current session's turns and reveal the chosen message. */
    private void openConversationOutline() {
        String current = sessionId;
        if (current == null || current.isBlank()) {
            notify(DshBundle.message("dsh.outline.no.active.session"));
            return;
        }
        List<String> labels = new java.util.ArrayList<>();
        List<Integer> sequences = new java.util.ArrayList<>();
        JsonElement outline = cellValue(current, "turnOutline");
        if (outline != null && outline.isJsonArray()) {
            for (JsonElement candidate : outline.getAsJsonArray()) {
                if (!candidate.isJsonObject()) continue;
                JsonObject row = candidate.getAsJsonObject();
                long seq = DshJson.longValue(row.get("seq"), -1);
                if (seq < 0 || seq > Integer.MAX_VALUE) continue;
                String prompt = stringOr(row, "prompt", "").replaceAll("\\s+", " ").trim();
                String response = stringOr(row, "response", "").replaceAll("\\s+", " ").trim();
                String label = prompt.isBlank() ? response : prompt;
                if (label.isBlank()) label = DshBundle.message("dsh.outline.turn", row.get("turn"));
                if (label.length() > 120) label = label.substring(0, 119) + "…";
                labels.add(label + "  (#" + seq + ")");
                sequences.add((int) seq);
            }
        }
        if (labels.isEmpty()) {
            for (JsonElement candidate : messages) {
                if (!candidate.isJsonObject()) continue;
                JsonObject row = candidate.getAsJsonObject();
                if (!"user".equals(string(row, "role"))) continue;
                long seq = DshJson.longValue(row.get("seq"), -1);
                String text = stringOr(row, "text", "").replaceAll("\\s+", " ").trim();
                if (seq < 0 || seq > Integer.MAX_VALUE || text.isBlank()) continue;
                if (text.length() > 120) text = text.substring(0, 119) + "…";
                labels.add(text + "  (#" + seq + ")");
                sequences.add((int) seq);
            }
        }
        if (labels.isEmpty()) {
            notify(DshBundle.message("dsh.outline.empty"));
            return;
        }
        int selected =
                Messages.showChooseDialog(
                        project,
                        DshBundle.message("dsh.outline.dialog.message"),
                        DshBundle.message("dsh.outline.dialog.title"),
                        Messages.getInformationIcon(),
                        labels.toArray(new String[0]),
                        labels.get(0));
        if (selected >= 0 && selected < sequences.size()) {
            JsonObject reveal = new JsonObject();
            reveal.addProperty("type", "revealMessage");
            reveal.addProperty("seq", sequences.get(selected));
            postToWebview(reveal);
        }
    }

    /** Discover visible Markdown drafts under .dsh/prompts without injecting them implicitly. */
    private void openPromptTemplatePicker() {
        String base = project.getBasePath();
        if (base == null || base.isBlank()) {
            notify(DshBundle.message("dsh.prompt.template.no.workspace"));
            return;
        }
        Path promptsRoot = Path.of(base).resolve(".dsh").resolve("prompts").normalize();
        operations.execute(
                () -> {
                    List<PromptTemplate> templates = discoverPromptTemplates(promptsRoot);
                    ApplicationManager.getApplication()
                            .invokeLater(
                                    () -> {
                                        if (disposed) return;
                                        if (templates.isEmpty()) {
                                            notify(DshBundle.message("dsh.prompt.template.empty"));
                                            return;
                                        }
                                        String[] labels =
                                                templates.stream()
                                                        .map(
                                                                template ->
                                                                        template.label
                                                                                + "  —  "
                                                                                + template.relative)
                                                        .toArray(String[]::new);
                                        int selected =
                                                Messages.showChooseDialog(
                                                        project,
                                                        DshBundle.message(
                                                                "dsh.prompt.template.choose.message"),
                                                        DshBundle.message(
                                                                "dsh.prompt.template.choose.title"),
                                                        Messages.getInformationIcon(),
                                                        labels,
                                                        labels[0]);
                                        if (selected >= 0 && selected < templates.size())
                                            setComposerText(templates.get(selected).content);
                                    });
                });
    }

    private static List<PromptTemplate> discoverPromptTemplates(Path root) {
        List<PromptTemplate> result = new ArrayList<>();
        if (!Files.isDirectory(root)) return result;
        try (var paths = Files.walk(root, 4)) {
            paths.filter(
                            path ->
                                    !Files.isSymbolicLink(path)
                                            && Files.isRegularFile(path)
                                            && path.getFileName()
                                                    .toString()
                                                    .toLowerCase(Locale.ROOT)
                                                    .endsWith(".md"))
                    .sorted()
                    .limit(100)
                    .forEach(
                            path -> {
                                try {
                                    if (Files.size(path) > 32 * 1024) return;
                                    String content = Files.readString(path, StandardCharsets.UTF_8);
                                    if (!content.isEmpty() && content.charAt(0) == '\ufeff')
                                        content = content.substring(1);
                                    String relative =
                                            root.relativize(path).toString().replace('\\', '/');
                                    String stem = relative.replaceFirst("(?i)\\.md$", "");
                                    result.add(
                                            new PromptTemplate(
                                                    relative,
                                                    promptTemplateTitle(content, stem),
                                                    content));
                                } catch (Exception ignored) {
                                    // A broken or concurrently deleted draft is simply not listed.
                                }
                            });
        } catch (Exception ignored) {
            // Missing/unreadable .dsh/prompts behaves like an empty template directory.
        }
        return result;
    }

    private static String promptTemplateTitle(String content, String fallback) {
        String[] lines = content.substring(0, Math.min(content.length(), 8_192)).split("\\R");
        if (lines.length > 0 && lines[0].trim().equals("---")) {
            for (int index = 1; index < lines.length && index <= 20; index++) {
                String line = lines[index].trim();
                if (line.equals("---")) break;
                if (line.startsWith("title:")) {
                    String title = line.substring("title:".length()).trim();
                    if (!title.isBlank()) return trimTemplateTitle(title);
                }
            }
        }
        for (String line : lines) {
            String text = line.trim();
            if (text.startsWith("# ")) return trimTemplateTitle(text.substring(2).trim());
        }
        return trimTemplateTitle(fallback);
    }

    private static String trimTemplateTitle(String value) {
        String trimmed = value.replaceAll("^[\\\"']|[\\\"']$", "").trim();
        return trimmed.length() <= 120 ? trimmed : trimmed.substring(0, 119) + "…";
    }

    private record PromptTemplate(String relative, String label, String content) {}

    private void openExternalLink(JsonObject action) {
        String url = string(action, "url");
        if (url == null) url = string(action, "href");
        if (!DshWebviewActionSanitizer.isSafeExternalUrl(url)) return;
        BrowserUtil.browse(url);
    }

    private void showLogs() {
        DshTextDialog.show(
                project,
                DshBundle.message("dsh.logs.dialog.title"),
                runtime.getLogs().isBlank()
                        ? DshBundle.message("dsh.logs.no.output")
                        : runtime.getLogs());
    }

    private void runAction(String name, java.util.function.Supplier<CompletableFuture<?>> action) {
        operations.execute(
                () ->
                        action.get()
                                .whenComplete(
                                        (ignored, error) -> {
                                            if (error != null) {
                                                lastError = message(error);
                                                LOG.warn("DSH " + name + " failed", error);
                                            } else {
                                                lastError = null;
                                            }
                                            postStateLater();
                                        }));
    }

    private void notify(String text) {
        ApplicationManager.getApplication()
                .invokeLater(
                        () ->
                                com.intellij.notification.NotificationGroupManager.getInstance()
                                        .getNotificationGroup("DeepSeek Harness")
                                        .createNotification(
                                                text,
                                                com.intellij.notification.NotificationType
                                                        .INFORMATION)
                                        .notify(project));
    }

    private static String runtimeState(DshRuntimeService.RuntimeState state) {
        return state.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public void dispose() {
        disposed = true;
        runtime.removeStatusListener(runtimeStatusListener);
        remote.removeListener(snapshotListener);
        releaseFollowedSession();
        changeReviews.dispose();
        feedback.dispose();
        dynamicPlugins.dispose();
        operations.shutdownNow();
        if (bridge != null) bridge.dispose();
    }
}
