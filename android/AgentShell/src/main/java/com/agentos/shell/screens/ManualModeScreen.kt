package com.agentos.shell.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.ToolRouter

/**
 * Always-available fallback. Each tool launches the real Android app via intent.
 * The agent is paused; resuming is instant.
 */
private val tools = listOf("Phone", "Messages", "Camera", "Browser", "Files", "Settings")

@Composable
fun ManualModeScreen(modifier: Modifier = Modifier, onResume: () -> Unit, onChecklist: () -> Unit = {}, onOutreach: () -> Unit = {}) {
    val ctx = LocalContext.current
    var status by remember { mutableStateOf("") }
    Column(modifier) {
        Heading("Manual Mode")
        Spacer(Modifier.height(6.dp))
        Text("Agent paused.", fontSize = T.small, color = T.danger)
        if (status.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(status, fontSize = T.small, color = T.accent)
        }
        Spacer(Modifier.height(10.dp))
        tools.forEach { t ->
            Text(t, fontSize = T.body, color = T.ink,
                modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp)
                    .clickable { status = ToolRouter.openTool(ctx, t) })
            Hairline()
        }
        Text("Checklist", fontSize = T.body, color = T.ink,
            modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp).clickable { onChecklist() })
        Hairline()
        Text("Outreach emails", fontSize = T.body, color = T.ink,
            modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp).clickable { onOutreach() })
        Hairline()
        Spacer(Modifier.weight(1f))
        Text("▸ Resume agent", fontSize = T.small, color = T.accent,
            modifier = Modifier.fillMaxWidth().clickable { onResume() }.padding(8.dp))
    }
}
