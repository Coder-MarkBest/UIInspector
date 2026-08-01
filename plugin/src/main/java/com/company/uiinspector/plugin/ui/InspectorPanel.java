package com.company.uiinspector.plugin.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBTextArea;
import java.awt.BorderLayout;
import java.awt.Dimension;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JPanel;

public final class InspectorPanel extends JBPanel<InspectorPanel> {
    private final Project project;
    private final JBLabel status = new JBLabel("Ready to inspect a connected Android device.");

    public InspectorPanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        JBTextArea description = new JBTextArea(
                "The browser inspector shows Window layers, View depth and grouped properties. "
                        + "The Collector APK is installed automatically when capture starts.");
        description.setEditable(false);
        description.setLineWrap(true);
        description.setWrapStyleWord(true);
        description.setOpaque(false);

        JPanel actions = new JPanel();
        actions.setLayout(new BoxLayout(actions, BoxLayout.Y_AXIS));
        JButton open = new JButton("Open inspector in browser");
        open.setAlignmentX(LEFT_ALIGNMENT);
        open.addActionListener(event -> run("Opening inspector…", () -> service().openBrowser()));
        JButton settings = new JButton("Open Collector accessibility settings");
        settings.setAlignmentX(LEFT_ALIGNMENT);
        settings.addActionListener(event -> run("Opening device settings…", () -> service().openCollectorSettings()));
        actions.add(open);
        actions.add(Box.createRigidArea(new Dimension(0, 8)));
        actions.add(settings);
        actions.add(Box.createRigidArea(new Dimension(0, 14)));
        actions.add(status);

        add(description, BorderLayout.NORTH);
        add(actions, BorderLayout.CENTER);
    }

    private InspectorProjectService service() {
        return project.getService(InspectorProjectService.class);
    }

    private void run(String pending, CheckedAction action) {
        status.setText(pending);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                action.run();
                ApplicationManager.getApplication().invokeLater(() -> status.setText("Ready."));
            } catch (Exception error) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    status.setText("Action failed.");
                    Messages.showErrorDialog(project, error.getMessage(), "UI Inspector");
                });
            }
        });
    }

    @FunctionalInterface
    private interface CheckedAction { void run() throws Exception; }
}
