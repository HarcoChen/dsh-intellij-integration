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
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBLabel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
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

    private final Consumer<DshRemoteState.Snapshot> snapshotListener = this::onSnapshot;
    private final Consumer<DshRuntimeService.RuntimeStatus> runtimeStatusListener =
            ignored -> postStateLater();
    private final DshMarkdownRenderCache markdownRenderCache = new DshMarkdownRenderCache();
    private final DshIdeContextController ideContext;
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
    private volatile JsonObject currentWorkspaceRegistration;
    private volatile String canonicalBasePath;
    private volatile DshRemoteState.Snapshot snapshot = DshRemoteService.emptySnapshot();
    private volatile JsonArray messages = new JsonArray();
    private volatile DshMessageProjector.Projection projection;
    private volatile String lastError;

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
                        this::catalogRows,
                        id -> {
                            sessionId = id;
                            newSessionDraft = false;
                        },
                        () -> {
                            sessionId = null;
                            pendingAgentPreset = null;
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

        if (selected != null) {
            String followKey = "session:" + selected;
            DshRemoteState.FollowView view = current.follows.get(followKey);
            long cursor = view == null ? Long.MIN_VALUE : view.cursor;
            boolean replaced = view != null && view.events != projectedEvents;
            if (view != null && (cursor != projectedCursor || replaced)) {
                JsonObject history = new JsonObject();
                history.add("events", view.events);
                DshSettingsState settings = DshSettingsState.getInstance(project);
                String statusLabel =
                        settings.agentStatusLabel == null || settings.agentStatusLabel.isBlank()
                                ? DshBundle.message("dsh.status.thinking")
                                : settings.agentStatusLabel.trim();
                DshSessionStateStore.HistoryProjection cached =
                        sessionState.projectHistory(selected, history, statusLabel);
                projection = cached.projection();
                messages = cached.messages();
                changeReviews.observe(selected, project.getBasePath(), history);
                prompts.refreshCatalogs(selected);
                sessionActions.refreshModelCatalog(selected);
                projectedCursor = cursor;
                projectedEvents = view.events;
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
        diagnose.addActionListener(
                event ->
                        DshTextDialog.show(
                                project,
                                DshBundle.message("dsh.diagnose.dialog.title"),
                                runtime.diagnoseEnvironment() + "\n" + remote.diagnostics()));
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
            subagents.refresh(sessionId);
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
            case "archiveSession" -> sessionActions.archive();
            case "openTrace" -> openTrace(action);
            case "openBrowser" -> openBrowser();
            case "openExternalLink" -> openExternalLink(action);
            case "openLogs" -> showLogs();
            case "manageSettings" -> runtimeSettings.togglePanel();
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
            case "setPlanMode" -> setPlanMode(bool(action, "active", false));
            case "openTerminalCommandPicker" ->
                    notify(
                            "Recent terminal command capture is not available in this IntelliJ build.");
            case "goalCreate", "goalEdit", "goalPause", "goalResume", "goalComplete", "goalClear" ->
                    goals.mutate(sessionId, action);
            case "refreshSubagents" -> subagents.refresh(sessionId);
            case "openSubagent" -> subagents.open(string(action, "childSessionId"));
            case "closeSubagent" -> subagents.clearPreview();
            case "followUpSubagent" ->
                    subagents.followUp(string(action, "childSessionId"), string(action, "text"));
            case "interruptSubagent" -> subagents.interrupt(string(action, "childSessionId"));
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

    private void setPlanMode(boolean active) {
        String current = sessionId;
        if (current == null || current.isBlank()) return;
        if (!sessionState.isRegisteredCommand(current, "plan")) {
            notify("The connected DSH Runtime does not expose the /plan command.");
            return;
        }
        sessionActions.setPlanMode(active);
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
        DshTextDialog.show(
                project,
                "DSH Environment",
                runtime.diagnoseEnvironment() + "\n" + remote.diagnostics());
    }

    // ---------------------------------------------------------------------------
    // session and workspace helpers
    // ---------------------------------------------------------------------------

    private String ensureSession() throws DshRemoteException {
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
        state.add("messages", messages == null ? new JsonArray() : messages.deepCopy());
        state.add("context", ideContext.contextMetadata());
        JsonObject selection = ideContext.currentSelection(false);
        if (selection != null) state.add("selection", selection);
        state.add("fileReferenceCandidates", ideContext.fileReferenceCandidates());
        JsonObject panel = runtimeSettings.panel();
        if (panel != null) state.add("settings", panel.deepCopy());
        state.addProperty("selectionEnabled", ideContext.isSelectionEnabled());
        JsonObject status = new JsonObject();
        status.addProperty("state", runtimeState(runtimeStatus.state));
        if (runtimeStatus.url != null) status.addProperty("url", runtimeStatus.url);
        String statusMessage = statusMessage(runtimeStatus, current);
        if (statusMessage != null && !statusMessage.isBlank())
            status.addProperty("message", statusMessage);
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
        state.add("interactions", view == null ? new JsonArray() : view.interactions);
        state.add("queue", view == null ? new JsonArray() : view.queue);
        state.add("jobs", view == null ? new JsonArray() : view.jobs);
        state.add("changeReviews", changeReviews.view(sessionId));
        state.add("skills", sessionState.skillCatalog(sessionId));
        state.add("commands", sessionState.commandCatalog(sessionId));
        JsonArray todos = DshSessionStateStore.todos(cellValue(sessionId, "todos"));
        if (todos != null) state.add("todos", todos);
        JsonObject imageLimits =
                DshSessionStateStore.imageLimits(cellValue(sessionId, "imageLimits"));
        if (imageLimits != null) state.add("imageLimits", imageLimits);
        JsonObject sessionStats =
                DshSessionStateStore.sessionStats(cellValue(sessionId, "sessionStats"));
        if (sessionStats != null) state.add("sessionStats", sessionStats);
        JsonObject plan = DshSessionStateStore.plan(cellValue(sessionId, "plan"));
        if (plan != null) state.add("plan", plan);
        JsonObject tokenUsage = tokenUsageView(view);
        if (tokenUsage != null) state.add("tokenUsage", tokenUsage);
        JsonObject goal = goals.view(sessionId);
        if (goal != null) state.add("goal", goal);
        state.add("subagents", subagents.treeView(sessionId));
        JsonObject subagentPreviewState = subagents.previewView(sessionId);
        if (subagentPreviewState != null) state.add("subagentPreview", subagentPreviewState);
        if (DshSettingsState.getInstance(project).agentStatusLabel != null
                && !DshSettingsState.getInstance(project).agentStatusLabel.isBlank()) {
            state.addProperty(
                    "agentStatusLabel",
                    DshSettingsState.getInstance(project).agentStatusLabel.trim());
        }
        state.add("reasoningEffort", sessionActions.reasoningEffort(sessionId, view));
        return state;
    }

    private static String statusMessage(
            DshRuntimeService.RuntimeStatus runtimeStatus, DshRemoteState.Snapshot current) {
        if (runtimeStatus.state != DshRuntimeService.RuntimeState.RUNNING) {
            return runtimeStatus.message;
        }
        if (DshRemoteService.PHASE_RECONNECTING.equals(current.phase) && current.message != null) {
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
        operations.shutdownNow();
        if (bridge != null) bridge.dispose();
    }
}
