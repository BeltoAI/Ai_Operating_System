package com.agentos.shell.tools

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs real shell commands in the user's Termux (a full Linux userland: python, pip, clang, git,
 * even llama.cpp) via Termux's RUN_COMMAND service, and returns stdout/stderr. This is what lets
 * Cowork actually BUILD AND RUN things locally — Termux is the execution engine, SlyOS drives it.
 *
 * Setup the user does once: install Termux (F-Droid), run `pkg install termux-api`, and set
 * `allow-external-apps=true` in ~/.termux/termux.properties.
 */
object TermuxBridge {
    private const val TAG = "SlyOS"
    private const val TERMUX = "com.termux"

    fun isInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getLaunchIntentForPackage(TERMUX) != null ||
            ctx.packageManager.getPackageInfo(TERMUX, 0) != null
    } catch (e: Exception) { false }

    /** Run [command] in Termux (bash -c), blocking up to [timeoutMs]. Returns combined output. */
    fun run(ctx: Context, rawCommand: String, timeoutMs: Long = 90_000): String {
        if (!isInstalled(ctx)) return "Termux isn't installed. Install it from F-Droid, then run `pkg install termux-api` and set allow-external-apps=true."
        // If the user has saved a GitHub token, export it for every command so `gh` and `git push`
        // work non-interactively — no `gh auth login` device flow needed. This is what makes
        // "push to GitHub" one-tap: paste the token once in Settings, then it just works.
        val token = MemoryStore.githubToken(ctx)
        // Default working directory = the shared SlyOS project dir. It's visible to BOTH sides: Termux
        // sees it at ~/storage/downloads/SlyOS (after `termux-setup-storage`) and the phone sees it as
        // Downloads/SlyOS — so files the agent builds, runs, and pushes all live in one place the user
        // can also open. A command can still `cd` elsewhere afterwards.
        val cwd = "SLYDIR=~/storage/downloads/SlyOS; mkdir -p \"\$SLYDIR\" 2>/dev/null; cd \"\$SLYDIR\" 2>/dev/null || cd ~; "
        val tokenExport = if (token.isNotBlank())
            "export GH_TOKEN='$token'; export GITHUB_TOKEN='$token'; git config --global credential.helper store 2>/dev/null; " +
                "printf 'https://x-access-token:%s@github.com\\n' \"$token\" > ~/.git-credentials 2>/dev/null; "
        else ""
        val command = cwd + tokenExport + rawCommand
        val latch = CountDownLatch(1)
        var result = ""
        val action = "com.agentos.shell.TERMUX_RESULT_" + System.nanoTime()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                try {
                    val b = i?.getBundleExtra("result")
                    val stdout = b?.getString("stdout").orEmpty()
                    val stderr = b?.getString("stderr").orEmpty()
                    val exit = b?.getInt("exitCode", -1) ?: -1
                    val err = b?.getString("errmsg").orEmpty()
                    result = buildString {
                        if (stdout.isNotBlank()) append(stdout.trim())
                        if (stderr.isNotBlank()) { if (isNotEmpty()) append("\n"); append("[stderr] ").append(stderr.trim()) }
                        if (err.isNotBlank()) { if (isNotEmpty()) append("\n"); append("[error] ").append(err.trim()) }
                        if (isEmpty()) append("(no output)")
                        append("\n[exit ").append(exit).append("]")
                    }
                } catch (e: Exception) { result = "Couldn't read Termux output." }
                latch.countDown()
            }
        }
        try {
            val filter = IntentFilter(action)
            if (Build.VERSION.SDK_INT >= 33)
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") ctx.registerReceiver(receiver, filter)
            val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(ctx, 0, Intent(action).setPackage(ctx.packageName), piFlags)
            val intent = Intent().apply {
                setClassName(TERMUX, "com.termux.app.RunCommandService")
                this.action = "com.termux.RUN_COMMAND"
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
                putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi)
            }
            ContextCompat.startForegroundService(ctx, intent)
        } catch (e: Exception) {
            try { ctx.unregisterReceiver(receiver) } catch (ex: Exception) {}
            Log.e(TAG, "termux run failed", e)
            return "Couldn't reach Termux. Make sure it's installed and `allow-external-apps=true` is set in ~/.termux/termux.properties."
        }
        val ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        try { ctx.unregisterReceiver(receiver) } catch (e: Exception) {}
        if (!ok) return "Timed out after ${timeoutMs / 1000}s (command may still be running in Termux)."
        val r = result.take(9000)
        // Translate the one common setup error into plain, actionable steps — never surface the raw message.
        return if (r.contains("allow-external-apps", true) || r.contains("RunCommandService requires", true)) setupHint() else r
    }

    /** True if a run result is actually the "please configure Termux" hint (not real output). */
    fun isSetupHint(s: String): Boolean = s.startsWith("One-time Termux setup")

    /** Friendly one-time-setup text shown when Termux isn't configured to accept external commands. */
    fun setupHint(): String =
        "One-time Termux setup. Open Termux and run these two lines, then try again:\n\n" +
            "echo \"allow-external-apps=true\" >> ~/.termux/termux.properties\n" +
            "termux-reload-settings"
}
