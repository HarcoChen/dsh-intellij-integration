package top.harcochen.dsh;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/** Settings page exposed under Settings | Tools | DeepSeek Harness. */
public final class DshSettingsConfigurable implements Configurable {
    private final Project project;
    private JPanel panel;
    private JBTextField command;
    private JBTextField commandArgs;
    private JBTextField serverUrl;
    private JBTextField serverPort;
    private JBTextField runtimeVersion;
    private JBTextField startupTimeout;
    private JBTextField requestTimeout;
    private JBTextField pollInterval;
    private JBTextField maxContextBytes;
    private JBTextField npmRegistry;
    private JBTextField apiKeyEnv;
    private JBCheckBox autoStart;
    private JBCheckBox installWhenMissing;

    public DshSettingsConfigurable(Project project) {
        this.project = project;
    }

    @Override
    public @Nls String getDisplayName() {
        return "DeepSeek Harness";
    }

    @Override
    public @Nullable JComponent createComponent() {
        if (panel == null) {
            buildPanel();
        }
        reset();
        return panel;
    }

    private void buildPanel() {
        panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        command = new JBTextField();
        commandArgs = new JBTextField();
        serverUrl = new JBTextField();
        serverPort = new JBTextField();
        runtimeVersion = new JBTextField();
        startupTimeout = new JBTextField();
        requestTimeout = new JBTextField();
        pollInterval = new JBTextField();
        maxContextBytes = new JBTextField();
        npmRegistry = new JBTextField();
        apiKeyEnv = new JBTextField();
        autoStart = new JBCheckBox("Start the DSH Runtime when the project opens");
        installWhenMissing = new JBCheckBox("Allow package-manager startup when dsh is not installed");

        addRow("Runtime command", command, "Executable used to start dsh web (for example pnpm, npx, or dsh).");
        addRow("Command arguments", commandArgs, "Whitespace-separated arguments. Quote a value containing spaces.");
        addRow("Server URL", serverUrl, "Optional existing Harness server, for example http://127.0.0.1:3080.");
        addRow("Server port", serverPort, "Optional port to probe/start; 0 lets dsh choose.");
        addRow("Runtime version", runtimeVersion, "Package version used by the npx fallback (for example 0.1.1-rc.2).");
        addRow("Startup timeout (ms)", startupTimeout, "How long to wait for dsh web to become healthy.");
        addRow("Request timeout (ms)", requestTimeout, "Timeout for Harness RPC calls.");
        addRow("Refresh interval (ms)", pollInterval, "History/catalog refresh interval used while the chat is open.");
        addRow("Maximum context bytes", maxContextBytes, "UTF-8 byte limit for the active editor selection attached to a prompt.");
        addRow("npm registry", npmRegistry, "Registry used when the command is pnpm or npx.");
        addRow("API key environment variable", apiKeyEnv, "Only the variable name is stored; the secret is never persisted here.");
        addCheckbox(autoStart);
        addCheckbox(installWhenMissing);

        JPanel note = new JPanel();
        note.setLayout(new BoxLayout(note, BoxLayout.Y_AXIS));
        note.add(new JBLabel("The protocol and defaults follow dsh-ide's Harness Web integration."));
        note.add(Box.createVerticalStrut(4));
        note.add(new JBLabel("Credentials are managed by the operating-system credential store when configured from the chat menu."));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = GridBagConstraints.RELATIVE;
        constraints.gridwidth = 2;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.insets = new Insets(12, 0, 0, 0);
        panel.add(note, constraints);
    }

    private void addRow(String label, JBTextField field, String tooltip) {
        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = 0;
        labelConstraints.gridy = GridBagConstraints.RELATIVE;
        labelConstraints.anchor = GridBagConstraints.WEST;
        labelConstraints.insets = new Insets(4, 0, 4, 12);
        panel.add(new JBLabel(label + ":"), labelConstraints);

        field.setToolTipText(tooltip);
        GridBagConstraints fieldConstraints = new GridBagConstraints();
        fieldConstraints.gridx = 1;
        fieldConstraints.gridy = GridBagConstraints.RELATIVE;
        fieldConstraints.weightx = 1;
        fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
        fieldConstraints.insets = new Insets(4, 0, 4, 0);
        panel.add(field, fieldConstraints);
    }

