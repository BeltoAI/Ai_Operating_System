package com.agentos.shell.screens

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.AgentAction
import com.agentos.shell.tools.ToolRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Consequential actions the Home AI proposes — sending a message/email, adding a calendar event,
 * setting a reminder — surface here as a single tap-to-confirm card with the fields pre-filled and
 * editable. Nothing fires until you tap Confirm. This is the shared "generative task card" for
 * anything that touches the outside world.
 */
object ActionConfirm {
    // Only these action types get a confirm card; everything else runs immediately.
    // "remind" is deliberately NOT here. Everything in this set reaches someone else or writes to a
    // shared calendar, so a tap of confirmation is worth the friction. A reminder is a private local
    // notification — no different in kind from a timer or an alarm, both of which fire immediately.
    // Gating it meant "remind me in 30 minutes to call mum" set nothing until the user tapped again,
    // and the confirm card only ever exposed an "at" field, so relative ("in 20 minutes") reminders
    // came out blank. Reminders now just get set, like the user asked.
    val CONFIRM_TYPES = setOf("add_event", "send_sms", "message", "send_email")

    data class Field(val key: String, val label: String, val multiline: Boolean = false)

    fun fieldsFor(type: String): List<Field> = when (type) {
        "add_event" -> listOf(Field("title", "Event"), Field("start", "Start"), Field("end", "End"), Field("location", "Where"))
        "send_sms" -> listOf(Field("name", "To"), Field("body", "Message", true))
        "message" -> listOf(Field("name", "To"), Field("app", "App"), Field("body", "Message", true))
        "send_email" -> listOf(Field("to", "To"), Field("subject", "Subject"), Field("body", "Body", true))
        "remind" -> listOf(Field("text", "Remind me", true), Field("at", "When"))
        else -> emptyList()
    }

    fun titleFor(type: String, o: JSONObject): String = when (type) {
        "add_event" -> "Add to calendar"
        "send_sms", "message" -> "Send message" + o.optString("name").let { if (it.isNotBlank()) " to $it" else "" }
        "send_email" -> "Send email" + o.optString("to").let { if (it.isNotBlank()) " to $it" else "" }
        "remind" -> "Set reminder"
        else -> type
    }

    /** "2026-07-18T14:00" → "Sat, Jul 18 · 2:00 PM" for a human-readable label (the field stays ISO-editable). */
    fun prettyWhen(iso: String): String = try {
        val m = Regex("(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2})").find(iso) ?: return iso
        val (y, mo, d, h, mi) = m.destructured
        val cal = java.util.Calendar.getInstance().apply { set(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt()) }
        java.text.SimpleDateFormat("EEE, MMM d · h:mm a", java.util.Locale.getDefault()).format(cal.time)
    } catch (e: Exception) { iso }

    /** Parse an action's arg into an editable JSON object, wrapping plain-string args sensibly. */
    fun parse(a: AgentAction): JSONObject = try { JSONObject(a.arg) } catch (e: Exception) {
        when (a.type) {
            "remind" -> JSONObject().put("text", a.arg)
            "add_event" -> JSONObject().put("title", a.arg)
            else -> JSONObject().put("body", a.arg)
        }
    }
}

@Composable
fun ConfirmActionCard(
    actions: List<AgentAction>,
    onResult: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    // One editable JSON object per action, plus a live field map we mutate as the user types.
    val objs = remember(actions) { actions.map { ActionConfirm.parse(it) } }
    val edits = remember(actions) {
        objs.map { o -> mutableStateMapOf<String, String>().apply { o.keys().forEach { k -> put(k, o.optString(k)) } } }
    }

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(T.bgElevated).padding(16.dp)) {
        Text("Ready to send — tap to confirm", fontSize = T.caption, color = T.inkFaint)
        Spacer(Modifier.height(4.dp))

        actions.forEachIndexed { idx, a ->
            val o = objs[idx]
            val map = edits[idx]
            if (idx > 0) { Spacer(Modifier.height(12.dp)); Box(Modifier.fillMaxWidth().height(1.dp).background(T.hairline)); Spacer(Modifier.height(12.dp)) }
            Text(ActionConfirm.titleFor(a.type, o), fontSize = T.body, color = T.accent)
            Spacer(Modifier.height(8.dp))
            // Show each known field that has a value, or the primary (first) field so there's always something to edit.
            val fields = ActionConfirm.fieldsFor(a.type).ifEmpty { listOf(ActionConfirm.Field("body", "")) }
            fields.forEachIndexed { fi, f ->
                val cur = map[f.key] ?: ""
                if (cur.isNotBlank() || fi == 0) {
                    val isWhen = (f.key == "start" || f.key == "end" || f.key == "at") && cur.contains("T")
                    if (f.label.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(f.label, fontSize = T.caption, color = T.inkFaint)
                    }
                    if (isWhen) {
                        // Pretty, human, TAP-TO-PICK datetime — no raw ISO to read or edit.
                        Text(ActionConfirm.prettyWhen(cur), fontSize = T.small, color = T.ink,
                            modifier = Modifier.fillMaxWidth().padding(top = 3.dp).clip(RoundedCornerShape(9.dp)).background(T.bg)
                                .clickable {
                                    val c = java.util.Calendar.getInstance()
                                    try {
                                        val m = Regex("(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2})").find(cur)
                                        if (m != null) { val (y, mo, d, h, mi) = m.destructured; c.set(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt()) }
                                    } catch (e: Exception) {}
                                    android.app.DatePickerDialog(ctx, { _, yy, mm2, dd ->
                                        android.app.TimePickerDialog(ctx, { _, hh, mn ->
                                            map[f.key] = "%04d-%02d-%02dT%02d:%02d".format(yy, mm2 + 1, dd, hh, mn)
                                        }, c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE), false).show()
                                    }, c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH)).show()
                                }.padding(12.dp))
                    } else BasicTextField(
                        value = cur,
                        onValueChange = { map[f.key] = it },
                        textStyle = TextStyle(color = T.ink, fontSize = T.small),
                        cursorBrush = SolidColor(T.accent),
                        modifier = Modifier.fillMaxWidth().padding(top = 3.dp)
                            .clip(RoundedCornerShape(9.dp)).background(T.bg)
                            .heightIn(min = if (f.multiline) 44.dp else 0.dp).padding(10.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (busy) "sending…" else "Confirm", fontSize = T.small, color = Color.White, textAlign = TextAlign.Center,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (busy) T.hairline else T.accent)
                    .clickable(enabled = !busy) {
                        busy = true
                        val rebuilt = actions.mapIndexed { idx, a ->
                            val o = objs[idx]
                            edits[idx].forEach { (k, v) -> o.put(k, v) }
                            a.copy(arg = o.toString())
                        }
                        scope.launch {
                            val msg = withContext(Dispatchers.IO) { ToolRouter.executeActions(ctx, rebuilt) }
                            onResult(msg)
                        }
                    }.padding(horizontal = 26.dp, vertical = 10.dp))
            Spacer(Modifier.width(14.dp))
            Text("Cancel", fontSize = T.small, color = T.inkSoft, modifier = Modifier.clickable(enabled = !busy) { onDismiss() }.padding(8.dp))
        }
    }
}
