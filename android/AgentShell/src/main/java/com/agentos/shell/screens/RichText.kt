package com.agentos.shell.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentos.shell.theme.T

/** Parse the inline markdown in a line (**bold**, *italic*, `code`) into a styled string. */
private fun inlineMd(s: String): AnnotatedString = buildAnnotatedString {
    val re = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`|\\*(.+?)\\*|__(.+?)__")
    var last = 0
    for (m in re.findAll(s)) {
        if (m.range.first > last) append(s.substring(last, m.range.first))
        when {
            m.groups[1] != null -> { pushStyle(SpanStyle(fontWeight = FontWeight.Bold)); append(m.groups[1]!!.value); pop() }
            m.groups[2] != null -> { pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = T.accent)); append(m.groups[2]!!.value); pop() }
            m.groups[3] != null -> { pushStyle(SpanStyle(fontStyle = FontStyle.Italic)); append(m.groups[3]!!.value); pop() }
            m.groups[4] != null -> { pushStyle(SpanStyle(fontWeight = FontWeight.Bold)); append(m.groups[4]!!.value); pop() }
        }
        last = m.range.last + 1
    }
    if (last < s.length) append(s.substring(last))
}

/**
 * Renders an answer as elegant markdown — headings, bold/italic/code, bullet and numbered lists, block
 * quotes and dividers — so EVERY reply reads as a polished document instead of a wall of text.
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val bullet = Regex("^\\s*[-*•]\\s+")
    val numbered = Regex("^\\s*(\\d{1,2})[.)]\\s+")
    val lines = text.trim().lines()
    Column(modifier.fillMaxWidth()) {
        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            // Fenced code block: ``` … ``` → monospace panel (language label optional after the fence).
            if (raw.trim().startsWith("```")) {
                val code = StringBuilder(); i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) { code.append(lines[i]).append("\n"); i++ }
                i++ // skip closing fence
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(T.bgElevated).padding(12.dp)) {
                    Text(code.toString().trimEnd(), fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = T.ink)
                }
                Spacer(Modifier.height(6.dp))
                continue
            }
            val line = raw.trim()
            when {
                line.isEmpty() -> Spacer(Modifier.height(7.dp))
                line == "---" || line == "***" || line == "___" -> {
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(T.hairline))
                    Spacer(Modifier.height(6.dp))
                }
                line.startsWith("### ") -> { Spacer(Modifier.height(5.dp)); Text(inlineMd(line.removePrefix("### ")), fontSize = T.body, fontWeight = FontWeight.SemiBold, color = T.ink) }
                line.startsWith("## ") -> { Spacer(Modifier.height(7.dp)); Text(inlineMd(line.removePrefix("## ")), fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = T.ink) }
                line.startsWith("# ") -> { Spacer(Modifier.height(7.dp)); Text(inlineMd(line.removePrefix("# ")), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = T.ink) }
                line.startsWith("> ") -> Row(Modifier.padding(vertical = 3.dp)) {
                    Box(Modifier.width(3.dp).heightIn(min = 18.dp).clip(RoundedCornerShape(999.dp)).background(T.accent))
                    Spacer(Modifier.width(10.dp))
                    Text(inlineMd(line.removePrefix("> ")), fontSize = T.body, color = T.inkSoft, fontStyle = FontStyle.Italic)
                }
                bullet.containsMatchIn(line) -> Row(Modifier.padding(vertical = 2.dp)) {
                    Text("•", color = T.accent, fontSize = T.body, modifier = Modifier.padding(end = 9.dp))
                    Text(inlineMd(line.replaceFirst(bullet, "")), fontSize = T.body, color = T.ink)
                }
                numbered.containsMatchIn(line) -> {
                    val n = numbered.find(line)?.groupValues?.get(1) ?: "•"
                    Row(Modifier.padding(vertical = 2.dp)) {
                        Text("$n.", color = T.accent, fontSize = T.body, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 9.dp))
                        Text(inlineMd(line.replaceFirst(numbered, "")), fontSize = T.body, color = T.ink)
                    }
                }
                else -> Text(inlineMd(line), fontSize = T.body, color = T.ink, modifier = Modifier.padding(vertical = 1.dp))
            }
            i++
        }
    }
}
