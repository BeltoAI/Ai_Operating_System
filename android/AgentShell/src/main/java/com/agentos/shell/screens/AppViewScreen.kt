package com.agentos.shell.screens

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.viewinterop.AndroidView
import com.agentos.shell.theme.T
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.AppBridge
import com.agentos.shell.tools.AppStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Runs a generated mini-app in a sandboxed WebView with the SlyOS bridge, and lets you refine it. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AppViewScreen(modifier: Modifier = Modifier, appId: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = remember(appId) { AppStore.get(ctx, appId) }
    var html by remember(appId) { mutableStateOf(app?.html ?: "") }
    var name by remember(appId) { mutableStateOf(app?.name ?: "App") }
    var edit by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val bridge = remember(appId) { AppBridge(ctx, appId, scope) }

    Column(modifier) {
        ScreenHeader(name, onBack)
        Spacer(Modifier.height(10.dp))
        if (app == null) {
            Text("This app is no longer available.", fontSize = T.body, color = T.inkSoft)
            return@Column
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(12.dp)),
            factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    setBackgroundColor(0xFFF4EFE6.toInt())
                    addJavascriptInterface(bridge, "SlyOSNative")
                    bridge.web = this
                    loadDataWithBaseURL("https://localhost/", AppBridge.wrap(html), "text/html", "utf-8", null)
                }
            },
            update = { wv ->
                if (wv.tag != html) {
                    wv.tag = html
                    wv.loadDataWithBaseURL("https://localhost/", AppBridge.wrap(html), "text/html", "utf-8", null)
                }
            }
        )
        Spacer(Modifier.height(8.dp))
        // Refine the app by prompt — Opus rebuilds it, keeping its saved data.
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = edit, onValueChange = { edit = it },
                textStyle = TextStyle(color = T.ink, fontSize = T.small),
                modifier = Modifier.weight(1f).heightIn(min = 20.dp).clip(RoundedCornerShape(10.dp))
                    .background(T.bgElevated).padding(10.dp),
                decorationBox = { inner -> if (edit.isEmpty()) Text("refine it — add a feature, change the look…", fontSize = T.small, color = T.inkFaint); inner() }
            )
            Spacer(Modifier.width(8.dp))
            Text(if (busy) "…" else "↻ Update", fontSize = T.small, color = T.bgElevated,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent)
                    .clickable(enabled = !busy && edit.isNotBlank()) {
                        busy = true; status = ""
                        val instr = edit
                        scope.launch {
                            val (newName, newHtml) = withContext(Dispatchers.IO) { AgentClient.reviseApp(html, instr) }
                            if (newHtml.contains("Couldn't build")) { status = "Couldn't update — try again."; busy = false; return@launch }
                            AppStore.update(ctx, appId, newHtml, newName)
                            html = newHtml; name = newName; edit = ""; busy = false; status = "Updated ✓"
                        }
                    }.padding(horizontal = 14.dp, vertical = 9.dp))
        }
        if (status.isNotEmpty()) { Spacer(Modifier.height(6.dp)); Text(status, fontSize = T.caption, color = T.accent) }
    }
}
