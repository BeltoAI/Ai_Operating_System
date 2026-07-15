package com.agentos.shell.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design tokens. Colors are reactive to [dark] — flip it and the whole app recolors, because every
 * composable that reads T.bg / T.ink etc. reads the [dark] snapshot state through these getters.
 */
object T {
    /** Global dark mode. Set at startup from MemoryStore and toggled in Settings. */
    var dark by mutableStateOf(false)

    // color — light | dark
    val bg get() = if (dark) Color(0xFF12100C) else Color(0xFFF4EFE6)
    val bgElevated get() = if (dark) Color(0xFF201B15) else Color(0xFFFBF8F2)
    val ink get() = if (dark) Color(0xFFF4EFE6) else Color(0xFF1A1714)
    val inkSoft get() = if (dark) Color(0xFFC7BEB0) else Color(0xFF5C544B)
    val inkFaint get() = if (dark) Color(0xFF8C8275) else Color(0xFF9A9085)
    val accent = Color(0xFFE8642C)                    // brand orange in both themes
    val accentSoft get() = if (dark) Color(0xFF5A3120) else Color(0xFFF2C7AE)
    val hairline get() = if (dark) Color(0xFF352D24) else Color(0xFFE2DACB)
    val danger get() = if (dark) Color(0xFFE06A5C) else Color(0xFFB23A2E)
    val good get() = if (dark) Color(0xFF5DCAA5) else Color(0xFF1D8F63)   // positive / value delivered

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

    val scriptFamily = FontFamily.Cursive
}
