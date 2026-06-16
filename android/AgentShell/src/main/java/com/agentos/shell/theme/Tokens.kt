package com.agentos.shell.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Generated to match ui/tokens.json. Minimal Apple-like: ivory, near-black, one orange. */
object T {
    // color
    val bg = Color(0xFFF4EFE6)
    val bgElevated = Color(0xFFFBF8F2)
    val ink = Color(0xFF1A1714)
    val inkSoft = Color(0xFF5C544B)
    val inkFaint = Color(0xFF9A9085)
    val accent = Color(0xFFE8642C)
    val accentSoft = Color(0xFFF2C7AE)
    val hairline = Color(0xFFE2DACB)
    val danger = Color(0xFFB23A2E)

    // type sizes
    val wordmark = 30.sp
    val wordmarkBig = 46.sp
    val time = 60.sp
    val prompt = 26.sp
    val body = 17.sp
    val small = 14.sp
    val caption = 12.sp

    // spacing
    val xs = 4.dp; val sm = 8.dp; val md = 16.dp; val lg = 24.dp; val xl = 40.dp; val xxl = 72.dp

    // The wordmark uses a cursive face; ship a bundled script font (e.g. Caveat) as
    // res/font/script.ttf and swap FontFamily.Cursive for it. Cursive is the safe fallback.
    val scriptFamily = FontFamily.Cursive
}
