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
    private fun bakedNetlify(): String = try { BuildConfig.NETLIFY_TOKEN } catch (e: Exception) { "" }
    private fun vercelShared(): String = try { BuildConfig.VERCEL_TOKEN } catch (e: Exception) { "" }
    fun available(ctx: Context): Boolean = MemoryStore.netlifyToken(ctx).isNotBlank() || bakedNetlify().isNotBlank() ||
        vercelShared().isNotBlank() || MemoryStore.vercelToken(ctx).isNotBlank()

    data class Result(val ok: Boolean, val url: String, val error: String)

    /** Publish an HTML page to a live, RENDERED public URL. Owner's own Netlify token first (their account), then
     *  the shared baked Netlify token (zero setup), then Vercel. */
    fun publish(ctx: Context, html: String, name: String = "site"): Result {
        if (html.length < 40) return Result(false, "", "nothing to publish")
        // 1) Netlify — the owner's own token (their account) if set, else the shared baked one.
        val netlify = MemoryStore.netlifyToken(ctx).ifBlank { bakedNetlify() }
        if (netlify.isNotBlank()) {
            val r = NetlifyClient.deployHtml(netlify, html)
            if (r.ok) { try { Analytics.track(ctx, "site_published", "netlify") } catch (e: Exception) {}; return Result(true, r.url, "") }
            // fall through to Vercel if Netlify failed
        }
        // 2) Vercel — owner's own token first, else the shared baked one.
        val vToken = MemoryStore.vercelToken(ctx).ifBlank { vercelShared() }
        if (vToken.isNotBlank()) {
            val r = DeployClient.deploy(ctx, name, mapOf("index.html" to html), tokenOverride = vToken)
            return Result(r.ok, r.url, r.error)
        }
        return Result(false, "", "site hosting isn't set up yet — add a Netlify token to enable it")
    }
}
