package top.harcochen.dsh;

import com.google.gson.JsonObject;
import java.util.Set;
import top.harcochen.dsh.remote.DshRemoteAccess;

/** Guard all chat entry points, including native IDE actions. */
final class DshActionAccess {
    private DshActionAccess() {}

    static boolean allows(DshRemoteAccess access, JsonObject action, String sessionId) {
        if (access.owner()) return true;
        String type = DshJson.stringOr(action, "type", "");
        if (Set.of(
                        "ready",
                        "start",
                        "restart",
                        "restartRuntime",
                        "configureApiKey",
                        "configureJevApiKey",
                        "newSession",
                        "newSessionInCurrentWorkspace",
                        "toggleFocus",
                        "openLogs",
                        "openExternalLink",
                        "openBrowser",
                        "manageSettings",
                        "refreshPluginInventory",
                        "openIdeContextPicker",
                        "openTerminalCommandPicker",
                        "openDiagnosticsPicker",
                        "insertEditorReference",
                        "explainDebugState",
                        "prefillGitDiff",
                        "askAboutResource",
                        "openPromptTemplatePicker",
                        "removeContext",
                        "toggleSelection",
                        "fileReferenceQuery",
                        "closeSubagent",
                        "searchSession",
                        "openSessionCenter",
                        "dismissSessionFeedback",
                        "stop",
                        "cancelRecovery",
                        "exportRecoveryDiagnostics",
                        "restoreRecovery",
                        "captureAppShot",
                        "insertCode",
                        "applyCode",
                        "openInEditor",
                        "showWhatsNew")
                .contains(type)) return true;
        if (type.equals("switchSession"))
            return access.allows("session/follow", "session", DshJson.string(action, "sessionId"));
        if (type.equals("openSubagent"))
            return access.allows(
                    "session/follow", "session", DshJson.string(action, "childSessionId"));
        if (type.equals("followUpSubagent") || type.equals("interruptSubagent")) {
            String endpoint =
                    type.equals("followUpSubagent")
                            ? "subagents/prompt"
                            : "subagents/interruptByParent";
            return sessionId != null
                    && access.allows(endpoint, "session", sessionId)
                    && access.allows(endpoint, "session", DshJson.string(action, "childSessionId"));
        }
        String endpoint =
                switch (type) {
                    case "sendPrompt", "retryPrompt" -> "session/prompt";
                    case "cancel" -> "session/cancel";
                    case "updateQueue" -> "session/updateQueue";
                    case "selectModel", "selectReasoningEffort" -> "session/selectModel";
                    case "openReasoningEffort" -> "session/modelCatalog";
                    case "selectAgentPreset" -> "agentPresets/select";
                    case "renameSession" -> "session/rename";
                    case "forkSession", "forkFromMessage" -> "session/fork";
                    case "archiveSession" -> "workspace/archiveSession";
                    case "killJob" -> "job/kill";
                    case "openRuntimeTerminal" -> "terminal/attach";
                    case "goalCreate" -> "goal/create";
                    case "goalEdit" -> "goal/edit";
                    case "goalPause" -> "goal/pause";
                    case "goalResume" -> "goal/resume";
                    case "goalClear" -> "goal/clear";
                    case "goalComplete" -> "goal/complete";
                    case "setPermissionPreset", "setPlanMode" -> "commands/execute";
                    case "refreshSubagents" -> "session/projections";
                    case "toggleMessageFeedback",
                            "submitMessageFeedback",
                            "saveMessageFeedbackNote" ->
                            "messageFeedback/put";
                    case "openSessionFeedback", "recordSessionFeedback" -> "sessionFeedback/record";
                    case "openTrace",
                            "openConversationOutline",
                            "copyMessage",
                            "copyCode",
                            "openCode",
                            "openFileLocation",
                            "openChangeDiff",
                            "openToolDiff",
                            "browseRuntimeFiles",
                            "refreshRuntimeFile",
                            "openTeamMember" ->
                            "session/follow";
                    case "loadImage" -> "session/attachment";
                    default -> null;
                };
        if (endpoint != null)
            return sessionId != null && access.allows(endpoint, "session", sessionId);
        endpoint =
                switch (type) {
                    case "configureWebSearch", "mutateSettings", "manageProviders" ->
                            "settings/mutate";
                    case "openSettingsDocument" -> "settings/openSettingsDocument";
                    case "setPluginEnabled" -> "pluginManager/setPluginEnabled";
                    case "setBundleEnabled" -> "pluginManager/setBundleEnabled";
                    case "managePlugins" -> "pluginManager/installBundle";
                    default -> null;
                };
        return endpoint != null && access.allows(endpoint);
    }
}
