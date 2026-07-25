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

        // 1b) CONCRETE CHANNEL NORMS. A one-line character buried in ~20k chars of context barely moved the
        //     output: an audit across 8 channels produced near-identical drafts (the "funny" Instagram reply
        //     read the same as the "professional CEO" LinkedIn one). Spelling out the actual form each channel
        //     takes — length, greeting, punctuation, emoji, links — is what makes them genuinely different.
        val norms = channelNorms(try { MemoryStore.platformKey(channel) } catch (e: Exception) { channel.lowercase() })
        if (norms.isNotBlank()) sb.append("⚑ HOW MESSAGES ACTUALLY LOOK ON $channel: ").append(norms).append("\n")

        // 2) The FULL identity — not just the About blurb. Name, contact, personal profile, work history,
        //    learned facts. This is what makes a post actually sound like this specific person.
        val profile = try { MemoryStore.fullProfile(ctx) } catch (e: Exception) { "" }
        if (profile.isNotBlank()) sb.append("About you: ").append(profile).append("\n")

        // Channels where register is FORMAL. On these, casual habits learned elsewhere must not bleed in.
        val key = try { MemoryStore.platformKey(channel) } catch (e: Exception) { channel.lowercase() }
        val formal = key == "linkedin" || key == "email" || key == "slack"

        // 3) HOW you write (the learned voice). This is learned across ALL channels, so on a formal channel it
        //    describes the user's casual habits ("often just a phrase, a reaction word") and directly fights the
        //    channel character — which is exactly how LinkedIn drafts came out too casual. The channel character
        //    wins on register; the learned style may only inform word choice.
        val style = try { MemoryStore.styleProfile(ctx) } catch (e: Exception) { "" }
        if (style.isNotBlank()) {
            if (formal && persona.isNotBlank())
                sb.append("Your general writing habits across all apps (for word choice ONLY — do NOT copy the " +
                    "casualness or length here; the character above sets the register on this channel): ")
                    .append(style).append("\n")
            else sb.append("How you write (mimic this precisely): ").append(style).append("\n")
        }

        // 3b) IMITATE FROM EVIDENCE: real messages the user has actually sent. Few-shot exemplars beat any
        //     description of a voice. They MUST come from this channel — falling back to messages from other
        //     apps fed casual one-liners into LinkedIn/email drafts as "match this style, length and tone",
        //     which is the single biggest reason formal drafts read unprofessionally.
        val label = try { platformLabel(channel) } catch (e: Exception) { null }
        val own = try { if (label != null) MessageStore.myRecentBodies(ctx, 6, label) else emptyList() } catch (e: Exception) { emptyList() }
        val examples = if (own.isNotEmpty()) own
            else if (formal) emptyList()   // better NO exemplars than wrong-register ones
            else try { MessageStore.myRecentBodies(ctx, 6, null) } catch (e: Exception) { emptyList() }
        if (examples.isNotEmpty())
            sb.append("Real examples of how you actually write" + (if (own.isNotEmpty()) " ON THIS CHANNEL" else "") +
                " (match this style, length and tone; don't copy them): ")
                .append(examples.joinToString(" | ") { "\"" + it.replace("\n", " ").take(160) + "\"" }).append("\n")
        else if (formal)
            sb.append("You have no prior messages on this channel to imitate — write to the character above: " +
                "polished, complete sentences, professional register. Do NOT write like a casual chat.\n")

        // 4) THE FLYWHEEL: how you've recently fixed the AI's drafts on this channel — your true voice.
        if (includeCorrections) {
            val corr = try { EditPairStore.recentCorrections(ctx, channel, 3) } catch (e: Exception) { emptyList() }
            if (corr.isNotEmpty())
                sb.append("How you fix my drafts here (mirror these edits — learn from the change): ")
                    .append(corr.joinToString(" · ")).append("\n")
        }

        return sb.toString().trim()
    }

    /**
     * The concrete FORM a message takes on each platform. Without this, every channel produced the same
     * message with the same link — the character line alone didn't survive a 20k-char context.
     */
    private fun channelNorms(key: String): String = when (key) {
        "linkedin" ->
            "Complete, polished sentences — no texting shorthand, no dropped subjects ('this week's packed' → " +
            "'my week is quite full'). 2-4 sentences. Greet them by name. Warm but businesslike. No emoji. " +
            "Reference something specific about THEM or their work. A scheduling link is fine but write a real " +
            "sentence around it, never bare."
        "email" ->
            "A real email: greet by name on its own line, 2-5 sentences of substance, then a natural close. " +
            "Full punctuation and capitalisation. No emoji. Say the specific thing — never a one-line brush-off."
        "instagram" ->
            "Playful and FUNNY — that's the whole point here. Lowercase is fine, 1-2 short lines, emoji welcome, " +
            "banter and jokes land well. Never corporate, never a formal scheduling message. React like a friend."
        "x" ->
            "Punchy, witty, opinionated — one or two short lines max, the shorter the better. A clever line beats " +
            "a helpful one. Lowercase fine, emoji sparingly. Never sound like customer support."
        "reddit" ->
            "Substantive and technical — Redditors smell marketing instantly. Explain the actual thing, concede " +
            "trade-offs, no hype, no links unless genuinely asked. Plain text, can be several sentences."
        "whatsapp", "telegram", "sms", "messenger", "signal" ->
            "Real texting: 1-2 short lines, contractions, casual, mirror their energy. No greeting block, no " +
            "sign-off. Emoji only if it fits naturally."
        "slack" ->
            "Work-casual: brief and direct like a colleague, 1-3 lines, lowercase fine, light emoji ok. " +
            "Get to the point, no formal greeting or sign-off."
        "discord" -> "Casual and quick, 1-2 lines, community tone, emoji fine."
        else -> ""
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
