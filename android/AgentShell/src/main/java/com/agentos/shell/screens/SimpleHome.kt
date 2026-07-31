package com.agentos.shell.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.SimpleMode

/**
 * The whole phone, for somebody who does not want a phone.
 *
 * Everything here is large, everything is a sentence, and nothing is an icon. The buttons are not a
 * simplified imitation of SlyOS — each one hands a sentence to exactly the same assistant the full
 * app uses, so "Call Joslyn" goes through the same machinery as typing it.
 *
 * Three things are deliberate and worth defending:
 *
 *  - **The way out is always on screen.** Turning the navigation off is a serious thing to do to
 *    somebody's phone, and burying the exit in a settings screen they can no longer reach would be
 *    indefensible. It sits at the bottom, in the same type size as everything else.
 *  - **Emergency asks first, and opens the dialer rather than dialling.** A one-tap ambulance
 *    button on a screen full of large targets will be pressed by accident, and a wasted emergency
 *    call is a real harm to somebody else. Two deliberate actions, and the last press is the
 *    phone's own green button.
 *  - **The people are real people.** Resolved from who this phone actually talks to, because
 *    "Call Michael" is a button somebody can use and "Call a contact" is one they have to think
 *    about first.
 */
@Composable
fun SimpleHome(
    modifier: Modifier = Modifier,
    onAsk: (String) -> Unit,
    onPhotos: () -> Unit,
    onExit: () -> Unit
) {
    val ctx = LocalContext.current
    val tasks = remember { SimpleMode.tasks(ctx) }
    var showPeople by remember { mutableStateOf(false) }
    var confirmEmergency by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().background(T.bg)
        .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {

        Spacer(Modifier.height(26.dp))
        Text(if (showPeople) "Who would you like to call?" else "What can I do for you?",
            fontSize = 30.sp, color = T.ink, fontWeight = FontWeight.Medium, lineHeight = 38.sp)
        Spacer(Modifier.height(22.dp))

        if (showPeople) {
            SimpleMode.callable(ctx).forEach { p ->
                Big(p.name) { onAsk("Call ${p.name}") }
            }
            Big("Back") { showPeople = false }
        } else {
            tasks.forEach { t ->
                Big(t.label) {
                    when (t.kind) {
                        "people" -> showPeople = true
                        "photos" -> onPhotos()
                        else -> onAsk(t.prompt)
                    }
                }
            }

            // Set apart, in red, at the bottom — reachable in a hurry but never next to "Order the
            // shopping" where a thumb might land on it.
            Spacer(Modifier.height(16.dp))
            if (!confirmEmergency) {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .background(T.danger.copy(alpha = 0.16f))
                    .clickable { confirmEmergency = true }
                    .padding(vertical = 26.dp), contentAlignment = Alignment.Center) {
                    Text("Emergency", fontSize = 24.sp, color = T.danger,
                        fontWeight = FontWeight.Bold)
                }
            } else {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .background(T.danger.copy(alpha = 0.16f)).padding(20.dp)) {
                    Text("Call ${SimpleMode.emergencyNumber(ctx)} for help?",
                        fontSize = 22.sp, color = T.ink, fontWeight = FontWeight.Medium,
                        lineHeight = 29.sp)
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                            .background(T.danger)
                            .clickable {
                                try {
                                    ctx.startActivity(android.content.Intent(
                                        android.content.Intent.ACTION_DIAL,
                                        android.net.Uri.parse("tel:" + SimpleMode.emergencyNumber(ctx)))
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                                } catch (e: Exception) {}
                                confirmEmergency = false
                            }.padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                            Text("Yes, call", fontSize = 20.sp, color = androidx.compose.ui.graphics.Color.White,
                                fontWeight = FontWeight.Bold)
                        }
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                            .background(T.bgElevated)
                            .clickable { confirmEmergency = false }
                            .padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                            Text("No", fontSize = 20.sp, color = T.ink, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
            // Never buried. Somebody who cannot find their way out of simple mode has been trapped
            // by it, and they cannot reach Settings from here to undo it.
            Text("Show me everything again", fontSize = 17.sp, color = T.inkFaint,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                    .clickable { onExit() }.padding(vertical = 14.dp))
        }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun Big(label: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(20.dp))
        .background(T.bgElevated).clickable { onClick() }
        .padding(horizontal = 22.dp, vertical = 26.dp)) {
        Text(label, fontSize = 24.sp, color = T.ink, fontWeight = FontWeight.Medium,
            lineHeight = 30.sp)
    }
}
