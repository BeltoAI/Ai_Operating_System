package com.agentos.shell.tools

/**
 * Lovable "Build with URL" integration. Lovable builds a FULL app end-to-end (React + Supabase backend + hosting)
 * from a single link — so for real full-stack apps we hand off to Lovable instead of our own static Vercel
 * deploy. No API key needed for this path: the link opens Lovable and the owner builds it in their own account.
 * (A token is only needed for the future MCP full-automation path.)
 */
object LovableClient {
    /** Build a tap-to-build Lovable URL from a prompt (+ optional public reference image URLs). */
    fun buildUrl(prompt: String, images: List<String> = emptyList()): String {
        // Lovable wants spaces as %20 etc. URLEncoder gives '+' for spaces, so fix that up.
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        val p = enc(prompt.take(45000))   // Lovable caps the prompt ~50k chars
        val imgs = images.filter { it.startsWith("http") }.take(10).joinToString("") { "&images=" + enc(it) }
        return "https://lovable.dev/?autosubmit=true#prompt=$p$imgs"
    }
}
