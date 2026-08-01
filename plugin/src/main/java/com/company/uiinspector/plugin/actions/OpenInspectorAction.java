package com.company.uiinspector.plugin.actions;

import com.company.uiinspector.plugin.ui.InspectorProjectService;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

public final class OpenInspectorAction extends AnAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) return;
        ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow("UI Inspector");
        if (window != null) window.show();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                project.getService(InspectorProjectService.class).openBrowser();
            } catch (Exception error) {
                ApplicationManager.getApplication().invokeLater(() ->
                        Messages.showErrorDialog(project, error.getMessage(), "UI Inspector"));
            }
        });
    }
}
