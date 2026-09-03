package top.harcochen.dsh;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/** Validates, projects, and performs CAS-guarded Goal mutations for a session. */
final class DshGoalController {
    private static final Map<String, List<String>> ACTIONS_BY_PHASE =
            Map.of(
                    "active", List.of("pause", "complete", "edit", "clear"),
                    "paused", List.of("resume", "complete", "edit", "clear"),
                    "blocked", List.of("resume", "complete", "edit", "clear"),
                    "complete", List.of("create", "clear"));

    private final Object lock = new Object();
    private final Map<String, GoalMutation> mutations = new LinkedHashMap<>();
    private final DshSessionStateStore sessionState;
    private final DshRpcClient client;
    private final ExecutorService operations;
    private final Runnable refreshState;
    private final Runnable stateChanged;
    private final Consumer<String> notifier;
    private final Consumer<String> errorSink;

    DshGoalController(
            DshSessionStateStore sessionState,
            DshRpcClient client,
            ExecutorService operations,
            Runnable refreshState,
            Runnable stateChanged,
            Consumer<String> notifier,
            Consumer<String> errorSink) {
        this.sessionState = sessionState;
        this.client = client;
        this.operations = operations;
        this.refreshState = refreshState;
        this.stateChanged = stateChanged;
        this.notifier = notifier;
        this.errorSink = errorSink;
    }

    JsonObject view(String session) {
        if (session == null) {
            return null;
        }
        DshSessionStateStore.ProjectionSnapshot snapshot = sessionState.projection(session, "goal");
        if (snapshot == null) {
            return null;
        }
        JsonElement value = snapshot.value();
        GoalMutation mutation = observeMutation(session, snapshot.seq());
        JsonObject result = new JsonObject();
        String error = projectionError(value);
        if (error != null) {
            result.addProperty("state", "invalid");
            result.addProperty(
                    "error", mutation != null && mutation.error != null ? mutation.error : error);
        } else if (value == null || value.isJsonNull()) {
            result.addProperty("state", "empty");
            if (mutation != null && mutation.error != null) {
                result.addProperty("error", mutation.error);
            }
        } else {
            JsonObject source = value.getAsJsonObject();
            result.addProperty("state", "present");
            result.add("goal", source.getAsJsonObject("goal").deepCopy());
            result.add("roundsStarted", source.get("roundsStarted").deepCopy());
            result.add("createdAt", source.get("createdAt").deepCopy());
            result.add("updatedAt", source.get("updatedAt").deepCopy());
            if (mutation != null && mutation.error != null) {
                result.addProperty("error", mutation.error);
            }
        }
        if (mutation != null && mutation.pending) {
            result.addProperty("pending", true);
            result.addProperty("pendingOperation", mutation.operation);
        }
        return result;
    }

    void mutate(String session, JsonObject action) {
        String operation = operationFor(DshJson.string(action, "type"));
        if (session == null || operation == null) {
            return;
        }
        DshSessionStateStore.ProjectionSnapshot snapshot = sessionState.projection(session, "goal");
        if (snapshot == null) {
            notifyUser(DshBundle.message("dsh.goal.no.projection"));
            return;
        }
        synchronized (lock) {
            GoalMutation current = mutations.get(session);
            if (current != null && current.pending) {
                return;
            }
            mutations.put(session, new GoalMutation(operation, snapshot.seq()));
        }
        JsonElement value = snapshot.value();
        String invalid = projectionError(value);
        if (invalid != null) {
            failMutation(session, invalid);
            return;
        }
        JsonObject projected = value == null || value.isJsonNull() ? null : value.getAsJsonObject();
        stateChanged.run();
        operations.execute(() -> executeMutation(session, action, operation, projected));
    }

    private void executeMutation(
            String session, JsonObject action, String operation, JsonObject projected) {
        try {
            if ("create".equals(operation)) {
                create(session, action, projected);
            } else {
                mutateExisting(session, action, operation, projected);
            }
            synchronized (lock) {
                GoalMutation mutation = mutations.get(session);
                if (mutation != null) {
                    mutation.pending = false;
                }
            }
            refreshState.run();
        } catch (Exception error) {
            failMutation(session, DshJson.message(error));
        }
    }

    private void create(String session, JsonObject action, JsonObject projected) throws Exception {
        if (projected != null
                && !actionAllowed(
                        DshJson.string(projected.getAsJsonObject("goal"), "phase"),
                        "create",
                        DshJson.longValue(projected.get("roundsStarted"), 0),
                        DshJson.longValue(
                                projected.getAsJsonObject("goal").get("maxGoalRounds"), 0))) {
            throw new IllegalStateException(
                    "A replacement Goal can only be created when the current Goal is empty or complete.");
        }
        client.createGoal(
                session,
                DshJson.stringOr(action, "objective", ""),
                action.has("maxGoalRounds") ? action.get("maxGoalRounds").getAsInt() : null);
    }

