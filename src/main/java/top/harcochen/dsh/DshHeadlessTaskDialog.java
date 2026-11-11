package top.harcochen.dsh;

import com.google.gson.GsonBuilder;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.swing.Action;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.Timer;

/** Native task form and bounded live output for the project's local headless CLI runs. */
final class DshHeadlessTaskDialog extends DialogWrapper {
    private static final Key<DshHeadlessTaskDialog> WINDOW = Key.create("dsh.headless.window");
    private final Project project;
    private final DshHeadlessTasks tasks;
    private final JTextArea prompt = new JTextArea(6, 80);
    private final JTextField directory = new JTextField();
    private final JTextField permission = new JTextField();
    private final JTextField session = new JTextField();
    private final JSpinner timeout = new JSpinner(new SpinnerNumberModel(10, 1, 1440, 1));
    private final DefaultListModel<String> model = new DefaultListModel<>();
    private final JList<String> list = new JList<>(model);
    private final List<String> ids = new ArrayList<>();
    private final JTextArea answer = text();
    private final JTextArea events = text();
    private final JTextArea diagnostics = text();
    private final JTextArea summary = text();
    private final Timer refresh;
    private Action cancel;
    private Action rerun;
    private Action resume;
    private Action copy;
    private Action export;
    private DshHeadlessTasks.View selected;
    private String selectedId;
    private long revision = -1;
    private List<DshHeadlessTasks.Row> rows = List.of();
    private boolean rendering;
    private boolean closed;

    static void open(Project project) {
        DshHeadlessTaskDialog existing = project.getUserData(WINDOW);
        if (existing != null && !existing.closed) {
            existing.toFront();
            return;
        }
        DshHeadlessTaskDialog dialog = new DshHeadlessTaskDialog(project);
        project.putUserData(WINDOW, dialog);
        dialog.show();
    }

    private DshHeadlessTaskDialog(Project project) {
        super(project, false);
        this.project = project;
        this.tasks = DshHeadlessTasks.getInstance(project);
        directory.setText(
                project.getBasePath() == null
                        ? System.getProperty("user.dir")
                        : project.getBasePath());
        prompt.setLineWrap(true);
        prompt.setWrapStyleWord(true);
        summary.setRows(3);
        summary.setLineWrap(true);
        summary.setWrapStyleWord(true);
        session.setToolTipText(DshBundle.message("dsh.headless.session.help"));
        permission.setToolTipText(DshBundle.message("dsh.headless.permission.help"));
        setTitle(DshBundle.message("dsh.headless.title"));
        setCancelButtonText(DshBundle.message("dsh.headless.close"));
        setModal(false);
        list.addListSelectionListener(
                event -> {
                    if (!rendering && !event.getValueIsAdjusting()) select();
                });
        init();
        refresh = new Timer(250, ignored -> render());
        refresh.start();
        render();
    }

    @Override
    public JComponent getPreferredFocusedComponent() {
        return prompt;
    }

