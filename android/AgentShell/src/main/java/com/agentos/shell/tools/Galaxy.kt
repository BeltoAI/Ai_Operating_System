package com.agentos.shell.tools

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where twenty thousand people go.
 *
 * The field used to draw thirty-six dots, and thirty-six dots is a diagram. The whole address book
 * is a galaxy — but only if it is laid out and drawn the way a galaxy is, which is to say once,
 * into flat arrays, and then handed to the canvas as a handful of batched calls.
 *
 * What makes this cheap enough to be worth doing at all:
 *
 *  - **Positions are computed once.** Not per frame. Twenty thousand sines on every frame is what
 *    would actually melt the phone, and it is entirely avoidable — the dots do not move relative to
 *    each other, the whole field turns.
 *  - **The turning is a canvas transform**, one per ring, so a frame costs ten `rotate` calls and
 *    ten `drawPoints` calls no matter how many people are in it. Twenty thousand and two hundred
 *    cost within a millisecond of each other.
 *  - **Nothing is a composable.** Twenty thousand composables is the version of this that does not
 *    run; twenty thousand floats in a `FloatArray` is 160KB.
 *
 * The arms are employers. That is the one grouping that makes a contact list look like structure
 * rather than noise — three hundred people at one company is a visible streak, and the eye reads it
 * instantly as "I am deep inside that place". People with no employer scatter as background haze
 * rather than clumping into a false arm of their own.
 */
object Galaxy {

    /** Ten concentric bands. Each turns at its own speed, and each is one draw call. */
    const val BANDS = 10

    class Layout(
        /** Interleaved x,y per band — the argument shape `Canvas.drawPoints` wants, no copying. */
        val cold: Array<FloatArray>,
        /** People you have actually written to. Same geometry, brighter paint. */
        val warm: Array<FloatArray>,
        val coldIdx: Array<IntArray>,
        val warmIdx: Array<IntArray>,
        /** World units from you to the far edge. This is the number that grows with your network. */
        val outer: Float,
        /** Points actually drawn. */
        val count: Int,
        /** People actually in the network — bigger than `count` once the cap bites. */
        val total: Int
    ) {
        /**
         * Inner bands turn faster, exactly as they do in something with mass at the middle. It is
         * also the only reason a field this dense reads as depth instead of a flat speckle.
         */
        fun omega(band: Int): Float = 1f / (0.55f + 2.2f * (band + 0.5f) / BANDS)
    }

    /** Where the dust begins — outside the ring of people you actually talk to. */
    const val INNER = 360f

    /**
     * How far out it goes.
     *
     * Deliberately NOT fitted to the screen. A field that always fills the display looks identical
     * with two hundred people and with twenty thousand, and the size of the thing you built is the
     * one fact this screen should tell you before you have read a single word on it. So the world
     * gets bigger and the screen does not: at twenty thousand you have to pull back to see it all.
     */
    fun outerFor(count: Int): Float =
        INNER + 260f + 940f * (log10((count + 1).toDouble()) / log10(20001.0)).toFloat()
            .coerceAtLeast(0f)

    private fun frac(x: Float): Float = x - kotlin.math.floor(x)

    /** The sweep. Everything at radius r is turned by the same amount, so clumps trail into arms. */
    private fun curl(r: Float): Float = 0.85f * ln(r / INNER)

