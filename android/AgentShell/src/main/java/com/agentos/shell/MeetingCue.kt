package com.agentos.shell

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.agentos.shell.tools.MeetingStore

/**
 * "Take notes" that actually takes notes.
 *
 * The first version of this toggle set a reminder saying "open Meetings and tap record". That is
 * honest, and it is also useless at the only moment it matters. A meeting starts while you are
 * greeting someone, finding a chair, or already talking — nobody unlocks a phone, finds an app,
 * finds a screen and finds a button in that window. The reminder gets swiped and the meeting goes
 * unrecorded, which is exactly the outcome the toggle was ticked to prevent.
 *
 * So the notification carries the action itself. One tap on the lock screen starts the recorder,
 * already named after the calendar block and already linked to it, so the notes that come out the
 * other end know which meeting they belong to and can be mailed to the people who were in it.
 *
 * What this is NOT: Google's own "take notes for me". That is a Workspace feature with no Calendar
 * API surface — it cannot be switched on from here by anyone, and a toggle claiming otherwise would
 * be a lie told at the moment of maximum trust. This records on the device, with the recorder SlyOS
 * already has.
 */
class MeetingCue : BroadcastReceiver() {

    companion object {
        const val CHANNEL = "meeting_cue"
        const val EXTRA_TITLE = "title"
        const val EXTRA_START = "start"
        /** Tapped "Record" — start the service rather than opening a screen. */
        const val CMD_RECORD = "record"

        /**
         * Put a cue at the start of a meeting.
         *
         * Fires slightly BEFORE the hour, because a recorder started two minutes late has already
         * missed the part where everyone says what the meeting is for.
         */
        fun schedule(ctx: Context, title: String, startMs: Long): Boolean = try {
            val at = startMs - 60_000L
            if (at <= System.currentTimeMillis()) false else {
                val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val pi = PendingIntent.getBroadcast(
                    ctx, (startMs % 900_000).toInt() + 4_000,
                    Intent(ctx, MeetingCue::class.java)
                        .putExtra(EXTRA_TITLE, title).putExtra(EXTRA_START, startMs),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
                if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                true
            }
        } catch (e: Exception) { false }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val title = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: "Meeting"
        val startMs = intent.getLongExtra(EXTRA_START, 0L)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Start it, link it to the block, and let the service own everything after that.
        if (intent.getStringExtra("cmd") == CMD_RECORD) {
            nm.cancel(startMs.toInt())
            val id = try { MeetingStore.start(ctx, title, title, startMs) } catch (e: Exception) { 0L }
            if (id != 0L) MeetingService.start(ctx, id)
            return
        }

        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL, "Meeting notes", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Offers to record meetings you asked SlyOS to take notes on"
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            })
        }

        val record = PendingIntent.getBroadcast(
            ctx, (startMs % 900_000).toInt() + 5_000,
            Intent(ctx, MeetingCue::class.java)
                .putExtra("cmd", CMD_RECORD)
                .putExtra(EXTRA_TITLE, title).putExtra(EXTRA_START, startMs),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(
            ctx, 41, Intent(ctx, ShellActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        nm.notify(startMs.toInt(), Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText("Starting now — record it and take notes?")
            .setStyle(Notification.BigTextStyle()
                .bigText("Starting now. Tap Record and SlyOS will transcribe it, separate who " +
                    "said what, and put your commitments on your list."))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_EVENT)
            .setPriority(Notification.PRIORITY_HIGH)
            .addAction(Notification.Action.Builder(
                null as android.graphics.drawable.Icon?, "Record", record).build())
            .build())
    }
}
