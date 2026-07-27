import SwiftUI

/// The brain, drawn — a port of Android's `MemoryGraphScreen`.
///
/// Every constant here is lifted from the Compose original rather than re-invented: the same
/// repulsion and spring forces, the same hash for depth, the same focal length, the same node
/// radius curve and edge alphas. That is what makes the two phones show recognisably the *same*
/// brain rather than two different visualisations of the same data.
enum GraphPalette {

    /// Colour a memory by what it *is*, so the cloud reads at a glance instead of looking like an
    /// undifferentiated cluster.
    static func type(_ t: String) -> Color {
        switch t {
        case "hub":        .hex(0x2E2A24)
        case "project":    .hex(0x46403A)
        case "person":     .hex(0x9A8B77)
        case "summary":    .hex(0xB09356)
        case "task":       .hex(0xBC6242)
        case "prompt":     .hex(0x8C8475)
        case "response":   .hex(0xB3AB9C)
        case "transcript": .hex(0x86907A)
        case "idea":       .hex(0xC39A5E)
        case "recall":     .hex(0x6E8FA6)
        case "network":    .hex(0x2E6F9E)
        default:           .hex(0x8C8475)
        }
    }

    /// A person is coloured by the platform they came from, so the brain reads as colourful lobes.
    static func platform(_ source: String) -> Color {
        let s = source.lowercased()
        if s.contains("whatsapp") { return .hex(0x1FA855) }
        if s.contains("instagram") { return .hex(0xC13584) }
        if s.contains("linkedin") { return .hex(0x2E6F9E) }
        if s.contains("telegram") { return .hex(0x2AABEE) }
        if s.contains("messen") || s.contains("orca") { return .hex(0x7B3FF2) }
        if s.contains("signal") { return .hex(0x3A76F0) }
        if s.contains("mail") || s.contains("gmail") { return .hex(0xD44638) }
        if s.contains("calendar") { return .hex(0x4285F4) }
        return .hex(0x9A8B77)
    }

    /// The legend, in the order Android shows it.
    static let legend: [(label: String, type: String)] = [
        ("Person", "person"), ("Fact", "idea"), ("Task", "task"), ("Paper", "paper"),
        ("Recall", "recall"), ("Network", "network"), ("Note", "prompt")
    ]
}

struct GraphNode: Identifiable {
    let id: Int
    var label: String
    var type: String
    var source: String
    var strength: Double        // 0…1, drives radius
    var x: Double = 0
    var y: Double = 0
}

struct GraphEdge {
    let a: Int
    let b: Int
}

/// Compose's `Canvas` measures in **pixels**; SwiftUI's measures in **points**.
///
/// Every geometry constant in the Android original — a 0.8 stroke, a radius of `4 + strength * 7` —
/// is therefore a pixel value on a ~2.75x screen. Copying those numbers straight into SwiftUI made
/// each one roughly three times too big: hairline edges became slabs and the dots swelled into
/// blobs, which is why the cloud read as noise. Dividing by the reference density restores the
/// proportions the design was drawn at.
let androidDensity: Double = 2.75

/// Builds and lays out the graph from whatever the store holds.
@Observable
final class MemoryGraphModel {

    private(set) var nodes: [GraphNode] = []
    private(set) var edges: [GraphEdge] = []
    private(set) var built = false

    /// Node 0 is always the hub — the "SlyOS" label at the centre of the cloud.
    func build() {
        var n: [GraphNode] = [
            GraphNode(id: 0, label: "SlyOS", type: "hub", source: "", strength: 1.0)
        ]
        var e: [GraphEdge] = []

        // One node per memory, typed by kind so the colours mean something.
        for (i, m) in SlyStore.shared.recent(limit: 600).enumerated() {
            let id = i + 1
            let label = m.person.isEmpty
                ? (m.title.isEmpty ? String(m.body.prefix(28)) : m.title)
                : m.person
            n.append(GraphNode(
                id: id,
                label: label,
                type: Self.graphType(for: m.kind),
                source: m.source,
                // Longer, more substantial memories sit slightly larger — same idea as Android's
                // strength, which is a blend of length and how often a memory gets used.
                strength: min(1.0, 0.15 + Double(m.body.count) / 900.0)
            ))
            e.append(GraphEdge(a: 0, b: id))
        }

        // Same-person memories link to each other, which is what makes lobes form.
        var byPerson: [String: [Int]] = [:]
        for node in n where node.type == "person" || !node.source.isEmpty {
            byPerson[node.label, default: []].append(node.id)
        }
        for (_, ids) in byPerson where ids.count > 1 {
            for i in 0..<(ids.count - 1) { e.append(GraphEdge(a: ids[i], b: ids[i + 1])) }
        }

        // Lay out on plain local arrays, then publish once.
        //
        // Doing this in place on `nodes` is what made the graph appear to hang: `nodes` is an
        // observed property, so every one of the ~5 million writes in the solver fired the
        // observation registrar. The physics is not the expensive part — telling SwiftUI about it
        // five million times is.
        Self.solve(nodes: &n, edges: e)
        nodes = n
        edges = e
        built = true
    }

