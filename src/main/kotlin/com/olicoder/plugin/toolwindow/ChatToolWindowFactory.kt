package com.olicoder.plugin.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener

/**
 * 多标签支持：每个标签都是一个独立的 ChatPanel 实例（独立的 history / messageBuffer / 附件状态），
 * 互不干扰。"当前活跃的对话" = ContentManager 里当前选中 tab 对应的那个 ChatPanel。
 */
class ChatToolWindowFactory : ToolWindowFactory {

    companion object {
        private val tabCounters = mutableMapOf<Project, Int>()

        /** 供 Action（如"发送选中代码到 AI 对话"）拿到当前用户正在看的那个标签页 */
        fun activePanel(project: Project): ChatPanel? {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Oli Coder") ?: return null
            return toolWindow.contentManager.selectedContent?.component as? ChatPanel
        }

        /** 新开一个对话标签，并自动切换过去 */
        fun addNewTab(project: Project, toolWindow: ToolWindow) {
            val panel = ChatPanel(project)
            val nextIndex = (tabCounters[project] ?: 0) + 1
            tabCounters[project] = nextIndex

            val content = ContentFactory.getInstance().createContent(panel, "对话 $nextIndex", false)
            content.isCloseable = true
            toolWindow.contentManager.addContent(content)
            toolWindow.contentManager.setSelectedContent(content)
        }
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // 默认打开时先给一个标签，避免用户一打开工具窗口看到空面板
        addNewTab(project, toolWindow)

        // 标题栏右上角加一个"新建对话"按钮
        toolWindow.setTitleActions(
            listOf(
                object : AnAction("新建对话", "新开一个独立的对话标签", AllIcons.General.Add) {
                    override fun actionPerformed(e: AnActionEvent) {
                        addNewTab(project, toolWindow)
                    }
                }
            )
        )

        // 保底：所有标签都被关掉时自动补一个新的，避免工具窗口变成完全空白、没法再新建
        toolWindow.contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun contentRemoved(event: ContentManagerEvent) {
                if (toolWindow.contentManager.contentCount == 0) {
                    addNewTab(project, toolWindow)
                }
            }
        })
    }
}
