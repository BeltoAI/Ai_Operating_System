package com.agentos.shell.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.BriefStore
import com.agentos.shell.tools.MemoryStore
import com.agentos.shell.tools.NotificationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LockScreen(modifier: Modifier = Modifier, onEnter: () -> Unit) {
    val ctx = LocalContext.current
    val notes = NotificationStore.notes

    // Generate an AI brief from the real notifications (cached, max once / 5 min).
    LaunchedEffect(notes.size) {
        if (notes.isNotEmpty() && AgentClient.hasKey() && BriefStore.stale() && !BriefStore.loading) {
            BriefStore.loading = true
            val items = notes.take(8).map {
                "${it.app}: ${(if (it.title.isNotBlank()) it.title else "")} ${it.text}".trim()
            }
            val memory = MemoryStore.about(ctx)
            val lines = withContext(Dispatchers.IO) { AgentClient.brief(items, memory) }
            if (lines.isNotEmpty()) { BriefStore.lines = lines; BriefStore.markGenerated() }
            BriefStore.loading = false
        }
    }

    val brief = BriefStore.lines
    Column(
        modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null
        ) { onEnter() }
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("9:41", fontSize = T.time, color = T.ink, fontWeight = FontWeight.Light)
            Text("82%", fontSize = T.caption, color = T.inkFaint)
        }
        Spacer(Modifier.height(18.dp))
        Text(
            if (notes.isEmpty()) "All clear."
            else "You have ${notes.size} things that matter.",
            fontSize = 19.sp, color = T.ink
        )
        Spacer(Modifier.height(14.dp))
        val priorities: List<String> = if (brief.isNotEmpty()) brief
            else notes.take(3).map { n ->
                val line = (if (n.title.isNotBlank()) n.title else n.text).ifBlank { n.app }
                "${n.app}: $line"
            }
        priorities.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 7.dp)) {
                OrangeDot()
                Spacer(Modifier.width(10.dp))
                Text(p, fontSize = T.small, color = T.inkSoft)
            }
            Hairline()
        }
        Spacer(Modifier.weight(1f))
        Column(Modifier.fillMaxWidth().clickable { onEnter() },
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("●", color = T.accent)
            Spacer(Modifier.height(8.dp))
            Text("hold to speak", fontSize = T.small, color = T.inkSoft)
        }
    }
}
