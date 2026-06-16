package com.agentos.shell.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.MemoryStore

/**
 * Memory = what the agent knows about you. You write it; the agent uses it to personalize
 * every answer and every reply it drafts. Stored locally on the phone.
 */
@Composable
fun MemoryScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var about by remember { mutableStateOf(MemoryStore.about(ctx)) }
    var saved by remember { mutableStateOf(false) }
    var autonomous by remember { mutableStateOf(MemoryStore.autonomous(ctx)) }

    Column(modifier) {
        ScreenHeader("Memory", onBack)
        Spacer(Modifier.height(16.dp))

        Text("What should the agent know about you?", fontSize = T.body, color = T.ink)
        Spacer(Modifier.height(6.dp))
        Text(
            "Name, how you like to be addressed, tone for replies, work, people who matter — " +
                "anything that makes its answers feel like you.",
            fontSize = T.small, color = T.inkFaint
        )
        Spacer(Modifier.height(12.dp))

        BasicTextField(
            value = about,
            onValueChange = { about = it; saved = false },
            textStyle = TextStyle(color = T.ink, fontSize = T.body),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 160.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(T.bgElevated)
                .padding(14.dp),
            decorationBox = { inner ->
                if (about.isEmpty())
                    Text(
                        "e.g. I'm Zaddy, a UCR student. Keep replies short and warm. " +
                            "My partner is Alex. I work nights, so don't assume I'm free in the evening.",
                        fontSize = T.small, color = T.inkFaint
                    )
                inner()
            }
        )

        Spacer(Modifier.height(14.dp))
        Text(
            if (saved) "Saved ✓" else "Save",
            fontSize = T.small,
            color = if (saved) T.inkSoft else T.bgElevated,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(if (saved) T.hairline else T.accent)
                .clickable {
                    MemoryStore.setAbout(ctx, about.trim())
                    saved = true
                }
                .padding(horizontal = 22.dp, vertical = 10.dp)
        )

        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Auto-reply to messages", fontSize = T.body, color = T.ink)
                Text(
                    "Agent replies on its own after an 8-second window you can cancel from Now.",
                    fontSize = T.small, color = T.inkFaint
                )
            }
            Switch(
                checked = autonomous,
                onCheckedChange = { autonomous = it; MemoryStore.setAutonomous(ctx, it) }
            )
        }

        Spacer(Modifier.weight(1f))
        Text(
            "The agent reads this on every request. Nothing here leaves your phone except as " +
                "part of a prompt you trigger.",
            fontSize = T.caption, color = T.inkFaint
        )
    }
}
