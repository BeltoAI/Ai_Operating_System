package com.agentos.shell.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.AppStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hidden console (long-press the SlyOS wordmark). Describe an app; Opus 4.8 builds it as a
 * self-contained mini-app stored in SlyOS and run in a sandboxed WebView.
 */
@Composable
fun ArchitectScreen(modifier: Modifier = Modifier, onBack: () -> Unit, onOpenApp: (Long) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var prompt by remember { mutableStateOf("") }
    var building by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val apps = remember { mutableStateListOf<AppStore.MiniApp>().apply { addAll(AppStore.load(ctx)) } }
    fun refresh() { apps.clear(); apps.addAll(AppStore.load(ctx)) }

    Column(modifier) {
        ScreenHeader("Architect", onBack)
        Spacer(Modifier.height(6.dp))
        Text("Describe an app or tool. Opus 4.8 builds it and adds it to SlyOS.",
            fontSize = T.small, color = T.inkFaint)
        Spacer(Modifier.height(14.dp))

        BasicTextField(
            value = prompt,
            onValueChange = { prompt = it },
            textStyle = TextStyle(color = T.ink, fontSize = T.body),
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp)
                .clip(RoundedCornerShape(12.dp)).background(T.bgElevated).padding(14.dp),
            decorationBox = { inner ->
                if (prompt.isEmpty())
                    Text("e.g. a tip calculator · a habit tracker · a dice roller · a unit converter",
                        fontSize = T.small, color = T.inkFaint)
                inner()
            }
        )
        Spacer(Modifier.height(12.dp))
        Text(
            if (building) "Opus is building…" else "Build it",
            fontSize = T.small, color = if (building) T.inkFaint else T.bgElevated,
            modifier = Modifier.clip(RoundedCornerShape(999.dp))
                .background(if (building) T.hairline else T.accent)
                .clickable(enabled = !building && prompt.isNotBlank()) {
                    building = true; status = ""
                    val p = prompt
                    scope.launch {
                        val (name, html) = withContext(Dispatchers.IO) { AgentClient.architect(p) }
                        val id = AppStore.add(ctx, name, html)
                        building = false; prompt = ""; refresh()
                        status = "Built “$name”. Opening…"
                        onOpenApp(id)
                    }
                }
                .padding(horizontal = 22.dp, vertical = 11.dp)
        )

        if (status.isNotEmpty()) {
            Spacer(Modifier.height(10.dp)); Text(status, fontSize = T.small, color = T.accent)
        }

        Spacer(Modifier.height(18.dp))
        Text("YOUR APPS · ${apps.size}", fontSize = T.caption, color = T.inkFaint)
        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(apps, key = { it.id }) { a ->
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { onOpenApp(a.id) }.padding(vertical = 12.dp)) {
                    Text("◆", color = T.accent, fontSize = T.small)
                    Spacer(Modifier.width(10.dp))
                    Text(a.name, fontSize = T.body, color = T.ink, modifier = Modifier.weight(1f))
                    Text("✕", fontSize = T.small, color = T.inkFaint,
                        modifier = Modifier.clickable { AppStore.remove(ctx, a.id); refresh() }.padding(start = 10.dp))
                }
                Hairline()
            }
        }
    }
}
