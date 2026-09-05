package top.harcochen.dsh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;
import top.harcochen.dsh.remote.DshRemoteService;
import top.harcochen.dsh.remote.DshRemoteState;

/** Owns session menu actions and the selected session's model catalog. */
final class DshSessionActionsController {
    private static final Logger LOG = Logger.getInstance(DshSessionActionsController.class);

    private final Project project;
    private final DshRemoteService remote;
    private final ExecutorService operations;
    private final Supplier<String> sessionId;
    private final Supplier<JsonArray> sessions;
    private final Consumer<String> selectSession;
    private final Runnable clearSession;
    private final Runnable refreshState;
    private final Runnable stateChanged;
    private final Consumer<String> notifier;
    private final Consumer<String> errorSink;

    private volatile JsonObject modelCatalog;
    private volatile String modelCatalogSession;
    private volatile JsonObject currentRouteCache;
    private volatile String currentRouteSession;

    DshSessionActionsController(
            Project project,
            DshRemoteService remote,
            ExecutorService operations,
            Supplier<String> sessionId,
            Supplier<JsonArray> sessions,
            Consumer<String> selectSession,
            Runnable clearSession,
            Runnable refreshState,
            Runnable stateChanged,
            Consumer<String> notifier,
            Consumer<String> errorSink) {
        this.project = project;
        this.remote = remote;
        this.operations = operations;
        this.sessionId = sessionId;
        this.sessions = sessions;
        this.selectSession = selectSession;
        this.clearSession = clearSession;
        this.refreshState = refreshState;
        this.stateChanged = stateChanged;
        this.notifier = notifier;
        this.errorSink = errorSink;
    }

    void refreshModelCatalog(String session) {
        if (session == null || session.equals(modelCatalogSession)) {
            return;
        }
        try {
            // Claim the session only once the load succeeds, so a transient RPC failure does not
            // permanently pin this session to an empty catalog via the guard above.
            JsonObject catalog = remote.modelCatalog();
            modelCatalog = catalog;
            modelCatalogSession = session;
        } catch (Exception error) {
            LOG.debug("The connected Harness did not expose a model catalog", error);
        }
    }

    JsonObject modelCatalog(String session) {
        return session != null && session.equals(modelCatalogSession) ? modelCatalog : null;
    }

    JsonObject reasoningEffort(String session, DshRemoteState.SessionView view) {
        JsonObject value = new JsonObject();
        JsonArray options = new JsonArray();
        JsonObject catalog = modelCatalog(session);
        JsonObject current = currentRoute(view, catalog);
        currentRouteCache = current;
        currentRouteSession = session;
        if (current != null && current.has("reasoningEffort")) {
            value.addProperty("current", DshJson.stringOr(current, "reasoningEffort", "default"));
        }
        String provider = current == null ? null : DshJson.string(current, "provider");
        String model = current == null ? null : DshJson.string(current, "model");
        JsonArray groups =
                catalog != null && catalog.has("groups") && catalog.get("groups").isJsonArray()
                        ? catalog.getAsJsonArray("groups")
                        : new JsonArray();
        for (JsonElement groupElement : groups) {
            if (!groupElement.isJsonObject()
                    || !DshJson.stringOr(groupElement.getAsJsonObject(), "id", "")
                            .equals(provider)) {
                continue;
            }
            addModelEfforts(options, groupElement.getAsJsonObject(), model);
        }
        value.add("options", options);
        return value;
    }

    /** Merge the host-wide catalog default with this session's model-selection projection. */
    private JsonObject currentRoute(DshRemoteState.SessionView view, JsonObject catalog) {
        if (view == null) {
            return catalog != null
                            && catalog.has("default")
                            && catalog.get("default").isJsonObject()
                    ? catalog.getAsJsonObject("default")
                    : null;
        }
        DshRemoteState.ProjectionCell cell = view.projections.get("modelSelection");
        JsonObject route =
                DshSessionStateStore.currentModelRoute(cell == null ? null : cell.value(), catalog);
        return route.has("provider") ? route : null;
    }

    private static void addModelEfforts(JsonArray options, JsonObject group, String selectedModel) {
        JsonArray models =
                group.has("models") && group.get("models").isJsonArray()
                        ? group.getAsJsonArray("models")
                        : new JsonArray();
        for (JsonElement modelElement : models) {
            if (!modelElement.isJsonObject()
                    || !DshJson.stringOr(modelElement.getAsJsonObject(), "id", "")
                            .equals(selectedModel)) {
                continue;
            }
            JsonObject model = modelElement.getAsJsonObject();
            JsonObject reasoning =
                    model.has("reasoning") && model.get("reasoning").isJsonObject()
                            ? model.getAsJsonObject("reasoning")
                            : null;
            JsonArray efforts =
                    reasoning != null
                                    && reasoning.has("efforts")
                                    && reasoning.get("efforts").isJsonArray()
                            ? reasoning.getAsJsonArray("efforts")
                            : new JsonArray();
            for (JsonElement effortElement : efforts) {
                if (!effortElement.isJsonObject()) {
                    continue;
                }
                String id = DshJson.string(effortElement.getAsJsonObject(), "id");
                if (id == null || id.isBlank()) {
                    continue;
                }
                JsonObject option = new JsonObject();
                option.addProperty("id", id);
                option.addProperty(
                        "label", DshJson.stringOr(effortElement.getAsJsonObject(), "name", id));
                options.add(option);
            }
        }
    }

