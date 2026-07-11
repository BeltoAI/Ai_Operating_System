package com.agentos.shell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telephony.SmsManager
import android.util.Log
import com.agentos.shell.tools.ConversationStore
import com.agentos.shell.tools.MessageStore
import com.agentos.shell.tools.MemoryStore

/**
 * ON-DEVICE AI CALL HANDLING (the achievable core on stock Android).
 *
 * Android's telephony audio stream is locked to system/carrier apps, so a third-party app CANNOT feed
 * live two-way audio into a cellular or WhatsApp call — that needs the SlyOS Phone (our own OS) or a
 * server-forwarding number (see VOICE_CALLS.md). What we CAN do on any phone, fully on-device, is screen
 * incoming calls: let people you know ring through, and have your AI handle the rest — decline the call
 * and text the caller back a reply written from your brain, in your voice. Plus a notification to answer
 * with the AI on speaker (which reuses the working voice loop).
 */
class SlyCallScreeningService : CallScreeningService() {

    override fun onScreenCall(details: Call.Details) {
        val allow = CallResponse.Builder().build()
        try {
            // Only incoming calls, only when the user turned this on.
            if (details.callDirection != Call.Details.DIRECTION_INCOMING ||
                !MemoryStore.aiCallHandling(applicationContext)) {
                respondToCall(details, allow); return
            }
            val number = details.handle?.schemeSpecificPart
            if (number.isNullOrBlank()) { respondToCall(details, allow); return }

            // People in your contacts always ring through untouched.
            val name = contactName(number)
            if (name != null) { respondToCall(details, allow); return }

            // Unknown caller → AI handles it.
            if (MemoryStore.callTextBack(applicationContext)) {
                val reject = CallResponse.Builder()
                    .setDisallowCall(true)     // don't let it ring
                    .setRejectCall(true)       // send it away (like a decline)
                    .setSkipCallLog(false)     // still log it so you can see who called
                    .setSkipNotification(false)
                    .build()
                respondToCall(details, reject)
                textBack(number)
            } else {
                respondToCall(details, allow)  // let it ring; just surface the "answer with AI" action
            }
            notifyHandled(number)
        } catch (e: Exception) {
            Log.e(TAG, "screen", e)
            try { respondToCall(details, allow) } catch (e2: Exception) {}
        }
    }

    /** Reverse-lookup a number in the user's contacts; null if it's a stranger. */
    private fun contactName(number: String): String? = try {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: Exception) { null }

    /** Text the caller a reply written from the brain, in the owner's voice. */
    private fun textBack(number: String) {
        Thread {
            try {
                val ctx = applicationContext
                val owner = MemoryStore.ownerName(ctx).ifBlank { MemoryStore.profileName(ctx) }.trim()
                val who = owner.ifBlank { "I" }
                val msg = if (owner.isBlank())
                    "Hi — I can't take calls right now, but this is my assistant. Text me what you need and I'll make sure I see it."
                else
                    "Hi, this is $who's assistant — $who can't take calls right now. Send a text with what you need and I'll make sure $who sees it."
                val sms = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
                sms.sendTextMessage(number, null, msg, null, null)
                // Feed the brain so you can see the exchange later.
                val label = "Call from $number"
                MessageStore.insertOne(ctx, label, "Calls", label, "me", msg)
                ConversationStore.add(ctx, "Calls", label, "me", msg)
            } catch (e: Exception) { Log.e(TAG, "textBack", e) }
        }.start()
    }

    /** A heads-up notification: who called, and a one-tap "answer with AI on speaker". */
    private fun notifyHandled(number: String) {
        // Log every screened call into the brain, timestamped.
        Thread {
            try {
                val t = java.text.SimpleDateFormat("MMM d, HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                val label = "Call from $number"
                MessageStore.insertOne(applicationContext, label, "Calls", label, "them", "📞 Call from $number — screened by AI at $t")
            } catch (e: Exception) {}
        }.start()
        try {
            val ctx = applicationContext
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26)
                nm.createNotificationChannel(NotificationChannel("calls", "AI call handling", NotificationManager.IMPORTANCE_HIGH))
            // Tapping opens SlyOS straight into the voice assistant (reuses the working listen→brain→speak loop;
            // put the call on speaker and the AI converses with the caller in your cloned voice).
            val open = Intent(ctx, ShellActivity::class.java)
                .putExtra("nav", "Converse").putExtra("call_from", number)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pi = PendingIntent.getActivity(ctx, number.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(ctx, "calls")
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle("SlyOS handled a call")
                .setContentText("$number — tap to answer with AI on speaker")
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            nm.notify(number.hashCode(), n)
        } catch (e: Exception) { Log.e(TAG, "notify", e) }
    }

    companion object { private const val TAG = "SlyOS-Call" }
}
