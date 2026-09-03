package top.harcochen.dsh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Loads the durable subagent tree and manages one opened child transcript. */
final class DshSubagentController {
    private final DshRpcClient client;
    private final ExecutorService operations;
    private final DshMarkdownRenderCache markdownRenderCache;
    private final Runnable stateChanged;
    private final Consumer<String> notifier;
    private final Consumer<String> errorSink;
    private final AtomicLong generation = new AtomicLong();

    private volatile SubagentTree tree;
    private volatile SubagentPreview preview;

    DshSubagentController(
            DshRpcClient client,
            ExecutorService operations,
            DshMarkdownRenderCache markdownRenderCache,
            Runnable stateChanged,
            Consumer<String> notifier,
            Consumer<String> errorSink) {
        this.client = client;
        this.operations = operations;
        this.markdownRenderCache = markdownRenderCache;
        this.stateChanged = stateChanged;
        this.notifier = notifier;
        this.errorSink = errorSink;
    }

    void reset() {
        generation.incrementAndGet();
        tree = null;
        preview = null;
    }

    void clearPreview() {
        preview = null;
        stateChanged.run();
    }

    void refresh(String rootSessionId) {
        if (rootSessionId == null) {
            return;
        }
        long requestedGeneration = generation.incrementAndGet();
        SubagentTree requestedTree = new SubagentTree(rootSessionId, requestedGeneration);
        tree = requestedTree;
        stateChanged.run();
        operations.execute(
                () -> {
                    try {
                        JsonArray nodes = loadTree(rootSessionId);
                        if (generation.get() != requestedGeneration) {
                            return;
                        }
                        requestedTree.nodes = nodes;
                        requestedTree.state = "ready";
                    } catch (Exception error) {
                        if (generation.get() != requestedGeneration) {
                            return;
                        }
                        requestedTree.state = "error";
                        requestedTree.error = DshJson.message(error);
                    } finally {
                        stateChanged.run();
                    }
                });
    }

    /** Walks durable direct-child catalogs breadth-first with depth and cycle guards. */
    private JsonArray loadTree(String rootSessionId) throws Exception {
        JsonArray nodes = new JsonArray();
        Set<String> visited = new HashSet<>();
        Deque<TreeLevel> frontier = new ArrayDeque<>();
        frontier.add(new TreeLevel(rootSessionId, 1));
        while (!frontier.isEmpty() && nodes.size() < 500) {
            TreeLevel level = frontier.poll();
            String parentSessionId = level.parentSessionId();
            int depth = level.depth();
            if (depth > 8 || !visited.add(parentSessionId)) {
                continue;
            }
            JsonObject catalog = client.subagents(parentSessionId);
            boolean parentAvailable = DshJson.bool(catalog, "parentAvailable", false);
            JsonArray entries =
                    catalog.has("entries") && catalog.get("entries").isJsonArray()
                            ? catalog.getAsJsonArray("entries")
                            : new JsonArray();
            for (JsonElement candidate : entries) {
                if (!candidate.isJsonObject()) {
                    continue;
                }
                JsonObject entry = candidate.getAsJsonObject();
                String id = DshJson.string(entry, "id");
                String kind = DshJson.string(entry, "kind");
                if (id == null || id.isBlank()) {
                    continue;
                }
                if ("diagnostic".equals(kind)) {
                    addDiagnosticNode(nodes, entry, id, parentSessionId, depth, parentAvailable);
                    continue;
                }
                String mode = DshJson.string(entry, "mode");
                String activity = DshJson.string(entry, "activity");
                if (!"child".equals(kind)
                        || (!("one-shot".equals(mode) || "continuable".equals(mode)))
                        || (!("running".equals(activity) || "inactive".equals(activity)))
                        || !entry.has("hasChildren")
                        || !entry.get("hasChildren").isJsonPrimitive()) {
                    continue;
                }
                String label = DshJson.string(entry, "label");
                if ("continuable".equals(mode) && label == null) {
                    continue;
                }
                boolean hasChildren = DshJson.bool(entry, "hasChildren", false);
                JsonObject node = new JsonObject();
                node.addProperty("kind", "child");
                node.addProperty("id", id);
                node.addProperty("parentSessionId", parentSessionId);
                node.addProperty("depth", depth);
                node.addProperty("parentAvailable", parentAvailable);
                node.addProperty("label", label == null ? id : label);
                node.addProperty("mode", mode);
                node.addProperty("activity", activity);
                node.addProperty("hasChildren", hasChildren);
                nodes.add(node);
                if (hasChildren) {
                    frontier.add(new TreeLevel(id, depth + 1));
                }
            }
        }
        return nodes;
    }

