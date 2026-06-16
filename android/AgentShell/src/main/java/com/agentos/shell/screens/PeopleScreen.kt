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

/**
 * People who are waiting on a reply: real messages that can be replied to, grouped by sender
 * (one card per person, their most recent message), each with the agent-reply flow.
 */
@Composable
fun PeopleScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val notes = NotificationStore.notes
    // notes are newest-first; first() per sender = their latest message.
    val people = notes
        .filter { it.canReply }
        .groupBy { it.title.ifBlank { it.app } }
        .map { it.value.first() }

    Column(modifier) {
        ScreenHeader("People", onBack)
        Spacer(Modifier.height(12.dp))

        if (people.isEmpty()) {
            Text("No one's waiting on a reply.", fontSize = T.body, color = T.inkSoft)
            Spacer(Modifier.height(8.dp))
            Text(
                "People who message you (WhatsApp, SMS, Signal…) appear here, newest first, " +
                    "each ready for a one-tap agent reply.",
                fontSize = T.small, color = T.inkFaint
            )
            return@Column
        }

        LazyColumn(Modifier.weight(1f)) {
            items(people, key = { it.key }) { note -> ReplyCard(note) }
        }
    }
}
