package com.agentos.shell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.agentos.shell.tools.ScreenAgent

/** The STOP button on the "SlyOS is controlling your screen" notification → aborts the action run. */
class StopActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        ScreenAgent.stop()
    }
}