    private static void addDiagnosticNode(
            JsonArray nodes,
            JsonObject entry,
            String id,
            String parentSessionId,
            int depth,
            boolean parentAvailable) {
        String reason = DshJson.string(entry, "reason");
        if (!"corrupt".equals(reason)
                && !"unsupported".equals(reason)
                && !"unavailable".equals(reason)) {
            return;
        }
        JsonObject node = new JsonObject();
        node.addProperty("kind", "diagnostic");
        node.addProperty("id", id);
        node.addProperty("parentSessionId", parentSessionId);
        node.addProperty("depth", depth);
        node.addProperty("parentAvailable", parentAvailable);
        node.addProperty("reason", reason);
        nodes.add(node);
    }

    void open(String childSessionId) {
        SubagentTree currentTree = tree;
        if (childSessionId == null || currentTree == null || !"ready".equals(currentTree.state)) {
            return;
        }
        JsonObject node = childNode(currentTree, childSessionId);
        if (node == null) {
            notifyUser(DshBundle.message("dsh.subagent.not.in.catalog"));
            return;
        }
        String parentSessionId = DshJson.string(node, "parentSessionId");
        String mode = DshJson.string(node, "mode");
        SubagentPreview requestedPreview =
                new SubagentPreview(
                        currentTree.rootSessionId,
                        childSessionId,
                        DshJson.stringOr(node, "label", childSessionId),
                        mode,
                        DshJson.bool(node, "parentAvailable", false),
                        DshJson.stringOr(node, "activity", "inactive"));
        preview = requestedPreview;
        stateChanged.run();
        operations.execute(
                () -> {
                    try {
                        JsonObject history =
                                client.subagentHistory(parentSessionId, childSessionId, mode, 250);
                        if (preview != requestedPreview) {
                            return;
                        }
                        DshMessageProjector.Projection projected =
                                DshMessageProjector.project(
                                        history, DshBundle.message("dsh.status.thinking"));
                        requestedPreview.messages =
                                markdownRenderCache.render(
                                        projected.messages, "subagent:" + childSessionId);
                        requestedPreview.state = "ready";
                    } catch (Exception error) {
                        if (preview != requestedPreview) {
                            return;
                        }
                        requestedPreview.state = "error";
                        requestedPreview.error = DshJson.message(error);
                    } finally {
                        stateChanged.run();
                    }
                });
    }

    private static JsonObject childNode(SubagentTree tree, String childSessionId) {
        for (JsonElement candidate : tree.nodes) {
            if (candidate.isJsonObject()
                    && childSessionId.equals(DshJson.string(candidate.getAsJsonObject(), "id"))
                    && "child".equals(DshJson.string(candidate.getAsJsonObject(), "kind"))) {
                return candidate.getAsJsonObject();
            }
        }
        return null;
    }

    void followUp(String childSessionId, String text) {
        SubagentPreview currentPreview = preview;
        if (currentPreview == null || !currentPreview.childSessionId.equals(childSessionId)) {
            return;
        }
        if (!"continuable".equals(currentPreview.mode)) {
            notifyUser(DshBundle.message("dsh.subagent.one.shot.no.follow.up"));
            return;
        }
        String parentSessionId = parentOf(childSessionId);
        if (parentSessionId == null) {
            return;
        }
        currentPreview.pendingAction = "follow-up";
        currentPreview.error = null;
        stateChanged.run();
        operations.execute(
                () -> {
                    try {
                        client.promptSubagent(parentSessionId, childSessionId, text);
                        if (preview == currentPreview) {
                            open(childSessionId);
                        }
                    } catch (Exception error) {
                        String message = DshJson.message(error);
                        if (preview == currentPreview) {
                            currentPreview.error = message;
                        }
                        errorSink.accept(message);
                    } finally {
                        if (preview == currentPreview) {
                            currentPreview.pendingAction = null;
                        }
                        stateChanged.run();
                    }
                });
    }

