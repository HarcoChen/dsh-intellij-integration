package top.harcochen.dsh;

import com.intellij.diff.DiffManager;
import com.intellij.diff.DiffRequestPanel;
import com.intellij.diff.requests.DiffRequest;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.util.function.Consumer;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

/** Reviews a proposed edit without changing the tool arguments or writing files locally. */
final class DshDiffApprovalDialog extends DialogWrapper {
    private final DiffRequestPanel diff;
    private final Consumer<String> decision;

    DshDiffApprovalDialog(Project project, DiffRequest request, Consumer<String> decision) {
        super(project, false);
        this.decision = decision;
        diff = DiffManager.getInstance().createRequestPanel(project, getDisposable(), null);
        diff.setRequest(request);
        setTitle(DshBundle.message("dsh.diff.approval.title"));
        setOKButtonText(DshBundle.message("dsh.diff.approval.approve"));
        setCancelButtonText(DshBundle.message("dsh.diff.approval.close"));
        init();
    }

    @Override
    protected JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(new JLabel(DshBundle.message("dsh.diff.approval.hint")), BorderLayout.NORTH);
        panel.add(diff.getComponent(), BorderLayout.CENTER);
        panel.setPreferredSize(new Dimension(1000, 650));
        return panel;
    }

    @Override
    protected Action[] createActions() {
        Action reject =
                new DialogWrapperAction(DshBundle.message("dsh.diff.approval.reject")) {
                    @Override
                    protected void doAction(ActionEvent event) {
                        close(CANCEL_EXIT_CODE);
                        decision.accept("rejected");
                    }
                };
        return new Action[] {getOKAction(), reject, getCancelAction()};
    }

    @Override
    protected void doOKAction() {
        super.doOKAction();
        decision.accept("allowed-once");
    }
}
