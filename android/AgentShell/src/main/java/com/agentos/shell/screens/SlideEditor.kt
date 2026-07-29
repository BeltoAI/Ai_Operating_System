package com.agentos.shell.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.DocForge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The deck, as something you can actually change.
 *
 * What existed was generate-and-open: a PDF handed to whatever app on the phone would display it.
 * That is a preview in the sense that you can see it, and useless in the sense that you cannot do
 * anything about what you see. If slide 4 says the wrong number, the only move was to describe the
 * problem in a sentence and hope the rewrite fixed that and broke nothing else.
 *
 * A PDF is output, though — not a document. You cannot move a bullet inside it. The thing to edit
 * is the deck's own words, which were already being kept so the model could revise them and were
 * never shown to the person whose deck it is.
 *
 * So both hands work here, because people use both:
 *
 *  - **By hand.** Every title and every bullet is a text field. Add a bullet, delete one, add a
 *    slide, remove a slide, reorder. For fixing a number or cutting a line this is far faster than
 *    describing the change, and it cannot misunderstand.
 *  - **By prompt.** "Add a slide on pricing", "make slide 3 punchier" — for the changes that are
 *    genuinely easier to say than to type, and which the model is good at.
 *
 * The preview is drawn here rather than rendered, so it is instant and always matches what has been
 * typed. It uses the same geometry the PDF does — the accent rule, the type hierarchy, the dot
 * bullets, the number in the corner — so what is on screen is what comes out. A preview that merely
 * approximates the output is a preview you stop trusting the first time they differ.
 */