    void search() {
        ApplicationManager.getApplication()
                .invokeLater(
                        () -> {
                            String query =
                                    Messages.showInputDialog(
                                            project,
                                            DshBundle.message("dsh.session.search.message"),
                                            DshBundle.message("dsh.session.search.title"),
                                            Messages.getQuestionIcon());
                            if (query == null) {
                                return;
                            }
                            chooseSearchResult(query);
                        });
    }

    private void chooseSearchResult(String query) {
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        List<String> choices = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (JsonElement candidate : sessions.get()) {
            if (!candidate.isJsonObject()) {
                continue;
            }
            JsonObject item = candidate.getAsJsonObject();
            String title = DshJson.stringOr(item, "title", "");
            String id = DshJson.stringOr(item, "sessionId", "");
            if (normalized.isBlank()
                    || title.toLowerCase(Locale.ROOT).contains(normalized)
                    || id.toLowerCase(Locale.ROOT).contains(normalized)) {
                choices.add(title + "  (" + id + ")");
                ids.add(id);
            }
        }
        if (choices.isEmpty()) {
            notifyUser(DshBundle.message("dsh.session.search.no.results"));
            return;
        }
        int selected =
                Messages.showChooseDialog(
                        project,
                        DshBundle.message("dsh.session.select.message"),
                        DshBundle.message("dsh.session.select.title"),
                        Messages.getQuestionIcon(),
                        choices.toArray(new String[0]),
                        choices.get(0));
        if (selected >= 0 && selected < ids.size()) {
            selectSession.accept(ids.get(selected));
            refreshState.run();
        }
    }

    void rename() {
        String current = sessionId.get();
        if (current == null) {
            return;
        }
        ApplicationManager.getApplication()
                .invokeLater(
                        () -> {
                            String title =
                                    Messages.showInputDialog(
                                            project,
                                            DshBundle.message("dsh.session.rename.message"),
                                            DshBundle.message("dsh.session.rename.title"),
                                            Messages.getQuestionIcon());
                            if (title == null || title.trim().isEmpty()) {
                                return;
                            }
                            operations.execute(() -> rename(current, title.trim()));
                        });
    }

    private void rename(String session, String title) {
        try {
            remote.renameSession(session, title);
            refreshState.run();
        } catch (Exception error) {
            report(error);
        }
    }

    void fork() {
        forkAt(null);
    }

    /** Fork the current session, optionally cutting it at a finalized message sequence. */
    void forkAt(Long atSeq) {
        String current = sessionId.get();
        if (current == null) {
            return;
        }
        operations.execute(
                () -> {
                    try {
                        String forked = remote.forkSession(current, atSeq);
                        if (!forked.isBlank()) {
                            selectSession.accept(forked);
                        }
                        refreshState.run();
                    } catch (Exception error) {
                        report(error);
                    }
                });
    }

    void archive() {
        String current = sessionId.get();
        if (current == null) {
            return;
        }
        ApplicationManager.getApplication()
                .invokeLater(
                        () -> {
                            int answer =
                                    Messages.showYesNoDialog(
                                            project,
                                            DshBundle.message("dsh.session.archive.message"),
                                            DshBundle.message("dsh.session.archive.title"),
                                            Messages.getQuestionIcon());
                            if (answer == Messages.YES) {
                                operations.execute(() -> archive(current));
                            }
                        });
    }

    private void archive(String session) {
        try {
            remote.archiveSession(session);
            clearSession.run();
            refreshState.run();
        } catch (Exception error) {
            report(error);
        }
    }

    void selectModel() {
        String current = sessionId.get();
        if (current == null) {
            return;
        }
        operations.execute(() -> loadModels(current));
    }

