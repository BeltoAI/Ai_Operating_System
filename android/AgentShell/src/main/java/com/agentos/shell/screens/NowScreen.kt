package com.agentos.shell.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.NotificationStore

/** Everything happening now — your real notifications, newest first, each replyable inline. */
@Composable
fun NowScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val notes = NotificationStore.notes
    Column(modifier) {
        ScreenHeader("Now", onBack)
        Spacer(Modifier.height(12.dp))

        if (notes.isEmpty()) {
            Text("Nothing here yet.", fontSize = T.body, color = T.inkSoft)
            Spacer(Modifier.height(8.dp))
            Text(
                "Send yourself a message — or grant access:\n" +
                    "Settings → Notifications → Notification access → enable SlyOS.",
                fontSize = T.small, color = T.inkFaint
            )
            return@Column
        }

        LazyColumn(Modifier.weight(1f)) {
            items(notes, key = { it.key }) { note -> ReplyCard(note) }
        }
    }
}
