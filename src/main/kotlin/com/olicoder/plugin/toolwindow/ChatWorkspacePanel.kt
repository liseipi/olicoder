package com.olicoder.plugin.toolwindow

import com.olicoder.plugin.settings.OliCoderSettingsConfigurable
import com.intellij.icons.AllIcons
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * 整个 ToolWindow 只挂这一个顶层组件。以前是"每个标签是 IntelliJ 原生 ContentManager 里的一个 Content"，
 * 现在改成自己画头部：第一行是 logo + 功能图标（新建对话/历史记录/设置/用户），第二行是标签条，
 * 标签切换用 CardLayout 在内部切换不同的 ChatPanel 实例。原生 ContentManager 的标签条做不出
 * "上面还有一整行 logo+图标"这种两行布局，所以这部分不能再借用 IntelliJ 自带的标签 UI，只能自己画。
 *
 * 模型配置的"增删改"已经不在这里了，统一挪到设置弹窗（复用 Settings -> Tools -> Oli Coder 那一页，
 * 见 OliCoderSettingsConfigurable）；每个 ChatPanel 自己仍然保留一个精简的"选哪个已配置模型"下拉。
 */
class ChatWorkspacePanel(private val project: Project) : JPanel(BorderLayout()) {

    private class ChatTab(val id: Int, var title: String, val panel: ChatPanel)

    private val tabs = mutableListOf<ChatTab>()
    private var activeTabId: Int = -1
    private var nextTabId = 1

    private val cardLayout = CardLayout()
    private val body = JPanel(cardLayout)
    private val tabStrip = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))

    init {
        tabStrip.border = JBUI.Borders.empty(0, 8)
        tabStrip.background = Color(30, 31, 34)
        tabStrip.isOpaque = true

        val north = JPanel(BorderLayout())
        north.add(buildHeaderRow(), BorderLayout.NORTH)
        north.add(tabStrip, BorderLayout.SOUTH)

        add(north, BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)

        addTab() // 打开插件时默认给一个空对话
    }

    // ---------------- 第一行：logo + 功能图标 ----------------

    private fun buildHeaderRow(): JComponent {
        val row = JPanel(BorderLayout())
        row.border = JBUI.Borders.empty(7, 12)

        val logo = JLabel("Oli Coder")
        logo.font = logo.font.deriveFont(Font.BOLD, 14f)
        row.add(logo, BorderLayout.WEST)

        val icons = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0))
        icons.isOpaque = false
        icons.add(flatIconButton(AllIcons.General.Add, "新建对话") { addTabOrFocusExistingBlank() })
        // 历史记录先只做图标占位，暂时没有实际功能
        icons.add(flatIconButton(AllIcons.Vcs.History, "历史记录（开发中）", enabled = false) {})
        icons.add(flatIconButton(AllIcons.General.Settings, "设置") { openSettings() })
        // 用户中心先只做图标占位，暂时没有实际功能
        icons.add(flatIconButton(AllIcons.General.User, "用户（开发中）", enabled = false) {})
        row.add(icons, BorderLayout.EAST)

        return row
    }

    private fun flatIconButton(icon: Icon, tooltip: String, enabled: Boolean = true, onClick: () -> Unit): JButton {
        val button = JButton(icon)
        button.toolTipText = tooltip
        button.isBorderPainted = false
        button.isContentAreaFilled = false
        button.isFocusPainted = false
        button.isOpaque = false
        button.isEnabled = enabled
        button.margin = JBUI.insets(4)
        button.addActionListener { onClick() }
        return button
    }

    private fun openSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, OliCoderSettingsConfigurable::class.java)
        // 从设置弹窗回来之后，把所有已经打开的标签页的"模型"下拉都刷新一下，
        // 这样如果刚才新增/编辑/删除了某个配置，不用重开插件就能立刻在下拉里看到。
        tabs.forEach { it.panel.refreshProviderConfigsExternally() }
    }

    // ---------------- 第二行：标签条 ----------------

    /** "+" 按钮的行为：如果已经有一个还没用过的空白标签（没聊过天、输入框也是空的），直接切过去，不重复创建 */
    private fun addTabOrFocusExistingBlank() {
        val blank = tabs.firstOrNull { it.panel.isFreshEmpty() }
        if (blank != null) {
            switchTo(blank.id)
            return
        }
        addTab()
    }

    private fun addTab(title: String = "新对话") {
        val id = nextTabId++
        val panel = ChatPanel(project)
        val tab = ChatTab(id, title, panel)
        tabs.add(tab)
        body.add(panel, id.toString())
        switchTo(id)
    }

    private fun closeTab(id: Int) {
        val idx = tabs.indexOfFirst { it.id == id }
        if (idx < 0) return
        val tab = tabs[idx]
        body.remove(tab.panel)
        tabs.removeAt(idx)

        if (tabs.isEmpty()) {
            addTab() // 保底：所有标签都关掉了，自动补一个新的，避免工作区变成空白
            return
        }
        if (activeTabId == id) {
            val fallbackIndex = idx.coerceAtMost(tabs.size - 1)
            switchTo(tabs[fallbackIndex].id)
        } else {
            rebuildTabStrip()
        }
    }

    private fun switchTo(id: Int) {
        activeTabId = id
        cardLayout.show(body, id.toString())
        rebuildTabStrip()
    }

    private fun rebuildTabStrip() {
        tabStrip.removeAll()
        tabs.forEach { tabStrip.add(buildTabChip(it)) }
        tabStrip.revalidate()
        tabStrip.repaint()
    }

    private fun buildTabChip(tab: ChatTab): JComponent {
        val active = tab.id == activeTabId

        val chip = JPanel(BorderLayout(6, 0))
        chip.isOpaque = true
        chip.background = if (active) Color(52, 55, 60) else Color(30, 31, 34)
        chip.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(if (active) Color(88, 152, 240) else chip.background, 0, 0, 2, 0),
            JBUI.Borders.empty(5, 12, 4, 6)
        )

        val label = JLabel(tab.title)
        label.foreground = if (active) Color(228, 228, 228) else Color(148, 148, 148)
        label.font = label.font.deriveFont(11.5f)
        chip.add(label, BorderLayout.CENTER)

        val closeBtn = JButton(AllIcons.Actions.Close)
        closeBtn.isBorderPainted = false
        closeBtn.isContentAreaFilled = false
        closeBtn.isFocusPainted = false
        closeBtn.isOpaque = false
        closeBtn.margin = JBUI.insets(2)
        closeBtn.toolTipText = "关闭这个对话"
        closeBtn.addActionListener { closeTab(tab.id) }
        chip.add(closeBtn, BorderLayout.EAST)

        // 点击标签空白处（不点关闭按钮）也要能切换过去
        val clickToSwitch = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) { switchTo(tab.id) }
        }
        chip.addMouseListener(clickToSwitch)
        label.addMouseListener(clickToSwitch)

        return chip
    }

    /** 供 SendSelectionToChatAction 用：把选中代码发到用户当前正在看的那个标签 */
    fun activePanel(): ChatPanel? = tabs.firstOrNull { it.id == activeTabId }?.panel
}