    private static JTextArea text() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        return area;
    }

    @Override
    protected JComponent createCenterPanel() {
        JPanel form = new JPanel(new GridLayout(0, 2, 8, 6));
        JPanel folder = new JPanel(new BorderLayout(6, 0));
        folder.add(directory, BorderLayout.CENTER);
        JButton browse = new JButton(DshBundle.message("dsh.headless.browse"));
        browse.addActionListener(
                ignored -> {
                    VirtualFile chosen =
                            FileChooser.chooseFile(
                                    FileChooserDescriptorFactory.createSingleFolderDescriptor(),
                                    project,
                                    null);
                    if (chosen != null) directory.setText(chosen.getPath());
                });
        folder.add(browse, BorderLayout.EAST);
        row(form, "directory", folder);
        row(form, "permission", permission);
        row(form, "session", session);
        row(form, "timeout", timeout);
        JPanel input = new JPanel(new BorderLayout(0, 6));
        input.add(form, BorderLayout.NORTH);
        JPanel instruction = new JPanel(new BorderLayout(0, 4));
        instruction.add(new JLabel(DshBundle.message("dsh.headless.prompt")), BorderLayout.NORTH);
        instruction.add(new JScrollPane(prompt), BorderLayout.CENTER);
        JPanel notes = new JPanel(new GridLayout(0, 1, 0, 2));
        notes.add(new JLabel(DshBundle.message("dsh.headless.credentials.help")));
        notes.add(new JLabel(DshBundle.message("dsh.headless.lifecycle.help")));
        instruction.add(notes, BorderLayout.SOUTH);
        input.add(instruction, BorderLayout.CENTER);
        JTabbedPane output = new JTabbedPane();
        output.addTab(DshBundle.message("dsh.headless.answer"), new JScrollPane(answer));
        output.addTab(DshBundle.message("dsh.headless.events"), new JScrollPane(events));
        output.addTab(DshBundle.message("dsh.headless.diagnostics"), new JScrollPane(diagnostics));
        JSplitPane split =
                new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(list), output);
        split.setDividerLocation(260);
        split.setResizeWeight(0.25);
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.add(input, BorderLayout.NORTH);
        content.add(split, BorderLayout.CENTER);
        content.add(new JScrollPane(summary), BorderLayout.SOUTH);
        content.setPreferredSize(new Dimension(1080, 720));
        return content;
    }

    private void row(JPanel panel, String key, JComponent value) {
        panel.add(new JLabel(DshBundle.message("dsh.headless." + key)));
        panel.add(value);
    }

    @Override
    protected Action[] createActions() {
        cancel =
                action(
                        "cancel",
                        () -> {
                            if (selected != null) tasks.cancel(selected.row().id());
                        });
        rerun =
                action(
                        "rerun",
                        () -> {
                            if (selected == null) return;
                            load(selected.request());
                            session.setText("");
                            start();
                        });
        resume =
                action(
                        "resume",
                        () -> {
                            if (selected == null
                                    || selected.active()
                                    || selected.row().sessionId().isEmpty()) return;
                            directory.setText(selected.request().directory());
                            session.setText(selected.row().sessionId());
                            prompt.requestFocusInWindow();
                            if (!prompt.getText().isBlank()) start();
                        });
        copy =
                action(
                        "copy",
                        () -> {
                            if (selected != null)
                                CopyPasteManager.getInstance()
                                        .setContents(new StringSelection(selected.answer()));
                        });
        export = action("export", this::export);
        return new Action[] {
            action("run", this::start), cancel, rerun, resume, copy, export, getCancelAction()
        };
    }

    private Action action(String key, Runnable operation) {
        return new DialogWrapperAction(DshBundle.message("dsh.headless." + key)) {
            @Override
            protected void doAction(java.awt.event.ActionEvent event) {
                try {
                    operation.run();
                    render();
                } catch (RuntimeException failure) {
                    summary.setText(DshJson.message(failure));
                }
            }
        };
    }

    private void load(DshHeadlessTasks.Request request) {
        prompt.setText(request.prompt());
        directory.setText(request.directory());
        permission.setText(request.permission());
        timeout.setValue(request.timeoutMinutes());
    }

    private void start() {
        DshHeadlessTasks.Request request =
                new DshHeadlessTasks.Request(
                        prompt.getText(),
                        directory.getText().strip(),
                        permission.getText(),
                        session.getText(),
                        (Integer) timeout.getValue());
        selectedId = tasks.start(request);
        prompt.setText("");
        revision = -1;
        render();
    }

    private void select() {
        int index = list.getSelectedIndex();
        selectedId = index >= 0 && index < ids.size() ? ids.get(index) : null;
        selected = null;
        revision = -1;
        render();
    }

    private void render() {
        if (closed) return;
        if (project.isDisposed() || tasks.closed()) {
            close(CANCEL_EXIT_CODE);
            return;
        }
        List<DshHeadlessTasks.Row> next = tasks.rows();
        if (!next.equals(rows)) {
            rows = next;
            rendering = true;
            model.clear();
            ids.clear();
            for (var item : rows) {
                ids.add(item.id());
                model.addElement(state(item.state()) + " · " + item.title());
            }
            int index = selectedId == null ? -1 : ids.indexOf(selectedId);
            if (index < 0 && !ids.isEmpty()) {
                index = 0;
                selectedId = ids.get(0);
                revision = -1;
            }
            if (index >= 0) list.setSelectedIndex(index);
            rendering = false;
        }
        DshHeadlessTasks.View view = selectedId == null ? null : tasks.view(selectedId, revision);
        if (view != null) {
            selected = view;
            revision = view.row().revision();
            updateText(answer, view.answer());
            updateText(events, view.events());
            updateText(diagnostics, view.diagnostics());
            String detail = state(view.row().state()) + "\n" + view.request().directory();
            if (!view.row().sessionId().isEmpty()) detail += "\n" + view.row().sessionId();
            if (view.exitCode() != null)
                detail += "\n" + DshBundle.message("dsh.headless.exit", view.exitCode());
            if (view.truncated()) detail += "\n" + DshBundle.message("dsh.headless.truncated");
            if (view.error() != null) detail += "\n" + view.error();
            summary.setText(detail);
        }
        if (cancel == null) return;
        boolean available = selected != null;
        cancel.setEnabled(available && selected.active());
        rerun.setEnabled(available && !selected.active());
        resume.setEnabled(available && !selected.active() && !selected.row().sessionId().isEmpty());
        copy.setEnabled(available && !selected.answer().isEmpty());
        export.setEnabled(available);
    }

    private static void updateText(JTextArea area, String value) {
        if (area.getText().equals(value)) return;
        int position = area.getCaretPosition();
        boolean follow = position == area.getDocument().getLength();
        area.setText(value);
        area.setCaretPosition(
                follow
                        ? area.getDocument().getLength()
                        : Math.min(position, area.getDocument().getLength()));
    }

    private static String state(String state) {
        return DshBundle.message("dsh.headless.state." + state);
    }

    private void export() {
        DshHeadlessTasks.View view = selected;
        if (view == null) return;
        FileSaverDescriptor descriptor =
                new FileSaverDescriptor(DshBundle.message("dsh.headless.export"), "", "json");
        var saved =
                FileChooserFactory.getInstance()
                        .createSaveFileDialog(descriptor, project)
                        .save((VirtualFile) null, "dsh-task-" + view.row().id() + ".json");
        if (saved == null) return;
        CompletableFuture.runAsync(
                () -> {
                    try {
                        DshConversationExporter.write(
                                saved.getFile().toPath(),
                                new GsonBuilder().setPrettyPrinting().create().toJson(view));
                    } catch (Exception failure) {
                        com.intellij.openapi.application.ApplicationManager.getApplication()
                                .invokeLater(
                                        () -> {
                                            if (!closed) summary.setText(DshJson.message(failure));
                                        });
                    }
                });
    }

    @Override
    protected void dispose() {
        closed = true;
        refresh.stop();
        if (project.getUserData(WINDOW) == this) project.putUserData(WINDOW, null);
        super.dispose();
    }
}