    private static func graphType(for kind: String) -> String {
        switch kind {
        case "message", "mail": "person"
        case "event": "task"
        case "doc", "paper": "paper"
        case "fact": "idea"
        case "contact": "network"
        case "note": "prompt"
        default: "prompt"
        }
    }

    /// Force-directed layout, with Android's constants exactly.
    ///
    /// Repulsion falls off as 1/d² and springs pull linked nodes toward a 150pt rest length. Sixty
    /// passes is enough to settle; it runs once on build rather than every frame, because the
    /// rotation in the view is a camera move, not a re-simulation.
    private static func solve(nodes: inout [GraphNode], edges: [GraphEdge]) {
        let count = nodes.count
        guard count > 1 else { return }

        // Flat Double arrays rather than an array of structs: the solver touches these millions of
        // times and this keeps each access a plain memory read.
        var xs = [Double](repeating: 0, count: count)
        var ys = [Double](repeating: 0, count: count)
        for i in 0..<count {
            xs[i] = (Double.random(in: 0...1) - 0.5) * 700
            ys[i] = (Double.random(in: 0...1) - 0.5) * 700
        }

        var fx = [Double](repeating: 0, count: count)
        var fy = [Double](repeating: 0, count: count)

        for _ in 0..<60 {
            for i in 0..<count { fx[i] = 0; fy[i] = 0 }

            for i in 0..<count {
                for j in (i + 1)..<count {
                    let dx = xs[i] - xs[j]
                    let dy = ys[i] - ys[j]
                    let d2 = dx * dx + dy * dy + 0.01
                    let d = d2.squareRoot()
                    let rep = 5200.0 / d2
                    let ux = dx / d, uy = dy / d
                    fx[i] += ux * rep; fy[i] += uy * rep
                    fx[j] -= ux * rep; fy[j] -= uy * rep
                }
            }

            for e in edges where e.a < count && e.b < count {
                let dx = xs[e.b] - xs[e.a]
                let dy = ys[e.b] - ys[e.a]
                let d = max(1.0, (dx * dx + dy * dy).squareRoot())
                let f = (d - 150.0) * 0.015
                let ux = dx / d, uy = dy / d
                fx[e.a] += ux * f; fy[e.a] += uy * f
                fx[e.b] -= ux * f; fy[e.b] -= uy * f
            }

            for i in 0..<count {
                xs[i] += max(-12, min(12, fx[i]))
                ys[i] += max(-12, min(12, fy[i]))
            }
        }

        // Recentre so the cloud sits in the middle regardless of where it drifted.
        let cx = xs.reduce(0, +) / Double(count)
        let cy = ys.reduce(0, +) / Double(count)
        for i in 0..<count { xs[i] -= cx; ys[i] -= cy }

        // Normalise the spread. A force layout's radius grows with the number of nodes, so without
        // this a brain with 400 memories fills the canvas and one with 4,000 spills off it. Scaling
        // the 90th-percentile radius to a fixed target keeps the compact globe-with-margin look at
        // any size — the thing that makes it read as a brain rather than static.
        var radii = (0..<count).map { (xs[$0] * xs[$0] + ys[$0] * ys[$0]).squareRoot() }
        radii.sort()
        let p90 = radii[min(count - 1, Int(Double(count) * 0.9))]
        let k = p90 > 1 ? spreadTarget / p90 : 1.0

        for i in 0..<count {
            nodes[i].x = xs[i] * k
            nodes[i].y = ys[i] * k
        }
    }

