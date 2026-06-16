package com.agentos.shell.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentos.shell.theme.T

/** Cursive "SlyOS" wordmark. Inspired-by handwritten energy — not a copy of any logo. */
@Composable
fun Wordmark(big: Boolean = false) = Text(
    "SlyOS",
    fontFamily = T.scriptFamily,
    fontSize = if (big) T.wordmarkBig else T.wordmark,
    color = T.ink,
    fontWeight = FontWeight.Medium
)

@Composable
fun OrangeDot(modifier: Modifier = Modifier) =
    Spacer(modifier.size(7.dp).clip(CircleShape).background(T.accent))

@Composable
fun Heading(text: String) =
    Text(text, fontSize = 22.sp, color = T.ink, fontWeight = FontWeight.Medium)

/** Title row with a back arrow that returns to Home. */
@Composable
fun ScreenHeader(title: String, onBack: () -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Filled.ArrowBack,
            contentDescription = "Back",
            tint = T.ink,
            modifier = Modifier.size(24.dp).clickable { onBack() }
        )
        Spacer(Modifier.width(12.dp))
        Text(title, fontSize = 22.sp, color = T.ink, fontWeight = FontWeight.Medium)
    }

@Composable
fun Hairline() =
    Spacer(Modifier.fillMaxWidth().height(1.dp).background(T.hairline))
