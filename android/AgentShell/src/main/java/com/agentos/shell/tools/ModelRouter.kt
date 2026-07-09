package com.agentos.shell.tools

import android.content.Context

/**
 * Picks which provider + model handles a given task. The memory/persona context is assembled BY
 * SlyOS and passed in identically to whichever model is chosen — so the brain is the same no matter
 * what runs. This only decides cost/quality/capability, never what the model knows.
 */
object ModelRouter {
    enum class Tier { CHEAP, STANDARD, HEAVY }

    val PROVIDERS = listOf("anthropic", "openai", "gemini")

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
            Tier.HEAVY to "gemini-1.5-pro-latest")
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
        else -> MemoryStore.anthropicKeyEffective(ctx)
    }

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
        return out
    }
}
