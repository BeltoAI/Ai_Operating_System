package com.agentos.shell.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.agentos.shell.theme.T

/** Runtime permissions SlyOS needs to act on your behalf. Only ones actually declared in the manifest. */
private fun corePermissions(): List<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    add(Manifest.permission.SEND_SMS)
    add(Manifest.permission.READ_CONTACTS)
    add(Manifest.permission.READ_CALENDAR)
    add(Manifest.permission.WRITE_CALENDAR)
    add(Manifest.permission.CAMERA)
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}

private fun missing(ctx: Context): List<String> =
    corePermissions().filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }

/** Once-per-process guard so we auto-ask on launch but never nag on every return to Home. */
private var autoAskedThisLaunch = false

/**
 * A slim, one-tap setup bar. On the first Home render after launch it auto-requests every missing
 * runtime permission in a single system dialog; if anything is still missing it shows a tappable pill
 * to grant the rest (or jump to app settings when they were permanently denied). Renders nothing once
 * everything is granted — no clutter, no BYOK, no uploads.
 */
@Composable
fun PermissionBar(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var missingNow by remember { mutableStateOf(missing(ctx)) }
    var deniedHard by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        missingNow = missing(ctx)
        // If the user was asked but some are still missing, the system won't re-prompt → route to settings.
        deniedHard = result.isNotEmpty() && missingNow.isNotEmpty()
    }

    // Auto-ask exactly once per app launch.
    LaunchedEffect(Unit) {
        val m = missing(ctx)
        missingNow = m
        if (m.isNotEmpty() && !autoAskedThisLaunch) {
            autoAskedThisLaunch = true
            launcher.launch(m.toTypedArray())
        }
    }

    AnimatedVisibility(visible = missingNow.isNotEmpty(), enter = fadeIn() + expandVertically()) {
        Row(
            modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(14.dp))
                .background(T.accent.copy(alpha = 0.12f))
                .clickable {
                    val m = missing(ctx)
                    if (deniedHard) {
                        // Some were permanently denied — open this app's settings page.
                        try {
                            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (e: Exception) {}
                    } else launcher.launch(m.toTypedArray())
                }
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.CheckCircle, null, tint = T.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Finish setup", fontSize = T.small, color = T.ink, fontWeight = FontWeight.SemiBold)
                Text(
                    if (deniedHard) "Tap to enable ${missingNow.size} permission(s) in Settings"
                    else "Grant ${missingNow.size} permission(s) so I can act for you",
                    fontSize = T.caption, color = T.inkSoft
                )
            }
            Text(if (deniedHard) "Open settings" else "Grant", fontSize = T.caption, color = Color.White,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(T.accent).padding(horizontal = 14.dp, vertical = 7.dp))
        }
    }
}