    /// How far the laid-out cloud reaches, in graph units, at the 90th percentile.
    ///
    /// The force solver's radius grows with node count, so the result is normalised to this instead
    /// of left to drift — 130 units at the view's resting 0.75 zoom puts the cloud at roughly the
    /// same fraction of the screen it occupies on Android.
    static let spreadTarget = 130.0

    /// Depth from a hash of the id — stable across launches, and free of any stored z.
    ///
    /// Scaled to `spreadTarget` so the cloud is **isotropic**. The raw hash spans ±320; against an
    /// x/y spread normalised to 130 that made the brain far deeper than it was wide, and rotating
    /// it swung a flat disc edge-on instead of turning a globe.
    static func depthZ(_ id: Int) -> Double {
        let h = (id &* 374761393) &+ 668265263
        let raw = Double((h & 0x7fffffff) % 640 - 320)
        return raw * (spreadTarget / 320.0)
    }

    /// Yaw, then tilt, then perspective divide. Shared by the renderer and hit-testing so taps
    /// land on what you actually see.
    static func project(_ n: GraphNode, cx: Double, cy: Double,
                        scale: Double, yaw: Double, tilt: Double) -> (CGPoint, Double) {
        let z = depthZ(n.id)
        let ca = cos(yaw), sa = sin(yaw)
        let x2 = n.x * ca - z * sa
        let z2 = n.x * sa + z * ca
        let ct = cos(tilt), st = sin(tilt)
        let y2 = n.y * ct - z2 * st
        let z3 = n.y * st + z2 * ct
        let focal = 1100.0
        let p = min(1.9, max(0.45, focal / (focal - z3)))
        return (CGPoint(x: cx + x2 * scale * p, y: cy + y2 * scale * p), p)
    }
}

/// The rendered brain: rotate by dragging, zoom by pinching, tap a node to select it.
struct MemoryGraphView: View {
    let model: MemoryGraphModel
    @Binding var selected: Int?
    var typeFilter: String?
    var query: String = ""

    @Environment(\.palette) private var p

    @State private var userYaw: Double = 0
    // A slight starting tilt reads as 3D immediately, and 0.75 is Android's resting zoom — at 1.0
    // the cloud fills the canvas edge to edge and reads as noise rather than a globe.
    @State private var userTilt: Double = 0.35
    @State private var scale: Double = 0.75
    @State private var pinchStart: Double = 0.75
    @State private var lastDrag: CGSize = .zero

