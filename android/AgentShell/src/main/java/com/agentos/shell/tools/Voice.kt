package com.agentos.shell.tools

import android.content.Context

/**
 * THE OUTBOUND VOICE CHOKE POINT.
 *
 * Every surface that writes something AS the user — replies, posts, spicy takes, the Telegram bot, outreach,
 * email — must sound like them AND carry the character they set for that channel (LinkedIn = professional,
 * Instagram = funny…). Before this, the per-channel character was consumed in essentially one place
 * (ReplyContext, the notification-reply path); the post composer used a generic "sharp copywriter" prompt and
 * only the bare About blurb, the spicy composer and the Telegram bot skipped the per-channel persona entirely.
 * So the character the user carefully set reached almost nothing but replies.
 *
 * [voiceFor] assembles the complete per-channel identity — full profile + the channel's character + how the
 * user writes + how they've recently corrected the AI here — as ONE author-context block. Every drafter passes
 * it, so the character reaches every channel by construction, and a new outbound feature can't silently drop it.
 */
object Voice {

    /**
     * The author context to draft AS the user on [channel] (a platform key or app label — normalized the same
     * way as the per-app persona lookup, so "Instagram"/"instagram"/the IG package all resolve alike).
     *
     * @param includeCorrections fold in the recent draft→sent edits for this channel (the convergence flywheel).
     */
    fun voiceFor(ctx: Context, channel: String, includeCorrections: Boolean = true): String {
        val sb = StringBuilder()

        // 1) The per-channel CHARACTER, loud and first — it's how the user deliberately presents here, and it
        //    takes precedence over the general writing style when they conflict.
        val persona = try { MemoryStore.styleFor(ctx, channel) } catch (e: Exception) { "" }
        if (persona.isNotBlank())
            sb.append("⚑ YOUR CHARACTER ON $channel (adopt this voice and register fully — it's how you choose to " +
                "come across here, and it overrides your general style): ").append(persona).append("\n")

        // 2) The FULL identity — not just the About blurb. Name, contact, personal profile, work history,
        //    learned facts. This is what makes a post actually sound like this specific person.
        val profile = try { MemoryStore.fullProfile(ctx) } catch (e: Exception) { "" }
        if (profile.isNotBlank()) sb.append("About you: ").append(profile).append("\n")

        // 3) HOW you write (the learned voice), when no channel character overrides it.
        val style = try { MemoryStore.styleProfile(ctx) } catch (e: Exception) { "" }
        if (style.isNotBlank()) sb.append("How you write (mimic this precisely): ").append(style).append("\n")

        // 3b) IMITATE FROM EVIDENCE: real messages the user has actually sent. Few-shot exemplars beat any
        //     description of a voice — they carry the real phrasings, rhythm, length, openings and sign-offs.
        //     Prefer this channel's own sent messages; fall back to the user's writing generally.
        val label = try { platformLabel(channel) } catch (e: Exception) { null }
        val examples = try {
            (if (label != null) MessageStore.myRecentBodies(ctx, 6, label) else emptyList())
                .ifEmpty { MessageStore.myRecentBodies(ctx, 6, null) }
        } catch (e: Exception) { emptyList() }
        if (examples.isNotEmpty())
            sb.append("Real examples of how you actually write (match this style, length and tone; don't copy them): ")
                .append(examples.joinToString(" | ") { "\"" + it.replace("\n", " ").take(160) + "\"" }).append("\n")

        // 4) THE FLYWHEEL: how you've recently fixed the AI's drafts on this channel — your true voice.
        if (includeCorrections) {
            val corr = try { EditPairStore.recentCorrections(ctx, channel, 3) } catch (e: Exception) { emptyList() }
            if (corr.isNotEmpty())
                sb.append("How you fix my drafts here (mirror these edits — learn from the change): ")
                    .append(corr.joinToString(" · ")).append("\n")
        }

        return sb.toString().trim()
    }

    /** Map a channel key/label to the platform string messages are stored under, so exemplars can be scoped
     *  to this channel. Returns null (→ use the user's writing generally) when there's no clean match. */
    private fun platformLabel(channel: String): String? = when (MemoryStore.platformKey(channel)) {
        "whatsapp" -> "WhatsApp"; "telegram" -> "Telegram"; "linkedin" -> "LinkedIn"
        "instagram" -> "Instagram"; "x" -> "X"; "reddit" -> "Reddit"; "email" -> "Email"
        "sms" -> "SMS"; "messenger" -> "Messenger"; "slack" -> "Slack"; "discord" -> "Discord"; "signal" -> "Signal"
        else -> null
    }
}
