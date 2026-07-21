package com.agentos.shell.screens

import android.annotation.SuppressLint
import android.graphics.Color as AColor
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.agentos.shell.theme.T

/**
 * True when the text contains LaTeX math delimiters worth rendering with MathJax.
 *
 * THE MONEY BUG: `$...$` is both LaTeX inline math and the way everyone writes prices. A reply
 * containing "between $500 and $2K" has two dollar signs on one line, so the old pattern matched
 * "500 and $" as math — the whole answer got handed to MathJax and rendered as italic gibberish.
 * Any answer mentioning two prices was mangled, which is most financial answers.
 *
 * A single-dollar span only counts as math now if it does NOT look like currency: money is a digit
 * (or a space then a digit) straight after the `$`, so we require the content to be non-money-ish.
 * Display math ($$…$$) and the unambiguous \( \) / \[ \] forms are untouched — they're never prices.
 */
fun hasMath(s: String): Boolean {
    if (Regex("\\$\\$[^$]+\\$\\$|\\\\\\((.|\\n)+?\\\\\\)|\\\\\\[(.|\\n)+?\\\\\\]").containsMatchIn(s)) return true
    // Inline $…$: reject anything where either delimiter is doing duty as a currency symbol.
    return Regex("(?<![A-Za-z0-9$])\\$([^$\\n]{1,120})\\$(?![0-9])").findAll(s).any { m ->
        val body = m.groupValues[1]
        val looksLikeMoney = Regex("^\\s*[\\d,.]").containsMatchIn(body) ||   // "$500 and 2K$"
            Regex("[\\d,]{2,}\\s*(k|m|bn?|million|billion|usd|eur)?\\s*$", RegexOption.IGNORE_CASE)
                .containsMatchIn(body) && !body.contains("\\")
        // Real math almost always carries a LaTeX command, an operator, or a superscript/subscript.
        val looksLikeMath = Regex("\\\\[a-zA-Z]+|[\\^_{}]|\\\\frac|=|\\+|/|\\\\times").containsMatchIn(body)
        looksLikeMath && !looksLikeMoney
    }
}

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/** Minimal Markdown → HTML (bold/italic/code, code blocks, headings, lists, quotes, paragraphs). Math
 *  delimiters are left untouched for MathJax. */
private fun mdToHtml(text: String): String {
    val out = StringBuilder()
    val lines = text.trim().lines()
    var i = 0
    var inList = false
    fun closeList() { if (inList) { out.append("</ul>"); inList = false } }
    fun inline(s: String): String {
        var r = esc(s)
        r = Regex("```").replace(r, "")
        r = Regex("\\*\\*(.+?)\\*\\*").replace(r) { "<b>${it.groupValues[1]}</b>" }
        r = Regex("(?<!\\*)\\*(?!\\*)(.+?)\\*(?!\\*)").replace(r) { "<i>${it.groupValues[1]}</i>" }
        r = Regex("`([^`]+)`").replace(r) { "<code>${it.groupValues[1]}</code>" }
        return r
    }
    while (i < lines.size) {
        val raw = lines[i]
        if (raw.trim().startsWith("```")) {
            closeList(); val code = StringBuilder(); i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) { code.append(esc(lines[i])).append("\n"); i++ }
            i++
            out.append("<pre><code>").append(code.toString().trimEnd()).append("</code></pre>")
            continue
        }
        val line = raw.trim()
        when {
            line.isEmpty() -> { closeList(); out.append("<div class='sp'></div>") }
            line.startsWith("### ") -> { closeList(); out.append("<h3>").append(inline(line.removePrefix("### "))).append("</h3>") }
            line.startsWith("## ") -> { closeList(); out.append("<h2>").append(inline(line.removePrefix("## "))).append("</h2>") }
            line.startsWith("# ") -> { closeList(); out.append("<h1>").append(inline(line.removePrefix("# "))).append("</h1>") }
            line.startsWith("> ") -> { closeList(); out.append("<blockquote>").append(inline(line.removePrefix("> "))).append("</blockquote>") }
            Regex("^\\s*[-*•]\\s+").containsMatchIn(line) -> {
                if (!inList) { out.append("<ul>"); inList = true }
                out.append("<li>").append(inline(line.replaceFirst(Regex("^\\s*[-*•]\\s+"), ""))).append("</li>")
            }
            else -> { closeList(); out.append("<p>").append(inline(line)).append("</p>") }
        }
        i++
    }
    closeList()
    return out.toString()
}

/**
 * Renders a message with real LaTeX (MathJax) plus light Markdown, in a transparent, self-sizing WebView
 * that reports its content height back so it fits inside the scrolling chat. Needs network for the MathJax
 * CDN; offline, the raw text still shows.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MathText(text: String, modifier: Modifier = Modifier) {
    val ink = "#%06X".format(0xFFFFFF and T.ink.toArgb())
    val accent = "#%06X".format(0xFFFFFF and T.accent.toArgb())
    val panel = "#%06X".format(0xFFFFFF and T.bgElevated.toArgb())
    var heightDp by remember(text) { mutableStateOf(0) }
    val html = remember(text) {
        """
        <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
        <script>window.MathJax={tex:{inlineMath:[['${'$'}','${'$'}'],['\\(','\\)']],displayMath:[['${'$'}${'$'}','${'$'}${'$'}'],['\\[','\\]']]},startup:{ready:function(){MathJax.startup.defaultReady();MathJax.startup.promise.then(reportH);}}};</script>
        <script src="https://cdnjs.cloudflare.com/ajax/libs/mathjax/3.2.2/es5/tex-mml-chtml.js"></script>
        <style>
          html,body{margin:0;padding:0;background:transparent;color:$ink;
            font-family:-apple-system,Roboto,system-ui,sans-serif;font-size:16px;line-height:1.5;word-wrap:break-word;}
          p{margin:0 0 2px} .sp{height:7px} h1{font-size:22px;margin:8px 0 2px} h2{font-size:19px;margin:8px 0 2px}
          h3{font-size:16px;font-weight:600;margin:6px 0 2px} ul{margin:2px 0 2px 20px;padding:0} li{margin:0 0 2px}
          code{font-family:monospace;color:$accent} pre{background:$panel;padding:10px;border-radius:10px;overflow-x:auto}
          pre code{color:$ink} blockquote{border-left:3px solid $accent;margin:4px 0;padding-left:10px;opacity:.85}
          mjx-container{overflow-x:auto;max-width:100%}
        </style></head>
        <body><div id="c">${mdToHtml(text)}</div>
        <script>function reportH(){try{var h=document.body.scrollHeight;if(window.AndroidBridge)AndroidBridge.setH(h);}catch(e){}}
        window.addEventListener('load',function(){setTimeout(reportH,150);setTimeout(reportH,600);});</script>
        </body></html>
        """.trimIndent()
    }
    AndroidView(
        modifier = modifier.fillMaxWidth().then(if (heightDp > 0) Modifier.height(heightDp.dp) else Modifier.heightIn(min = 40.dp)),
        factory = { c ->
            WebView(c).apply {
                settings.javaScriptEnabled = true
                setBackgroundColor(AColor.TRANSPARENT)
                addJavascriptInterface(object {
                    @JavascriptInterface fun setH(h: Int) { post { heightDp = h + 4 } }
                }, "AndroidBridge")
                loadDataWithBaseURL("https://localhost/", html, "text/html", "utf-8", null)
            }
        },
        update = { wv -> if (wv.tag != html) { wv.tag = html; wv.loadDataWithBaseURL("https://localhost/", html, "text/html", "utf-8", null) } }
    )
}
