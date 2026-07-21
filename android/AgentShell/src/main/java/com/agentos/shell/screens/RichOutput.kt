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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.text.font.FontStyle
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
    data class YesNo(val yes: Boolean, val sub: String) : Hero()
    data class Quote(val text: String, val author: String) : Hero()
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
    // "3 days until launch", "2 weeks left" — a clear time-to-event headline.
    private val countdown = Regex("(?i)\\b(\\d{1,4})\\s*(day|days|week|weeks|hour|hours|month|months)\\s+(?:until|till|til|to go|left|remaining|away|before)\\b")
    // A rating like "4.5 out of 5" or "9/10" — only trusted when the text is clearly about a rating.
    private val ratingCtx = Regex("(?i)\\b(star|stars|rating|rated|review|reviews)\\b")
    private val rating = Regex("\\b(\\d(?:\\.\\d)?)\\s*(?:/|out of)\\s*(\\d{1,2})\\b")
    // Currency conversion — "€100 = $108", "£50 ≈ $63".
    private val convert = Regex("([$€£¥]\\s?[0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(?:=|≈|~|is about|is roughly|equals?)\\s*([$€£¥]\\s?[0-9][0-9,]*(?:\\.[0-9]+)?)")
    // Time in a place — "3:45 PM in Tokyo".
    private val timeIn = Regex("(?i)\\b(\\d{1,2}:\\d{2}\\s*(?:[ap]\\.?m\\.?)?)\\s+in\\s+([A-Z][A-Za-z .'-]{2,28})")
    // A bare calculation result — a short reply that ends in "= number".
    private val mathEq = Regex("=\\s*(-?[0-9][0-9,]*(?:\\.[0-9]+)?)\\s*\\.?\\s*$")
    // Translation — "in Spanish: hola" / "in French is « bonjour »".
    private val translate = Regex("(?i)\\bin (spanish|french|german|italian|portuguese|japanese|chinese|korean|russian|arabic|hindi|dutch|greek|latin)\\b\\s*(?:is|:|,)?\\s*[\"“«']?([\\p{L} ]{1,40})[\"”»']?")
    // A quotation with attribution — "…" — Author.
    private val quote = Regex("[\"“](.{12,220}?)[\"”]\\s*[—–-]\\s*([A-Z][A-Za-z. '-]{2,40})")
    // Distance / weight with a unit, near the start.
    private val measure = Regex("(?i)\\b([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(km|kilometers?|miles?|kg|kilograms?|pounds?|lbs)\\b")

    /** A hero card for [reply], or null to fall back to plain text. */
    fun detect(reply: String): Hero? {
        val r = reply.trim()
        if (r.length < 3) return null

        temp.find(r)?.takeIf { it.range.first < 60 }?.let { m ->
            val (t, u) = m.destructured
            return Hero.Metric("Weather", "$t°${u.uppercase()}", "", firstSentence(r))
        }

        // Countdown — "3 days until launch", "2 weeks left".
        countdown.find(r)?.takeIf { it.range.first < 90 }?.let { m ->
            val (num, unit) = m.destructured
            return Hero.Metric("Countdown", num, unit.lowercase(), firstSentence(r))
        }

        // Rating — only when the text clearly talks about a rating/review (avoids "5/10 people").
        if (ratingCtx.containsMatchIn(r)) rating.find(r)?.let { m ->
            val (a, b) = m.destructured
            return Hero.Metric("Rating", "$a/$b", "", firstSentence(r))
        }

        // Quotation with attribution — "…" — Author.
        quote.find(r)?.let { m ->
            val (txt, author) = m.destructured
            return Hero.Quote(txt.trim(), author.trim())
        }

        // Currency conversion — show the converted value big.
        convert.find(r)?.takeIf { it.range.first < 90 }?.let { m ->
            val (from, to) = m.destructured
            return Hero.Metric("Conversion", to.trim(), "", from.trim() + " = " + to.trim())
        }

        // Time in a place — "3:45 PM in Tokyo".
        timeIn.find(r)?.takeIf { it.range.first < 60 }?.let { m ->
            val (t, place) = m.destructured
            return Hero.Metric("Time · " + place.trim(), t.trim().uppercase(), "", firstSentence(r))
        }

        // Translation — a single word/phrase in another language.
        translate.find(r)?.let { m ->
            val lang = m.groupValues[1].replaceFirstChar { it.uppercase() }
            val word = m.groupValues[2].trim()
            if (word.length in 1..40) return Hero.Metric(lang, word, "", firstSentence(r))
        }

        // Measurement — distance / weight with a unit near the start.
        measure.find(r)?.takeIf { it.range.first < 55 }?.let { m ->
            val (n, unit) = m.destructured
            return Hero.Metric("Measurement", n, unit.lowercase(), firstSentence(r))
        }

        // Bare calculation result — only for a short reply ending in "= number".
        if (r.length < 70) mathEq.find(r)?.let { m ->
            return Hero.Metric("Result", m.groupValues[1], "", firstSentence(r))
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

        // Yes / No — a clear affirmative or negative opener on a concise answer.
        //
        // A giant YES or NO is the most assertive thing this app can put on screen, so it has to be
        // right. Two ways it was wrong:
        //
        //  1. NUANCE. "No, he's not in your contacts, but I did find him in your messages" opened with
        //     "No" and got a huge NO headline sitting directly above a body saying the person WAS
        //     found. The card flatly contradicted the answer underneath it. Any pivot word means the
        //     answer is qualified, and a qualified answer has no business being reduced to one word.
        //  2. IDIOM. "No problem", "No worries", "Yes please" aren't answers to a yes/no question at
        //     all — they're conversational filler that happens to start with the right token.
        if (r.length < 280) Regex("(?i)^(yes|yep|yeah|correct|absolutely|no|nope|nah)\\b[,.!: ]").find(r)?.let { m ->
            val w = m.groupValues[1].lowercase()
            val idiom = Regex("(?i)^(no (problem|worries|need|rush|idea)|yes please|yeah sure)\\b").containsMatchIn(r)
            val qualified = Regex("(?i)\\b(but|however|although|though|that said|on the other hand|technically|" +
                "sort of|kind of|depends|unless|except)\\b").containsMatchIn(r)
            if (!idiom && !qualified) {
                return Hero.YesNo(w in setOf("yes", "yep", "yeah", "correct", "absolutely"), firstSentence(r))
            }
        }
        return null
    }

    /**
     * The reliable path: the model may prefix an answer with a machine card tag it controls, e.g.
     * `[[card:score;Warriors;121;Lakers;118]]`. We parse that (100% reliable, phrasing-independent) and
     * strip it from the displayed text. Types: score, stat, stock, quote, yesno.
     */
    fun fromTag(reply: String): Pair<Hero?, String> {
        val m = Regex("^\\s*\\[\\[card:([^\\]]+)\\]\\]\\s*", RegexOption.IGNORE_CASE).find(reply) ?: return null to reply
        val body = reply.removeRange(m.range).trim()
        val p = m.groupValues[1].split(";").map { it.trim() }
        fun g(i: Int) = p.getOrElse(i) { "" }
        val hero: Hero? = when (g(0).lowercase()) {
            "score" -> { val a = g(2).toIntOrNull(); val b = g(4).toIntOrNull(); if (a != null && b != null) Hero.Score(g(1), a, g(3), b) else null }
            "stat", "metric" -> if (g(2).isNotBlank()) Hero.Metric(g(1).ifBlank { "" }, g(2), g(3), g(4)) else null
            "stock" -> if (g(2).isNotBlank()) Hero.Stock(g(1).ifBlank { "Market" }, g(2), g(3), !g(3).startsWith("-"), g(4)) else null
            "quote" -> if (g(1).isNotBlank()) Hero.Quote(g(1), g(2)) else null
            "yesno" -> Hero.YesNo(g(1).lowercase().startsWith("y"), g(2))
            else -> null
        }
        return hero to body
    }

    /** Card from the model's tag if present, otherwise from best-effort detection — plus the clean text. */
    fun render(reply: String): Pair<Hero?, String> {
        val (tagHero, body) = fromTag(reply)
        if (tagHero != null) return tagHero to body
        return detect(body) to body
    }

    private fun tickerOf(s: String): String {
        val m = Regex("\\b([A-Z]{2,5})\\b").findAll(s).map { it.value }.firstOrNull { it !in stopTickers }
        return m ?: "Market"
    }

    /**
     * The card's one-line subtitle.
     *
     * The old version cut at a hard character count, which lands mid-word — a subtitle about a venture
     * investor rendered as "VC S", which looks like a rendering fault rather than an abbreviation. If
     * there's no sentence break to use, back up to the last whole word and mark the cut with an
     * ellipsis so it reads as deliberately shortened.
     */
    private fun firstSentence(s: String): String {
        val cut = s.indexOfFirst { it == '.' || it == '\n' }
        if (cut in 12..96) return s.substring(0, cut).trim()
        val t = s.trim()
        if (t.length <= 84) return t
        val slice = t.substring(0, 84)
        val lastSpace = slice.lastIndexOf(' ')
        // Only honour the word boundary if it doesn't gut the line — otherwise a single long token
        // would leave us with almost nothing.
        val body = if (lastSpace >= 40) slice.substring(0, lastSpace) else slice
        return body.trimEnd().trimEnd(',', ';', ':', '-', '—') + "…"
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
            is Hero.YesNo -> {
                val c = if (hero.yes) GREEN else T.danger
                Eyebrow("Answer")
                Spacer(Modifier.height(8.dp))
                Text(if (hero.yes) "Yes" else "No", fontSize = 48.sp, fontWeight = FontWeight.Bold, color = c, letterSpacing = (-1).sp)
                if (hero.sub.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(hero.sub, fontSize = T.small, color = T.inkSoft) }
            }
            is Hero.Quote -> {
                Row {
                    Box(Modifier.width(3.dp).heightIn(min = 30.dp).clip(RoundedCornerShape(999.dp)).background(T.accent))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("“" + hero.text + "”", fontSize = 20.sp, fontWeight = FontWeight.Light, color = T.ink, fontStyle = FontStyle.Italic)
                        Spacer(Modifier.height(8.dp))
                        Text("— " + hero.author, fontSize = T.small, fontWeight = FontWeight.Medium, color = T.accent)
                    }
                }
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
