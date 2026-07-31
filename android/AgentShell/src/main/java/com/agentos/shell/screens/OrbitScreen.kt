package com.agentos.shell.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentos.shell.theme.T
import com.agentos.shell.tools.Field
import com.agentos.shell.tools.Crm
import com.agentos.shell.tools.Galaxy
import com.agentos.shell.tools.NetworkProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Orbit — your whole network as one field.
 *
 * The thing this replaces was a shelf: rows of cards you scroll past. That is a directory, and a
 * directory is not a place anybody returns to. What makes a social product work is that something
 * is always quietly moving and it is *yours* — so the canvas IS the app.
 *
 * Everyone is here, not a curated few. A screen that showed thirty-six of twenty thousand people
 * was a chart of a sample, and a sample does not feel like anything. The whole address book does:
 * you pull back and the disc keeps going, which is the single most accurate thing this screen can
 * tell you about what you have built.
 *
 * Three layers, and the difference between them is the whole point:
 *
 *  - **You**, at the centre, because everything here exists only in relation to you.
 *  - **The people you actually talk to** — the inner ring. Named, tappable, sized by how much you
 *    have said to each other and lit by how recently. This is a fact the CRM has always held and
 *    no screen has ever shown.
 *  - **Everyone else** — the disc. Twenty thousand of them, clustered into arms by employer, so
 *    three hundred people at one company reads instantly as a streak you are deep inside.
 *
 * It turns, and the inner bands turn faster than the outer ones. Not decoration: a still field of
 * dots reads as a diagram, something you look at once, and uniform rotation reads as a spinning
 * picture. Differential rotation is the thing that makes it read as depth.
 */
