package top.harcochen.dsh;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Project-scoped settings for the IntelliJ Platform integration.
 *
 * <p>The defaults intentionally mirror the VS Code extension's defaults. Keeping the command and
 * argument list separate makes it possible to use an installed {@code dsh}, pnpm dlx, npx, or a
 * locally checked-out Harness without changing the plugin code.
 */
@State(name = "DshSettings", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class DshSettingsState implements PersistentStateComponent<DshSettingsState> {
    public String command = defaultPackageManagerCommand();
    public String commandArgs = "dlx @deepseek-ai/dsh@0.1.2-rc.1 web --no-open";
    public String serverUrl = "";
    public int serverPort = 0;
    public boolean autoStart = true;

    /** Whether the selected session id survives IDE restarts. */
    public boolean persistSession = true;

    /** Last selected session for this project; empty when nothing is pinned. */
    public String lastSessionId = "";

    public boolean installWhenMissing = true;

    /** Enable the compaction command by injecting a launcher patch into web-profile launches. */
    public boolean enableCompaction = true;

    public String runtimeVersion = "0.1.2-rc.1";
    public String npmRegistry = "https://registry.npmmirror.com";
    public int startupTimeoutMs = 30_000;
    public int requestTimeoutMs = 600_000;
    public int maxContextBytes = 120_000;
    public String apiKeyEnv = "DEEPSEEK_API_KEY";
    public String agentStatusLabel = "";

    /** Candidate labels shown while the agent is running; one is picked per session. */
    public java.util.List<String> agentStatusLabels =
            new java.util.ArrayList<>(
                    java.util.List.of(
                            "大肥鱼正在深潜…",
                            "大肥鱼摆摆尾巴，想想办法…",
                            "大肥鱼翻了个身，继续思考…",
                            "大肥鱼正在吞吐上下文…",
                            "大肥鱼在鱼缸里转圈…",
                            "大肥鱼：这题我会…"));

    public boolean enableEffortKnob = true;
    public int balanceRefreshIntervalMs = 30_000;

    public static DshSettingsState getInstance(@NotNull Project project) {
        return project.getService(DshSettingsState.class);
    }

    @Override
    public DshSettingsState getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull DshSettingsState state) {
        command =
                isWindows() && "pnpm".equalsIgnoreCase(state.command) ? "pnpm.cmd" : state.command;
        commandArgs = state.commandArgs;
        serverUrl = state.serverUrl;
        serverPort = state.serverPort;
        autoStart = state.autoStart;
        persistSession = state.persistSession;
        lastSessionId = state.lastSessionId;
        installWhenMissing = state.installWhenMissing;
        enableCompaction = state.enableCompaction;
        runtimeVersion = state.runtimeVersion;
        npmRegistry = state.npmRegistry;
        startupTimeoutMs = state.startupTimeoutMs;
        requestTimeoutMs = state.requestTimeoutMs;
        maxContextBytes = state.maxContextBytes;
        apiKeyEnv = state.apiKeyEnv;
        agentStatusLabel = state.agentStatusLabel;
        agentStatusLabels =
                new java.util.ArrayList<>(
                        state.agentStatusLabels == null
                                ? java.util.List.of()
                                : state.agentStatusLabels);
        enableEffortKnob = state.enableEffortKnob;
        balanceRefreshIntervalMs = state.balanceRefreshIntervalMs;
    }

    private static String defaultPackageManagerCommand() {
        return isWindows() ? "pnpm.cmd" : "pnpm";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}
