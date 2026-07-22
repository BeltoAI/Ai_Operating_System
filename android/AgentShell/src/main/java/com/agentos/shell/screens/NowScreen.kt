package com.agentos.shell.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.ui.text.TextStyle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.AgentClient
import com.agentos.shell.tools.MemoryStore
import com.agentos.shell.tools.NotificationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Everything happening now — an auto "what you missed" briefing, then people who need you as
 *  swipeable cards grouped per person: tap to open, swipe left to dismiss, ✦ to reply. */
@Composable
fun NowScreen(modifier: Modifier = Modifier, onReconnect: () -> Unit = {}, onOutbox: () -> Unit = {}, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val notes = NotificationStore.notes
    var digest by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var briefHidden by remember { mutableStateOf(false) }
    var briefDragX by remember { mutableStateOf(0f) }

    fun catchUp() {
        if (loading) return
        loading = true; digest = ""
        scope.launch {
            val mem = MemoryStore.about(ctx)
            val snapshot = notes.toList()
            val awaiting = snapshot.filter { it.isConversational && it.text.isNotBlank() }
                .map { "${it.title.ifBlank { it.app }} (${it.app}): \"${it.text.take(120)}\"" }
            val otherNotifs = snapshot.filter { !it.isConversational && !it.isLikelyBot && it.text.isNotBlank() }
                .map { "${it.app}: ${it.text.take(120)}" }
            digest = withContext(Dispatchers.IO) { AgentClient.catchUp(otherNotifs, awaiting, mem) }
            loading = false
        }
    }
    LaunchedEffect(Unit) { if (notes.isNotEmpty()) catchUp() }
    val dateStr = remember { java.text.SimpleDateFormat("EEEE, MMM d", java.util.Locale.getDefault()).format(java.util.Date()) }

    Column(modifier) {
        ScreenHeader("Now", onBack)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
            Text(dateStr, fontSize = T.caption, color = T.inkFaint, modifier = Modifier.weight(1f))
            Text("Sent for you", fontSize = T.caption, color = T.inkSoft, modifier = Modifier.clickable { onOutbox() }.padding(end = 14.dp))
            Text("Reconnect", fontSize = T.caption, color = T.inkSoft, modifier = Modifier.clickable { onReconnect() })
        }
        // If drafts have piled up (e.g. lots of X/social replies), offer a one-tap clear-all.
        val draftCount = NotificationStore.stagedDrafts.size
        if (draftCount >= 5) {
            Spacer(Modifier.height(6.dp))
            Text("Clear $draftCount unsent drafts", fontSize = T.caption, color = T.danger,
                modifier = Modifier.clickable { NotificationStore.clearAllDrafts() })
        }
        Spacer(Modifier.height(14.dp))

        // ── Proactive proposals (P5.3): one-tap suggestions like "add this booking to your calendar" ──
        com.agentos.shell.tools.ProposalStore.ensureLoaded(ctx)
        val proposals = com.agentos.shell.tools.ProposalStore.items
        if (proposals.isNotEmpty()) {
            Text("SUGGESTED", fontSize = 11.sp, color = T.inkFaint, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            proposals.toList().forEach { p ->
                Spacer(Modifier.height(8.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(T.bgElevated).padding(14.dp)) {
                    Text(p.title, fontSize = T.body, color = T.ink)
                    if (p.subtitle.isNotBlank()) Text(p.subtitle, fontSize = T.caption, color = T.inkFaint)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Confirm", fontSize = T.small, color = Color.White, textAlign = TextAlign.Center,
                            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent).clickable {
                                scope.launch {
                                    val msg = withContext(Dispatchers.IO) { com.agentos.shell.tools.ToolRouter.executeActions(ctx, p.actions, userInitiated = true) }
                                    com.agentos.shell.tools.OutboxStore.record(ctx, "Proposal", p.title, "proposal", msg.ifBlank { p.subtitle }, "you confirmed a suggestion")
                                    com.agentos.shell.tools.ProposalStore.remove(ctx, p.id)
                                }
                            }.padding(horizontal = 20.dp, vertical = 9.dp))
                        Spacer(Modifier.width(14.dp))
                        Text("Dismiss", fontSize = T.small, color = T.inkSoft,
                            modifier = Modifier.clickable { com.agentos.shell.tools.ProposalStore.remove(ctx, p.id) }.padding(6.dp))
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        // ── Briefing card (swipe left to dismiss) ──
        if (!briefHidden) Column(Modifier.fillMaxWidth()
            .offset { IntOffset(briefDragX.roundToInt(), 0) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = { if (briefDragX < -120f) briefHidden = true; briefDragX = 0f },
                    onDragCancel = { briefDragX = 0f }
                ) { _, dx -> briefDragX = (briefDragX + dx).coerceAtMost(0f) }
            }
            .clip(RoundedCornerShape(18.dp)).background(T.bgElevated).padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("WHAT YOU MISSED", fontSize = 11.sp, color = T.inkFaint, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
                if (loading) SlyOrbit(12)
                else Text("↻", fontSize = T.small, color = T.accent, modifier = Modifier.clickable { catchUp() }.padding(4.dp))
            }
            Spacer(Modifier.height(10.dp))
            when {
                loading && digest.isBlank() -> Row(verticalAlignment = Alignment.CenterVertically) {
                    SlyOrbit(20); Spacer(Modifier.width(12.dp)); Text("reading your day", fontSize = T.small, color = T.accent)
                }
                digest.isBlank() -> Text(if (notes.isEmpty()) "You're all caught up." else "Tap ↻ for a summary.", fontSize = T.small, color = T.inkSoft)
                else -> {
                    val idx = digest.indexOf("Text back", ignoreCase = true)
                    if (idx > 0) {
                        Text(digest.substring(0, idx).trim(), fontSize = T.small, color = T.ink)
                        Spacer(Modifier.height(8.dp))
                        Text(digest.substring(idx).trim(), fontSize = T.small, color = T.accent)
                    } else Text(digest, fontSize = T.small, color = T.ink)
                }
            }
        }

        if (notes.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("All caught up.", fontSize = T.body, color = T.inkSoft)
            Spacer(Modifier.height(8.dp))
            Text("Grant notification access in Settings to see what's waiting.",
                fontSize = T.caption, color = T.inkFaint)
            return@Column
        }

        Spacer(Modifier.height(20.dp))
        val groups = notes.groupBy { it.title.ifBlank { it.app } }.map { it.key to it.value }
        // Clear-all lives on the section header, next to the count it clears. Swiping every card left
        // one at a time was the only way to empty this screen.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("WAITING · ${groups.size}", fontSize = 11.sp, color = T.inkFaint,
                fontWeight = FontWeight.Bold, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
            Text("Clear all", fontSize = T.caption, color = T.danger,
                modifier = Modifier.clickable { NotificationStore.dismissAll() }.padding(4.dp))
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(groups, key = { it.first }) { (contact, group) -> NoteGroupCard(ctx, contact, group) }
        }
    }
}

