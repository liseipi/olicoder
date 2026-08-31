# AI Coder — 多模型自选的 JetBrains AI 编程助手（MVP）

一个类似 CodeBuddy / Cursor 的 JetBrains 插件骨架，核心差异化：**用户自己选厂商、自填 API Key**，
不绑定任何单一模型服务商，Key 只加密存储在本机。

## 已实现功能

- ✅ 多"模型配置档"管理（面板内点"＋/✎/🗑"直接增删改，无需跳系统设置）
- ✅ 支持三类协议，覆盖国内外主流模型：
  - **OpenAI 兼容协议**：DeepSeek、通义千问(Qwen)、Kimi(Moonshot)、智谱GLM、MiniMax、OpenAI 官方、
    以及大部分开源模型托管平台，只要暴露 `/v1/chat/completions` 接口都能直接用
  - **Anthropic**：Claude 官方 Messages API
  - **Gemini**：Google 官方 API
- ✅ API Key 用 IntelliJ 自带的 `PasswordSafe` 加密存储在本机，不经过任何第三方服务器
- ✅ 聊天气泡样式：你的消息在右侧（蓝色气泡），AI 回复在左侧（灰色气泡），仿 CodeBuddy 风格
- ✅ 支持流式输出（打字机效果）
- ✅ **输入框上方图标工具栏**（仿 CodeBuddy 布局）：
  - `@` 引用项目里的文件（弹出可搜索列表）
  - `Img` 上传本地图片，作为多模态消息发给支持视觉的模型（OpenAI/Anthropic/Gemini 协议均已适配图片格式）
  - `File` 从磁盘任意位置选择一个文本文件，内容作为上下文（不要求文件在项目索引里）
  - `...` 更多菜单：清空对话记录 / 清除已附加的文件和图片
  - 选好的图片/文件会在输入框上方显示成"附件条"，可以单个移除
- ✅ **`@` 选择文件**：输入框里敲 `@` 或点工具栏 `@` 图标，弹出项目文件搜索列表，选中后自动把文件内容作为上下文发给模型
- ✅ **代码自动应用 + 保留/撤销/查看变更**（仿 CodeBuddy 的核心体验）：
  - AI 回复里只有一段代码时，会**自动**写入目标文件（不需要手动点击）
  - 目标文件判断优先级：本轮用 `@` 引用的唯一文件 > 当前打开的编辑器文件；两者都没有则退回"手动应用"链接
  - 应用后回复下方出现 **保留 / 撤销 / 查看变更** 三个链接：
    - **保留**：确认这次修改，无需其他操作（文件已经改好了）
    - **撤销**：只回退**这一步**修改，不会影响这次之后你在同一个文件上做的其他修改
      （原理是调用 IntelliJ 该文件自己的撤销历史栈，等价于对着这次改动按 Ctrl+Z，
      如果这次改动之后又有更新的修改压在上面，会先撤销最上面那个，符合撤销栈的正常逻辑）
    - **查看变更**：用 IntelliJ 原生 Diff 面板并排对比"修改前/修改后"
  - 回复里有多段代码时（无法安全判断该改哪个文件），走原来的手动"应用第N段代码"链接，避免误改
- ✅ 编辑器右键菜单"发送选中代码到 AI 对话"
- ❌ 暂不包含：Skill、代码库全文检索/RAG、Agent 自动改多文件（见"后续可扩展"）

## 项目结构

```
ai-coder-plugin/
├── build.gradle.kts          # Gradle 构建配置（IntelliJ Platform Gradle Plugin）
├── settings.gradle.kts
├── gradle.properties
└── src/main/
    ├── kotlin/com/aicoder/plugin/
    │   ├── model/             # 模型适配层：统一接口 + 各厂商实现
    │   │   ├── Models.kt
    │   │   ├── ModelProvider.kt
    │   │   ├── OpenAICompatibleProvider.kt
    │   │   ├── AnthropicProvider.kt
    │   │   ├── GeminiProvider.kt
    │   │   └── ProviderFactory.kt
    │   ├── settings/          # 设置页 + Token 加密存储
    │   │   ├── AiCoderSettingsState.kt
    │   │   ├── AiCoderSettingsConfigurable.kt
    │   │   └── TokenStorage.kt
    │   ├── toolwindow/        # 聊天 UI
    │   │   ├── ChatToolWindowFactory.kt
    │   │   └── ChatPanel.kt
    │   └── actions/
    │       └── SendSelectionToChatAction.kt
    └── resources/META-INF/plugin.xml
```

## 如何运行 / 调试

1. 用 **IntelliJ IDEA**（Community 或 Ultimate 均可）打开这个项目目录，需要装好 **Plugin DevKit** 插件（一般默认自带）
2. 首次打开会自动走 Gradle 同步，下载 IntelliJ Platform SDK（`intellij { version.set("2024.1") }`），需要联网，第一次会比较慢
3. 右侧 Gradle 面板 -> Tasks -> intellij -> `runIde`，或者直接在终端执行：
   ```bash
   ./gradlew runIde
   ```
   会拉起一个装好本插件的"沙盒" IDE 实例，在里面右下角状态栏或右侧栏能看到 "AI Coder" 工具窗口
4. 打包发布用：
   ```bash
   ./gradlew buildPlugin
   ```
   产物在 `build/distributions/*.zip`，可以在 IDE 里 Settings -> Plugins -> 从磁盘安装

## 使用步骤

