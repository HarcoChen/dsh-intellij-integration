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
 * The defaults intentionally mirror the VS Code extension's defaults. Keeping
 * the command and argument list separate makes it possible to use an installed
 * {@code dsh}, pnpm dlx, npx, or a locally checked-out Harness without changing
 * the plugin code.
 */
@State(name = "DshSettings", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class DshSettingsState implements PersistentStateComponent<DshSettingsState> {
    public String command = defaultPackageManagerCommand();
    public String commandArgs = "dlx @deepseek-ai/dsh web --no-open";
    public String serverUrl = "";
    public int serverPort = 0;
    public boolean autoStart = true;
    public boolean installWhenMissing = true;
    public String runtimeVersion = "0.1.1-rc.2";
    public String npmRegistry = "https://registry.npmmirror.com";
    public int startupTimeoutMs = 30_000;
    public int requestTimeoutMs = 600_000;
    public int pollIntervalMs = 700;
    public int maxContextBytes = 120_000;
    public String apiKeyEnv = "DEEPSEEK_API_KEY";
    public String agentStatusLabel = "";
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
        command = isWindows() && "pnpm".equalsIgnoreCase(state.command) ? "pnpm.cmd" : state.command;
        commandArgs = state.commandArgs;
        serverUrl = state.serverUrl;
        serverPort = state.serverPort;
        autoStart = state.autoStart;
        installWhenMissing = state.installWhenMissing;
        runtimeVersion = state.runtimeVersion;
        npmRegistry = state.npmRegistry;
        startupTimeoutMs = state.startupTimeoutMs;
        requestTimeoutMs = state.requestTimeoutMs;
        pollIntervalMs = state.pollIntervalMs;
        maxContextBytes = state.maxContextBytes;
        apiKeyEnv = state.apiKeyEnv;
        agentStatusLabel = state.agentStatusLabel;
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
