package com.agentos.shell.tools

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Direct deep-links into Android's system Settings panels. Instead of making the screen agent HUNT through
 * menus to reach (say) Bluetooth, it can jump straight to the exact page in one hop — far more reliable and
 * far faster. The agent's SETTINGS <key> primitive resolves through here.
 */
object SystemPanels {
    // keyword → Settings intent action. Multiple keywords can map to the same panel.
    private val MAP: List<Pair<Regex, String>> = listOf(
        Regex("(?i)bluetooth") to Settings.ACTION_BLUETOOTH_SETTINGS,
        Regex("(?i)wi-?fi|wireless") to Settings.ACTION_WIFI_SETTINGS,
        Regex("(?i)airplane|flight mode") to Settings.ACTION_AIRPLANE_MODE_SETTINGS,
        Regex("(?i)location|gps") to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
        Regex("(?i)nfc") to Settings.ACTION_NFC_SETTINGS,
        Regex("(?i)hotspot|tether") to "android.settings.TETHER_SETTINGS",
        Regex("(?i)data usage|mobile data|cellular") to Settings.ACTION_DATA_USAGE_SETTINGS,
        Regex("(?i)display|brightness|screen") to Settings.ACTION_DISPLAY_SETTINGS,
        Regex("(?i)sound|volume|ringtone|silent|do not disturb|dnd") to Settings.ACTION_SOUND_SETTINGS,
        Regex("(?i)battery|power saver") to "android.settings.BATTERY_SAVER_SETTINGS",
        Regex("(?i)storage") to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
        Regex("(?i)date|time|clock") to Settings.ACTION_DATE_SETTINGS,
        Regex("(?i)language|keyboard|input") to Settings.ACTION_LOCALE_SETTINGS,
        Regex("(?i)security|lock screen|fingerprint|biometric") to Settings.ACTION_SECURITY_SETTINGS,
        Regex("(?i)accessibility") to Settings.ACTION_ACCESSIBILITY_SETTINGS,
        Regex("(?i)notifications?") to "android.settings.NOTIFICATION_SETTINGS",
        Regex("(?i)apps?|application manager") to Settings.ACTION_APPLICATION_SETTINGS,
        Regex("(?i)developer") to Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
        Regex("(?i)vpn") to "android.settings.VPN_SETTINGS",
        Regex("(?i)hotword|assistant|voice") to Settings.ACTION_VOICE_INPUT_SETTINGS,
        Regex("(?i)wallpaper") to "android.intent.action.SET_WALLPAPER",
        Regex("(?i)^settings$|all settings|main settings") to Settings.ACTION_SETTINGS
    )

    /** Best-matching Settings action for a free-text key ("bluetooth", "wifi", …), or null. */
    fun actionFor(key: String): String? = MAP.firstOrNull { it.first.containsMatchIn(key) }?.second

    /** Open the panel that matches [key]. Returns true if we launched something. */
    fun open(ctx: Context, key: String): Boolean {
        val action = actionFor(key) ?: return false
        return try {
            ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        } catch (e: Exception) {
            try { ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (e2: Exception) { false }
        }
    }

    /** Open a specific app's system "App info" page (permissions, force-stop, uninstall, notifications). */
    fun openAppInfo(ctx: Context, pkg: String): Boolean = try {
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: Exception) { false }
}
