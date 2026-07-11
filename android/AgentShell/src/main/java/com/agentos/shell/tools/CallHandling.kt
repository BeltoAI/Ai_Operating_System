package com.agentos.shell.tools

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Helpers for the on-device call-screening role. To screen calls, SlyOS must be granted the system
 * "call screening app" role — a one-time grant the user confirms in a system dialog. Nothing here needs
 * a server, a key, or an upload.
 */
object CallHandling {

    /** True if SlyOS currently holds the call-screening role. */
    fun hasRole(ctx: Context): Boolean = try {
        val rm = ctx.getSystemService(RoleManager::class.java)
        rm != null && rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) && rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    } catch (e: Exception) { false }

    fun available(ctx: Context): Boolean = try {
        Build.VERSION.SDK_INT >= 29 &&
            ctx.getSystemService(RoleManager::class.java)?.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) == true
    } catch (e: Exception) { false }

    /** Intent that opens the system dialog asking the user to make SlyOS the call-screening app. */
    fun requestRoleIntent(ctx: Context): Intent? = try {
        if (Build.VERSION.SDK_INT >= 29)
            ctx.getSystemService(RoleManager::class.java)?.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
        else null
    } catch (e: Exception) { null }
}
