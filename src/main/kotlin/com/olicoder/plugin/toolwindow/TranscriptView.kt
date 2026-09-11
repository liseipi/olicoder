package com.olicoder.plugin.toolwindow

import com.google.gson.Gson
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import javax.swing.JComponent

/**
 * 用 JCEF（IDE 内嵌的 Chromium）渲染聊天记录，替代原来 JEditorPane + HTMLEditorKit 的方案。
 * 换掉之后可以用 highlight.js 做真正的代码语法高亮，圆角气泡、间距这些也都是普通 CSS，比 Swing 布局好调很多。
 *
 * 对上层（ChatPanel）暴露的用法尽量保持和以前一样：还是整段塞一份拼好的 HTML body 内容进来（不用带 <html>/<body>），
 * 内部 <a href="xxx">…</a> 超链接被点击后，会通过 onLinkClicked 回调把 href 原样传回 Kotlin，
 * 效果等价于原来 JEditorPane 的 HyperlinkListener，ChatPanel 里 handleHyperlink() 那套 apply:/keep:/undo:/diff: 前缀逻辑完全不用改。
 *
 * highlight.js 不走 CDN：js/css 文件在构建时打包进了插件 resources/webview/ 目录，运行时从 classpath 读出来，
 * 直接内联进页面的 <script>/<style> 标签里，完全离线可用，不依赖任何外部网络请求。
 * 这份 highlight.min.js 是用 highlight.js 的 `lib/common` 入口自己打包的，覆盖了常见的几十种语言
 * （JS/TS/Python/Java/Kotlin/Go/Rust/C系/Shell/SQL/JSON/YAML/Markdown 等）；如果你需要它没覆盖的小众语言，
 * 找我说一声，换个入口重新打包一份就行。
 */
class TranscriptView(private val onLinkClicked: (String) -> Unit) {

    private val browser = JBCefBrowser()
    private val linkQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val gson = Gson()

    @Volatile
    private var pageLoaded = false
    private var pendingHtml: String? = null

    val component: JComponent get() = browser.component

