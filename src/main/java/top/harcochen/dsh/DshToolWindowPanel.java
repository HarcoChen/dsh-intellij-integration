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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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

/**
 * Host-side controller for the reused React chat bundle.
 *
 * <p>The panel intentionally owns no Swing chat widgets. Its job is the same as dsh-ide's {@code
 * ChatViewProvider}: validate/dispatch actions, project Harness history to ChatViewState, and
 * resolve editor-aware actions through IntelliJ APIs.
 */
public final class DshToolWindowPanel extends JPanel implements com.intellij.openapi.Disposable {
    private static final Logger LOG = Logger.getInstance(DshToolWindowPanel.class);
    private static final int WEBVIEW_PROTOCOL_VERSION = 1;

    private final Project project;
    private final DshRuntimeService runtime;
    private final DshRpcClient client;
    private final ScheduledExecutorService poller;
    private final ExecutorService operations;
    private final AtomicBoolean refreshInFlight = new AtomicBoolean();

    /** Coalesces bursts of mux/status updates into one EDT state publish. */
    private final AtomicBoolean statePostPending = new AtomicBoolean();

    private final Consumer<DshRuntimeService.RuntimeStatus> statusListener;
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
    private final DshMuxClient muxClient;
    private final DshChangeReviewStore changeReviews;

    /** Workspace registry projections: which workspace owns a session, and what is archived. */
    private volatile Map<String, JsonObject> workspaceBySession = new LinkedHashMap<>();

    private volatile java.util.Set<String> archivedSessionIds = java.util.Set.of();
    private volatile JsonObject currentWorkspaceRegistration;
    private DshBridge bridge;
    private JPanel fallbackPanel;
    private JLabel fallbackLabel;
    private volatile boolean disposed;
    private volatile boolean webviewReady;
    private volatile boolean focusMode;
    private volatile String sessionId;
    private volatile boolean newSessionDraft;
    private volatile String pendingAgentPreset;
    private volatile JsonArray sessions = new JsonArray();
    private volatile JsonArray messages = new JsonArray();
    private volatile DshMessageProjector.Projection projection;
    private volatile String lastError;

