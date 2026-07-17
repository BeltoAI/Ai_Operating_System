package com.agentos.shell.tools

import android.content.Context

/**
 * Picks which provider + model handles a given task. The memory/persona context is assembled BY
 * SlyOS and passed in identically to whichever model is chosen — so the brain is the same no matter
 * what runs. This only decides cost/quality/capability, never what the model knows.
 */
object ModelRouter {
    enum class Tier { CHEAP, STANDARD, HEAVY }

    // Primary three first (order matters for fallback); new free providers appended — inert until keyed.
    val PROVIDERS = listOf("anthropic", "openai", "gemini", "groq", "cerebras", "mistral", "githubmodels")

    val PROVIDER_FREE = setOf("gemini", "groq", "cerebras", "mistral", "githubmodels")
    val PROVIDER_KEYURL = mapOf(
        "gemini" to "https://aistudio.google.com/app/apikey",
        "groq" to "https://console.groq.com/keys",
        "cerebras" to "https://cloud.cerebras.ai/",
        "mistral" to "https://console.mistral.ai/api-keys/",
        "githubmodels" to "https://github.com/settings/tokens",
        "anthropic" to "https://console.anthropic.com/settings/keys",
        "openai" to "https://platform.openai.com/api-keys")

    /** Default concrete model per provider per tier (each editable in settings). */
    val DEFAULT_MODELS: Map<String, Map<Tier, String>> = mapOf(
        "anthropic" to mapOf(
            Tier.CHEAP to "claude-haiku-4-5",
            Tier.STANDARD to "claude-sonnet-4-6",
            Tier.HEAVY to "claude-opus-4-8"),
        "openai" to mapOf(
            Tier.CHEAP to "gpt-4o-mini",
            Tier.STANDARD to "gpt-4o",
            Tier.HEAVY to "gpt-4o"),
        "gemini" to mapOf(
            Tier.CHEAP to "gemini-2.0-flash",
            Tier.STANDARD to "gemini-2.0-flash",
            Tier.HEAVY to "gemini-1.5-pro-latest"),
        // ---- Free, OpenAI-compatible providers (text-only; kept out of vision/web sets) ----
        "groq" to mapOf(
            Tier.CHEAP to "llama-3.1-8b-instant",
            Tier.STANDARD to "llama-3.3-70b-versatile",
            Tier.HEAVY to "llama-3.3-70b-versatile"),
        "cerebras" to mapOf(
            Tier.CHEAP to "llama3.1-8b",
            Tier.STANDARD to "llama-3.3-70b",
            Tier.HEAVY to "llama-3.3-70b"),
        "mistral" to mapOf(
            Tier.CHEAP to "mistral-small-latest",
            Tier.STANDARD to "mistral-small-latest",
            Tier.HEAVY to "mistral-large-latest"),
        "githubmodels" to mapOf(
            Tier.CHEAP to "gpt-4o-mini",
            Tier.STANDARD to "gpt-4o",
            Tier.HEAVY to "gpt-4o")
    )

    // Only Anthropic exposes the web_search tool the paper writer uses today.
    // P2: Anthropic via webTool(), Gemini via Google Search grounding — both can browse for the agent loop.
    private val WEB_PROVIDERS = setOf("anthropic", "gemini")
    // All three current providers have vision-capable models.
    private val VISION_PROVIDERS = setOf("anthropic", "openai", "gemini")

    data class Choice(val provider: String, val model: String, val apiKey: String)

    private fun keyFor(ctx: Context, p: String): String = when (p) {
        "openai" -> MemoryStore.openaiKey(ctx)
        "gemini" -> MemoryStore.geminiKey(ctx)
        "groq" -> MemoryStore.groqKey(ctx)
        "anthropic" -> MemoryStore.anthropicKeyEffective(ctx)
        else -> MemoryStore.providerKey(ctx, p)   // cerebras / mistral / githubmodels
    }

    /** True if this provider has a usable key — so the UI only offers brains you can actually route to. */
    fun hasKey(ctx: Context, provider: String): Boolean = keyFor(ctx, provider).isNotBlank()