    var body: some View {
        // GeometryReader so hit-testing uses the very same centre the renderer did — otherwise taps
        // land next to the node you aimed at, and worse the further you are from the middle.
        GeometryReader { geo in
            // A slow idle rotation, as on Android. Driven off the timeline clock rather than a
            // repeating animation so the canvas redraws at a steady rate instead of per-node.
            TimelineView(.animation(minimumInterval: 1.0 / 30.0)) { timeline in
                let spin = timeline.date.timeIntervalSinceReferenceDate * 0.05
                Canvas { ctx, size in
                    draw(ctx: ctx, size: size, yaw: spin + userYaw, tilt: userTilt)
                }
                .contentShape(Rectangle())
                .gesture(rotateAndZoom)
                .onTapGesture { point in
                    selected = hitTest(point, in: geo.size, yaw: spin + userYaw, tilt: userTilt)
                }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }

    private var rotateAndZoom: some Gesture {
        SimultaneousGesture(
            // `translation` is cumulative for the whole drag, so using it directly re-applies the
            // entire gesture every frame and the globe accelerates away from your finger. Android
            // gets a per-frame `pan` delta; this reconstructs it.
            DragGesture()
                .onChanged { v in
                    let dx = v.translation.width - lastDrag.width
                    let dy = v.translation.height - lastDrag.height
                    lastDrag = v.translation
                    userYaw += dx * 0.006
                    userTilt = min(1.3, max(-1.3, userTilt - dy * 0.006))
                }
                .onEnded { _ in lastDrag = .zero },
            MagnifyGesture()
                .onChanged { v in scale = min(3, max(0.4, pinchStart * v.magnification)) }
                .onEnded { _ in pinchStart = scale }
        )
    }

    private func draw(ctx: GraphicsContext, size: CGSize, yaw: Double, tilt: Double) {
        let cx = size.width / 2, cy = size.height / 2
        let nodes = model.nodes
        guard !nodes.isEmpty else { return }

        func P(_ n: GraphNode) -> (CGPoint, Double) {
            MemoryGraphModel.project(n, cx: cx, cy: cy, scale: scale, yaw: yaw, tilt: tilt)
        }

        // Which nodes are connected to the selection — everything else dims.
        var connected: Set<Int>?
        if let s = selected {
            var c = Set<Int>()
            for e in model.edges where e.a == s || e.b == s { c.insert(e.a); c.insert(e.b) }
            connected = c
        }

        // Edges first, underneath everything.
        let edgeBase = p.dark ? Color.hex(0xB8AE9E) : Color.hex(0x1A1714)
        for e in model.edges {
            guard e.a < nodes.count, e.b < nodes.count else { continue }
            let hot = selected != nil && (e.a == selected || e.b == selected)
            let colour = hot ? p.accent.opacity(0.35)
                             : edgeBase.opacity(selected != nil ? 0.05 : 0.10)
            var path = Path()
            path.move(to: P(nodes[e.a]).0)
            path.addLine(to: P(nodes[e.b]).0)
            ctx.stroke(path, with: .color(colour), lineWidth: (hot ? 1.4 : 0.8) / androidDensity)
        }

        // Nodes, far to near so the near ones land on top.
        let ordered = nodes.enumerated()
            .map { ($0.offset, P($0.element).1) }
            .sorted { $0.1 < $1.1 }

        for (index, depth) in ordered {
            let n = nodes[index]
            let (pos, _) = P(n)
            let isHub = n.type == "hub"
            let isSelected = n.id == selected

            let dim: Bool
            if let tf = typeFilter { dim = !isHub && n.type != tf }
            else if selected != nil { dim = !(connected?.contains(n.id) ?? false) }
            else if !query.isEmpty {
                dim = !n.label.localizedCaseInsensitiveContains(query) && !isSelected
            } else { dim = false }

            let alpha = dim ? 0.16 : 1.0
            let colour: Color = isSelected || isHub ? p.accent
                : (n.type == "person" ? GraphPalette.platform(n.source) : GraphPalette.type(n.type))

            let r = ((4 + n.strength * 7) / androidDensity) * scale * depth
            let rect = CGRect(x: pos.x - r, y: pos.y - r, width: r * 2, height: r * 2)
            ctx.fill(Circle().path(in: rect), with: .color(colour.opacity(alpha)))
            ctx.stroke(Circle().path(in: rect),
                       with: .color(edgeBase.opacity(0.12 * alpha)),
                       lineWidth: 0.8 / androidDensity)

            if isSelected {
                let inset = -5.0 / androidDensity
                let g = rect.insetBy(dx: inset, dy: inset)
                ctx.stroke(Circle().path(in: g), with: .color(p.accent),
                           lineWidth: 1.6 / androidDensity)
            }

            // Labels only where they can be read: the hub, the selection, or when zoomed in.
            if isSelected || isHub || scale > 1.4 {
                let text = n.label.count > 22 ? n.label.prefix(21) + "…" : n.label[...]
                ctx.draw(
                    Text(String(text))
                        .font(.system(size: 10.5))
                        .foregroundStyle((p.dark ? Color.hex(0xC7BEB0) : Color.hex(0x6B6258))
                            .opacity(alpha * 0.86)),
                    at: CGPoint(x: pos.x, y: pos.y + r + 13 / androidDensity)
                )
            }
        }
    }

    /// Nearest node under the finger, preferring the one closest to the camera when several
    /// overlap. The hit radius is generous on purpose — these are small targets.
    private func hitTest(_ tap: CGPoint, in size: CGSize, yaw: Double, tilt: Double) -> Int? {
        var best: Int?
        var bestDepth = -Double.infinity
        for n in model.nodes {
            let (pos, depth) = MemoryGraphModel.project(
                n, cx: size.width / 2, cy: size.height / 2,
                scale: scale, yaw: yaw, tilt: tilt)
            let r = (7 + n.strength * 13 + 6) * depth
            if hypot(tap.x - pos.x, tap.y - pos.y) < r && depth > bestDepth {
                bestDepth = depth
                best = n.id
            }
        }
        return best
    }
}
