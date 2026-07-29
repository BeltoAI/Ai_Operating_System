package com.agentos.shell

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/**
 * Why SlyOS wants health data, shown when Health Connect asks.
 *
 * Health Connect will not list an app that cannot answer this, so without it SlyOS is invisible in
 * "Your health apps" and there is no way in from that side at all. It is a legal-ish requirement
 * that doubles as a real one: someone about to hand over their sleep and heart data deserves a
 * sentence about where it goes.
 */
class HealthRationaleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Toast.makeText(
            this,
            "SlyOS reads your sleep, heart and activity data to show it back to you and answer " +
            "questions about it. It stays on this phone.",
            Toast.LENGTH_LONG
        ).show()
        finish()
    }
}
