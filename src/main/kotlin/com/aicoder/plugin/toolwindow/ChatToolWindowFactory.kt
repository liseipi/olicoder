package com.aicoder.plugin.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class ChatToolWindowFactory : ToolWindowFactory {

    companion object {
        // 缓存每个项目对应的 ChatPanel 实例，供 Action 拿到并塞入选中代码
        val panels = mutableMapOf<Project, ChatPanel>()
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val chatPanel = ChatPanel(project)
        panels[project] = chatPanel
        val content = ContentFactory.getInstance().createContent(chatPanel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