    /**
     * Choose a provider+model for [tier], honoring the user's preferred provider, then any provider
     * with a key, while respecting capability needs (web → Anthropic; vision → vision model). Returns
     * null only if NO provider has a key at all.
     */
    fun choose(ctx: Context?, tier: Tier, needVision: Boolean, needWeb: Boolean): Choice? {
        if (ctx == null) return null
        val geminiKey = MemoryStore.geminiKey(ctx)

        // HARD BUDGET CAP: if this month's spend crossed the cap, force everything (that free Gemini can
        // do) onto Gemini so the bill can't blow up. Web tasks still need Anthropic, so only those escape.
        val budget = MemoryStore.monthlyBudget(ctx)
        if (budget > 0.0 && CostStore.monthCostUsd(ctx) >= budget && geminiKey.isNotBlank() && !needWeb) {
            val model = MemoryStore.modelOverride(ctx, "gemini", tier).ifBlank { DEFAULT_MODELS["gemini"]?.get(tier) ?: "gemini-2.0-flash" }
            return Choice("gemini", model, geminiKey)
        }

        // Per-task routing override takes precedence, then the global preferred, then any keyed provider.
        val tierPref = MemoryStore.tierProvider(ctx, tier)
        val preferred = MemoryStore.preferredProvider(ctx)
        // COST DEFAULT: high-volume CHEAP work (triage, understanding commands, memory lookups) goes to
        // FREE Gemini unless you explicitly set that tier to something else — so everyday use is $0.
        val cheapFree = if (tier == Tier.CHEAP && tierPref.isBlank() && geminiKey.isNotBlank()) "gemini" else ""
        val order = (listOf(cheapFree, tierPref, preferred) + PROVIDERS).filter { it.isNotBlank() }.distinct()
        // First pass: respect capability constraints.
        for (p in order) {
            val k = keyFor(ctx, p); if (k.isBlank()) continue
            if (needWeb && p !in WEB_PROVIDERS) continue
            if (needVision && p !in VISION_PROVIDERS) continue
            val ov = MemoryStore.modelOverride(ctx, p, tier)
            val model = if (ov.isNotBlank()) ov else (DEFAULT_MODELS[p]?.get(tier) ?: continue)
            return Choice(p, model, k)
        }
        // Second pass: a constraint filtered everyone — fall back to any keyed provider (best effort).
        for (p in order) {
            val k = keyFor(ctx, p); if (k.isBlank()) continue
            val ov = MemoryStore.modelOverride(ctx, p, tier)
            val model = if (ov.isNotBlank()) ov else (DEFAULT_MODELS[p]?.get(tier) ?: continue)
            return Choice(p, model, k)
        }
        return null
    }

    /**
     * The FULL ordered list of usable provider+model choices for a task — best first, capability-respecting
     * choices ahead of best-effort ones. The caller tries them in order, so if the preferred provider is
     * down / quota-exhausted / rejecting the key, SlyOS transparently falls back to another keyed provider.
     * This is what makes quality/uptime consistent no matter which models you've configured.
     */
    fun chooseAll(ctx: Context?, tier: Tier, needVision: Boolean, needWeb: Boolean): List<Choice> {
        if (ctx == null) return emptyList()
        val geminiKey = MemoryStore.geminiKey(ctx)
        val budget = MemoryStore.monthlyBudget(ctx)
        val budgetCapped = budget > 0.0 && CostStore.monthCostUsd(ctx) >= budget && geminiKey.isNotBlank() && !needWeb
        val tierPref = MemoryStore.tierProvider(ctx, tier)
        val preferred = MemoryStore.preferredProvider(ctx)
        val cheapFree = if (tier == Tier.CHEAP && tierPref.isBlank() && geminiKey.isNotBlank()) "gemini" else ""
        val order = (listOf(if (budgetCapped) "gemini" else "", cheapFree, tierPref, preferred) + PROVIDERS)
            .filter { it.isNotBlank() }.distinct()
        val out = ArrayList<Choice>()
        fun modelFor(p: String): String? {
            val ov = MemoryStore.modelOverride(ctx, p, tier)
            return if (ov.isNotBlank()) ov else DEFAULT_MODELS[p]?.get(tier)
        }
        // Capability-respecting choices first (best quality for the task)…
        for (p in order) {
            val k = keyFor(ctx, p); if (k.isBlank()) continue
            if (needWeb && p !in WEB_PROVIDERS) continue
            if (needVision && p !in VISION_PROVIDERS) continue
            val m = modelFor(p) ?: continue
            out.add(Choice(p, m, k))
        }
        // …then any other keyed provider as a fallback (better a degraded answer than none).
        for (p in order) {
            if (out.any { it.provider == p }) continue
            val k = keyFor(ctx, p); if (k.isBlank()) continue
            val m = modelFor(p) ?: continue
            out.add(Choice(p, m, k))
        }
        // Cascade memory: a provider that just hit its rate-limit is moved to the BACK (relative order kept
        // otherwise) so fresh brains are tried first — but it's never dropped, so if everything's parked we
        // still try (its daily cap may have reset). Success clears the park flag in AgentClient.
        val parked = out.filter { ProviderLimit.limited(ctx, it.provider) }
        if (parked.isNotEmpty()) { out.removeAll(parked); out.addAll(parked) }
        // On-device model = OFFLINE SAFETY-NET ONLY. It's slow, hot, can't browse or see images, and small
        // models give weak answers — so it must NEVER pre-empt the cloud. We append it DEAD LAST and only
        // for plain-text tasks. Because callers try choices best-first and stop on the first success, local
        // runs ONLY when every cloud choice above has failed (you're offline, or no key works). When you're
        // online with any key, cloud answers first and local never wakes up — so no phone heat in daily use.
        if (LocalLlm.ready(ctx) && !needVision && !needWeb) {
            out.add(Choice("local", LocalLlm.selectedId(ctx), "local"))
        }
        return out
    }
}
