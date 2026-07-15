package com.agentos.shell.tools

import android.content.Context

/**
 * WHAT'S ACTUALLY CONNECTED right now. Employees are told this so they USE what's available (Gmail, calendar…)
 * instead of asking for access they already have — and only flag "needs you" for things genuinely missing
 * (a CRM like HubSpot that hasn't been set up), pointing the owner to add it.
 */
object Capabilities {
    fun summary(ctx: Context): String {
        val have = ArrayList<String>()
        try { if (GoogleAuth.isConnected(ctx)) have.add("Gmail — read the inbox AND send/draft email") } catch (e: Exception) {}
        try { if (CalendarTool.hasPermission(ctx)) have.add("Calendar — see the schedule and add events") } catch (e: Exception) {}
        try { if (ContactsTool.canRead(ctx)) have.add("Contacts — look people up") } catch (e: Exception) {}
        have.add("Web search")
        have.add("The owner's brain — memory, notes, files, photos, expenses")
        try { IntegrationStore.names(ctx).forEach { have.add(it) } } catch (e: Exception) {}

        return "ALREADY CONNECTED (use these RIGHT NOW, never ask for them): " + have.joinToString("; ") + ". " +
            "Do real work with what's connected — actually read the inbox, check the calendar, etc. " +
            "ONLY report that you 'need' something when it is genuinely NOT in that list (for example a CRM like " +
            "HubSpot, a helpdesk, or a service the owner hasn't set up). When you do, name it plainly and say the " +
            "owner can connect it in the Team screen. Never claim to need Gmail, calendar or contacts if they're listed above."
    }
}
