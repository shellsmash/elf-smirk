package com.aspe

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages

internal class MyAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
    }

    override fun actionPerformed(event: AnActionEvent) {
        Messages.showMessageDialog(
            event.project,
            "HelloWorld!",
        "My First Action",
        Messages.getInformationIcon())
    }
}