private fun appColor(pkg: String): Color = when {
    pkg.contains("whatsapp") -> Color(0xFF25D366)
    pkg.contains("telegram") -> Color(0xFF26A5E4)
    pkg.contains("instagram") -> Color(0xFFC13584)
    pkg.contains("gm") -> Color(0xFFEA4335)
    pkg.contains("messaging") -> Color(0xFF1A73E8)
    pkg.contains("twitter") || pkg.contains("x.android") -> Color(0xFF111111)
    pkg.contains("linkedin") -> Color(0xFF0A66C2)
    pkg.contains("slack") -> Color(0xFF4A154B)
    pkg.contains("discord") -> Color(0xFF5865F2)
    pkg.contains("securesms") || pkg.contains("signal") -> Color(0xFF3A76F0)
    pkg.contains("facebook") || pkg.contains("orca") -> Color(0xFF0866FF)
    pkg.contains("snapchat") -> Color(0xFFFFFC00)
    pkg.contains("reddit") -> Color(0xFFFF4500)
    pkg.contains("teams") -> Color(0xFF6264A7)
    pkg.contains("viber") -> Color(0xFF7360F2)
    pkg.contains("line") -> Color(0xFF06C755)
    pkg.contains("outlook") -> Color(0xFF0078D4)
    else -> T.accent
}

