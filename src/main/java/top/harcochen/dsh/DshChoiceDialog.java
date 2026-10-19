package top.harcochen.dsh;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import java.awt.BorderLayout;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.AbstractAction;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;

/** Searchable native single-choice dialog; filtered rows retain their original indices. */
final class DshChoiceDialog extends DialogWrapper {
    private record Choice(int index, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private final List<Choice> choices = new ArrayList<>();
    private final JBTextField search = new JBTextField();
    private final DefaultListModel<Choice> model = new DefaultListModel<>();
    private final JBList<Choice> list = new JBList<>(model);
    private final String message;
    private final Icon icon;

    private DshChoiceDialog(
            Project project,
            String message,
            String title,
            Icon icon,
            String[] values,
            String initial) {
        super(project);
        this.message = message;
        this.icon = icon;
        setTitle(title);
        for (int index = 0; index < values.length; index++)
            choices.add(new Choice(index, values[index]));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        DefaultListCellRenderer renderer = new DefaultListCellRenderer();
        renderer.putClientProperty("html.disable", true);
        list.setCellRenderer(renderer);
        list.getEmptyText().setText(DshBundle.message("dsh.choice.empty"));
        search.getEmptyText().setText(DshBundle.message("dsh.choice.search"));
        search.getAccessibleContext().setAccessibleName(DshBundle.message("dsh.choice.search"));
        list.getAccessibleContext().setAccessibleName(message);
        search.getDocument()
                .addDocumentListener(
                        new DocumentAdapter() {
                            @Override
                            protected void textChanged(DocumentEvent event) {
                                filter();
                            }
                        });
        list.addListSelectionListener(event -> setOKActionEnabled(list.getSelectedValue() != null));
        bind(search, "DOWN", () -> moveSelection(1));
        bind(search, "UP", () -> moveSelection(-1));
        bind(search, "ENTER", this::doOKAction);
        bind(list, "ENTER", this::doOKAction);
        list.addMouseListener(
                new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent event) {
                        int index = list.locationToIndex(event.getPoint());
                        if (SwingUtilities.isLeftMouseButton(event)
                                && event.getClickCount() == 2
                                && index >= 0
                                && list.getCellBounds(index, index).contains(event.getPoint()))
                            doOKAction();
                    }
                });
        init();
        filter();
        for (Choice choice : choices)
            if (choice.label().equals(initial)) {
                list.setSelectedValue(choice, true);
                break;
            }
    }

    static int show(
            Project project,
            String message,
            String title,
            Icon icon,
            String[] values,
            String initial) {
        if (values.length == 0 || project.isDisposed()) return -1;
        DshChoiceDialog dialog =
                new DshChoiceDialog(project, message, title, icon, values, initial);
        return dialog.showAndGet() && dialog.list.getSelectedValue() != null
                ? dialog.list.getSelectedValue().index()
                : -1;
    }

    private void filter() {
        Choice selected = list.getSelectedValue();
        String[] terms = search.getText().strip().toLowerCase(Locale.ROOT).split("\\s+");
        model.clear();
        for (Choice choice : choices) {
            String label = choice.label().toLowerCase(Locale.ROOT);
            boolean matches = true;
            for (String term : terms)
                if (!label.contains(term)) {
                    matches = false;
                    break;
                }
            if (matches) model.addElement(choice);
        }
        if (selected != null && model.contains(selected)) list.setSelectedValue(selected, true);
        else if (!model.isEmpty()) list.setSelectedIndex(0);
        setOKActionEnabled(list.getSelectedValue() != null);
    }

    private void moveSelection(int delta) {
        if (model.isEmpty()) return;
        int index = Math.max(0, Math.min(model.size() - 1, list.getSelectedIndex() + delta));
        list.setSelectedIndex(index);
        list.ensureIndexIsVisible(index);
    }

    private static void bind(JComponent component, String key, Runnable action) {
        String name = "dsh-choice-" + key;
        component.getInputMap().put(KeyStroke.getKeyStroke(key), name);
        component
                .getActionMap()
                .put(
                        name,
                        new AbstractAction() {
                            @Override
                            public void actionPerformed(ActionEvent event) {
                                action.run();
                            }
                        });
    }

    @Override
    protected void doOKAction() {
        if (list.getSelectedValue() != null) super.doOKAction();
    }

    @Override
    public JComponent getPreferredFocusedComponent() {
        return search;
    }

    @Override
    protected JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        JTextArea prompt = new JTextArea(message);
        prompt.setEditable(false);
        prompt.setLineWrap(true);
        prompt.setWrapStyleWord(true);
        prompt.setOpaque(false);
        prompt.setFont(search.getFont());
        prompt.setRows(Math.min(6, Math.max(1, message.split("\n", -1).length)));
        prompt.setColumns(56);
        JPanel header = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        if (icon != null) {
            JLabel decoration = new JLabel(icon);
            decoration.setVerticalAlignment(JLabel.TOP);
            header.add(decoration, BorderLayout.WEST);
        }
        JBScrollPane promptScroll = new JBScrollPane(prompt);
        promptScroll.setBorder(JBUI.Borders.empty());
        header.add(promptScroll, BorderLayout.CENTER);
        panel.add(header, BorderLayout.NORTH);
        JPanel body = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        body.add(search, BorderLayout.NORTH);
        JBScrollPane scroll = new JBScrollPane(list);
        scroll.setPreferredSize(JBUI.size(640, 280));
        body.add(scroll, BorderLayout.CENTER);
        panel.add(body, BorderLayout.CENTER);
        return panel;
    }
}
