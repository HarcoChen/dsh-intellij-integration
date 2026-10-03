package top.harcochen.dsh;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

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
    private JBTextField maxContextBytes;
    private JBTextField npmRegistry;
    private JBTextField apiKeyEnv;
    private JBTextArea agentStatusLabels;
    private JBCheckBox autoStart;
    private JBCheckBox persistSession;
    private JBCheckBox installWhenMissing;
    private JBCheckBox useManagedRuntime;
    private JBCheckBox enableCompaction;
    private JBCheckBox jevEnabled;
    private JBTextField jevBaseUrl;
    private JBTextField jevModel;
    private JBCheckBox jevLoopGuard;
    private JBCheckBox jevResultShaper;
    private JBCheckBox jevDoneGate;
    private JBCheckBox jevToolPruner;
    private JBCheckBox jevSkillRouter;
    private JBCheckBox jevDecisionTools;
    private JBCheckBox recoveryEnabled;
    private JBCheckBox recoveryIsolation;

    public DshSettingsConfigurable(Project project) {
        this.project = project;
    }

    @Override
    public @Nls String getDisplayName() {
        return DshBundle.message("dsh.settings.displayName");
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
        maxContextBytes = new JBTextField();
        npmRegistry = new JBTextField();
        apiKeyEnv = new JBTextField();
        agentStatusLabels = new JBTextArea(4, 40);
        autoStart = new JBCheckBox(DshBundle.message("dsh.settings.auto.start.label"));
        persistSession = new JBCheckBox(DshBundle.message("dsh.settings.persist.session.label"));
        installWhenMissing =
                new JBCheckBox(DshBundle.message("dsh.settings.install.when.missing.label"));
        useManagedRuntime = new JBCheckBox(DshBundle.message("dsh.settings.managed.runtime.label"));
        enableCompaction =
                new JBCheckBox(DshBundle.message("dsh.settings.enable.compaction.label"));
        jevEnabled = new JBCheckBox(DshBundle.message("dsh.settings.jev.enabled"));
        jevBaseUrl = new JBTextField();
        jevModel = new JBTextField();
        jevLoopGuard = new JBCheckBox(DshBundle.message("dsh.settings.jev.loop.guard"));
        jevResultShaper = new JBCheckBox(DshBundle.message("dsh.settings.jev.result.shaper"));
        jevDoneGate = new JBCheckBox(DshBundle.message("dsh.settings.jev.done.gate"));
        jevToolPruner = new JBCheckBox(DshBundle.message("dsh.settings.jev.tool.pruner"));
        jevSkillRouter = new JBCheckBox(DshBundle.message("dsh.settings.jev.skill.router"));
        jevDecisionTools = new JBCheckBox(DshBundle.message("dsh.settings.jev.decision.tools"));

        addRow(
                DshBundle.message("dsh.settings.command.label"),
                command,
                DshBundle.message("dsh.settings.command.tooltip"));
        addRow(
                DshBundle.message("dsh.settings.command.args.label"),
                commandArgs,
                DshBundle.message("dsh.settings.command.args.tooltip"));
        addRow(
                DshBundle.message("dsh.settings.server.url.label"),
                serverUrl,
                DshBundle.message("dsh.settings.server.url.tooltip"));
        addRow(
                DshBundle.message("dsh.settings.server.port.label"),
                serverPort,
                DshBundle.message("dsh.settings.server.port.tooltip"));
        addRow(
                DshBundle.message("dsh.settings.runtime.version.label"),
                runtimeVersion,
                DshBundle.message("dsh.settings.runtime.version.tooltip"));
        addRow(
                DshBundle.message("dsh.settings.startup.timeout.label"),
                startupTimeout,
                DshBundle.message("dsh.settings.startup.timeout.tooltip"));
        addRow(
                DshBundle.message("dsh.settings.request.timeout.label"),
                requestTimeout,
                DshBundle.message("dsh.settings.request.timeout.tooltip"));
        addRow(
                DshBundle.message("dsh.settings.max.context.bytes.label"),
                maxContextBytes,
                DshBundle.message("dsh.settings.max.context.bytes.tooltip"));
        addRow(
                DshBundle.message("dsh.settings.npm.registry.label"),
                npmRegistry,
                DshBundle.message("dsh.settings.npm.registry.tooltip"));
        addRow(
                DshBundle.message("dsh.settings.api.key.env.label"),
                apiKeyEnv,
                DshBundle.message("dsh.settings.api.key.env.tooltip"));
        GridBagConstraints labelsConstraints = new GridBagConstraints();
        labelsConstraints.gridx = 0;
        labelsConstraints.gridy = GridBagConstraints.RELATIVE;
        labelsConstraints.anchor = GridBagConstraints.NORTHWEST;
        labelsConstraints.insets = new Insets(4, 0, 4, 12);
        panel.add(
                new JBLabel(DshBundle.message("dsh.settings.agent.status.labels.label") + ":"),
                labelsConstraints);
        agentStatusLabels.setToolTipText(
                DshBundle.message("dsh.settings.agent.status.labels.tooltip"));
        GridBagConstraints labelsField = new GridBagConstraints();
        labelsField.gridx = 1;
        labelsField.gridy = GridBagConstraints.RELATIVE;
        labelsField.weightx = 1;
        labelsField.fill = GridBagConstraints.HORIZONTAL;
        labelsField.insets = new Insets(4, 0, 4, 0);
        panel.add(agentStatusLabels, labelsField);
        addCheckbox(autoStart);
        addCheckbox(persistSession);
        addCheckbox(enableCompaction);
        addCheckbox(jevEnabled);
        addRow(
                DshBundle.message("dsh.settings.jev.base.url"),
                jevBaseUrl,
                DshBundle.message("dsh.settings.jev.base.url.tooltip"));
        addRow(
                DshBundle.message("dsh.settings.jev.model"),
                jevModel,
                DshBundle.message("dsh.settings.jev.model.tooltip"));
        addCheckbox(jevLoopGuard);
        addCheckbox(jevResultShaper);
        addCheckbox(jevDoneGate);
        addCheckbox(jevToolPruner);
        addCheckbox(jevSkillRouter);
        addCheckbox(jevDecisionTools);
        recoveryEnabled = new JBCheckBox(DshBundle.message("dsh.settings.recovery.enabled"));
        recoveryIsolation = new JBCheckBox(DshBundle.message("dsh.settings.recovery.isolation"));
        addCheckbox(recoveryEnabled);
        addCheckbox(recoveryIsolation);

        JPanel note = new JPanel();
        note.setLayout(new BoxLayout(note, BoxLayout.Y_AXIS));
        note.add(new JBLabel(DshBundle.message("dsh.settings.note.protocol")));
        note.add(Box.createVerticalStrut(4));
        note.add(new JBLabel(DshBundle.message("dsh.settings.note.credentials")));
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
                || parseOr(startupTimeout.getText(), state.startupTimeoutMs)
                        != state.startupTimeoutMs
                || parseOr(requestTimeout.getText(), state.requestTimeoutMs)
                        != state.requestTimeoutMs
                || parseOr(maxContextBytes.getText(), state.maxContextBytes)
                        != state.maxContextBytes
                || !safe(npmRegistry.getText()).equals(safe(state.npmRegistry))
                || !safe(apiKeyEnv.getText()).equals(safe(state.apiKeyEnv))
                || !joinedLabels().equals(String.join("\n", state.agentStatusLabels))
                || autoStart.isSelected() != state.autoStart
                || persistSession.isSelected() != state.persistSession
                || installWhenMissing.isSelected() != state.installWhenMissing
                || useManagedRuntime.isSelected() != state.useManagedRuntime
                || enableCompaction.isSelected() != state.enableCompaction
                || jevEnabled.isSelected() != state.jevEnabled
                || !safe(jevBaseUrl.getText()).equals(safe(state.jevBaseUrl))
                || !safe(jevModel.getText()).equals(safe(state.jevModel))
                || jevLoopGuard.isSelected() != state.jevLoopGuard
                || jevResultShaper.isSelected() != state.jevResultShaper
                || jevDoneGate.isSelected() != state.jevDoneGate
                || jevToolPruner.isSelected() != state.jevToolPruner
                || jevSkillRouter.isSelected() != state.jevSkillRouter
                || jevDecisionTools.isSelected() != state.jevDecisionTools
                || recoveryEnabled.isSelected() != state.recoveryEnabled
                || recoveryIsolation.isSelected() != state.recoveryAutoPersistBundleIsolation;
    }

    @Override
    public void apply() throws ConfigurationException {
        DshSettingsState state = DshSettingsState.getInstance(project);
        int port =
                parseRequired(
                        serverPort.getText(),
                        DshBundle.message("dsh.settings.server.port.label"),
                        0,
                        65_535);
        String version = safe(runtimeVersion.getText());
        if (version.isBlank() || !version.matches("[A-Za-z0-9.-]+")) {
            throw new ConfigurationException(
                    DshBundle.message("dsh.settings.error.runtime.version"));
        }
        int startup =
                parseRequired(
                        startupTimeout.getText(),
                        DshBundle.message("dsh.settings.startup.timeout.label"),
                        1_000,
                        600_000);
        int request =
                parseRequired(
                        requestTimeout.getText(),
                        DshBundle.message("dsh.settings.request.timeout.label"),
                        10_000,
                        3_600_000);
        int context =
                parseRequired(
                        maxContextBytes.getText(),
                        DshBundle.message("dsh.settings.max.context.bytes.label"),
                        1_000,
                        1_000_000);
        if (safe(command.getText()).isBlank()) {
            throw new ConfigurationException(DshBundle.message("dsh.settings.error.command.empty"));
        }
        if (!safe(apiKeyEnv.getText()).isBlank()
                && !safe(apiKeyEnv.getText()).matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new ConfigurationException(
                    DshBundle.message("dsh.settings.error.api.key.env.invalid"));
        }
        String jevEndpoint = safe(jevBaseUrl.getText()).trim();
        try {
            java.net.URI uri = java.net.URI.create(jevEndpoint);
            boolean localHttp =
                    "http".equals(uri.getScheme())
                            && ("localhost".equals(uri.getHost())
                                    || "127.0.0.1".equals(uri.getHost()));
            if (!("https".equals(uri.getScheme()) || localHttp)
                    || uri.getHost() == null
                    || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("Invalid Jev endpoint");
            }
        } catch (RuntimeException invalid) {
            throw new ConfigurationException(
                    DshBundle.message("dsh.settings.jev.base.url.invalid"));
        }
        String jevModelValue = safe(jevModel.getText()).trim();
        if (jevModelValue.isBlank()) {
            throw new ConfigurationException(DshBundle.message("dsh.settings.jev.model.invalid"));
        }
        state.command = safe(command.getText());
        state.commandArgs = safe(commandArgs.getText());
        state.serverUrl = safe(serverUrl.getText());
        state.serverPort = port;
        state.runtimeVersion = version;
        state.jevBaseUrl = jevEndpoint;
        state.jevModel = jevModelValue;
        state.jevEnabled = jevEnabled.isSelected();
        state.jevLoopGuard = jevLoopGuard.isSelected();
        state.jevResultShaper = jevResultShaper.isSelected();
        state.jevDoneGate = jevDoneGate.isSelected();
        state.jevToolPruner = jevToolPruner.isSelected();
        state.jevSkillRouter = jevSkillRouter.isSelected();
        state.jevDecisionTools = jevDecisionTools.isSelected();
        state.startupTimeoutMs = startup;
        state.requestTimeoutMs = request;
        state.maxContextBytes = context;
        state.npmRegistry = safe(npmRegistry.getText());
        state.apiKeyEnv = safe(apiKeyEnv.getText());
        state.agentStatusLabels = parsedLabels();
        state.autoStart = autoStart.isSelected();
        state.persistSession = persistSession.isSelected();
        state.installWhenMissing = installWhenMissing.isSelected();
        state.useManagedRuntime = useManagedRuntime.isSelected();
        state.enableCompaction = enableCompaction.isSelected();
        state.recoveryEnabled = recoveryEnabled.isSelected();
        state.recoveryAutoPersistBundleIsolation = recoveryIsolation.isSelected();
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
        maxContextBytes.setText(Integer.toString(state.maxContextBytes));
        npmRegistry.setText(state.npmRegistry);
        apiKeyEnv.setText(state.apiKeyEnv);
        agentStatusLabels.setText(String.join("\n", state.agentStatusLabels));
        autoStart.setSelected(state.autoStart);
        persistSession.setSelected(state.persistSession);
        installWhenMissing.setSelected(state.installWhenMissing);
        useManagedRuntime.setSelected(state.useManagedRuntime);
        enableCompaction.setSelected(state.enableCompaction);
        jevEnabled.setSelected(state.jevEnabled);
        jevBaseUrl.setText(safe(state.jevBaseUrl));
        jevModel.setText(safe(state.jevModel));
        jevLoopGuard.setSelected(state.jevLoopGuard);
        jevResultShaper.setSelected(state.jevResultShaper);
        jevDoneGate.setSelected(state.jevDoneGate);
        jevToolPruner.setSelected(state.jevToolPruner);
        jevSkillRouter.setSelected(state.jevSkillRouter);
        jevDecisionTools.setSelected(state.jevDecisionTools);
        recoveryEnabled.setSelected(state.recoveryEnabled);
        recoveryIsolation.setSelected(state.recoveryAutoPersistBundleIsolation);
    }

    private String joinedLabels() {
        return String.join("\n", parsedLabels());
    }

    private java.util.List<String> parsedLabels() {
        java.util.List<String> result = new java.util.ArrayList<>();
        for (String line : safe(agentStatusLabels.getText()).split("\n", -1)) {
            String candidate = line.trim();
            if (!candidate.isEmpty() && candidate.length() <= 256) result.add(candidate);
        }
        return result;
    }

    private static int parseRequired(String value, String label, int minimum, int maximum)
            throws ConfigurationException {
        try {
            int parsed = Integer.parseInt(safe(value));
            if (parsed < minimum || parsed > maximum) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException error) {
            throw new ConfigurationException(
                    DshBundle.message("dsh.settings.error.range", label, minimum, maximum));
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
