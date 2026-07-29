package com.agentos.shell.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.agentos.shell.theme.T
import com.agentos.shell.tools.ScreenControlGate

/**
 * Shown instead of a takeover, when a takeover would have no stop button.
 *
 * Deliberately a *sheet with a verb on it*, not a toast and not an error. The owner asked for
 * something; the honest reply is that it did not happen, why, and the one tap that fixes it. A
 * refusal that leaves someone with nothing to press is only marginally better than the silent
 * failure it replaced.
 *
 * The red pill is drawn here as a preview of the thing being asked for, because "allow display over
 * other apps" is a phrase nobody wants to say yes to, and a picture of a STOP button is.
 */
@Composable
fun ScreenControlSheet(state: ScreenControlGate.State, onDismiss: () -> Unit) {
    if (state == ScreenControlGate.State.READY) return
    val ctx = LocalContext.current

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(T.bgElevated)
                .padding(22.dp)
        ) {
            Text(ScreenControlGate.title(state),
                fontSize = T.body, color = T.ink, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(10.dp))
            Text(ScreenControlGate.why(state),
                fontSize = T.small, color = T.inkSoft, lineHeight = 20.sp)

            // What is actually being granted, shown rather than named.
            if (state == ScreenControlGate.State.NO_OVERLAY) {
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Row(
                        Modifier.clip(RoundedCornerShape(999.dp))
                            .background(T.danger)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("■", fontSize = 13.sp, color = Color.White)
                        Spacer(Modifier.size(8.dp))
                        Text("STOP · typing", fontSize = T.small, color = Color.White,
                            fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Always on top, one tap, at any point.",
                    fontSize = T.caption, color = T.inkFaint,
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }

            Spacer(Modifier.height(22.dp))
            Text(
                ScreenControlGate.action(state),
                fontSize = T.small, color = Color.White, fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(999.dp))
                    .background(T.accent)
                    .clickable {
                        try { ScreenControlGate.settingsIntent(ctx, state)?.let { ctx.startActivity(it) } }
                        catch (e: Exception) {}
                        onDismiss()
                    }
                    .padding(vertical = 13.dp)
            )
            Spacer(Modifier.height(6.dp))
            Text("Not now", fontSize = T.small, color = T.inkFaint, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable { onDismiss() }.padding(vertical = 11.dp))
        }
    }
}
