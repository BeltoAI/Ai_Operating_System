package com.agentos.shell

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

/**
 * Device Owner entry point. Granted (after a factory reset, no accounts present) via:
 *   adb shell dpm set-device-owner com.agentos.shell/.AdminReceiver
 *
 * This is the official Android Enterprise provisioning path — no root, no bootloader unlock —
 * and is fully removed by a factory reset. See scripts/provision_device_owner.md.
 */
class AdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: android.content.Intent) {
        // Once Device Owner, lock AgentShell in as the persistent launcher and (optionally)
        // enter kiosk / lock-task mode for the "no notification chaos" experience.
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, AdminReceiver::class.java)
        if (dpm.isDeviceOwnerApp(context.packageName)) {
            dpm.setLockTaskPackages(admin, arrayOf(context.packageName))
            // Persistent preferred HOME so AgentShell is the locked-in launcher:
            // dpm.addPersistentPreferredActivity(admin, homeIntentFilter, shellComponent)
            // Kiosk is started from ShellActivity via startLockTask() when desired.
        }
    }

    companion object {
        fun component(context: Context) = ComponentName(context, AdminReceiver::class.java)
    }
}
