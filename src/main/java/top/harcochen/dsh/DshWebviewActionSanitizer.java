package top.harcochen.dsh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.URI;

/** Validates messages crossing the untrusted JCEF-to-host boundary. */
final class DshWebviewActionSanitizer {
    private DshWebviewActionSanitizer() {}

    static JsonObject sanitize(JsonObject input) {
        String type = DshJson.string(input, "type");
        if (type == null || type.length() > 64 || !type.matches("[A-Za-z][A-Za-z0-9]*")) {
            return null;
        }
        if ("sendPrompt".equals(type)) {
            if (!hasOnly(input, "type", "text", "mode", "images")) {
                return null;
            }
            String text = DshJson.string(input, "text");
            String mode = DshJson.string(input, "mode");
            if (text == null
                    || text.length() > 1_000_000
                    || !("queue".equals(mode) || "steer".equals(mode))) {
                return null;
            }
            if (input.has("images")) {
                if (!input.get("images").isJsonArray()
                        || input.getAsJsonArray("images").size() > 20) {
                    return null;
                }
                for (JsonElement image : input.getAsJsonArray("images")) {
                    if (!image.isJsonObject()) {
                        return null;
                    }
                    JsonObject imageObject = image.getAsJsonObject();
                    if (!hasOnly(imageObject, "data", "mediaType", "name")) {
                        return null;
                    }
                    String data = DshJson.string(imageObject, "data");
                    String mediaType = DshJson.string(imageObject, "mediaType");
                    if (data == null
                            || data.isBlank()
                            || data.length() > 16 * 1024 * 1024
                            || mediaType == null
                            || !(mediaType.equals("image/png")
                                    || mediaType.equals("image/jpeg")
                                    || mediaType.equals("image/webp")
                                    || mediaType.equals("image/gif"))) {
                        return null;
                    }
                }
            }
            return input;
        }
        if ("openFileLocation".equals(type)) {
            if (!hasOnly(input, "type", "path", "line", "column")) {
                return null;
            }
            String path = DshJson.string(input, "path");
            int line = DshJson.integer(input, "line", 0);
            int column = DshJson.integer(input, "column", 1);
            return path != null
                            && !path.isBlank()
                            && path.length() <= 8_192
                            && !path.contains("\0")
                            && line > 0
                            && line <= 1_000_000
                            && column > 0
                            && column <= 1_000_000
                    ? input
                    : null;
        }
        if ("openExternalLink".equals(type)) {
            if (!hasOnly(input, "type", "url", "href")) {
                return null;
            }
            String url = DshJson.string(input, "url");
            if (url == null) {
                url = DshJson.string(input, "href");
            }
            return isSafeExternalUrl(url) ? input : null;
        }
        if ("fileReferenceQuery".equals(type)) {
            if (!hasOnly(input, "type", "query")) {
                return null;
            }
            String query = DshJson.string(input, "query");
            return query != null && query.length() <= 256 ? input : null;
        }
        if ("updateQueue".equals(type)) {
            if (!hasOnly(input, "type", "itemId", "action", "text")) {
                return null;
            }
            String itemId = DshJson.string(input, "itemId");
            String action = DshJson.string(input, "action");
            String text = DshJson.string(input, "text");
            return itemId != null
                            && !itemId.isBlank()
                            && itemId.length() <= 256
                            && ("remove".equals(action)
                                    || "steer".equals(action)
                                    || ("edit".equals(action)
                                            && text != null
                                            && !text.isBlank()
                                            && text.length() <= 1_000_000))
                    ? input
                    : null;
        }
        if ("switchSession".equals(type)) {
            String id = DshJson.string(input, "sessionId");
            return hasOnly(input, "type", "sessionId")
                            && id != null
                            && !id.isBlank()
                            && id.length() <= 256
                    ? input
                    : null;
        }
        if ("retryPrompt".equals(type) || "removeContext".equals(type)) {
            String id = DshJson.string(input, "id");
            return hasOnly(input, "type", "id") && id != null && !id.isBlank() && id.length() <= 256
                    ? input
                    : null;
        }
        if ("loadImage".equals(type)) {
            String attachmentId = DshJson.string(input, "attachmentId");
            return hasOnly(input, "type", "attachmentId")
                            && attachmentId != null
                            && !attachmentId.isBlank()
                            && attachmentId.length() <= 256
                    ? input
                    : null;
        }
        if ("selectAgentPreset".equals(type)) {
            String preset = DshJson.string(input, "agentPreset");
            return hasOnly(input, "type", "agentPreset")
                            && (preset == null
                                    || (preset.length() <= 256
                                            && preset.matches("[A-Za-z0-9_.-]+")))
                    ? input
                    : null;
        }
        if ("openTrace".equals(type)) {
            int sequence = DshJson.integer(input, "seq", 0);
            return hasOnly(input, "type", "seq") && sequence >= 0 ? input : null;
        }
        if ("answerApproval".equals(type)) {
            String key = DshJson.string(input, "key");
            String outcome = DshJson.string(input, "outcome");
            return hasOnly(input, "type", "key", "outcome")
                            && key != null
                            && !key.isBlank()
                            && key.length() <= 256
                            && ("allowed-once".equals(outcome) || "rejected".equals(outcome))
                    ? input
                    : null;
        }
        if ("answerQuestion".equals(type)) {
            String key = DshJson.string(input, "key");
            JsonElement answers = input.get("answers");
            return hasOnly(input, "type", "key", "answers")
                            && key != null
                            && !key.isBlank()
                            && key.length() <= 256
                            && answers != null
                            && answers.isJsonArray()
                            && answers.getAsJsonArray().size() <= 100
                    ? input
                    : null;
        }
        if ("selectReasoningEffort".equals(type)) {
            String effort = DshJson.string(input, "effort");
            return hasOnly(input, "type", "effort")
                            && effort != null
                            && !effort.isBlank()
                            && effort.length() <= 128
                    ? input
                    : null;
        }
        if ("setPermissionPreset".equals(type)) {
            String value = DshJson.string(input, "value");
            return hasOnly(input, "type", "value")
                            && value != null
                            && value.matches("[A-Za-z0-9_.-]{1,64}")
                    ? input
                    : null;
        }
        if ("copyCode".equals(type)
                || "insertCode".equals(type)
                || "openCode".equals(type)
                || "applyCode".equals(type)) {
            String renderId = DshJson.string(input, "renderId");
            String blockId = DshJson.string(input, "codeBlockId");
            String language = DshJson.string(input, "language");
            boolean languageAllowed = !"copyCode".equals(type) && !"insertCode".equals(type);
            String[] allowed =
                    languageAllowed
                            ? new String[] {"type", "renderId", "codeBlockId", "language"}
                            : new String[] {"type", "renderId", "codeBlockId"};
            return hasOnly(input, allowed)
                            && renderId != null
                            && renderId.matches("[a-f0-9]{32}")
                            && blockId != null
                            && blockId.matches("code-[0-9]{1,6}")
                            && (language == null
                                    || (languageAllowed
                                            && language.matches("[A-Za-z0-9_+#.-]{1,40}")))
                    ? input
                    : null;
        }
        if ("openChangeDiff".equals(type)) {
            int turn = DshJson.integer(input, "turn", 0);
            String fileId = DshJson.string(input, "fileId");
            return hasOnly(input, "type", "turn", "fileId")
                            && turn > 0
                            && turn <= 1_000_000
                            && fileId != null
                            && fileId.matches(
                                    "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
                    ? input
                    : null;
        }
        if ("openToolDiff".equals(type)) {
            String callId = DshJson.string(input, "callId");
            String path = DshJson.string(input, "path");
            return hasOnly(input, "type", "callId", "path")
                            && callId != null
                            && !callId.isBlank()
                            && callId.length() <= 256
                            && path != null
                            && !path.isBlank()
                            && path.length() <= 8_192
                    ? input
                    : null;
        }
        if ("mutateSettings".equals(type)) {
            return sanitizeSettingsMutation(input);
        }
        if ("goalCreate".equals(type) || "goalEdit".equals(type)) {
            if (!hasOnly(input, "type", "objective", "maxGoalRounds")) {
                return null;
            }
            String objective = DshJson.string(input, "objective");
            boolean hasObjective =
                    objective != null && !objective.isBlank() && objective.length() <= 8_192;
            boolean hasRounds = false;
            if (input.has("maxGoalRounds")) {
                int rounds = DshJson.integer(input, "maxGoalRounds", 0);
                if (rounds <= 0 || rounds > 1_000_000) {
                    return null;
                }
                hasRounds = true;
            }
            if (input.has("objective") && !hasObjective) {
                return null;
            }
            return "goalCreate".equals(type)
                    ? (hasObjective ? input : null)
                    : (hasObjective || hasRounds ? input : null);
        }
        if ("openSubagent".equals(type) || "interruptSubagent".equals(type)) {
            String childSessionId = DshJson.string(input, "childSessionId");
            return hasOnly(input, "type", "childSessionId")
                            && childSessionId != null
                            && !childSessionId.isBlank()
                            && childSessionId.length() <= 256
                    ? input
                    : null;
        }
        if ("followUpSubagent".equals(type)) {
            String childSessionId = DshJson.string(input, "childSessionId");
            String text = DshJson.string(input, "text");
            return hasOnly(input, "type", "childSessionId", "text")
                            && childSessionId != null
                            && !childSessionId.isBlank()
                            && childSessionId.length() <= 256
                            && text != null
                            && !text.isBlank()
                            && text.length() <= 1_000_000
                    ? input
                    : null;
        }
        if ("restoreTurnChanges".equals(type)) {
            int turn = DshJson.integer(input, "turn", 0);
            return hasOnly(input, "type", "turn") && turn > 0 && turn <= 1_000_000 ? input : null;
        }
        if ("forkFromMessage".equals(type)
                || "restoreCodeToMessage".equals(type)
                || "forkAndRestoreCodeToMessage".equals(type)) {
            int sequence = DshJson.integer(input, "seq", -1);
            return hasOnly(input, "type", "seq") && sequence >= 0 && sequence <= 1_000_000
                    ? input
                    : null;
        }
        if ("setPlanMode".equals(type)) {
            return hasOnly(input, "type", "active")
                            && input.has("active")
                            && input.get("active").isJsonPrimitive()
                            && input.getAsJsonPrimitive().isBoolean()
                    ? input
                    : null;
        }
        if (type.startsWith("switch")
                || type.startsWith("open")
                || type.startsWith("remove")
                || type.startsWith("retry")
                || type.startsWith("answer")
                || type.startsWith("select")
                || type.startsWith("manage")
                || type.startsWith("configure")
                || type.startsWith("new")
                || type.startsWith("toggle")
                || type.startsWith("capture")
                || type.startsWith("cancel")
                || type.startsWith("archive")
                || type.startsWith("fork")
                || type.startsWith("rename")
                || type.startsWith("start")
                || type.startsWith("stop")
                || type.startsWith("ready")
                || type.startsWith("goal")
                || type.startsWith("refresh")
                || type.startsWith("close")) {
            return hasOnly(input, "type") ? input : null;
        }
        return null;
    }

    private static JsonObject sanitizeSettingsMutation(JsonObject input) {
        if (!hasOnly(input, "type", "ns", "revision", "changes")) {
            return null;
        }
        String namespace = DshJson.string(input, "ns");
        if (namespace == null || namespace.isBlank() || namespace.length() > 128) {
            return null;
        }
        if (!input.has("revision")
                || !input.get("revision").isJsonPrimitive()
                || !input.get("revision").getAsJsonPrimitive().isNumber()) {
            return null;
        }
        if (!input.has("changes") || !input.get("changes").isJsonArray()) {
            return null;
        }
        JsonArray changes = input.getAsJsonArray("changes");
        if (changes.isEmpty() || changes.size() > 256) {
            return null;
        }
        for (JsonElement candidate : changes) {
            if (!candidate.isJsonObject()) {
                return null;
            }
            JsonObject change = candidate.getAsJsonObject();
            if (!hasOnly(change, "path", "value", "clear")) {
                return null;
            }
            if (!change.has("path") || !change.get("path").isJsonArray()) {
                return null;
            }
            JsonArray path = change.getAsJsonArray("path");
            if (path.isEmpty() || path.size() > 16) {
                return null;
            }
            for (JsonElement segment : path) {
                if (!segment.isJsonPrimitive()
                        || segment.getAsString().isBlank()
                        || segment.getAsString().length() > 128) {
                    return null;
                }
            }
            String value = DshJson.string(change, "value");
            if (value == null || value.length() > 1_000_000) {
                return null;
            }
            if (!change.has("clear") || !change.get("clear").isJsonPrimitive()) {
                return null;
            }
        }
        return input;
    }

    static boolean isSafeExternalUrl(String value) {
        if (value == null || value.isBlank() || value.length() > 4_096) {
            return false;
        }
        try {
            URI uri = URI.create(value.trim());
            return ("http".equalsIgnoreCase(uri.getScheme())
                            || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null
                    && uri.getUserInfo() == null
                    && uri.getFragment() == null;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean hasOnly(JsonObject input, String... allowed) {
        for (String key : input.keySet()) {
            boolean accepted = false;
            for (String candidate : allowed) {
                if (candidate.equals(key)) {
                    accepted = true;
                    break;
                }
            }
            if (!accepted) {
                return false;
            }
        }
        return true;
    }
}