    public DshToolWindowPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;
        this.runtime = DshRuntimeService.getInstance(project);
        this.client = runtime.getClient();
        this.muxClient =
                new DshMuxClient(
                        runtime::getUrl,
                        runtime::requestHeaders,
                        runtime::ensureAuthenticatedForTransport,
                        this::receiveMuxFrame);
        this.operations =
                Executors.newCachedThreadPool(
                        runnable -> {
                            Thread thread = new Thread(runnable, "dsh-intellij-chat");
                            thread.setDaemon(true);
                            return thread;
                        });
        this.poller =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, "dsh-intellij-chat-refresh");
                            thread.setDaemon(true);
                            return thread;
                        });
        this.sessionState = new DshSessionStateStore(markdownRenderCache, this::postStateLater);
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
                        client,
                        operations,
                        markdownRenderCache,
                        () -> sessions,
                        this::postStateLater,
                        this::notify,
                        error -> lastError = error);
        this.goals =
                new DshGoalController(
                        sessionState,
                        client,
                        operations,
                        this::refreshState,
                        this::postStateLater,
                        this::notify,
                        error -> lastError = error);
        this.runtimeSettings =
                new DshSettingsController(
                        project,
                        runtime,
                        client,
                        operations,
                        this::postStateLater,
                        this::openBrowser,
                        this::notify,
                        error -> lastError = error);
        this.workspaces =
                new DshWorkspaceController(
                        project,
                        client,
                        operations,
                        this::refreshState,
                        this::postStateLater,
                        this::notify,
                        error -> lastError = error);
        this.agentPresets =
                new DshAgentPresetController(
                        project,
                        client,
                        operations,
                        () -> sessionId,
                        preset -> pendingAgentPreset = preset,
                        this::refreshState,
                        this::postStateLater,
                        this::notify,
                        error -> lastError = error);
        this.diffs =
                new DshDiffController(
                        project,
                        client,
                        changeReviews,
                        operations,
                        () -> sessionId,
                        () -> sessions,
                        this::postStateLater,
                        this::notify,
                        error -> lastError = error);
        this.sessionActions =
                new DshSessionActionsController(
                        project,
                        client,
                        operations,
                        () -> sessionId,
                        () -> sessions,
                        id -> {
                            sessionId = id;
                            newSessionDraft = false;
                        },
                        () -> {
                            sessionId = null;
                            pendingAgentPreset = null;
                        },
                        this::refreshState,
                        this::postStateLater,
                        this::notify,
                        error -> lastError = error);
        this.prompts =
                new DshPromptController(
                        runtime,
                        client,
                        ideContext,
                        sessionState,
                        operations,
                        this::ensureSession,
                        () -> sessionId,
                        () -> messages,
                        this::refreshState,
                        this::postStateLater,
                        this::notify,
                        error -> lastError = error);
        this.statusListener = ignored -> postStateLater();
        runtime.addStatusListener(statusListener);
        setBorder(BorderFactory.createEmptyBorder());
        createWebview();
        int interval =
                Math.max(
                        250,
                        Math.min(DshSettingsState.getInstance(project).pollIntervalMs, 60_000));
        poller.scheduleWithFixedDelay(this::refreshState, 100, interval, TimeUnit.MILLISECONDS);
        if (DshSettingsState.getInstance(project).autoStart && project.getBasePath() != null) {
            operations.execute(
                    () -> runtime.startAsync().whenComplete((ignored, error) -> refreshState()));
        }
    }

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
                                runtime.diagnoseEnvironment()));
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

    private void receiveAction(JsonElement value) {
        if (disposed || value == null || !value.isJsonObject()) return;
        JsonObject action = DshWebviewActionSanitizer.sanitize(value.getAsJsonObject());
        if (action == null) return;
        String type = string(action, "type");
        if (type == null || type.isBlank()) return;
        if ("ready".equals(type)) {
            webviewReady = true;
            refreshState();
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
                refreshState();
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
        DshTextDialog.show(project, "DSH Environment", runtime.diagnoseEnvironment());
    }

    private String ensureSession() throws DshRpcClient.DshRpcException {
        if (sessionId != null && !sessionId.isBlank()) return sessionId;
        String cwd = project.getBasePath();
        String workspaceId = resolveWorkspaceId(cwd);
        JsonObject created =
                client.createSession(
                        workspaceId != null ? null : cwd, workspaceId, pendingAgentPreset);
        String createdId = string(created, "sessionId");
        if (createdId == null || createdId.isBlank())
            throw new DshRpcClient.DshRpcException(
                    "session.create", "invalid-result", "Harness did not return a sessionId");
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
            JsonObject result = client.createWorkspace(cwd);
            JsonObject workspace = result.getAsJsonObject("workspace");
            String id = string(workspace, "workspaceId");
            currentWorkspaceRegistration = workspace;
            return id;
        } catch (Exception error) {
            LOG.debug("Unable to resolve workspace for session creation", error);
        }
        return null;
    }

    private void refreshState() {
        if (SwingUtilities.isEventDispatchThread()) {
            operations.execute(this::refreshState);
            return;
        }
        if (disposed || !refreshInFlight.compareAndSet(false, true)) return;
        try {
            DshRuntimeService.RuntimeStatus runtimeStatus = runtime.getStatus();
            if (runtimeStatus.state == DshRuntimeService.RuntimeState.RUNNING
                    && runtime.getUrl() != null) {
                muxClient.ensureConnected();
                refreshWorkspaceRegistry();
                JsonArray catalog = client.sessions();
                sessions = normalizeSessions(catalog);
                chooseSessionIfNecessary();
                changeReviews.retain(sessionState.prune(sessions, sessionId));
                if (sessionId != null) {
                    JsonObject history = client.history(sessionId, 250);
                    DshSettingsState settings = DshSettingsState.getInstance(project);
                    String statusLabel =
                            settings.agentStatusLabel == null || settings.agentStatusLabel.isBlank()
                                    ? DshBundle.message("dsh.status.thinking")
                                    : settings.agentStatusLabel.trim();
                    DshSessionStateStore.HistoryProjection cached =
                            sessionState.projectHistory(sessionId, history, statusLabel);
                    projection = cached.projection();
                    messages = cached.messages();
                    sessionState.seedProjections(sessionId, history);
                    changeReviews.observe(sessionId, project.getBasePath(), history);
                    prompts.refreshCatalogs(sessionId);
                    sessionActions.refreshModelCatalog(sessionId);
                } else {
                    projection = DshMessageProjector.Projection.empty();
                    messages = new JsonArray();
                }
            } else {
                sessions = new JsonArray();
                messages = new JsonArray();
                projection = null;
            }
        } catch (Exception error) {
            lastError = message(error);
            LOG.debug("DSH state refresh failed", error);
        } finally {
            refreshInFlight.set(false);
            postStateLater();
        }
    }

    /**
     * Project and render only when the event log changed. History responses also carry live
     * projection cells, so comparing the complete response would defeat the cache whenever a todo
     * or token counter advances.
     */
    private void refreshWorkspaceRegistry() {
        try {
            JsonObject registry = client.workspaces();
            Map<String, JsonObject> bySession = new LinkedHashMap<>();
            java.util.Set<String> archived = new java.util.LinkedHashSet<>();
            JsonObject current = null;
            String base = project.getBasePath();
            String canonical = base == null ? null : canonicalPath(base);
            JsonArray items =
                    registry.has("items") && registry.get("items").isJsonArray()
                            ? registry.getAsJsonArray("items")
                            : new JsonArray();
            for (JsonElement candidate : items) {
                if (!candidate.isJsonObject()) continue;
                JsonObject workspace = candidate.getAsJsonObject();
                if (canonical != null
                        && canonical.equals(canonicalPath(string(workspace, "path")))) {
                    current = workspace.deepCopy();
                }
                JsonArray sessionIds =
                        workspace.has("sessionIds") && workspace.get("sessionIds").isJsonArray()
                                ? workspace.getAsJsonArray("sessionIds")
                                : new JsonArray();
                for (JsonElement sessionId : sessionIds) {
                    if (sessionId.isJsonPrimitive())
                        bySession.put(sessionId.getAsString(), workspace);
                }
            }
            JsonArray archivedIds =
                    registry.has("archivedSessionIds")
                                    && registry.get("archivedSessionIds").isJsonArray()
                            ? registry.getAsJsonArray("archivedSessionIds")
                            : new JsonArray();
            for (JsonElement candidate : archivedIds) {
                if (candidate.isJsonPrimitive()) archived.add(candidate.getAsString());
            }
            workspaceBySession = bySession;
            archivedSessionIds = archived;
            currentWorkspaceRegistration = current;
        } catch (Exception error) {
            LOG.debug("The connected Harness did not expose a workspace registry", error);
        }
        agentPresets.refreshCatalogIfNecessary();
    }

    private static String canonicalPath(String path) {
        if (path == null || path.isBlank()) return null;
        try {
            return Path.of(path).toRealPath().toString();
        } catch (Exception ignored) {
            return Path.of(path).toAbsolutePath().normalize().toString();
        }
    }

    private void chooseSessionIfNecessary() {
        if (newSessionDraft) return;
        if (sessionId != null && containsSession(sessionId)) return;
        // Do not auto-select a blank session. dsh-ide creates the session only
        // when the first prompt is sent, which keeps an opened tool window quiet.
        for (JsonElement candidate : sessions) {
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

    /**
     * The session catalog for the webview, with each row's title refreshed from the live title
     * projection. The mux thread only ever writes projection cells, so the catalog array stays
     * owned by the refresh that built it and no reader races a concurrent mutation.
     */
    private JsonArray sessionCatalogForState() {
        JsonArray catalog = sessions.deepCopy();
        for (JsonElement candidate : catalog) {
            if (!candidate.isJsonObject()) continue;
            JsonObject item = candidate.getAsJsonObject();
            String id = string(item, "sessionId");
            if (id == null) continue;
            String title = sessionState.sessionTitle(id);
            if (title != null) item.addProperty("title", title);
        }
        return catalog;
    }

    /** A catalog row's title, preferring the live projection over the catalog snapshot. */
    private String sessionTitleOf(JsonObject item, String fallback) {
        String id = string(item, "sessionId");
        String projected = id == null ? null : sessionState.sessionTitle(id);
        return projected != null ? projected : stringOr(item, "title", fallback);
    }

    private boolean containsSession(String id) {
        for (JsonElement candidate : sessions) {
            if (candidate.isJsonObject()
                    && id.equals(string(candidate.getAsJsonObject(), "sessionId"))) return true;
        }
        return false;
    }

    private JsonArray normalizeSessions(JsonArray raw) {
        JsonArray result = new JsonArray();
        List<JsonObject> items = new ArrayList<>();
        for (JsonElement candidate : raw) {
            if (!candidate.isJsonObject()) continue;
            JsonObject source = candidate.getAsJsonObject();
            String id = string(source, "sessionId");
            if (id == null || id.isBlank()) continue;
            JsonObject item = new JsonObject();
            item.addProperty("sessionId", id);
            String title = string(source, "title");
            if (title == null || title.isBlank()) {
                JsonObject projections =
                        source.has("projections") && source.get("projections").isJsonObject()
                                ? source.getAsJsonObject("projections")
                                : null;
                JsonObject values =
                        projections != null
                                        && projections.has("values")
                                        && projections.get("values").isJsonObject()
                                ? projections.getAsJsonObject("values")
                                : null;
                if (values != null
                        && values.has("title")
                        && values.get("title").isJsonPrimitive()) {
                    title = values.get("title").getAsString();
                }
            }
            item.addProperty(
                    "title",
                    title == null || title.isBlank()
                            ? id.substring(0, Math.min(12, id.length()))
                            : title);
            item.addProperty("running", bool(source, "running", false));
            // Attention is this host's own pending-interaction state; archive
            // and grouping are the workspace registry's.
            item.addProperty("attention", sessionState.hasPendingInteractions(id));
            item.addProperty("archived", archivedSessionIds.contains(id));
            JsonObject workspace = workspaceBySession.get(id);
            if (workspace != null) {
                DshJson.copyString(workspace, item, "workspaceId");
                String workspaceTitle = string(workspace, "title");
                if (workspaceTitle != null) item.addProperty("workspaceTitle", workspaceTitle);
            }
            if (source.has("blank")) item.addProperty("blank", bool(source, "blank", false));
            if (source.has("agentPreset"))
                item.add("agentPreset", source.get("agentPreset").deepCopy());
            JsonObject sourceProjections =
                    source.has("projections") && source.get("projections").isJsonObject()
                            ? source.getAsJsonObject("projections")
                            : null;
            JsonObject sourceValues =
                    sourceProjections != null
                                    && sourceProjections.has("values")
                                    && sourceProjections.get("values").isJsonObject()
                            ? sourceProjections.getAsJsonObject("values")
                            : null;
            if (sourceValues != null
                    && sourceValues.has("subagentTiming")
                    && sourceValues.get("subagentTiming").isJsonObject()) {
                item.add("subagentTiming", sourceValues.get("subagentTiming").deepCopy());
            }
            items.add(item);
        }
        items.sort(
                Comparator.comparing((JsonObject value) -> bool(value, "running", false))
                        .reversed());
        for (JsonObject item : items) result.add(item);
        return result;
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
        String statusMessage = runtimeStatus.message != null ? runtimeStatus.message : lastError;
        if (statusMessage != null && !statusMessage.isBlank())
            status.addProperty("message", statusMessage);
        state.add("status", status);
        boolean running = projection != null && projection.running;
        for (JsonElement candidate : sessions) {
            if (candidate.isJsonObject()
                    && sessionId != null
                    && sessionId.equals(string(candidate.getAsJsonObject(), "sessionId"))) {
                running = bool(candidate.getAsJsonObject(), "running", running);
                break;
            }
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
        state.add("sessions", sessionCatalogForState());
        if (pendingAgentPreset != null && !pendingAgentPreset.isBlank()) {
            state.addProperty("agentPreset", pendingAgentPreset);
        }
        if (sessionId != null) {
            JsonObject sessionStatus = new JsonObject();
            sessionStatus.addProperty("running", running);
            sessionStatus.addProperty("attention", sessionState.hasPendingInteractions(sessionId));
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
            for (JsonElement candidate : sessions) {
                if (candidate.isJsonObject()
                        && sessionId.equals(string(candidate.getAsJsonObject(), "sessionId"))) {
                    String preset = string(candidate.getAsJsonObject(), "agentPreset");
                    if (preset != null && !preset.isBlank()) {
                        state.addProperty("agentPreset", preset);
                        String label = agentPresets.label(preset);
                        if (label != null) state.addProperty("agentPresetLabel", label);
                    }
                    break;
                }
            }
        }
        JsonObject permissions =
                DshSessionStateStore.permissions(
                        sessionState.projectionValue(sessionId, "permissions"));
        if (permissions != null) state.add("permissions", permissions);
        state.add("interactions", sessionState.interactions(sessionId));
        state.add("queue", sessionState.queue(sessionId));
        state.add("jobs", sessionState.jobs(sessionId));
        state.add("changeReviews", changeReviews.view(sessionId));
        state.add("skills", sessionState.skillCatalog(sessionId));
        state.add("commands", sessionState.commandCatalog(sessionId));
        JsonArray todos = sessionState.todos(sessionId);
        if (todos != null) state.add("todos", todos);
        JsonObject imageLimits = sessionState.imageLimits(sessionId);
        if (imageLimits != null) state.add("imageLimits", imageLimits);
        JsonObject sessionStats = sessionState.sessionStats(sessionId);
        if (sessionStats != null) state.add("sessionStats", sessionStats);
        JsonObject plan = sessionState.plan(sessionId);
        if (plan != null) state.add("plan", plan);
        JsonObject tokenUsage =
                sessionState.tokenUsage(sessionId, sessionActions.modelCatalog(sessionId));
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
        state.add("reasoningEffort", sessionActions.reasoningEffort(sessionId));
        return state;
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
        String rpcId = key != null && key.matches("[aq]:.+") ? key.substring(2) : key;
        String current = sessionId;
        if (rpcId == null || rpcId.isBlank() || current == null || current.isBlank()) return;
        sessionState.updateInteractionStatus(current, key, "submitting", null);
        JsonObject value = new JsonObject();
        value.addProperty("sessionId", current);
        if (action.has("outcome")) {
            value.addProperty("approvalId", rpcId);
            value.add("outcome", action.get("outcome").deepCopy());
        } else {
            JsonObject answer = new JsonObject();
            answer.add(
                    "answers",
                    action.has("answers") ? action.get("answers").deepCopy() : new JsonArray());
            value.add("answer", answer);
        }
        operations.execute(
                () -> {
                    try {
                        client.respond(rpcId, true, value);
                    } catch (Exception error) {
                        sessionState.updateInteractionStatus(
                                current, key, "failed", message(error));
                        lastError = message(error);
                        postStateLater();
                    }
                });
    }

    private void receiveMuxFrame(JsonObject frame) {
        sessionState.receiveMuxFrame(frame, sessionId);
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
        String title = current;
        for (JsonElement candidate : sessions) {
            if (!candidate.isJsonObject()) continue;
            JsonObject item = candidate.getAsJsonObject();
            if (current.equals(string(item, "sessionId"))) {
                title = sessionTitleOf(item, current);
                break;
            }
        }
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
                                            refreshState();
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
        runtime.removeStatusListener(statusListener);
        muxClient.close();
        changeReviews.dispose();
        poller.shutdownNow();
        operations.shutdownNow();
        if (bridge != null) bridge.dispose();
    }
}
