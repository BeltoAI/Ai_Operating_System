package com.agentos.shell

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.agentos.shell.tools.GoogleAuth

/**
 * Invisible activity that catches the Google OAuth redirect (reversed-client-id scheme), finishes
 * the token exchange off the main thread, then drops the user back into SlyOS.
 */
class GoogleAuthRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        if (data == null) { goHome(); return }
        Thread {
            val err = GoogleAuth.handleRedirect(applicationContext, data)
            runOnUiThread {
                val msg = if (err.isBlank()) {
                    val who = GoogleAuth.account(applicationContext)
                    "Google connected" + (if (who.isNotBlank()) " · $who" else "") + " ✓"
                } else err
                Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
                goHome()
            }
        }.start()
    }

    private fun goHome() {
        startActivity(Intent(this, ShellActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }
}
