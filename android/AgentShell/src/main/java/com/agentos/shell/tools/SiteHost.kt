package com.agentos.shell.tools

import android.content.Context
import com.agentos.shell.BuildConfig

/**
 * Zero-config, free website hosting for non-technical users — the site ships LIVE and RENDERS in any browser.
 *
 * We host on Vercel using a shared BeltoAI-owned token baked into the app (VERCEL_TOKEN), so the end user needs
 * NO account and NO setup. (We can't use Supabase Storage for this: it deliberately serves uploaded HTML as
 * text/plain to stop people hosting phishing pages on the supabase.co domain, so pages show source instead of
 * rendering.) If the owner has set their OWN Vercel token in Settings, that takes precedence.
 */
object SiteHost {
    fun sharedToken(): String = try { BuildConfig.VERCEL_TOKEN } catch (e: Exception) { "" }
    fun available(ctx: Context): Boolean = sharedToken().isNotBlank() || MemoryStore.vercelToken(ctx).isNotBlank()

    data class Result(val ok: Boolean, val url: String, val error: String)

    /** Publish an HTML page to a live, rendered URL. */
    fun publish(ctx: Context, html: String, name: String = "site"): Result {
        if (html.length < 40) return Result(false, "", "nothing to publish")
        // Owner's own Vercel token first (their account/domain), else the shared baked token (zero setup).
        val ownToken = MemoryStore.vercelToken(ctx)
        val token = ownToken.ifBlank { sharedToken() }
        if (token.isBlank()) return Result(false, "", "site hosting isn't set up in this build yet")
        val r = DeployClient.deploy(ctx, name, mapOf("index.html" to html), tokenOverride = token)
        return Result(r.ok, r.url, r.error)
    }
}