    init {
        linkQuery.addHandler { href ->
            onLinkClicked(href)
            null
        }
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (frame.isMain) {
                    pageLoaded = true
                    pendingHtml?.let { pushHtml(it) }
                    pendingHtml = null
                }
            }
        }, browser.cefBrowser)
        browser.loadHTML(shellHtml())
    }

    /** 整段覆盖式更新聊天区域内容，等价于原来 `transcript.text = wrapHtml(...)` 再重绘 */
    fun setHtml(bodyHtml: String) {
        if (!pageLoaded) {
            pendingHtml = bodyHtml
            return
        }
        pushHtml(bodyHtml)
    }

    private fun pushHtml(bodyHtml: String) {
        // 用 Gson 把整段 HTML 编码成安全的 JS 字符串字面量，避免手写转义漏掉边界情况（反斜杠、</script> 等）
        val encoded = gson.toJson(bodyHtml)
        val script = """
            (function() {
                var root = document.getElementById('oli-root');
                if (!root) return;
                root.innerHTML = $encoded;
                if (window.hljs) {
                    root.querySelectorAll('pre code').forEach(function(el) { hljs.highlightElement(el); });
                }
                window.scrollTo(0, document.body.scrollHeight);
            })();
        """.trimIndent()
        browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url, 0)
    }

    /** 从插件 jar 里的 classpath 资源读取文本（webview/ 目录，打包自 src/main/resources/webview/） */
    private fun loadBundledResourceText(resourcePath: String): String {
        val stream = javaClass.classLoader.getResourceAsStream(resourcePath)
            ?: return "/* 找不到内置资源：$resourcePath，请确认它在 src/main/resources/$resourcePath 下 */"
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            // 极端保险：万一压缩后的 JS/CSS 里恰好出现字面量 </script> 或 </style>，转义掉避免提前闭合标签
            .replace("</script", "<\\/script", ignoreCase = true)
            .replace("</style", "<\\/style", ignoreCase = true)
    }

    private fun shellHtml(): String {
        // linkQuery.inject("href") 生成的 JS 代码会引用当前作用域里名叫 href 的变量，
        // 拼进 oliCall(href) 函数体里，就是把点击到的链接内容回传给 Kotlin 这一侧的 addHandler 回调。
        val clickBridge = linkQuery.inject("href")
        val hljsScript = loadBundledResourceText("webview/highlight.min.js")
        val hljsTheme = loadBundledResourceText("webview/hljs-theme.min.css")
        return """
            <html>
            <head>
              <meta charset="utf-8">
              <style>
                $hljsTheme
                html, body { margin: 0; padding: 0; background: #26282c; }
                body { font-family: "Segoe UI", system-ui, sans-serif; font-size: 12px; color: #dddddd; padding: 4px 10px 18px 10px; }
                a { color: #6cb6ff; text-decoration: none; cursor: pointer; }
                a:hover { text-decoration: underline; }

                /* ---- 消息行：头像 + 气泡，左右布局用 flex 而不是老式 table ---- */
                .msg-row { display: flex; align-items: flex-start; gap: 8px; margin: 14px 0; }
                .msg-row.user { flex-direction: row-reverse; }
                .avatar {
                    flex: 0 0 26px; width: 26px; height: 26px; border-radius: 50%;
                    display: flex; align-items: center; justify-content: center;
                    font-size: 12px; color: #fff; user-select: none;
                }
                .avatar.user-avatar { background: linear-gradient(135deg, #4f8ff0, #2b5278); }
                .avatar.assistant-avatar { background: linear-gradient(135deg, #6b6f76, #43464c); }
                .bubble-col { display: flex; flex-direction: column; max-width: 82%; }
                .msg-row.user .bubble-col { align-items: flex-end; }
                .bubble-name { font-size: 10px; color: #8b8f96; margin: 0 2px 3px 2px; }
                .bubble {
                    padding: 8px 11px; border-radius: 11px; line-height: 1.55;
                    word-wrap: break-word; overflow-wrap: anywhere;
                }
                .bubble.user-bubble {
                    background: linear-gradient(135deg, #3178dd, #2760b0);
                    color: #ffffff; border-top-right-radius: 3px;
                }
                .bubble.assistant-bubble {
                    background: #34373c; color: #e4e4e4; border-top-left-radius: 3px;
                }
                .attach-line { color: #cfe0f5; font-size: 10px; margin-top: 4px; opacity: 0.9; }

                /* ---- 代码块：语言 tag + 复制按钮的头部条，深色卡片 ---- */
                .code-block { margin: 6px 0; border-radius: 8px; overflow: hidden; border: 1px solid #43464c; }
                .code-header {
                    display: flex; align-items: center; justify-content: space-between;
                    background: #26282c; padding: 4px 10px; font-size: 10px; color: #9aa0a8;
                }
                .code-lang { text-transform: lowercase; letter-spacing: 0.3px; }
                .code-actions a { color: #8ab4f8; margin-left: 12px; font-size: 10px; }
                .code-block pre { margin: 0; overflow-x: auto; }
                .code-block pre code.hljs { padding: 10px !important; border-radius: 0; font-size: 11px; line-height: 1.45; }

                /* ---- 系统提示 / 应用变更操作条 / 手动应用链接 ---- */
                .sys-notice { text-align: center; color: #8b8f95; font-size: 11px; margin: 10px 0; font-style: italic; }
                .change-bar, .apply-links {
                    margin: 2px 4px 14px 34px; padding: 6px 10px; font-size: 11px; color: #cfd2d6;
                    background: #2e3136; border-left: 3px solid #4c8bf5; border-radius: 5px;
                }
                .change-bar a, .apply-links a { color: #8ab4f8; margin: 0 3px; }
              </style>
              <script>
                $hljsScript
              </script>
            </head>
            <body>
              <div id="oli-root"></div>
              <script>
                function oliCall(href) {
                  $clickBridge
                }
                document.addEventListener('click', function(e) {
                  var a = e.target.closest('a');
                  if (a) {
                    e.preventDefault();
                    oliCall(a.getAttribute('href'));
                  }
                });
              </script>
            </body>
            </html>
        """.trimIndent()
    }
}