/** Real launcher icon for a package — this is what gives the Now feed true per-app recognition
 *  (any installed app, not just the hardcoded colors). Rasterized once and cached per package. */
private val iconCache = HashMap<String, androidx.compose.ui.graphics.ImageBitmap?>()
private fun appIcon(ctx: android.content.Context, pkg: String): androidx.compose.ui.graphics.ImageBitmap? {
    if (pkg.isBlank()) return null
    iconCache[pkg]?.let { return it }
    if (iconCache.containsKey(pkg)) return null
    val img = try {
        val d = ctx.packageManager.getApplicationIcon(pkg)
        val w = d.intrinsicWidth.coerceIn(1, 144); val h = d.intrinsicHeight.coerceIn(1, 144)
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp); d.setBounds(0, 0, w, h); d.draw(c)
        bmp.asImageBitmap()
    } catch (e: Exception) { null }
    iconCache[pkg] = img
    return img
}

/** Human app name for a package (e.g. "WhatsApp"), falling back to the note's own label. */
private fun appName(ctx: android.content.Context, pkg: String, fallback: String): String = try {
    if (pkg.isBlank()) fallback
    else ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
} catch (e: Exception) { fallback }

@Composable
private fun NoteGroupCard(ctx: android.content.Context, contact: String, group: List<NotificationStore.Note>) {
    val latest = group.first()
    val scope = rememberCoroutineScope()
    var expanded by remember(latest.key) { mutableStateOf(false) }
    var dragX by remember(latest.key) { mutableStateOf(0f) }
    val staged = NotificationStore.stagedDrafts[latest.key]
    var draft by remember(latest.key) { mutableStateOf(staged ?: "") }
    var replyBusy by remember(latest.key) { mutableStateOf(false) }
    var sendMsg by remember(latest.key) { mutableStateOf("") }

    // Draft a reply the first time this card is opened — in your voice, from the brain.
    LaunchedEffect(expanded) {
        if (expanded && draft.isBlank() && latest.canReply) {
            replyBusy = true
            val d = withContext(Dispatchers.IO) { run {
                val th = com.agentos.shell.tools.ConversationStore.thread(ctx, latest.app, latest.title).map { it.role to it.text }
                val m = com.agentos.shell.tools.ReplyContext.forSender(ctx, latest.app, latest.title, latest.text)
                if (th.isNotEmpty()) AgentClient.draftReplyThread(latest.title.ifBlank { latest.app }, th, m, null, latest.text)
                else AgentClient.draftReply(latest.title.ifBlank { latest.app }, latest.text, m)
            } }
            if (!AgentClient.looksLikeError(d)) draft = d
            replyBusy = false
        }
    }

    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))) {
        // Reveal layer behind the card: swipe RIGHT to open, LEFT to close.
        Row(Modifier.matchParentSize().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Open ↗", fontSize = T.small, color = if (dragX > 20f) T.accent else T.hairline)
            Spacer(Modifier.weight(1f))
            Text("Close ✕", fontSize = T.small, color = if (dragX < -20f) T.danger else T.hairline)
        }
    Column(Modifier.fillMaxWidth()
        .offset { IntOffset(dragX.roundToInt(), 0) }
        .pointerInput(latest.key) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    when {
                        dragX < -130f -> group.forEach { NotificationStore.dismiss(it.key) }   // left → close
                        dragX > 130f  -> NotificationStore.open(ctx, latest)                    // right → open
                    }
                    dragX = 0f
                },
                onDragCancel = { dragX = 0f }
            ) { _, dx -> dragX = (dragX + dx).coerceIn(-320f, 320f) }
        }
        .clip(RoundedCornerShape(16.dp)).background(T.bgElevated)
    ) {
        // Header — tap opens the actual conversation/app.
        Row(Modifier.fillMaxWidth().clickable { NotificationStore.open(ctx, latest) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            // Avatar = contact initial in the app's brand color, with the REAL app icon as a corner
            // badge — so you recognize at a glance which app each card came from.
            val icon = appIcon(ctx, latest.pkg)
            Box(Modifier.size(46.dp)) {
                Box(Modifier.size(42.dp).clip(CircleShape).background(appColor(latest.pkg)), contentAlignment = Alignment.Center) {
                    Text(contact.trim().firstOrNull()?.uppercase() ?: "•", color = Color.White, fontSize = T.body)
                }
                if (icon != null) androidx.compose.foundation.Image(
                    bitmap = icon, contentDescription = null,
                    modifier = Modifier.align(Alignment.BottomEnd).size(18.dp)
                        .clip(CircleShape).background(Color.White).padding(1.dp).clip(CircleShape))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(contact.take(30), fontSize = T.body, color = T.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (group.size > 1) Text("${group.size}", fontSize = T.caption, color = Color.White, textAlign = TextAlign.Center,
                        modifier = Modifier.clip(CircleShape).background(T.accent).padding(horizontal = 7.dp, vertical = 2.dp))
                }
                Spacer(Modifier.height(2.dp))
                Text("via ${appName(ctx, latest.pkg, latest.app)}", fontSize = T.caption, color = appColor(latest.pkg))
                // Show a real chunk of the ACTUAL message (the full text is captured), not a one-line stub —
                // so you can see what's going on without opening the app. Expands to the whole message on tap.
                if (latest.text.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(if (expanded) latest.text else latest.text.take(320),
                        fontSize = T.small, color = T.inkSoft,
                        maxLines = if (expanded) 40 else 6, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
                }
            }
        }
        // Actions — Reply (opens inline draft) and Open. Nothing else.
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (latest.canReply)
                Text(if (expanded) "Close" else if (staged != null) "✦ Reply ready" else "✦ Reply", fontSize = T.small, color = T.accent,
                    modifier = Modifier.clickable { expanded = !expanded }.padding(vertical = 4.dp, horizontal = 2.dp))
            Spacer(Modifier.weight(1f))
            Text("Open ↗", fontSize = T.small, color = T.inkSoft, modifier = Modifier.clickable { NotificationStore.open(ctx, latest) }.padding(4.dp))
        }
        // Clean inline reply — draft box + Send. No second header, no event icons.
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                if (replyBusy && draft.isBlank()) {
                    Text("drafting in your voice…", fontSize = T.small, color = T.accent)
                } else {
                    BasicTextField(draft, { draft = it },
                        textStyle = TextStyle(color = T.ink, fontSize = T.small),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(T.accent),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp)
                            .clip(RoundedCornerShape(10.dp)).background(T.bg).padding(12.dp))
                }
                Spacer(Modifier.height(9.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Send", fontSize = T.small, color = Color.White, textAlign = TextAlign.Center,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (draft.isBlank()) T.hairline else T.accent)
                            .clickable(enabled = draft.isNotBlank()) {
                                val d = draft
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) { NotificationStore.sendReply(ctx, latest, d) }
                                    sendMsg = if (ok) "sent ✓" else "couldn't send"
                                }
                            }.padding(horizontal = 22.dp, vertical = 9.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(if (replyBusy) "…" else "Regenerate", fontSize = T.small, color = T.inkSoft,
                        modifier = Modifier.clickable(enabled = !replyBusy) {
                            scope.launch {
                                replyBusy = true
                                val d = withContext(Dispatchers.IO) { run {
                val th = com.agentos.shell.tools.ConversationStore.thread(ctx, latest.app, latest.title).map { it.role to it.text }
                val m = com.agentos.shell.tools.ReplyContext.forSender(ctx, latest.app, latest.title, latest.text)
                if (th.isNotEmpty()) AgentClient.draftReplyThread(latest.title.ifBlank { latest.app }, th, m, null, latest.text)
                else AgentClient.draftReply(latest.title.ifBlank { latest.app }, latest.text, m)
            } }
                                if (!AgentClient.looksLikeError(d)) draft = d
                                replyBusy = false
                            }
                        }.padding(6.dp))
                    if (sendMsg.isNotBlank()) { Spacer(Modifier.width(12.dp)); Text(sendMsg, fontSize = T.caption, color = T.accent) }
                }
            }
        }
    }
    }
    Spacer(Modifier.height(10.dp))
}
