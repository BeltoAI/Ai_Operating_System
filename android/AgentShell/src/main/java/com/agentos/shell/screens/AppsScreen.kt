package com.agentos.shell.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.ToolRouter

/** Full app reachability — you can always get to every installed app from here. */
@Composable
fun AppsScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val apps = remember { ToolRouter.installedApps(ctx) }
    Column(modifier) {
        ScreenHeader("Apps", onBack)
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(apps) { app ->
                Text(
                    app.label,
                    fontSize = T.body,
                    color = T.ink,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { ToolRouter.launchApp(ctx, app.pkg) }
                        .padding(vertical = 12.dp)
                )
                Hairline()
            }
        }
    }
}
