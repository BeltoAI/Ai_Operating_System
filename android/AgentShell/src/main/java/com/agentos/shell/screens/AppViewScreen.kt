package com.agentos.shell.screens

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.agentos.shell.theme.T
import com.agentos.shell.tools.AppStore

/** Runs a generated mini-app in a sandboxed WebView. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AppViewScreen(modifier: Modifier = Modifier, appId: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = remember(appId) { AppStore.get(ctx, appId) }

    Column(modifier) {
        ScreenHeader(app?.name ?: "App", onBack)
        Spacer(Modifier.height(10.dp))
        if (app == null) {
            Text("This app is no longer available.", fontSize = T.body, color = T.inkSoft)
            return@Column
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    setBackgroundColor(0xFFF4EFE6.toInt())
                    loadDataWithBaseURL("https://localhost/", app.html, "text/html", "utf-8", null)
                }
            }
        )
    }
}
