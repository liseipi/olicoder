package com.olicoder.plugin.actions

import com.olicoder.plugin.toolwindow.ChatToolWindowFactory
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.wm.ToolWindowManager

class SendSelectionToChatAction : AnAction("发送选中代码到 AI 对话") {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor?.selectionModel?.hasSelection() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val selectedText = editor.selectionModel.selectedText ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE)
        val fileName = file?.name ?: "code"

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Oli Coder")
        toolWindow?.show {
            val panel = ChatToolWindowFactory.panels[project]
            panel?.insertIntoInput("以下是来自 $fileName 的代码：\n```\n$selectedText\n```\n")
        }
    }
}