    fun build(sky: Field.Sky): Layout {
        val n = sky.size
        // Radius comes from how many people there ARE, not how many fit on a GPU. A network ten
        // times the size looks bigger even when the far half is drawn as a sample.
        val outer = outerFor(sky.total)
        if (n == 0) return Layout(
            Array(BANDS) { FloatArray(0) }, Array(BANDS) { FloatArray(0) },
            Array(BANDS) { IntArray(0) }, Array(BANDS) { IntArray(0) }, outer, 0, sky.total)

        val span = outer - INNER
        val rs = FloatArray(n)
        val th = FloatArray(n)

        // Employer clusters, biggest first, so the largest streaks are spread evenly around the
        // disc by the golden angle rather than landing on top of one another.
        val groups = HashMap<String, MutableList<Int>>(4096)
        for (i in 0 until n) {
            val c = sky.companies[i]
            if (c.isBlank()) continue
            groups.getOrPut(c) { ArrayList(4) }.add(i)
        }
        val ranked = groups.entries.sortedByDescending { it.value.size }

        val biggest = (ranked.firstOrNull()?.value?.size ?: 1).toFloat()
        val lastRank = maxOf(1, ranked.size - 1).toFloat()

        ranked.forEachIndexed { rank, e ->
            val members = e.value
            val m = members.size
            // Big employers sit close in. That is the honest reading of the number — three hundred
            // people at one company is somewhere you are deep inside, and it belongs near you.
            val depth = Math.pow((rank / lastRank).toDouble(), 0.55).toFloat()
            val rC = INNER + span * (0.05f + 0.92f * depth)
            // Log, not sqrt: a thousand-person employer should be a big clump, not a slab that
            // eats a quarter of the disc. And soft-edged — see the triangular draw below.
            val weight = (ln(1f + m) / ln(1f + biggest)).coerceIn(0f, 1f)
            // A company is a CLUMP, not a streak across the whole disc. Spread one employer over
            // every radius and five thousand employers average out into featureless speckle — the
            // exact failure the first version of this had.
            val blobR = span * (0.012f + 0.075f * weight)
            val blobA = (0.05f + 0.34f * weight).coerceAtMost(0.62f)
            val a0 = rank * 2.39996323f + curl(rC)
            members.forEachIndexed { j, idx ->
                // Two uniforms averaged — a triangular distribution, so a clump is dense in the
                // middle and fades at the edge instead of ending in a hard rectangle.
                val u1 = (frac(idx * 0.6180339887f + rank * 0.31f) +
                          frac(idx * 0.3247179f + rank * 0.61f)) * 0.5f - 0.5f
                val u2 = (frac(idx * 0.7548776f + rank * 0.77f + j * 0.113f) +
                          frac(idx * 0.5698402f + rank * 0.19f)) * 0.5f - 0.5f
                rs[idx] = (rC + u1 * 2f * blobR).coerceIn(INNER, outer)
                th[idx] = a0 + u2 * 2f * blobA
            }
        }
        // No employer on file — background haze, thinning outwards. Inventing an arm for them would
        // be drawing a fact that is not there.
        for (i in 0 until n) {
            if (sky.companies[i].isNotBlank()) continue
            val u = frac(i * 0.7548776f + 0.17f)
            val r = INNER + span * Math.pow(u.toDouble(), 1.55).toFloat()
            rs[i] = r
            th[i] = i * 2.39996323f + curl(r)
        }

        val bandOf = IntArray(n) {
            (((rs[it] - INNER) / span) * BANDS).toInt().coerceIn(0, BANDS - 1)
        }
        val nCold = IntArray(BANDS); val nWarm = IntArray(BANDS)
        for (i in 0 until n) if (sky.touched[i]) nWarm[bandOf[i]]++ else nCold[bandOf[i]]++

        val cold = Array(BANDS) { FloatArray(nCold[it] * 2) }
        val warm = Array(BANDS) { FloatArray(nWarm[it] * 2) }
        val coldIdx = Array(BANDS) { IntArray(nCold[it]) }
        val warmIdx = Array(BANDS) { IntArray(nWarm[it]) }
        val cCold = IntArray(BANDS); val cWarm = IntArray(BANDS)

        for (i in 0 until n) {
            val b = bandOf[i]
            val x = rs[i] * cos(th[i]); val y = rs[i] * sin(th[i])
            if (sky.touched[i]) {
                val k = cWarm[b]++
                warm[b][k * 2] = x; warm[b][k * 2 + 1] = y; warmIdx[b][k] = i
            } else {
                val k = cCold[b]++
                cold[b][k * 2] = x; cold[b][k * 2 + 1] = y; coldIdx[b][k] = i
            }
        }
        return Layout(cold, warm, coldIdx, warmIdx, outer, n, sky.total)
    }
}
