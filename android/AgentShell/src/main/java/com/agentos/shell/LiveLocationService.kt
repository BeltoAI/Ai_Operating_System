package com.agentos.shell

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.agentos.shell.tools.ConversationStore
import com.agentos.shell.tools.MessageStore
import com.agentos.shell.tools.TelegramClient

/**
 * "Share my live location with <person> until I'm home, and navigate me there."
 *
 * A foreground service that watches the phone's location and, on a gentle interval, sends the chosen
 * contact a fresh Google Maps link over their channel (SMS auto-sends; Telegram to the paired owner).
 * When the phone gets within [ARRIVE_M] metres of home it sends a final "made it home" note and stops.
 * At startup it also opens turn-by-turn navigation home. All stock intents — no special privilege.
 */
class LiveLocationService : Service(), LocationListener {

    private var toName = "them"
    private var toNumber = ""
    private var channel = "sms"            // sms | telegram
    private var homeLat = 0.0
    private var homeLng = 0.0
    private var homeLabel = ""
    private var lastSent = 0L
    private var startedAt = 0L
    private var sends = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        toName = intent?.getStringExtra("to_name")?.ifBlank { "them" } ?: "them"
        toNumber = intent?.getStringExtra("to_number") ?: ""
        channel = intent?.getStringExtra("channel")?.ifBlank { "sms" } ?: "sms"
        homeLat = intent?.getDoubleExtra("home_lat", 0.0) ?: 0.0
        homeLng = intent?.getDoubleExtra("home_lng", 0.0) ?: 0.0
        homeLabel = intent?.getStringExtra("home_label") ?: ""
        startedAt = System.currentTimeMillis()

        try { startForeground(21, notif("Sharing your location with $toName")) } catch (e: Exception) { Log.e(TAG, "fg", e) }
        openNavigationHome()
        requestUpdates()
        return START_STICKY
    }

    // ── Location stream ──────────────────────────────────────────────────────────────────────────

    private fun requestUpdates() {
        if (!hasLocationPermission()) { stopSelf(); return }
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        try {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (lm.isProviderEnabled(p))
                    lm.requestLocationUpdates(p, 30_000L, 40f, this, Looper.getMainLooper())
            }
            // Seed with the last known fix so the first share goes out immediately.
            (lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))?.let { onLocationChanged(it) }
        } catch (e: SecurityException) { stopSelf() } catch (e: Exception) { Log.e(TAG, "req", e) }
    }

    override fun onLocationChanged(loc: Location) {
        val now = System.currentTimeMillis()

        // Arrived home? (only if we have home coords) → final note + stop.
        if (homeLat != 0.0 || homeLng != 0.0) {
            val d = FloatArray(1)
            Location.distanceBetween(loc.latitude, loc.longitude, homeLat, homeLng, d)
            updateNotif("~${d[0].toInt()} m from home · shared ${sends}×")
            if (d[0] <= ARRIVE_M) {
                send("🏠 Made it home safely.")
                stopSelf(); return
            }
        }

        // Safety cap: never run longer than MAX_MS.
        if (now - startedAt > MAX_MS) { send("📍 Ending live-location share."); stopSelf(); return }

        // Gentle throttle between shares.
        if (now - lastSent >= INTERVAL_MS || lastSent == 0L) {
            lastSent = now
            sends++
            send("📍 My live location: " + mapsLink(loc.latitude, loc.longitude) +
                 (if (homeLabel.isNotBlank() || homeLat != 0.0) "  — sharing until I'm home." else "  — I'll keep you posted."))
        }
    }

    override fun onProviderEnabled(p: String) {}
    override fun onProviderDisabled(p: String) {}
    @Deprecated("kept for older APIs") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}

    // ── Sending ──────────────────────────────────────────────────────────────────────────────────

    private fun send(text: String) {
        try {
            when (channel) {
                "telegram" -> {
                    val id = com.agentos.shell.tools.MemoryStore.telegramOwnerId(applicationContext)
                    if (id != 0L && TelegramClient.configured()) TelegramClient.sendMessage(id, text)
                }
                else -> { // sms
                    if (toNumber.isBlank()) return
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return
                    val sms = if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java) else SmsManager.getDefault()
                    sms.sendTextMessage(toNumber, null, text, null, null)
                    MessageStore.insertOne(applicationContext, toName, "SMS", toName, "me", text)
                    ConversationStore.add(applicationContext, "SMS", toName, "me", text)
                }
            }
        } catch (e: Exception) { Log.e(TAG, "send", e) }
    }

    private fun mapsLink(lat: Double, lng: Double) = "https://maps.google.com/?q=%.5f,%.5f".format(lat, lng)

    private fun openNavigationHome() {
        val dest = when {
            homeLabel.isNotBlank() -> homeLabel
            homeLat != 0.0 || homeLng != 0.0 -> "$homeLat,$homeLng"
            else -> return
        }
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(dest) + "&mode=d"))
                .setPackage("com.google.android.apps.maps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (i.resolveActivity(packageManager) != null) startActivity(i)
            else startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + Uri.encode(dest) + "&travelmode=driving"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { Log.e(TAG, "nav", e) }
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        try { (getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.removeUpdates(this) } catch (e: Exception) {}
    }

    // ── Notification ─────────────────────────────────────────────────────────────────────────────

    private fun notif(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel("loc", "Live location", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, "loc")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("SlyOS · Live location")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun updateNotif(text: String) = try {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(21, notif(text))
    } catch (e: Exception) {}

    companion object {
        private const val TAG = "SlyOS-Loc"
        private const val ARRIVE_M = 150f          // "home" radius
        private const val INTERVAL_MS = 90_000L    // send at most every 90s
        private const val MAX_MS = 3 * 60 * 60_000L // hard stop after 3h

        fun start(ctx: Context, toName: String, toNumber: String, channel: String,
                  homeLat: Double, homeLng: Double, homeLabel: String) {
            val i = Intent(ctx, LiveLocationService::class.java)
                .putExtra("to_name", toName).putExtra("to_number", toNumber).putExtra("channel", channel)
                .putExtra("home_lat", homeLat).putExtra("home_lng", homeLng).putExtra("home_label", homeLabel)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, LiveLocationService::class.java)) }
    }
}