    void interrupt(String childSessionId) {
        SubagentPreview currentPreview = preview;
        if (currentPreview == null || !currentPreview.childSessionId.equals(childSessionId)) {
            return;
        }
        if (!"continuable".equals(currentPreview.mode)) {
            notifyUser(DshBundle.message("dsh.subagent.one.shot.no.interrupt"));
            return;
        }
        String parentSessionId = parentOf(childSessionId);
        if (parentSessionId == null) {
            return;
        }
        currentPreview.pendingAction = "interrupt";
        currentPreview.error = null;
        stateChanged.run();
        operations.execute(
                () -> {
                    try {
                        client.interruptSubagent(parentSessionId, childSessionId);
                    } catch (Exception error) {
                        String message = DshJson.message(error);
                        if (preview == currentPreview) {
                            currentPreview.error = message;
                        }
                        errorSink.accept(message);
                    } finally {
                        if (preview == currentPreview) {
                            currentPreview.pendingAction = null;
                        }
                        stateChanged.run();
                    }
                });
    }

    private String parentOf(String childSessionId) {
        SubagentTree currentTree = tree;
        if (currentTree == null) {
            return null;
        }
        for (JsonElement candidate : currentTree.nodes) {
            if (candidate.isJsonObject()
                    && childSessionId.equals(DshJson.string(candidate.getAsJsonObject(), "id"))) {
                return DshJson.string(candidate.getAsJsonObject(), "parentSessionId");
            }
        }
        return null;
    }

    JsonObject treeView(String rootSessionId) {
        SubagentTree currentTree = tree;
        JsonObject result = new JsonObject();
        if (currentTree == null
                || rootSessionId == null
                || !rootSessionId.equals(currentTree.rootSessionId)) {
            result.addProperty("rootSessionId", rootSessionId == null ? "" : rootSessionId);
            result.addProperty("state", "ready");
            result.add("nodes", new JsonArray());
            return result;
        }
        result.addProperty("rootSessionId", currentTree.rootSessionId);
        result.addProperty("state", currentTree.state);
        result.add("nodes", currentTree.nodes.deepCopy());
        if (currentTree.error != null) {
            result.addProperty("error", currentTree.error);
        }
        return result;
    }

    JsonObject previewView(String rootSessionId) {
        SubagentPreview currentPreview = preview;
        if (currentPreview == null
                || rootSessionId == null
                || !rootSessionId.equals(currentPreview.rootSessionId)) {
            return null;
        }
        JsonObject result = new JsonObject();
        result.addProperty("rootSessionId", currentPreview.rootSessionId);
        result.addProperty("childSessionId", currentPreview.childSessionId);
        result.addProperty("label", currentPreview.label);
        result.addProperty("mode", currentPreview.mode);
        result.addProperty("parentAvailable", currentPreview.parentAvailable);
        result.addProperty("activity", currentPreview.activity);
        result.addProperty("state", currentPreview.state);
        result.add("messages", currentPreview.messages.deepCopy());
        if (currentPreview.pendingAction != null) {
            result.addProperty("pendingAction", currentPreview.pendingAction);
        }
        if (currentPreview.error != null) {
            result.addProperty("error", currentPreview.error);
        }
        return result;
    }

    private void notifyUser(String message) {
        notifier.accept(message);
    }

    private record TreeLevel(String parentSessionId, int depth) {}

    private static final class SubagentTree {
        private final String rootSessionId;

        @SuppressWarnings("unused")
        private final long generation;

        private String state = "loading";
        private JsonArray nodes = new JsonArray();
        private String error;

        private SubagentTree(String rootSessionId, long generation) {
            this.rootSessionId = rootSessionId;
            this.generation = generation;
        }
    }

    private static final class SubagentPreview {
        private final String rootSessionId;
        private final String childSessionId;
        private final String label;
        private final String mode;
        private final boolean parentAvailable;
        private final String activity;
        private String state = "loading";
        private JsonArray messages = new JsonArray();
        private String pendingAction;
        private String error;

        private SubagentPreview(
                String rootSessionId,
                String childSessionId,
                String label,
                String mode,
                boolean parentAvailable,
                String activity) {
            this.rootSessionId = rootSessionId;
            this.childSessionId = childSessionId;
            this.label = label;
            this.mode = mode;
            this.parentAvailable = parentAvailable;
            this.activity = activity;
        }
    }
}