    private void loadModels(String session) {
        try {
            JsonObject catalog = remote.modelCatalog();
            JsonArray groups =
                    catalog.has("groups") && catalog.get("groups").isJsonArray()
                            ? catalog.getAsJsonArray("groups")
                            : new JsonArray();
            List<String> labels = new ArrayList<>();
            List<ModelChoice> choices = new ArrayList<>();
            for (JsonElement groupElement : groups) {
                if (!groupElement.isJsonObject()) {
                    continue;
                }
                JsonObject group = groupElement.getAsJsonObject();
                String provider =
                        DshJson.stringOr(group, "id", DshJson.stringOr(group, "name", "provider"));
                JsonArray models =
                        group.has("models") && group.get("models").isJsonArray()
                                ? group.getAsJsonArray("models")
                                : new JsonArray();
                for (JsonElement modelElement : models) {
                    if (!modelElement.isJsonObject()) {
                        continue;
                    }
                    JsonObject model = modelElement.getAsJsonObject();
                    String id = DshJson.stringOr(model, "id", "");
                    if (id.isBlank()) {
                        continue;
                    }
                    labels.add(
                            DshJson.stringOr(group, "name", provider)
                                    + " / "
                                    + DshJson.stringOr(model, "name", id));
                    choices.add(new ModelChoice(provider, id));
                }
            }
            if (labels.isEmpty()) {
                notifyUser(DshBundle.message("dsh.model.no.choices"));
                return;
            }
            ApplicationManager.getApplication()
                    .invokeLater(() -> chooseModel(session, labels, choices));
        } catch (Exception error) {
            report(error);
        }
    }

    private void chooseModel(String session, List<String> labels, List<ModelChoice> choices) {
        int selected =
                Messages.showChooseDialog(
                        project,
                        DshBundle.message("dsh.model.select.message"),
                        DshBundle.message("dsh.model.select.title"),
                        Messages.getQuestionIcon(),
                        labels.toArray(new String[0]),
                        labels.get(0));
        if (selected >= 0 && selected < choices.size()) {
            ModelChoice choice = choices.get(selected);
            operations.execute(() -> selectModel(session, choice.provider(), choice.model(), null));
        }
    }

    void selectReasoningEffort(String effort) {
        String session = sessionId.get();
        JsonObject catalog = modelCatalog(session);
        if (session == null || effort == null || catalog == null) {
            return;
        }
        JsonObject current =
                session.equals(currentRouteSession) && currentRouteCache != null
                        ? currentRouteCache
                        : catalog.has("default") && catalog.get("default").isJsonObject()
                                ? catalog.getAsJsonObject("default")
                                : null;
        String provider = current == null ? null : DshJson.string(current, "provider");
        String model = current == null ? null : DshJson.string(current, "model");
        if (provider == null || model == null) {
            notifyUser(DshBundle.message("dsh.model.no.current"));
            return;
        }
        operations.execute(() -> selectModel(session, provider, model, effort));
    }

    private void selectModel(String session, String provider, String model, String effort) {
        try {
            remote.selectModel(session, provider, model, effort);
            modelCatalog = remote.modelCatalog();
            modelCatalogSession = session;
            refreshState.run();
        } catch (Exception error) {
            report(error);
        }
    }

    void openReasoningEffort() {
        String session = sessionId.get();
        DshRemoteState.SessionView view = null;
        JsonObject value = reasoningEffort(session, view);
        JsonArray options = value.getAsJsonArray("options");
        if (options == null || options.isEmpty()) {
            notifyUser(DshBundle.message("dsh.model.no.reasoning.effort"));
            return;
        }
        stateChanged.run();
    }

    void setPermissionPreset(String preset) {
        String current = sessionId.get();
        if (current == null || preset == null) {
            return;
        }
        operations.execute(
                () -> {
                    try {
                        JsonElement execution =
                                remote.executeCommand(current, "/permission " + preset);
                        showCommandResult(execution);
                        refreshState.run();
                    } catch (Exception error) {
                        report(error);
                    }
                });
    }

    /** Toggle the Runtime's public plan command for the current session. */
    void setPlanMode(boolean active) {
        String current = sessionId.get();
        if (current == null) return;
        operations.execute(
                () -> {
                    try {
                        JsonElement execution =
                                remote.executeCommand(current, active ? "/plan on" : "/plan off");
                        showCommandResult(execution);
                        refreshState.run();
                    } catch (Exception error) {
                        report(error);
                    }
                });
    }

    private void showCommandResult(JsonElement execution) {
        if (execution == null || !execution.isJsonObject()) {
            return;
        }
        JsonObject result =
                execution.getAsJsonObject().has("result")
                                && execution.getAsJsonObject().get("result").isJsonObject()
                        ? execution.getAsJsonObject().getAsJsonObject("result")
                        : null;
        String kind = result == null ? null : DshJson.string(result, "kind");
        String text = result == null ? null : DshJson.string(result, "text");
        if ("error".equals(kind)) {
            throw new IllegalStateException(
                    text == null ? DshBundle.message("dsh.command.failed") : text);
        }
        if (text != null && !text.isBlank()) {
            notifyUser(text.trim());
        }
    }

    private void report(Exception error) {
        errorSink.accept(DshJson.message(error));
        stateChanged.run();
    }

    private void notifyUser(String message) {
        notifier.accept(message);
    }

    private record ModelChoice(String provider, String model) {}
}
