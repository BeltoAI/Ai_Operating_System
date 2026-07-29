package com.agentos.shell.tools

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * Whether SlyOS is allowed to drive the phone at all — asked BEFORE the first tap, never after.
 *
 * Two permissions have to be in place, and until now neither was checked at the right moment.
 * Accessibility was checked inside the run, so a request that could never work still got as far as
 * starting and then died into the outbox where nobody was looking. Draw-over-apps was worse:
 * [com.agentos.shell.StopOverlayService.canShow] returns false without it and `show()` then returns
 * having done nothing, so the agent began tapping and typing on someone's phone **with no stop
 * button and no warning that there wasn't one**. The single control that must never be missing was
 * the one that failed quietly.
 *
 * So the rule this file exists to enforce:
 *
 *   > SlyOS never takes over a screen it cannot be stopped on.
 *
 * The check happens before anything is opened, tapped or typed. If it fails, nothing runs — the
 * request is refused with the reason and the one tap that fixes it, rather than half-attempted.
 */
object ScreenControlGate {

    enum class State {
        /** Both permissions in place — a run may start. */
        READY,
        /** The accessibility service isn't enabled, so there is nothing to read or tap the screen with. */
        NO_ACCESSIBILITY,
        /** Accessibility is on, but nothing could float a stop button over other apps. */
        NO_OVERLAY
    }

    /**
     * Order matters: accessibility first, because without it screen control does not exist at all
     * and asking for the overlay would be asking for a permission that buys nothing yet.
     */
    fun state(ctx: Context): State = when {
        com.agentos.shell.InteractionLogService.instance == null -> State.NO_ACCESSIBILITY
        !canOverlay(ctx) -> State.NO_OVERLAY
        else -> State.READY
    }

    fun ready(ctx: Context): Boolean = state(ctx) == State.READY

    private fun canOverlay(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 23 || try { Settings.canDrawOverlays(ctx) } catch (e: Exception) { false }

    // MARK: - What the owner is told

    /** The heading on the sheet. States the situation, not the permission's official name. */
    fun title(s: State): String = when (s) {
        State.NO_ACCESSIBILITY -> "SlyOS can't see your screen yet"
        State.NO_OVERLAY -> "SlyOS is about to control your phone"
        State.READY -> ""
    }

    /**
     * Why this is being asked, in terms of what it buys the owner.
     *
     * The overlay text deliberately leads with the stop button rather than with the permission.
     * Nobody wants to grant "display over other apps"; everybody wants a way to stop something that
     * is typing on their behalf.
     */
    fun why(s: State): String = when (s) {
        State.NO_ACCESSIBILITY ->
            "To open apps and tap through them for you, SlyOS needs screen control. " +
            "Turn on Total Recall under Installed apps."
        State.NO_OVERLAY ->
            "It needs a stop button that floats over other apps. Once it's driving, the " +
            "notification shade is exactly the thing you can't reach — pulling it down fights the " +
            "automation for the same screen."
        State.READY -> ""
    }

    fun action(s: State): String = when (s) {
        State.NO_ACCESSIBILITY -> "Open accessibility settings"
        State.NO_OVERLAY -> "Allow the stop button"
        State.READY -> ""
    }

    /**
     * One line for the reply, the outbox and the brain — so "did it post that?" is answerable later.
     * Written as a refusal with a reason, never as a failure.
     */
    fun refusal(s: State): String = when (s) {
        State.NO_ACCESSIBILITY ->
            "**I didn't touch your phone** — screen control is off, so I can't see or tap anything. " +
            "Turn on Total Recall in accessibility settings and ask again."
        State.NO_OVERLAY ->
            "**I didn't touch your phone** — I won't drive it without a way for you to stop me. " +
            "Allow the floating stop button and ask again."
        State.READY -> ""
    }

    /** The settings page that fixes it. Null only if the device has no such screen. */
    fun settingsIntent(ctx: Context, s: State): Intent? = try {
        when (s) {
            State.NO_ACCESSIBILITY ->
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            State.NO_OVERLAY ->
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            State.READY -> null
        }
    } catch (e: Exception) { null }

    /**
     * What a Publish-style button should say when the gate isn't open.
     *
     * The point is that the obstacle is visible BEFORE it is pressed. A button that looks live and
     * then explains itself only on tap teaches people that the app is unreliable.
     */
    fun buttonLabel(ctx: Context, verb: String = "Publish"): String = when (state(ctx)) {
        State.READY -> "$verb (beta)"
        else -> "$verb (needs screen control)"
    }
}
