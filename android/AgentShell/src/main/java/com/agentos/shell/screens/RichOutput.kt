package com.agentos.shell.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentos.shell.theme.T

/**
 * Rich Visual Outputs — turns a plain answer into an elegant hero card when it leads with a headline
 * value worth seeing first: weather, a price, a stock move, or a game score. Design language is quiet and
 * premium (soft gradient surface, hairline border, generous space, one big number) rather than a loud
 * filled box. Detection stays conservative so ordinary answers are never restyled.
 */
sealed class Hero {
    data class Metric(val eyebrow: String, val big: String, val unit: String, val sub: String) : Hero()
    data class Stock(val eyebrow: String, val price: String, val delta: String, val up: Boolean, val sub: String) : Hero()
    data class Score(val teamA: String, val a: Int, val teamB: String, val b: Int) : Hero()
}

private val GREEN = Color(0xFF1FA855)

object RichParse {
    private val temp = Regex("(-?\\d{1,3})\\s*°\\s*([CFcf])")
    private val price = Regex("\\$\\s?([0-9][0-9,]{0,12}(?:\\.[0-9]{1,2})?)")
    private val pctSigned = Regex("([+-]\\d{1,3}(?:\\.\\d+)?)\\s*%")
    private val pctWord = Regex("(?i)\\b(up|down|gained|lost|fell|rose|climbed|dropped|jumped|slid|surged)\\b[^%\\n]{0,14}?(\\d{1,3}(?:\\.\\d+)?)\\s*%")
    private val score = Regex("([A-Z][A-Za-z.&'0-9 ]{1,20}?)\\s+(\\d{1,3})\\s*[-–—]\\s*(\\d{1,3})\\s+([A-Z][A-Za-z.&'0-9 ]{1,20})")
    private val gameWord = Regex("(?i)\\b(beat|defeat|final|score|won|win|vs\\.?|versus|match|game|quarter|half[- ]?time|inning|goals?|touchdown|nba|nfl|mlb|nhl|premier league|la liga)\\b")
    private val stopTickers = setOf("THE", "AND", "FOR", "USD", "CEO", "ETF", "IPO", "USA", "GDP", "API", "AI", "PM", "AM", "EPS", "YOY", "Q1", "Q2", "Q3", "Q4")

    /** A hero card for [reply], or null to fall back to plain text. */
    fun detect(reply: String): Hero? {
        val r = reply.trim()
        if (r.length < 3) return null

        temp.find(r)?.takeIf { it.range.first < 60 }?.let { m ->
            val (t, u) = m.destructured
            return Hero.Metric("Weather", "$t°${u.uppercase()}", "", firstSentence(r))
        }

        // Game score — only when the text clearly reads like a match result (avoids "iPhone 15 - 128 GB").
        if (gameWord.containsMatchIn(r)) score.find(r)?.takeIf { it.range.first < 110 }?.let { m ->
            val (ta, sa, sb, tb) = m.destructured
            val ai = sa.toIntOrNull(); val bi = sb.toIntOrNull()
            if (ai != null && bi != null) return Hero.Score(ta.trim(), ai, tb.trim(), bi)
        }

        // Price present → either a stock move (price + %) or a plain price headline.
        price.find(r)?.takeIf { it.range.first < 60 }?.let { m ->
            val priceStr = "$" + m.groupValues[1]
            val signed = pctSigned.find(r)
            val word = pctWord.find(r)
            if (signed != null || word != null) {
                val (delta, up) = when {
                    signed != null -> signed.groupValues[1] to !signed.groupValues[1].startsWith("-")
                    else -> {
                        val w = word!!.groupValues[1].lowercase()
                        val n = word.groupValues[2]
                        val u = w in setOf("up", "gained", "rose", "climbed", "jumped", "surged")
                        (if (u) "+$n" else "-$n") to u
                    }
                }
                return Hero.Stock(tickerOf(r), priceStr, "$delta%", up, firstSentence(r))
            }
            return Hero.Metric("Price", priceStr, "", firstSentence(r))
        }
        return null
    }

    private fun tickerOf(s: String): String {
        val m = Regex("\\b([A-Z]{2,5})\\b").findAll(s).map { it.value }.firstOrNull { it !in stopTickers }
        return m ?: "Market"
    }

    private fun firstSentence(s: String): String {
        val cut = s.indexOfFirst { it == '.' || it == '\n' }
        val one = if (cut in 12..96) s.substring(0, cut) else s.take(84)
        return one.trim()
    }
}

/** The elegant headline card shown above a Home answer when [RichParse] finds a hero value. */
@Composable
fun HeroCardView(hero: Hero) {
    val surface = Brush.verticalGradient(listOf(T.bgElevated, T.accentSoft.copy(alpha = 0.22f)))
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(surface)
            .border(1.dp, T.hairline, RoundedCornerShape(22.dp))
            .padding(horizontal = 24.dp, vertical = 22.dp)
    ) {
        when (hero) {
            is Hero.Metric -> {
                Eyebrow(hero.eyebrow)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(hero.big, fontSize = 52.sp, fontWeight = FontWeight.Light, color = T.ink, letterSpacing = (-1).sp)
                    if (hero.unit.isNotBlank()) {
                        Spacer(Modifier.width(4.dp))
                        Text(hero.unit, fontSize = T.body, color = T.inkSoft, modifier = Modifier.padding(bottom = 10.dp))
                    }
                }
                if (hero.sub.isNotBlank() && !hero.sub.equals(hero.eyebrow, true)) {
                    Spacer(Modifier.height(8.dp)); Text(hero.sub, fontSize = T.small, color = T.inkSoft)
                }
            }
            is Hero.Stock -> {
                Eyebrow(hero.eyebrow)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(hero.price, fontSize = 46.sp, fontWeight = FontWeight.Light, color = T.ink, letterSpacing = (-1).sp)
                    Spacer(Modifier.width(14.dp))
                    DeltaPill(hero.delta, hero.up)
                }
                if (hero.sub.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(hero.sub, fontSize = T.small, color = T.inkSoft) }
            }
            is Hero.Score -> {
                Eyebrow("Final score")
                Spacer(Modifier.height(14.dp))
                TeamRow(hero.teamA, hero.a, hero.a >= hero.b)
                Spacer(Modifier.height(10.dp))
                TeamRow(hero.teamB, hero.b, hero.b >= hero.a)
            }
        }
    }
}

@Composable
private fun Eyebrow(text: String) {
    Text(text.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = T.accent, letterSpacing = 2.sp)
}

@Composable
private fun DeltaPill(delta: String, up: Boolean) {
    val c = if (up) GREEN else T.danger
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(c.copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(if (up) "▲" else "▼", fontSize = 11.sp, color = c)
        Spacer(Modifier.width(5.dp))
        Text(delta, fontSize = T.small, fontWeight = FontWeight.SemiBold, color = c)
    }
}

@Composable
private fun TeamRow(team: String, score: Int, winner: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            if (winner) { Box(Modifier.width(3.dp).height(22.dp).clip(RoundedCornerShape(999.dp)).background(T.accent)); Spacer(Modifier.width(10.dp)) }
            else Spacer(Modifier.width(13.dp))
            Text(team, fontSize = T.body, fontWeight = if (winner) FontWeight.SemiBold else FontWeight.Normal,
                color = if (winner) T.ink else T.inkSoft)
        }
        Text("$score", fontSize = 30.sp, fontWeight = if (winner) FontWeight.SemiBold else FontWeight.Light,
            color = if (winner) T.ink else T.inkSoft)
    }
}
