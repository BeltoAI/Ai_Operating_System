package com.agentos.shell.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.ChecklistStore

@Composable
fun ChecklistScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val items = remember { mutableStateListOf<ChecklistStore.Item>().apply { ChecklistStore.prune(ctx); addAll(ChecklistStore.load(ctx)) } }
    var text by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<Long?>(null) }
    var editText by remember { mutableStateOf("") }
    fun refresh() { items.clear(); items.addAll(ChecklistStore.load(ctx)) }

    Column(modifier) {
        ScreenHeader("Checklist", onBack)
        Spacer(Modifier.height(14.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(color = T.ink, fontSize = T.body),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    ChecklistStore.addManual(ctx, text); text = ""; refresh()
                }),
                modifier = Modifier.weight(1f)
                    .clip(RoundedCornerShape(10.dp)).background(T.bgElevated).padding(12.dp),
                decorationBox = { inner ->
                    if (text.isEmpty()) Text("Add an item…", color = T.inkFaint, fontSize = T.body)
                    inner()
                }
            )
            Spacer(Modifier.width(10.dp))
            Text("Add", fontSize = T.small, color = T.bgElevated,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                    .clickable { ChecklistStore.addManual(ctx, text); text = ""; refresh() }
                    .padding(horizontal = 16.dp, vertical = 10.dp))
        }

        Spacer(Modifier.height(14.dp))
        if (items.isEmpty()) {
            Text("Nothing yet. Add an item above, or just tell the agent " +
                "“add milk to my checklist”.", fontSize = T.small, color = T.inkFaint)
        }

        LazyColumn(Modifier.weight(1f)) {
            items(items, key = { it.id }) { it2 ->
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp)) {
                    Box(
                        Modifier.size(20.dp).clip(CircleShape)
                            .background(if (it2.done) T.accent else T.hairline)
                            .clickable { ChecklistStore.toggle(ctx, it2.id); refresh() },
                        contentAlignment = Alignment.Center
                    ) { if (it2.done) Text("✓", color = T.bgElevated, fontSize = T.caption) }
                    Spacer(Modifier.width(12.dp))
                    if (editingId == it2.id) {
                        BasicTextField(
                            value = editText, onValueChange = { editText = it }, singleLine = true,
                            textStyle = TextStyle(color = T.ink, fontSize = T.body),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { ChecklistStore.edit(ctx, it2.id, editText); editingId = null; refresh() }),
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(T.bgElevated).padding(8.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Save", fontSize = T.small, color = T.accent,
                            modifier = Modifier.clickable { ChecklistStore.edit(ctx, it2.id, editText); editingId = null; refresh() })
                    } else {
                        Text(
                            it2.text, fontSize = T.body,
                            color = if (it2.done) T.inkFaint else T.ink,
                            textDecoration = if (it2.done) TextDecoration.LineThrough else null,
                            modifier = Modifier.weight(1f).clickable { editingId = it2.id; editText = it2.text }   // tap to edit
                        )
                        Text("✕", fontSize = T.small, color = T.inkFaint,
                            modifier = Modifier.clickable { ChecklistStore.remove(ctx, it2.id); refresh() }
                                .padding(start = 10.dp))
                    }
                }
                Hairline()
            }
        }

        if (items.any { it.done }) {
            Text("Clear completed", fontSize = T.small, color = T.accent,
                modifier = Modifier.clickable { ChecklistStore.clearDone(ctx); refresh() }.padding(vertical = 10.dp))
        }
    }
}
