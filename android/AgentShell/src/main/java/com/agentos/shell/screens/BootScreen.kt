package com.agentos.shell.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agentos.shell.theme.T

@Composable
fun BootScreen(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Wordmark(big = true)
            Spacer(Modifier.height(14.dp))
            Text("waking up…", color = T.inkFaint, fontSize = T.body)
        }
    }
}