@Composable
fun SlideEditor(
    modifier: Modifier = Modifier,
    onDone: (String) -> Unit,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    /** One slide, while it is being edited. */
    data class Slide(val title: String, val bullets: List<String>)

    fun parse(src: String): List<Slide> = src.split(Regex("(?m)^===+\\s*$"))
        .map { it.trim() }.filter { it.isNotEmpty() }
        .map { block ->
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            Slide(lines.firstOrNull().orEmpty().removePrefix("#").trim(),
                lines.drop(1).map { it.removePrefix("-").removePrefix("•").trim() }
                    .filter { it.isNotEmpty() })
        }

    fun serialise(list: List<Slide>) = list.joinToString("\n===\n") { s ->
        (s.title + "\n" + s.bullets.joinToString("\n") { "- $it" }).trim()
    }

    var slides by remember { mutableStateOf(parse(DocForge.draftContent(ctx))) }
    // The deck's OWN palette, as it was actually built — so this is a preview and not a lookalike.
    val palette = remember {
        val (a, bg, ink) = DocForge.lastPalette(ctx)
        fun col(h: String) = try { Color(android.graphics.Color.parseColor("#" + h.trimStart('#'))) }
            catch (e: Exception) { T.accent }
        Triple(col(a), col(bg), col(ink))
    }
    val deckAccent = palette.first; val deckBg = palette.second; val deckInk = palette.third
    var busy by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(0) }

    /** Rebuild the PDF from whatever is on screen right now. */
    fun save(then: (String) -> Unit = {}) {
        busy = true
        scope.launch {
            val made = withContext(Dispatchers.IO) {
                try { DocForge.rebuild(ctx, serialise(slides)) } catch (e: Exception) { null }
            }
            busy = false
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            if (made != null && made.ok && made.uri != null) then(made.uri.toString())
            else note = "Couldn't rebuild the deck."
        }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(14.dp))
        ScreenHeader("Slides", onBack)

        if (slides.isEmpty()) {
            Spacer(Modifier.height(40.dp))
            Text("No deck to edit yet.", fontSize = T.small, color = T.inkFaint)
            return@Column
        }

        Spacer(Modifier.height(6.dp))
        Text("${slides.size} slides · tap one to edit", fontSize = T.caption, color = T.inkFaint)
        Spacer(Modifier.height(14.dp))

        slides.forEachIndexed { i, s ->
            val isOpen = open == i
            val lift by animateFloatAsState(if (isOpen) 1f else 0.985f,
                spring(dampingRatio = 0.82f, stiffness = 300f), label = "l")

            // ── The slide as it will print ──
            Box(
                Modifier.fillMaxWidth().aspectRatio(1.414f)
                    .graphicsLayer { scaleX = lift; scaleY = lift }
                    .clip(RoundedCornerShape(12.dp)).background(deckBg)
                    .clickable { open = if (isOpen) -1 else i }
                    .padding(horizontal = 18.dp, vertical = 16.dp)
            ) {
                if (i == 0) {
                    Box(Modifier.width(5.dp).fillMaxSize().background(deckAccent))
                    Column(Modifier.align(Alignment.BottomStart).padding(start = 14.dp)) {
                        Text(s.title, fontSize = 21.sp, color = deckInk,
                            fontWeight = FontWeight.Bold, lineHeight = 24.sp)
                        s.bullets.firstOrNull()?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(it, fontSize = 11.sp, color = deckInk.copy(alpha = 0.62f),
                                lineHeight = 15.sp)
                        }
                    }
                } else {
                    Column {
                        Box(Modifier.width(26.dp).height(3.dp).clip(RoundedCornerShape(2.dp))
                            .background(deckAccent))
                        Spacer(Modifier.height(10.dp))
                        Text(s.title, fontSize = 15.sp, color = deckInk,
                            fontWeight = FontWeight.Bold, lineHeight = 18.sp)
                        Spacer(Modifier.height(10.dp))
                        s.bullets.take(6).forEach { b ->
                            Row(Modifier.padding(bottom = 6.dp)) {
                                Box(Modifier.padding(top = 5.dp).size(4.dp)
                                    .clip(RoundedCornerShape(2.dp)).background(deckAccent))
                                Spacer(Modifier.width(8.dp))
                                Text(b, fontSize = 10.sp, color = deckInk.copy(alpha = 0.9f),
                                    lineHeight = 14.sp)
                            }
                        }
                    }
                    Text("$i", fontSize = 9.sp, color = deckInk.copy(alpha = 0.33f),
                        modifier = Modifier.align(Alignment.BottomEnd))
                }
            }

            // ── The same slide, as fields ──
            if (isOpen) {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(T.bg)
                    .padding(horizontal = 14.dp, vertical = 11.dp)) {
                    BasicTextField(s.title,
                        { v -> slides = slides.toMutableList().also { it[i] = s.copy(title = v) } },
                        textStyle = TextStyle(color = T.ink, fontSize = T.small,
                            fontWeight = FontWeight.Medium),
                        modifier = Modifier.fillMaxWidth())
                }
                s.bullets.forEachIndexed { bi, b ->
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(T.bg)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        BasicTextField(b, { v ->
                            slides = slides.toMutableList().also {
                                it[i] = s.copy(bullets = s.bullets.toMutableList().also { l -> l[bi] = v })
                            }
                        },
                            textStyle = TextStyle(color = T.ink, fontSize = T.caption,
                                lineHeight = 19.sp),
                            modifier = Modifier.weight(1f))
                        Text("✕", fontSize = T.caption, color = T.inkFaint,
                            modifier = Modifier.padding(start = 10.dp).clickable {
                                slides = slides.toMutableList().also {
                                    it[i] = s.copy(bullets = s.bullets.filterIndexed { x, _ -> x != bi })
                                }
                            })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("＋ Bullet") {
                        slides = slides.toMutableList().also {
                            it[i] = s.copy(bullets = s.bullets + "")
                        }
                    }
                    Chip("＋ Slide after") {
                        slides = slides.toMutableList().also {
                            it.add(i + 1, Slide("New slide", listOf("")))
                        }
                        open = i + 1
                    }
                    if (i > 0) Chip("Move up") {
                        slides = slides.toMutableList().also {
                            val m = it.removeAt(i); it.add(i - 1, m)
                        }
                        open = i - 1
                    }
                    if (slides.size > 1) Chip("Delete") {
                        slides = slides.filterIndexed { x, _ -> x != i }
                        open = -1
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        // ── By prompt, for the changes easier said than typed ──
        Text("OR JUST SAY IT", fontSize = 9.sp, color = T.inkFaint,
            fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(T.bg)
            .padding(horizontal = 14.dp, vertical = 11.dp)) {
            if (ask.isEmpty())
                Text("e.g. add a slide on pricing, or make slide 3 punchier",
                    fontSize = T.caption, color = T.inkFaint)
            BasicTextField(ask, { ask = it },
                textStyle = TextStyle(color = T.ink, fontSize = T.caption),
                modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
        Chip(if (busy) "working…" else "Rewrite it", enabled = !busy && ask.isNotBlank()) {
            busy = true
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            scope.launch {
                // Save what has been typed FIRST, so a hand edit is not silently discarded by the
                // rewrite that follows it — refine works from the stored content, and losing an
                // edit you just made is the fastest way to stop trusting a tool.
                val edited = serialise(slides)
                val made = withContext(Dispatchers.IO) {
                    try {
                        DocForge.rebuild(ctx, edited)
                        DocForge.refine(ctx, ask, "pdf")
                    } catch (e: Exception) { null }
                }
                if (made != null && made.ok) {
                    slides = parse(DocForge.draftContent(ctx))
                    ask = ""; note = ""
                } else note = "Couldn't make that change."
                busy = false
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }

        if (note.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(note, fontSize = T.caption, color = T.danger)
        }

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (busy) "…" else "Open the PDF", fontSize = T.small, color = T.inkSoft,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(999.dp))
                    .background(T.bgElevated)
                    .clickable(enabled = !busy) {
                        save { uri ->
                            try { DocForge.open(ctx, uri, DocForge.lastTitle(ctx) + ".pdf") }
                            catch (e: Exception) {}
                        }
                    }
                    .padding(vertical = 14.dp))
            Text(if (busy) "…" else "Use it", fontSize = T.small, color = Color.White,
                fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(999.dp))
                    .background(T.accent)
                    .clickable(enabled = !busy) { save { uri -> onDone(uri) } }
                    .padding(vertical = 14.dp))
        }
        Spacer(Modifier.height(50.dp))
    }
}

@Composable
private fun Chip(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(999.dp)).background(T.bgElevated)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 13.dp, vertical = 9.dp)
    ) {
        Text(label, fontSize = T.caption, color = if (enabled) T.inkSoft else T.inkFaint,
            maxLines = 1, softWrap = false)
    }
}
