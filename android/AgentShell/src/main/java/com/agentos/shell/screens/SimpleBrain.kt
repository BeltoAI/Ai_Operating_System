package com.agentos.shell.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentos.shell.theme.T
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One box, one answer.
 *
 * The full Brain screen is where the phone is configured: forty collapsible cards covering models,
 * spending, personas, sync, integrations. All of it matters to somebody building a company on this
 * and none of it should ever be the first thing an eighty-year-old sees when they press the middle
 * button.
 *
 * What is genuinely useful to them is the part underneath: a phone that remembers. *What did the
 * doctor say about the tablets? When is Carlos's birthday? Where did we go in June?* So this is the
 * question, the answer, and nothing else — the same recall the full app uses, with the machinery
 * for configuring it taken away rather than reimplemented.
 */
@Composable
fun SimpleBrain(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val examples = remember {
        listOf("What did I do last week?",
               "When is my next appointment?",
               "What did I say I would do?")
    }

    fun ask(text: String) {
        if (text.isBlank() || busy) return
        busy = true; answer = ""
        scope.launch {
            val a = withContext(Dispatchers.IO) {
                try {
                    val mem = com.agentos.shell.tools.BrainContext.build(ctx, text).take(4000)
                    com.agentos.shell.tools.AgentClient.complete(
                        "Answer from the person's own records below. Two or three short " +
                        "sentences, plain words, no lists, no markdown. If the records do not say, " +
                        "reply exactly: I don't have anything about that.",
                        "Records:\n$mem\n\nQuestion: $text", 300)
                } catch (e: Exception) { "" }
            }
            busy = false
            answer = a.ifBlank { "I don't have anything about that." }
        }
    }

    Column(modifier.fillMaxSize().background(T.bg)
        .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(26.dp))
        Text("What would you like to know?", fontSize = 28.sp, color = T.ink,
            fontWeight = FontWeight.Medium, lineHeight = 35.sp)

        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(T.bgElevated)
            .padding(horizontal = 20.dp, vertical = 22.dp)) {
            if (q.isEmpty()) Text("Ask me anything you told me before",
                fontSize = 20.sp, color = T.inkFaint, lineHeight = 27.sp)
            BasicTextField(q, { q = it },
                textStyle = TextStyle(color = T.ink, fontSize = 21.sp, lineHeight = 29.sp),
                modifier = Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(if (q.isBlank()) T.hairline else T.accent)
            .clickable(enabled = q.isNotBlank() && !busy) { ask(q) }
            .padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
            Text(if (busy) "…" else "Ask", fontSize = 22.sp, color = Color.White,
                fontWeight = FontWeight.Medium)
        }

        if (answer.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(T.bgElevated).padding(20.dp)) {
                Text(answer, fontSize = 21.sp, color = T.ink, lineHeight = 30.sp)
            }
        } else if (!busy) {
            // Three real questions, because an empty box with no examples is a box nobody uses.
            Spacer(Modifier.height(26.dp))
            Text("For example:", fontSize = 17.sp, color = T.inkFaint)
            Spacer(Modifier.height(10.dp))
            examples.forEach { ex ->
                Box(Modifier.fillMaxWidth().padding(bottom = 10.dp)
                    .clip(RoundedCornerShape(16.dp)).background(T.bgElevated)
                    .clickable { q = ex; ask(ex) }
                    .padding(horizontal = 18.dp, vertical = 20.dp)) {
                    Text(ex, fontSize = 19.sp, color = T.inkSoft, lineHeight = 26.sp)
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}