    private void addCheckbox(JBCheckBox checkbox) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = GridBagConstraints.RELATIVE;
        constraints.gridwidth = 2;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.insets = new Insets(4, 0, 4, 0);
        panel.add(checkbox, constraints);
    }

    @Override
    public boolean isModified() {
        if (panel == null) return false;
        DshSettingsState state = DshSettingsState.getInstance(project);
        return !safe(command.getText()).equals(safe(state.command))
                || !safe(commandArgs.getText()).equals(safe(state.commandArgs))
                || !safe(serverUrl.getText()).equals(safe(state.serverUrl))
                || parseOr(serverPort.getText(), 0) != state.serverPort
                || !safe(runtimeVersion.getText()).equals(safe(state.runtimeVersion))
                || parseOr(startupTimeout.getText(), state.startupTimeoutMs) != state.startupTimeoutMs
                || parseOr(requestTimeout.getText(), state.requestTimeoutMs) != state.requestTimeoutMs
                || parseOr(pollInterval.getText(), state.pollIntervalMs) != state.pollIntervalMs
                || parseOr(maxContextBytes.getText(), state.maxContextBytes) != state.maxContextBytes
                || !safe(npmRegistry.getText()).equals(safe(state.npmRegistry))
                || !safe(apiKeyEnv.getText()).equals(safe(state.apiKeyEnv))
                || autoStart.isSelected() != state.autoStart
                || installWhenMissing.isSelected() != state.installWhenMissing;
    }

    @Override
    public void apply() throws ConfigurationException {
        DshSettingsState state = DshSettingsState.getInstance(project);
        int port = parseRequired(serverPort.getText(), "Server port", 0, 65_535);
        String version = safe(runtimeVersion.getText());
        if (version.isBlank() || !version.matches("[A-Za-z0-9.-]+")) {
            throw new ConfigurationException("Runtime version must contain only letters, numbers, dots, and hyphens.");
        }
        int startup = parseRequired(startupTimeout.getText(), "Startup timeout", 1_000, 600_000);
        int request = parseRequired(requestTimeout.getText(), "Request timeout", 10_000, 3_600_000);
        int poll = parseRequired(pollInterval.getText(), "Refresh interval", 100, 60_000);
        int context = parseRequired(maxContextBytes.getText(), "Maximum context bytes", 1_000, 1_000_000);
        if (safe(command.getText()).isBlank()) {
            throw new ConfigurationException("Runtime command must not be empty.");
        }
        if (!safe(apiKeyEnv.getText()).isBlank() && !safe(apiKeyEnv.getText()).matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new ConfigurationException("API key environment variable must be a valid variable name.");
        }
        state.command = safe(command.getText());
        state.commandArgs = safe(commandArgs.getText());
        state.serverUrl = safe(serverUrl.getText());
        state.serverPort = port;
        state.runtimeVersion = version;
        state.startupTimeoutMs = startup;
        state.requestTimeoutMs = request;
        state.pollIntervalMs = poll;
        state.maxContextBytes = context;
        state.npmRegistry = safe(npmRegistry.getText());
        state.apiKeyEnv = safe(apiKeyEnv.getText());
        state.autoStart = autoStart.isSelected();
        state.installWhenMissing = installWhenMissing.isSelected();
        DshRuntimeService.getInstance(project).settingsChanged();
    }

    @Override
    public void reset() {
        if (panel == null) return;
        DshSettingsState state = DshSettingsState.getInstance(project);
        command.setText(state.command);
        commandArgs.setText(state.commandArgs);
        serverUrl.setText(state.serverUrl);
        serverPort.setText(Integer.toString(state.serverPort));
        runtimeVersion.setText(safe(state.runtimeVersion));
        startupTimeout.setText(Integer.toString(state.startupTimeoutMs));
        requestTimeout.setText(Integer.toString(state.requestTimeoutMs));
        pollInterval.setText(Integer.toString(state.pollIntervalMs));
        maxContextBytes.setText(Integer.toString(state.maxContextBytes));
        npmRegistry.setText(state.npmRegistry);
        apiKeyEnv.setText(state.apiKeyEnv);
        autoStart.setSelected(state.autoStart);
        installWhenMissing.setSelected(state.installWhenMissing);
    }

    private static int parseRequired(String value, String label, int minimum, int maximum)
            throws ConfigurationException {
        try {
            int parsed = Integer.parseInt(safe(value));
            if (parsed < minimum || parsed > maximum) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException error) {
            throw new ConfigurationException(label + " must be between " + minimum + " and " + maximum + ".");
        }
    }

    private static int parseOr(String value, int fallback) {
        try {
            return Integer.parseInt(safe(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