1. 打开右侧 "AI Coder" 工具窗口
2. 点顶部工具栏的"＋"，直接在弹出的小窗口里填：
   - 配置名称：DeepSeek
   - 协议类型：OpenAI 兼容协议
   - Base URL：`https://api.deepseek.com`（带不带结尾 `/v1` 都可以，程序会自动识别拼接）
   - 模型名称：`deepseek-chat`
   - API Key：填你自己申请的 Key
3. 点 OK 保存后即可直接对话，不需要跳系统 Settings
4. 想切换模型：顶部下拉框直接选另一个配置档
5. 想改配置：先在下拉框选中，再点"✎"编辑（Key 留空 = 不修改原来存的）
6. 想删配置：选中后点"🗑"
7. 编辑器里选中一段代码，右键 -> "发送选中代码到 AI 对话"，会自动把代码带入输入框
8. 输入框里敲 `@`（或点工具栏 `@` 图标），会弹出项目文件搜索框，输入关键字过滤，双击或回车选中，
   文件名会插入到输入框（如 `@Main.java`），发送时会自动把该文件内容作为上下文一起发给模型
9. 点工具栏 `Img` 可以选一张本地图片附加到这条消息，会以气泡形式显示已附加的图片，发送后清空；
   注意：只有支持视觉理解的模型（如 GPT-4o、Claude、Gemini 系列）才能真正"看懂"图片，
   纯文本模型收到图片消息可能会报错或直接忽略
10. 点工具栏 `File` 可以从磁盘任意位置选一个文本文件（不需要是项目里的文件），内容会作为上下文发送
11. 点工具栏 `...` 可以清空当前对话记录，或者清除已经选好但还没发送的文件/图片附件
12. AI 回复如果只包含**一段代码**，会自动应用到目标文件（优先用你本轮 `@` 到的文件，没有的话用当前
    打开的编辑器文件），应用后可以点：
    - **保留** - 确认这次修改
    - **撤销** - 只撤销这一步，不影响之前对这个文件做的其他修改（基于该文件自己的撤销栈）
    - **查看变更** - 打开 IntelliJ 原生 Diff 面板对比修改前后
    如果回复里有**多段代码**，为了避免自动改错文件，会退回手动"应用第N段代码"链接，
    点击后有选中内容替换选中内容、没有则插入光标处

> 备注：应用级设置页（Settings -> Tools -> AI Coder）仍然保留，两边操作的是同一份数据，
> 互相同步，喜欢用系统设置页管理的话也可以继续用，纯粹是多一个入口。

## 常见 404 排查

如果调用报 `HTTP 404`，最常见原因是 **Base URL 里的路径和程序自动拼接的路径重复了**。
例如 DeepSeek 官方文档给的地址常写成 `https://api.deepseek.com/v1`（已经带 `/v1`），
现在程序会自动识别：
- 你填 `https://api.deepseek.com` → 自动拼成 `.../v1/chat/completions`
- 你填 `https://api.deepseek.com/v1` → 自动拼成 `.../v1/chat/completions`（不会重复拼 `/v1`）
- 你填完整地址 `https://api.deepseek.com/v1/chat/completions` → 原样使用

如果还是 404/401 等错误，现在报错信息里会直接带上"请求地址"和"服务器返回内容"，
可以照着返回内容里的信息（比如 `model not found` / `invalid api key`）进一步排查。

## 常用厂商的 Base URL 参考（自行确认最新地址）

| 厂商 | 协议类型 | Base URL 示例 |
|---|---|---|
| OpenAI | OpenAI 兼容 | https://api.openai.com |
| DeepSeek | OpenAI 兼容 | https://api.deepseek.com |
| 通义千问 (DashScope兼容模式) | OpenAI 兼容 | https://dashscope.aliyuncs.com/compatible-mode |
| Kimi (Moonshot) | OpenAI 兼容 | https://api.moonshot.cn |
| 智谱 GLM | OpenAI 兼容 | https://open.bigmodel.cn/api/paas/v4（部分接口路径略有差异，需按官方文档核对） |
| Anthropic Claude | Anthropic | https://api.anthropic.com |
| Google Gemini | Gemini | https://generativelanguage.googleapis.com |

## 后续可扩展方向（对齐 Cursor / CodeBuddy 的更多能力）

这些都是纯工程工作，不需要自己训练模型：

1. **代码补全（Tab 联想）**：监听光标位置变化，取前后文本做 FIM 请求（需模型支持 Fill-in-Middle，如 DeepSeek）
2. **真正的 Diff 预览**：目前"应用到编辑器"是直接替换/插入，下一步可以升级成 IntelliJ 的 `Diff` API 做并排对比，
   用户逐块确认后再写入，比现在的"整段替换 + 二次确认"更精细
3. **代码库检索 / RAG**：本地起一个轻量向量库（如 SQLite + 向量扩展，或纯 Java 实现的 HNSW），配合任意 embedding API 做全项目问答
4. **Agent 模式（工具调用循环）**：给模型暴露"读文件/写文件/跑命令"等工具描述，用 OpenAI/Anthropic 的 tool_use 格式让模型自主决策，插件负责执行并把结果回传，循环直到任务完成
5. **Skill 体系**：把常用的 prompt 模板/工作流固化成可复用的"技能"，可后续再加

## 已知限制（当前 MVP）

- 聊天界面用 Swing + `JEditorPane` 渲染，代码块没有语法高亮（后续可以升级用 JCEF 内嵌浏览器 + highlight.js）
- 没有做 token 计数和上下文自动截断，长对话可能超出模型上下文窗口
- 没有取消/重试按钮
- Gemini 的 embedding/多模态输入暂未支持，仅纯文本对话
