package top.harcochen.dsh.remote;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Opt-in RC.2 Team snapshot API. It does not install the experimental service or poll it. */
public final class DshAgentTeamClient {
    private static final Gson JSON = new Gson();
    private final DshRemoteUnaryClient unary;

    public DshAgentTeamClient(DshRemoteUnaryClient unary) {
        this.unary = unary;
    }

    public record Member(
            String id,
            String name,
            String role,
            String status,
            String description,
            String provider,
            String context,
            String model,
            List<String> diagnostics) {}

    public record Task(
            String id,
            long revision,
            String subject,
            String description,
            String status,
            List<String> blockedBy,
            List<String> writeScopes,
            String ownerName,
            boolean ready,
            List<String> writeScopeWarnings) {}

    public record View(List<Member> members, List<Task> tasks) {}

    public record CreateTask(
            String subject, String description, List<String> blockedBy, List<String> writeScopes) {}

    public record UpdateTask(
            String taskId,
            long expectedRevision,
            String action,
            String subject,
            String description,
            List<String> blockedBy,
            List<String> writeScopes,
            String owner) {
        public UpdateTask {
            if (taskId == null
                    || taskId.isBlank()
                    || expectedRevision < 0
                    || expectedRevision > 9007199254740991L
                    || !Set.of(
                                    "claim",
                                    "release",
                                    "edit",
                                    "set_dependencies",
                                    "complete",
                                    "reopen",
                                    "reassign",
                                    "delete")
                            .contains(action)) {
                throw new IllegalArgumentException("Invalid Team task update");
            }
        }
    }

    /** Business conflicts stay distinct from exceptional Remote transport/capability failures. */
    public record TaskError(String code, String message) {}

    public record MutationResult(boolean ok, Task value, TaskError error) {}

    public CompletableFuture<View> view(String agentId) {
        return call("agentTeams/view", args(agentId, null), View.class);
    }

    public CompletableFuture<MutationResult> createTask(String agentId, CreateTask request) {
        if (request == null) throw new IllegalArgumentException("Task request is required");
        return call("agentTeams/createTask", args(agentId, request), MutationResult.class);
    }

    /**
     * Sends the exact caller revision once. A conflict requires a fresh view and caller decision.
     */
    public CompletableFuture<MutationResult> updateTask(String agentId, UpdateTask request) {
        if (request == null) throw new IllegalArgumentException("Task request is required");
        return call("agentTeams/updateTask", args(agentId, request), MutationResult.class);
    }

    private static JsonObject args(String agentId, Object request) {
        if (agentId == null || agentId.isBlank())
            throw new IllegalArgumentException("Agent id is required");
        JsonObject args = new JsonObject();
        args.addProperty("agentId", agentId);
        if (request != null) args.add("request", JSON.toJsonTree(request));
        return args;
    }

    private <T> CompletableFuture<T> call(String endpoint, JsonObject args, Class<T> type) {
        CompletableFuture<JsonElement> transport = unary.callAsync(endpoint, args);
        CompletableFuture<T> result =
                transport.thenApply(
                        value -> {
                            if (!value.isJsonObject())
                                throw new IllegalArgumentException("Malformed Team response");
                            return JSON.fromJson(value, type);
                        });
        result.whenComplete(
                (value, error) -> {
                    if (result.isCancelled()) transport.cancel(true);
                });
        return result;
    }
}
