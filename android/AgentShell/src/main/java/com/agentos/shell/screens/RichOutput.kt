package com.agentos.shell.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentos.shell.theme.T

/**
 * Rich Visual Outputs. Turns a plain-text answer into a stylized "hero" card when it clearly leads with
 * a headline value the eye wants first: a game score, a temperature, or a price. Precision over recall —
 * detection is deliberately conservative so ordinary answers are never mangled; when nothing matches,
 * Home just shows the normal text.
 */
data class HeroCard(val big: String, val label: String, val sub: String)

object RichParse {
    private val temp = Regex("(-?\\d{1,3})\\s*°\\s*([CFcf])")
    private val price = Regex("\\$\\s?([0-9][0-9,]{0,12}(?:\\.[0-9]{1,2})?)")

    /** A hero card for [reply], or null to fall back to plain text. Only fires on an unambiguous headline
     *  value near the start (a temperature with ° or a price with $), so normal answers are never touched. */
    fun detect(reply: String): HeroCard? {
        val r = reply.trim()
        if (r.length < 3) return null
        temp.find(r)?.takeIf { it.range.first < 60 }?.let { m ->
            val (t, u) = m.destructured
            return HeroCard("$t°${u.uppercase()}", "Temperature", firstSentence(r))
        }
        price.find(r)?.takeIf { it.range.first < 60 }?.let { m ->
            return HeroCard("$" + m.groupValues[1], "Price", firstSentence(r))
        }
        return null
    }

    private fun firstSentence(s: String): String {
        val cut = s.indexOfFirst { it == '.' || it == '\n' }
        val one = if (cut in 12..90) s.substring(0, cut) else s.take(80)
        return one.trim()
    }
}

/** The stylized headline card shown above a Home answer when [RichParse] finds a hero value. */
@Composable
fun HeroCardView(card: HeroCard) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(T.accent).padding(20.dp)
    ) {
        Text(card.big, fontSize = 44.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(4.dp))
        Text(card.label.uppercase(), fontSize = T.caption, fontWeight = FontWeight.SemiBold, color = Color(0xFFFFE6D8))
        if (card.sub.isNotBlank() && !card.sub.equals(card.label, ignoreCase = true)) {
            Spacer(Modifier.height(8.dp))
            Text(card.sub, fontSize = T.small, color = Color(0xFFFFF3EC))
        }
    }
}