    private void mutateExisting(
            String session, JsonObject action, String operation, JsonObject projected)
            throws Exception {
        if (projected == null) {
            throw new IllegalStateException("The current session has no actionable Goal.");
        }
        JsonObject goal = projected.getAsJsonObject("goal");
        long roundsStarted = DshJson.longValue(projected.get("roundsStarted"), 0);
        long maxGoalRounds = DshJson.longValue(goal.get("maxGoalRounds"), 0);
        if (!actionAllowed(
                DshJson.string(goal, "phase"), operation, roundsStarted, maxGoalRounds)) {
            throw new IllegalStateException(
                    "resume".equals(operation) && roundsStarted >= maxGoalRounds
                            ? "Goal has reached its maximum rounds and cannot be resumed."
                            : "That Goal action is not available in the current phase.");
        }
        JsonObject reference = new JsonObject();
        reference.add("id", goal.get("id").deepCopy());
        reference.add("revision", goal.get("revision").deepCopy());
        switch (operation) {
            case "edit" ->
                    client.editGoal(
                            session,
                            reference,
                            action.has("objective") ? DshJson.string(action, "objective") : null,
                            action.has("maxGoalRounds")
                                    ? action.get("maxGoalRounds").getAsInt()
                                    : null);
            case "pause" -> client.mutateGoal("goal.pause", session, reference);
            case "resume" -> client.mutateGoal("goal.resume", session, reference);
            case "complete" -> client.mutateGoal("goal.complete", session, reference);
            case "clear" -> client.mutateGoal("goal.clear", session, reference);
            default -> throw new IllegalStateException("Unsupported Goal action");
        }
    }

    private GoalMutation observeMutation(String session, long seq) {
        synchronized (lock) {
            GoalMutation mutation = mutations.get(session);
            if (mutation == null) {
                return null;
            }
            if (!mutation.pending && seq > mutation.beforeSeq && mutation.error == null) {
                mutations.remove(session);
                return null;
            }
            return mutation;
        }
    }

    private void failMutation(String session, String error) {
        synchronized (lock) {
            GoalMutation mutation = mutations.get(session);
            if (mutation != null) {
                mutation.pending = false;
                mutation.error = error;
            }
        }
        errorSink.accept(error);
        stateChanged.run();
    }

    private static String projectionError(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (!value.isJsonObject()
                || !value.getAsJsonObject().has("goal")
                || !value.getAsJsonObject().get("goal").isJsonObject()) {
            return "Harness returned an invalid goal projection.";
        }
        JsonObject source = value.getAsJsonObject();
        JsonObject goal = source.getAsJsonObject("goal");
        String phase = DshJson.string(goal, "phase");
        if (DshJson.string(goal, "id") == null
                || !positiveNumber(goal, "revision")
                || DshJson.string(goal, "objective") == null
                || phase == null
                || !ACTIONS_BY_PHASE.containsKey(phase)
                || !positiveNumber(goal, "maxGoalRounds")
                || !nonNegativeNumber(source, "roundsStarted")
                || !finiteNumber(source, "createdAt")
                || !finiteNumber(source, "updatedAt")) {
            return "Harness returned an invalid goal projection.";
        }
        boolean blocked = "blocked".equals(phase);
        if (goal.has("blockedReason") && !goal.get("blockedReason").isJsonNull()) {
            JsonObject reason =
                    goal.get("blockedReason").isJsonObject()
                            ? goal.getAsJsonObject("blockedReason")
                            : null;
            if (reason == null
                    || DshJson.string(reason, "code") == null
                    || DshJson.string(reason, "message") == null) {
                return "Harness returned an invalid goal blockedReason.";
            }
            if (!blocked) {
                return "Harness goal phase is inconsistent with blockedReason.";
            }
        } else if (blocked) {
            return "Harness goal phase is inconsistent with blockedReason.";
        }
        return null;
    }

    private static boolean actionAllowed(
            String phase, String action, long roundsStarted, long maxGoalRounds) {
        List<String> allowed = ACTIONS_BY_PHASE.get(phase);
        if (allowed == null || !allowed.contains(action)) {
            return false;
        }
        return !"resume".equals(action) || roundsStarted < maxGoalRounds;
    }

    private static String operationFor(String actionType) {
        return switch (actionType) {
            case "goalCreate" -> "create";
            case "goalEdit" -> "edit";
            case "goalPause" -> "pause";
            case "goalResume" -> "resume";
            case "goalComplete" -> "complete";
            case "goalClear" -> "clear";
            case null, default -> null;
        };
    }

    private static boolean finiteNumber(JsonObject source, String key) {
        if (source == null
                || !source.has(key)
                || !source.get(key).isJsonPrimitive()
                || !source.get(key).getAsJsonPrimitive().isNumber()) {
            return false;
        }
        try {
            return Double.isFinite(source.get(key).getAsDouble());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean nonNegativeNumber(JsonObject source, String key) {
        if (!finiteNumber(source, key)) {
            return false;
        }
        return source.get(key).getAsDouble() >= 0;
    }

    private static boolean positiveNumber(JsonObject source, String key) {
        return nonNegativeNumber(source, key) && source.get(key).getAsDouble() > 0;
    }

    private void notifyUser(String message) {
        notifier.accept(message);
    }

    private static final class GoalMutation {
        private final String operation;
        private final long beforeSeq;
        private boolean pending = true;
        private String error;

        private GoalMutation(String operation, long beforeSeq) {
            this.operation = operation;
            this.beforeSeq = beforeSeq;
        }
    }
}