@Composable
fun OrbitScreen(
    modifier: Modifier = Modifier,
    onPerson: (String) -> Unit,
    onStanding: () -> Unit = {},
    onAsk: () -> Unit = {},
    onSetup: () -> Unit = {},
    /** Set right after the guided flow sends an ask, so the field can show it leaving. */
    justLaunched: Boolean = false,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    var people by remember { mutableStateOf<List<Crm.Person>>(emptyList()) }
    var sky by remember { mutableStateOf<Field.Sky?>(null) }
    var galaxy by remember { mutableStateOf<Galaxy.Layout?>(null) }
    var loaded by remember { mutableStateOf(false) }

    var zoom by remember { mutableStateOf(1f) }
    var framed by remember { mutableStateOf(false) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var picked by remember { mutableStateOf(-1) }       // index into `people`
    var pickedDust by remember { mutableStateOf(-1) }   // index into `sky`
    var peers by remember { mutableStateOf<List<NetworkProfile.Peer>>(emptyList()) }
    var pickedPeer by remember { mutableStateOf(-1) }
    var bridges by remember { mutableStateOf<List<com.agentos.shell.tools.Asks.Bridge>>(emptyList()) }
    // Re-read when we come back from the guided flow, or the field still thinks you have no
    // profile after you have just written one.
    val stand = remember(justLaunched) { NetworkProfile.get(ctx) }
    var liveAsk by remember { mutableStateOf<com.agentos.shell.tools.Asks.Ask?>(null) }
    var liveReach by remember { mutableStateOf(0) }
    var liveEligible by remember { mutableStateOf(0) }
    var pickedBridge by remember { mutableStateOf(-1) }

    LaunchedEffect(Unit) {
        // Everything off the main thread, and the layout is built exactly once — twenty thousand
        // sines belong in a background coroutine, not in a frame.
        val loadedPeople = withContext(Dispatchers.IO) {
            try { Crm.peopleCached(ctx, 400).filter { it.reciprocal } } catch (e: Exception) { emptyList() }
        }
        people = loadedPeople

        // Show the field it had last time FIRST, then quietly rebuild. Reading twenty-one thousand
        // people takes sixteen seconds, and staring at an empty screen for sixteen seconds is how
        // somebody decides a page is broken.
        val warm = withContext(Dispatchers.IO) { try { Field.cached(ctx) } catch (e: Exception) { null } }
        if (warm != null) {
            sky = warm
            galaxy = withContext(Dispatchers.Default) { try { Galaxy.build(warm) } catch (e: Exception) { null } }
            loaded = true
        }
        // Rebuild only when the snapshot has actually aged. Doing it on every open meant a full
        // scan of the message table and the connections table each time the screen was touched —
        // the memory spike that was killing the process, and a phone warm enough to notice.
        val fresh = if (warm != null && !Field.stale(ctx)) null
                    else withContext(Dispatchers.IO) { try { Field.load(ctx) } catch (e: Exception) { null } }
        if (fresh != null && (warm == null || fresh.size != warm.size)) {
            val g = withContext(Dispatchers.Default) { try { Galaxy.build(fresh) } catch (e: Exception) { null } }
            sky = fresh; galaxy = g
        }
        loaded = true

        // The other galaxies. Last, because it is a network call and the field must never wait on
        // one — your own people are on this phone and owe nobody a round trip.
        peers = withContext(Dispatchers.IO) {
            try { NetworkProfile.others(ctx) } catch (e: Exception) { emptyList() }
        }
        // Anything still running, so the field can show it rather than looking idle.
        withContext(Dispatchers.IO) {
            try {
                liveAsk = com.agentos.shell.tools.Asks.myAsks(ctx).firstOrNull { it.live }
                liveAsk?.let {
                    liveReach = com.agentos.shell.tools.Asks.funnel(ctx, it.id)?.reached ?: 0
                    liveEligible = com.agentos.shell.tools.Asks.eligible(ctx, it.tags)
                }
            } catch (e: Exception) {}
        }
        bridges = withContext(Dispatchers.IO) {
            // One node per person, not one per route — several people knowing the same person is
            // several ways to reach ONE person, and drawing it as several would be a lie about the
            // shape of the network.
            try { com.agentos.shell.tools.Asks.bridgesByPerson(ctx) } catch (e: Exception) { emptyList() }
        }
        // Deal with anything asked of us while nobody was looking. Most of these terminate silently
        // — the whole point is that being asked costs nothing — so this is the right place for it:
        // no notification, no badge, no interruption unless there is genuinely somebody we know.
        withContext(Dispatchers.IO) {
            try {
                com.agentos.shell.tools.Asks.inbox(ctx).take(20).forEach {
                    com.agentos.shell.tools.Asks.handle(ctx, it)
                }
            } catch (e: Exception) {}
        }
    }

    // One turn every four minutes. Slow enough that nothing crawls out from under a thumb, present
    // enough that the screen is never quite still.
    val drift by rememberInfiniteTransition(label = "d").animateFloat(
        0f, (2 * Math.PI).toFloat(),
        infiniteRepeatable(tween(240_000, easing = LinearEasing)), label = "dv")
    /**
     * The disc, recorded once.
     *
     * Handing twenty-one thousand points to the canvas on every frame means Compose re-records that
     * many display-list operations sixty times a second, and the native heap it churns is what was
     * killing the process — the app already sits near three hundred megabytes of native memory
     * before this screen opens, and the spike tipped it over. Nothing about those points ever
     * changes; only the angle does. So they are recorded into one Picture per band at load, and a
     * frame becomes ten rotations and ten replays.
     */
    val discs = remember(galaxy) {
        val g = galaxy ?: return@remember null
        if (g.count == 0) return@remember null
        val half = (g.outer + 40f).toInt()
        Array(Galaxy.BANDS) { b ->
            android.graphics.Picture().also { pic ->
                val c = pic.beginRecording(half * 2, half * 2)
                c.translate(half.toFloat(), half.toFloat())
                val cold = android.graphics.Paint().apply {
                    isAntiAlias = true
                    style = android.graphics.Paint.Style.STROKE
                    strokeCap = android.graphics.Paint.Cap.ROUND
                    strokeWidth = 2.7f
                    // Outer bands are dimmer: it is the only thing that says which way is far, and
                    // it stops the edge of the disc ending in a hard line.
                    val fade = 1f - 0.55f * (b.toFloat() / Galaxy.BANDS)
                    color = android.graphics.Color.argb((86 * fade).toInt(), 214, 218, 232)
                }
                val warm = android.graphics.Paint().apply {
                    isAntiAlias = true
                    style = android.graphics.Paint.Style.STROKE
                    strokeCap = android.graphics.Paint.Cap.ROUND
                    strokeWidth = 2.7f
                    val fade = 1f - 0.55f * (b.toFloat() / Galaxy.BANDS)
                    color = android.graphics.Color.argb((155 * fade).toInt(), 255, 150, 66)
                }
                if (g.cold[b].isNotEmpty()) c.drawPoints(g.cold[b], cold)
                if (g.warm[b].isNotEmpty()) c.drawPoints(g.warm[b], warm)
                pic.endRecording()
            }
        } to half
    }

    /**
     * The launch.
     *
     * Right after an ask goes out, the lines physically reach from you to every other person in
     * the field, one after another. It is the only moment any of this looks like it is doing
     * something — up to here the network has been a claim on a settings screen — and it is honest:
     * a line is drawn per peer the ask actually went to.
     */
    val sweep by animateFloatAsState(
        if (justLaunched) 1f else 0f,
        tween(if (justLaunched) 6500 else 0, easing = LinearEasing), label = "sweep")

    // The travelling pulse along a connection. Four seconds is the speed at which it reads as
    // something moving between two people rather than as a blinking line.
    val pulse by rememberInfiniteTransition(label = "p").animateFloat(
        0f, 1f, infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "pv")
    // Fast, only used while loading.
    val spin by rememberInfiniteTransition(label = "s").animateFloat(
        0f, (2 * Math.PI).toFloat(),
        infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "sv")
    val breath by rememberInfiniteTransition(label = "b").animateFloat(
        0.85f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing),
            androidx.compose.animation.core.RepeatMode.Reverse), label = "bv")

    /**
     * Where somebody you talk to sits, in world units, before the field turns.
     *
     * Ring by how much you actually say to each other — daily is close, once is far out. Angle from
     * a golden-angle spiral, so the ring fills evenly with no clumping and, crucially, is the SAME
     * every time it opens. A field that rearranges itself between visits destroys the only spatial
     * memory anybody ever builds of it.
     */
    fun innerRing(p: Crm.Person): Float {
        val closeness = log10((p.totalMessages + 10).toDouble()).toFloat()      // 1 .. ~4
        return 1f - (closeness / 4.2f).coerceIn(0.12f, 0.95f)                   // 0 = closest
    }
    fun innerAt(i: Int, p: Crm.Person): Offset {
        val ring = innerRing(p)
        val r = 62f + ring * (Galaxy.INNER - 95f)
        val a = i * 2.39996323f + drift * (1.55f - 0.55f * ring)
        return Offset(r * cos(a), r * sin(a))
    }

    /**
     * Where somebody else's galaxy sits.
     *
     * Outside yours, on a slow ring of their own, spaced by the golden angle so two peers never sit
     * on top of each other. The radius of THEIR disc comes from the one number they published, so a
     * person with twenty thousand people looks like it — and none of their names are here, because
     * none of their names ever left their phone.
     */
    fun peerAt(i: Int, outer: Float): Offset {
        // Just outside your own disc. At 1.55x they sat two thousand pixels off a thousand-pixel
        // screen — rendering perfectly, drawing a line to themselves, and invisible unless you
        // happened to pinch all the way out. A person you cannot find is a person who is not there.
        val ring = outer * (1.12f + 0.09f * (i % 3))
        val a = i * 2.39996323f + drift * 0.22f
        return Offset(ring * cos(a), ring * sin(a))
    }
    /**
     * A shared person sits ON the line between the two people who share them.
     *
     * That position is the whole claim: this is not in your galaxy or in theirs, it is the single
     * node two networks turned out to have in common. It exists only because somebody chose to put
     * it there, so it is drawn as one bright point and never as a crowd.
     */
    fun bridgeAt(b: com.agentos.shell.tools.Asks.Bridge, outer: Float): Offset {
        val other = if (b.mine) b.holder else b.asker
        val i = peers.indexOfFirst { it.userId == other }
        // Somebody not in the visible peer list still needs a place of their own, or every such
        // bridge stacks on the same pixel.
        val far = if (i >= 0) peerAt(i, outer) else {
            val k = bridges.indexOfFirst { it.person == b.person }.coerceAtLeast(0)
            val a = 1.1f + k * 2.39996323f + drift * 0.22f
            Offset(outer * 1.2f * cos(a), outer * 1.2f * sin(a))
        }
        // Nearer the end that actually holds the relationship.
        val t = if (b.mine) 0.62f else 0.38f
        return far * t
    }
    fun peerRadius(p: NetworkProfile.Peer): Float =
        (Galaxy.outerFor(p.networkSize) - Galaxy.INNER) * 0.42f + 40f

    Box(modifier.fillMaxSize().background(T.bg)) {
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, panChange, z, _ ->
                        zoom = (zoom * z).coerceIn(0.22f, 6f); pan += panChange
                    }
                }
                .pointerInput(people, galaxy) {
                    detectTapGestures { tap ->
                        val g = galaxy
                        val w = size.width.toFloat(); val h = size.height.toFloat()
                        val unit = (minOf(w, h) / 2f) / 620f
                        val s = unit * zoom
                        val centre = Offset(w / 2f, h / 2f)
                        // Into world space once, then everything is compared there.
                        val world = (tap - centre - pan) / s
                        // Tight. At this density a generous radius means the nearest dot is always
                        // "hit" and nothing can ever be deselected.
                        val near = 13f / s

                        var hitBridge = -1
                        if (g != null) bridges.forEachIndexed { i, b ->
                            if ((bridgeAt(b, g.outer) - world).getDistance() < 24f / s) hitBridge = i
                        }
                        if (hitBridge >= 0) {
                            pickedBridge = if (pickedBridge == hitBridge) -1 else hitBridge
                            picked = -1; pickedDust = -1; pickedPeer = -1
                            return@detectTapGestures
                        }
                        pickedBridge = -1

                        var hitPeer = -1
                        if (g != null) peers.forEachIndexed { i, _ ->
                            if ((peerAt(i, g.outer) - world).getDistance() < 26f / s) hitPeer = i
                        }
                        if (hitPeer >= 0) {
                            pickedPeer = if (pickedPeer == hitPeer) -1 else hitPeer
                            picked = -1; pickedDust = -1
                            return@detectTapGestures
                        }
                        pickedPeer = -1

                        var bestP = -1; var bestPd = Float.MAX_VALUE
                        people.forEachIndexed { i, p ->
                            val d = (innerAt(i, p) - world).getDistance()
                            if (d < bestPd) { bestPd = d; bestP = i }
                        }
                        if (bestPd < near * 1.6f) {
                            picked = if (picked == bestP) -1 else bestP; pickedDust = -1
                            return@detectTapGestures
                        }

                        // Twenty thousand distance tests on a tap is under a millisecond, and it
                        // only happens on a tap. Each band is un-turned first rather than turning
                        // every point.
                        var bestD = -1; var bestDd = Float.MAX_VALUE
                        if (g != null) for (b in 0 until Galaxy.BANDS) {
                            val a = -drift * g.omega(b)
                            val ca = cos(a); val sa = sin(a)
                            val wx = world.x * ca - world.y * sa
                            val wy = world.x * sa + world.y * ca
                            fun scan(pts: FloatArray, idx: IntArray) {
                                for (k in idx.indices) {
                                    val dx = pts[k * 2] - wx; val dy = pts[k * 2 + 1] - wy
                                    val d = dx * dx + dy * dy
                                    if (d < bestDd) { bestDd = d; bestD = idx[k] }
                                }
                            }
                            scan(g.cold[b], g.coldIdx[b]); scan(g.warm[b], g.warmIdx[b])
                        }
                        if (bestD >= 0 && sqrt(bestDd) < near) {
                            pickedDust = if (pickedDust == bestD) -1 else bestD; picked = -1
                        } else { picked = -1; pickedDust = -1 }
                    }
                }
        ) {
            val w = size.width; val h = size.height
            val centre = Offset(w / 2f, h / 2f)
            val unit = (minOf(w, h) / 2f) / 620f
            val g = galaxy

            // Open framed on everything there is — your disc, and anybody else's beside it. The
            // world is deliberately bigger than the screen (a network ten times the size should
            // LOOK ten times the size), so the one thing that must not happen is opening on a view
            // that cuts off the part you came to see.
            if (!framed && g != null && g.count > 0) {
                val reach = g.outer * (if (peers.isEmpty()) 1.04f else 1.34f)
                zoom = (0.92f * 620f / reach).coerceIn(0.22f, 1.6f)
                framed = true
            }
            val s = unit * zoom

            // ── The disc ──
            // Ten rotate + twenty drawPoints calls, and that is the entire cost whether there are
            // two hundred people out here or sixty thousand.
            if (g != null && discs != null) {
                val (pics, half) = discs
                val nc = drawContext.canvas.nativeCanvas
                nc.save()
                nc.translate(centre.x + pan.x, centre.y + pan.y)
                nc.scale(s, s)
                for (b in 0 until Galaxy.BANDS) {
                    val deg = Math.toDegrees((drift * g.omega(b)).toDouble()).toFloat()
                    nc.save()
                    nc.rotate(deg)
                    nc.translate(-half.toFloat(), -half.toFloat())
                    nc.drawPicture(pics[b])
                    nc.restore()
                }
                nc.restore()
            }
            if (g != null) {
                // The tapped one, drawn once in screen space so it is never lost in the haze.
                if (pickedDust >= 0) {
                    var at: Offset? = null
                    for (b in 0 until Galaxy.BANDS) {
                        val a = drift * g.omega(b)
                        val ca = cos(a); val sa = sin(a)
                        fun look(pts: FloatArray, idx: IntArray) {
                            val k = idx.indexOf(pickedDust)
                            if (k >= 0) {
                                val x = pts[k * 2]; val y = pts[k * 2 + 1]
                                at = Offset(x * ca - y * sa, x * sa + y * ca) * s + centre + pan
                            }
                        }
                        look(g.cold[b], g.coldIdx[b]); look(g.warm[b], g.warmIdx[b])
                        if (at != null) break
                    }
                    at?.let {
                        drawCircle(T.accent.copy(alpha = 0.20f), 26f, it)
                        drawCircle(T.accent, 5.5f, it)
                    }
                }
            }

            // ── The other galaxies ──
            //
            // Somebody else running SlyOS, drawn as a glow rather than as dots. Dots would be a lie:
            // it would look like their contacts, and their contacts are not in this database and
            // never will be. What IS known is how many there are, so that is exactly what the size
            // says and nothing else does.
            if (g != null) peers.forEachIndexed { i, pr ->
                val at = peerAt(i, g.outer) * s + centre + pan
                val rad = peerRadius(pr) * s
                val on = pickedPeer == i

                // The line home. This is the thing the whole network is for — your dot reaching
                // another one — so it travels: a bright segment runs along it rather than a static
                // rule, and it only exists between two people who have both published.
                val t = ((pulse + i * 0.37f) % 1f)
                val from = centre + pan
                // During a launch each line grows outward in turn; afterwards they are simply there.
                val reach = if (!justLaunched) 1f
                    else ((sweep * (peers.size + 1)) - i).coerceIn(0f, 1f)
                if (reach <= 0f) return@forEachIndexed
                val at2 = from + (at - from) * reach
                drawLine(T.accent.copy(alpha = if (on) 0.30f else 0.11f), from, at2, strokeWidth = 1.1f)
                if (reach < 1f) {
                    drawCircle(T.accent.copy(alpha = 0.9f), 4f, at2)
                    return@forEachIndexed
                }
                val head = from + (at - from) * t
                val tail = from + (at - from) * (t - 0.11f).coerceAtLeast(0f)
                drawLine(T.accent.copy(alpha = if (on) 0.95f else 0.5f), tail, head, strokeWidth = 2f)

                drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(
                    listOf(T.accent.copy(alpha = if (on) 0.30f else 0.17f), T.accent.copy(alpha = 0f)),
                    center = at, radius = rad.coerceAtLeast(1f)), rad.coerceAtLeast(1f), at)
                drawCircle(T.accent.copy(alpha = 0.18f * breath), 22f * breath, at)
                drawCircle(T.accent, if (on) 12f else 9f, at)

                drawContext.canvas.nativeCanvas.drawText(
                    pr.name.split(' ').first().take(14), at.x, at.y - rad.coerceAtMost(90f) - 14f,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.argb(if (on) 235 else 150, 255, 190, 150)
                        textSize = 24f; isAntiAlias = true
                        textAlign = android.graphics.Paint.Align.CENTER
                    })
            }

            // Labels are laid out after the dots, greedily, skipping any that would collide.
            // Thirty overlapping first names is less readable than eight clear ones.
            val labelled = ArrayList<Offset>(24)

            // ── The shared ones ──
            if (g != null) bridges.forEachIndexed { i, b ->
                val at = bridgeAt(b, g.outer) * s + centre + pan
                val on = pickedBridge == i
                drawCircle(T.good.copy(alpha = if (on) 0.30f else 0.16f), if (on) 30f else 20f, at)
                drawCircle(T.good, if (on) 8f else 6f, at)
                drawContext.canvas.nativeCanvas.drawText(
                    b.person.split(' ').first().take(14), at.x, at.y - 18f,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.argb(if (on) 235 else 165, 150, 235, 180)
                        textSize = 21f; isAntiAlias = true
                        textAlign = android.graphics.Paint.Align.CENTER
                    })
            }

            // ── The people you talk to ──
            people.forEachIndexed { i, p ->
                val at = innerAt(i, p) * s + centre + pan
                val alive = when {
                    p.silentDays <= 2 -> 1f
                    p.silentDays <= 14 -> 0.62f
                    p.silentDays <= 60 -> 0.34f
                    else -> 0.16f
                }
                val on = picked == i
                // A faint thread home. Every one of these is a real two-way relationship, which is
                // exactly what the twenty thousand in the disc are not.
                drawLine(T.inkSoft.copy(alpha = 0.05f + 0.11f * alive),
                    centre + pan, at, strokeWidth = 0.9f)
                val r = ((4.5f + sqrt(p.totalMessages.toFloat()) * 0.55f).coerceAtMost(17f)) *
                    zoom.coerceIn(0.7f, 1.5f)
                if (on) drawCircle(T.accent.copy(alpha = 0.18f), r * 2.6f, at)
                drawCircle((if (on) T.accent else T.inkSoft).copy(alpha = if (on) 1f else alive), r, at)

                // Names only when close, or when zoomed in. A field with 139 labels is a wall of
                // text; a field with none is abstract art.
                val wantsLabel = on || (alive > 0.6f && zoom > 1.7f)
                val clear = labelled.none {
                    Math.abs(it.x - at.x) < 88f && Math.abs(it.y - (at.y - r - 12f)) < 26f
                }
                if (wantsLabel && (on || clear)) {
                    labelled.add(Offset(at.x, at.y - r - 12f))
                    drawContext.canvas.nativeCanvas.drawText(
                        p.name.split(' ').first().take(12), at.x, at.y - r - 12f,
                        android.graphics.Paint().apply {
                            color = android.graphics.Color.argb(if (on) 240 else 130, 235, 235, 240)
                            textSize = 22f; isAntiAlias = true
                            textAlign = android.graphics.Paint.Align.CENTER
                        })
                }
            }

            // You, last, so nothing is drawn over you.
            drawCircle(T.accent.copy(alpha = 0.13f * breath), 46f * breath, centre + pan)
            drawCircle(T.accent, 15f, centre + pan)

            // ── While it reads ──
            //
            // A screen with one dot on it and no explanation is a screen somebody backs out of. Two
            // arcs sweeping around you, in the same language as the field they are about to become,
            // so the wait looks like the thing loading rather than like nothing happening.
            if (!loaded) {
                val rr = 118f
                for (k in 0 until 2) {
                    val start = Math.toDegrees((spin + k * Math.PI).toFloat().toDouble()).toFloat()
                    drawArc(
                        color = T.accent.copy(alpha = if (k == 0) 0.55f else 0.22f),
                        startAngle = start, sweepAngle = 46f, useCenter = false,
                        topLeft = centre + pan - Offset(rr, rr),
                        size = androidx.compose.ui.geometry.Size(rr * 2, rr * 2),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.2f))
                }
                drawCircle(T.inkSoft.copy(alpha = 0.06f), rr, centre + pan,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f))
            }
        }

        // The top bar, and the standing link lives HERE rather than in the card below.
        //
        // In a field of twenty thousand dots there is no empty space: every tap lands within reach
        // of somebody, so the detail card is up almost permanently — and while it was up it covered
        // the only way into the three lines the whole network runs on. A link you can only reach by
        // not touching anything is a link nobody reaches.
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("◀", fontSize = 18.sp, color = T.inkSoft,
                modifier = Modifier.clickable { onBack() }.padding(6.dp))
            Text("where you stand →", fontSize = 10.sp, color = T.accent,
                fontWeight = FontWeight.Medium, maxLines = 1,
                modifier = Modifier.clickable { onStanding() }.padding(6.dp))
        }

        val bridge = pickedBridge.takeIf { it >= 0 && it < bridges.size }?.let { bridges[it] }
        val peer = pickedPeer.takeIf { it >= 0 && it < peers.size }?.let { peers[it] }
        val sel = picked.takeIf { it >= 0 && it < people.size }?.let { people[it] }
        val dust = sky?.takeIf { pickedDust in 0 until it.size }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(14.dp)) {
            when {
                bridge != null -> Card {
                    Text(bridge.person, fontSize = T.small, color = T.ink,
                        fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(buildString {
                            append(if (bridge.mine) "introduced to you" else "you introduced them")
                            append("  ·  closeness ${(bridge.strength * 100).toInt()}%")
                            // The overlap, said out loud. It is the most interesting fact the
                            // network can produce and it would otherwise be invisible.
                            if (bridge.routes > 1) append("  ·  ${bridge.routes} people know them")
                        }, fontSize = 10.sp, color = T.inkFaint, lineHeight = 15.sp)
                    if (bridge.note.isNotBlank()) {
                        Spacer(Modifier.height(7.dp))
                        Text(bridge.note, fontSize = T.caption, color = T.inkSoft, lineHeight = 18.sp)
                    }
                }
                peer != null -> Card {
                    Text(peer.name, fontSize = T.small, color = T.ink, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text("%,d people  ·  %s".format(peer.networkSize, when (peer.reachability) {
                            "open"   -> "open to anyone"
                            "closed" -> "not taking requests"
                            else     -> "only through someone they know"
                        }), fontSize = 10.sp, color = T.inkFaint)
                    // A peer who has published nothing is a real person on an older build, not a
                    // broken row. Saying so beats three empty sections.
                    if (peer.offer.isBlank() && peer.lookingFor.isBlank()) {
                        Spacer(Modifier.height(9.dp))
                        Text("Hasn't said what they're looking for yet.",
                            fontSize = T.caption, color = T.inkFaint, lineHeight = 18.sp)
                    }
                    if (peer.offer.isNotBlank()) {
                        Spacer(Modifier.height(9.dp))
                        Text("OFFERS", fontSize = 8.sp, color = T.inkFaint)
                        Text(peer.offer, fontSize = T.caption, color = T.inkSoft, lineHeight = 18.sp)
                    }
                    if (peer.lookingFor.isNotBlank()) {
                        Spacer(Modifier.height(7.dp))
                        Text("LOOKING FOR", fontSize = 8.sp, color = T.inkFaint)
                        Text(peer.lookingFor, fontSize = T.caption, color = T.inkSoft, lineHeight = 18.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    // Said once, plainly. The size of their galaxy is a count and nothing else, and
                    // a screen that draws somebody's network should say what it does not know.
                    Text("who they know stays on their phone",
                        fontSize = 9.sp, color = T.inkFaint)
                }
                sel != null -> Card {
                    Text(sel.name, fontSize = T.small, color = T.ink, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(buildString {
                            if (sel.role.isNotBlank()) append(sel.role).append("  ·  ")
                            if (sel.company.isNotBlank()) append(sel.company).append("  ·  ")
                            append(sel.mainChannel).append("  ·  ")
                            append(if (sel.silentDays == 0) "today" else "${sel.silentDays}d ago")
                        }, fontSize = 10.sp, color = T.inkFaint, lineHeight = 15.sp)
                    Spacer(Modifier.height(11.dp))
                    Text("Open →", fontSize = T.caption, color = T.accent,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable { onPerson(sel.key) })
                }
                dust != null -> Card {
                    Text(dust.names[pickedDust], fontSize = T.small, color = T.ink,
                        fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(buildString {
                            val c = dust.companies[pickedDust]
                            if (c.isNotBlank()) append(c)
                            // Said plainly, because the alternative is a screen implying you have a
                            // relationship with twenty thousand people.
                            if (isNotEmpty()) append("  ·  ")
                            append(if (dust.touched[pickedDust]) "you have spoken"
                                   else "never spoken")
                        }, fontSize = 10.sp, color = T.inkFaint, lineHeight = 15.sp)
                }
                // Nothing about the field says what it is for, and the two things the network
                // cannot work without are both down a path nobody has a reason to take. So when
                // there is no profile there is exactly one thing to press.
                stand.isEmpty -> Card {
                    Text("Your agent isn't set up yet.", fontSize = T.small, color = T.ink,
                        fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(5.dp))
                    Text("Three questions, then it starts looking for the people you can't reach.",
                        fontSize = 10.sp, color = T.inkFaint, lineHeight = 15.sp)
                    Spacer(Modifier.height(13.dp))
                    Text("Set it up →", fontSize = T.caption, color = Color.White,
                        fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(999.dp))
                            .background(T.accent).clickable { onSetup() }.padding(vertical = 13.dp))
                }
                liveAsk != null -> Card {
                    val a = liveAsk!!
                    Text(a.criteria, fontSize = T.caption, color = T.ink, lineHeight = 18.sp)
                    Spacer(Modifier.height(9.dp))
                    // A real bar against a real target, not a spinner: the ask asks up to fifty
                    // people and you can watch it get there.
                    // Against what exists, not against an ambition. "1 of 1" is a true statement
                    // about a young network; "1 of 50" is a false one about a broken feature.
                    val denom = com.agentos.shell.tools.Asks.denominator(a.targetReach, liveEligible)
                    val frac = (liveReach.toFloat() / denom).coerceIn(0.02f, 1f)
                    Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(999.dp))
                        .background(T.hairline)) {
                        Box(Modifier.fillMaxWidth(frac).height(3.dp)
                            .clip(RoundedCornerShape(999.dp)).background(T.accent))
                    }
                    Spacer(Modifier.height(7.dp))
                    Text(buildString {
                            append("asked $liveReach of $denom")
                            if (denom < a.targetReach) append(" on SlyOS so far")
                            append("  ·  ").append(a.closesIn)
                            append("  ·  stops early at 3 found")
                        }, fontSize = 9.sp, color = T.inkFaint, lineHeight = 13.sp)
                }
                else -> {
                    // No card. The count is one faint line, because the field is the screen and a
                    // panel sitting on top of it was chrome explaining a picture that explains itself.
                    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when {
                                !loaded && sky == null -> "reading your network…"
                                !loaded -> "%,d people  ·  looking for new ones…".format(
                                    sky?.total ?: 0)
                                galaxy == null || galaxy!!.total == 0 ->
                                    "Import your connections and the field fills in."
                                galaxy!!.count < galaxy!!.total ->
                                    // Never a silent cap.
                                    "%,d of %,d".format(galaxy!!.count, galaxy!!.total)
                                peers.isEmpty() -> "%,d people  ·  %d you talk to".format(
                                    galaxy!!.total, people.size)
                                else -> "%,d people  ·  %d on SlyOS".format(
                                    galaxy!!.total, peers.size)
                            },
                            fontSize = 10.sp, color = T.inkFaint, maxLines = 1)
                        Text("ask the network →", fontSize = 10.sp, color = T.accent,
                            fontWeight = FontWeight.Medium, maxLines = 1,
                            modifier = Modifier.clickable { onAsk() })
                    }
                }
            }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .background(T.bgElevated).padding(16.dp)) { content() }
}
