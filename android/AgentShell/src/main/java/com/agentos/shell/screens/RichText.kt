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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
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
 * A markdown table, rendered as a real table: a weighted header row, hairline rules, and alternating
 * row tint. Columns share width by weight rather than a fixed grid, so a two-column comparison and a
 * five-column figure table both look deliberate.
 *
 * Long cells wrap instead of being clipped — truncating a number is worse than a taller row.
 */
@Composable
private fun MarkdownTable(header: List<String>, rows: List<List<String>>) {
    val cols = maxOf(header.size, rows.maxOfOrNull { it.size } ?: 0)
    if (cols == 0) return
    fun padded(r: List<String>) = List(cols) { r.getOrElse(it) { "" } }
    Spacer(Modifier.height(8.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(T.bgElevated)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
            padded(header).forEach { c ->
                Text(inlineMd(c), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = T.ink,
                    modifier = Modifier.weight(1f).padding(end = 8.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(T.hairline))
        rows.forEachIndexed { idx, r ->
            Row(Modifier.fillMaxWidth()
                .background(if (idx % 2 == 1) T.bg else androidx.compose.ui.graphics.Color.Transparent)
                .padding(horizontal = 10.dp, vertical = 7.dp)) {
                padded(r).forEach { c ->
                    Text(inlineMd(c), fontSize = 13.sp, color = T.inkSoft,
                        modifier = Modifier.weight(1f).padding(end = 8.dp))
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

/**
 * Renders an answer as elegant markdown — headings, bold/italic/code, tables, bullet and numbered
 * lists, block quotes and dividers — so EVERY reply reads as a polished document, not a wall of text.
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val bullet = Regex("^\\s*[-*•]\\s+")
    val numbered = Regex("^\\s*(\\d{1,2})[.)]\\s+")
    val lines = text.trim().lines()
    val clip = LocalClipboardManager.current
    Column(modifier.fillMaxWidth()) {
        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            // Fenced code block: ``` … ``` → monospace panel (language label optional after the fence).
            if (raw.trim().startsWith("```")) {
                val code = StringBuilder(); i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) { code.append(lines[i]).append("\n"); i++ }
                i++ // skip closing fence
                val codeStr = code.toString().trimEnd()
                Spacer(Modifier.height(6.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(T.bgElevated).padding(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text("Copy", fontSize = 12.sp, color = T.accent,
                            modifier = Modifier.clickable { clip.setText(AnnotatedString(codeStr)) })
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(codeStr, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = T.ink)
                }
                Spacer(Modifier.height(6.dp))
                continue
            }
            val line = raw.trim()
            // GitHub-style table. There was NO table support at all, so every table the model produced
            // arrived as a wall of literal pipes and dashes — the single ugliest thing in the app, and
            // the models emit tables constantly for comparisons and figures.
            if (line.startsWith("|") && line.indexOf('|', 1) > 0 && i + 1 < lines.size &&
                Regex("^\\|?[\\s:|-]*-[\\s:|-]*\\|?$").matches(lines[i + 1].trim()) &&
                lines[i + 1].contains("-")) {
                fun cells(l: String) = l.trim().trim('|').split("|").map { it.trim() }
                val header = cells(raw)
                i += 2
                val rows = ArrayList<List<String>>()
                while (i < lines.size && lines[i].trim().startsWith("|")) { rows.add(cells(lines[i])); i++ }
                MarkdownTable(header, rows)
                continue
            }
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
