package com.agentos.shell.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentos.shell.theme.T

/**
 * Attachment furniture — the chip, the file row, the section label. Editorial, not decorative:
 * a typographic type-tile, a name, a whisper of a hint, and a way out. No emoji, no toy badges.
 */

/** Small letterspaced section header, e.g. SENT TO YOU. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text, fontSize = 9.sp, color = T.inkFaint,
        letterSpacing = 1.6.sp, fontWeight = FontWeight.Medium, modifier = modifier
    )
}

/** The type tile: "PDF", "IMG", "DOCX" — a quiet monospace-ish square that reads as a file. */
@Composable
fun TypeTile(kind: String, size: Int = 34, accent: Boolean = false) {
    Box(
        Modifier.size(size.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (accent) T.accent.copy(alpha = 0.14f) else T.hairline.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            kind.take(4), fontSize = 9.sp, letterSpacing = 0.6.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (accent) T.accent else T.inkFaint
        )
    }
}

/** The attached-file chip that sits under the ask bar. Tap it to preview; ✕ to remove. */
@Composable
fun AttachChip(kind: String, title: String, hint: String, onPreview: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, T.hairline, RoundedCornerShape(16.dp))
            .clickable { onPreview() }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TypeTile(kind, accent = true)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = T.small, color = T.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(hint, fontSize = 11.sp, color = T.inkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(
            Icons.Filled.Visibility, contentDescription = "Preview", tint = T.inkFaint,
            modifier = Modifier.size(17.dp).clickable { onPreview() }
        )
        Spacer(Modifier.width(14.dp))
        Icon(
            Icons.Filled.Close, contentDescription = "Remove", tint = T.inkFaint,
            modifier = Modifier.size(16.dp).clickable { onRemove() }
        )
    }
}

/** A row in the attach sheet — an action (Browse, Take a photo) or an incoming file. Optional eye = preview. */
@Composable
fun AttachRow(
    kind: String,
    title: String,
    meta: String,
    accent: Boolean = false,
    onPreview: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TypeTile(kind, accent = accent)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = T.small, color = T.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (meta.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(meta, fontSize = 11.sp, color = T.inkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (onPreview != null) {
            Icon(
                Icons.Filled.Visibility, contentDescription = "Preview", tint = T.inkFaint,
                modifier = Modifier.size(17.dp).clickable { onPreview() }
            )
        }
    }
}

/** "2h ago" / "yesterday" / "3d ago" — human, never a timestamp. */
fun agoLabel(ts: Long): String {
    if (ts <= 0) return ""
    val mins = ((System.currentTimeMillis() - ts) / 60000L).coerceAtLeast(0)
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "${mins}m ago"
        mins < 60 * 24 -> "${mins / 60}h ago"
        mins < 60 * 48 -> "yesterday"
        else -> "${mins / (60 * 24)}d ago"
    }
}
