package com.olicoder.plugin.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory

/**
 * 现在整个 ToolWindow 只挂一个 ChatWorkspacePanel：logo/图标行 + 标签条 + 标签内容
 * 全部由它自己管理（见 ChatWorkspacePanel），不再使用 IntelliJ 原生 ContentManager 的多标签机制。
 */
class ChatToolWindowFactory : ToolWindowFactory {

    companion object {
        private val workspaces = mutableMapOf<Project, ChatWorkspacePanel>()

        /** 供 Action（如"发送选中代码到 AI 对话"）拿到当前用户正在看的那个标签对应的 ChatPanel */
        fun activePanel(project: Project): ChatPanel? = workspaces[project]?.activePanel()
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val workspace = ChatWorkspacePanel(project)
        workspaces[project] = workspace
        val content = ContentFactory.getInstance().createContent(workspace, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
