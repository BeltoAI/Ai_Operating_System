import SwiftUI

/// SLY ORBIT — the loading indicator, ported from Compose `SlyOrbit`.
///
/// An actual Calabi–Yau manifold (Fermat-quintic cross-section, 25 patches) rendered as a filled,
/// shaded, iridescent surface tumbling in 3D, painter-sorted back to front. Wherever SlyOS is
/// thinking, this is what turns — a spinner would work, but this is the app's signature and the
/// reason waiting for it doesn't feel like waiting.
///
/// The geometry is built once and only re-projected each frame; the two rotation periods are
/// deliberately coprime-ish (7s and 11s) so the tumble never visibly repeats.
struct SlyOrbit: View {

    var size: CGFloat = 56

    private static let ramp: [Color] = [
        .hex(0x6E5AA8), .hex(0xB0468C), .hex(0xD65A6E), .hex(0xE8642C), .hex(0xE0A24E)
    ]

    /// Flat vertex arrays plus a quad index list and a hue per quad — the same shape the Compose
    /// version builds, so the surface tessellates identically.
    private struct Geometry {
        let x: [Double], y: [Double], z: [Double]
        let quads: [[Int]]
        let hue: [Double]
    }

    private static let geometry: Geometry = {
        let nu = 4, nv = 3, n = 5.0
        let pointsPerPatch = (nu + 1) * (nv + 1)
        var x = [Double](repeating: 0, count: 25 * pointsPerPatch)
        var y = x, z = x
        var quads: [[Int]] = []
        var hue: [Double] = []
        var vi = 0

        for k1 in 0..<5 {
            for k2 in 0..<5 {
                let base = vi
                for iu in 0...nu {
                    for iv in 0...nv {
                        let u = Double(iu) / Double(nu) * (.pi / 2)
                        let v = (Double(iv) / Double(nv) - 0.5) * 1.9
                        let p = point(k1: k1, k2: k2, u: u, v: v, n: n)
                        x[vi] = p.0; y[vi] = p.1; z[vi] = p.2
                        vi += 1
                    }
                }
                for iu in 0..<nu {
                    for iv in 0..<nv {
                        let a = base + iu * (nv + 1) + iv
                        quads.append([a, a + (nv + 1), a + (nv + 1) + 1, a + 1])
                        hue.append(Double((k1 + k2) % 5) / 4.0)
                    }
                }
            }
        }
        return Geometry(x: x, y: y, z: z, quads: quads, hue: hue)
    }()

    /// One point on the Fermat quintic's cross-section.
    ///
    /// `z1 = (cos(u+iv))^(2/n) · e^(i·2πk1/n)` and `z2 = (sin(u+iv))^(2/n) · e^(i·2πk2/n)`, with the
    /// surface embedded in 3D by mixing the two imaginary parts at 45°.
    private static func point(k1: Int, k2: Int, u: Double, v: Double, n: Double) -> (Double, Double, Double) {
        let p = 2.0 / n

        func power(_ re: Double, _ im: Double, phase: Double) -> (Double, Double) {
            let magnitude = pow(re * re + im * im, p / 2)
            let angle = atan2(im, re) * p
            let a = magnitude * cos(angle), b = magnitude * sin(angle)
            return (a * cos(phase) - b * sin(phase), a * sin(phase) + b * cos(phase))
        }

        let z1 = power(cos(u) * cosh(v), -sin(u) * sinh(v), phase: 2 * .pi * Double(k1) / n)
        let z2 = power(sin(u) * cosh(v),  cos(u) * sinh(v), phase: 2 * .pi * Double(k2) / n)

        let alpha = Double.pi / 4
        return (z1.0, z2.0, cos(alpha) * z1.1 + sin(alpha) * z2.1)
    }

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30.0)) { timeline in
            let t = timeline.date.timeIntervalSinceReferenceDate
            let ry = (t.truncatingRemainder(dividingBy: 7) / 7) * 2 * .pi
            let rx = (t.truncatingRemainder(dividingBy: 11) / 11) * 2 * .pi

            Canvas { ctx, canvasSize in
                draw(ctx: ctx, canvasSize: canvasSize, ry: ry, rx: rx)
            }
            .frame(width: size, height: size)
        }
        .accessibilityHidden(true)
    }

    private func draw(ctx: GraphicsContext, canvasSize: CGSize, ry: Double, rx: Double) {
        let g = Self.geometry
        let cx = canvasSize.width / 2, cy = canvasSize.height / 2
        let scale = min(canvasSize.width, canvasSize.height) * 0.33

        let cry = cos(ry), sry = sin(ry), crx = cos(rx), srx = sin(rx)
        var sx = [Double](repeating: 0, count: g.x.count)
        var sy = sx, sz = sx

        for i in 0..<g.x.count {
            let x1 = g.x[i] * cry + g.z[i] * sry
            let z1 = -g.x[i] * sry + g.z[i] * cry
            let y2 = g.y[i] * crx - z1 * srx
            let z2 = g.y[i] * srx + z1 * crx
            sx[i] = cx + x1 * scale
            sy[i] = cy - y2 * scale
            sz[i] = z2
        }

        // Painter's algorithm: back to front, so nearer patches cover farther ones.
        let order = g.quads.indices.sorted { a, b in
            let da = g.quads[a].reduce(0.0) { $0 + sz[$1] }
            let db = g.quads[b].reduce(0.0) { $0 + sz[$1] }
            return da < db
        }

        let edge = Color.hex(0x15120E).opacity(0.16)
        let base = Color.hex(0x1A1714)

        for qi in order {
            let q = g.quads[qi]
            let depth = q.reduce(0.0) { $0 + sz[$1] } / 4
            let shade = min(1, max(0, (depth + 1.4) / 2.8))
            let colour = base.mix(with: Self.ramp(g.hue[qi]), by: 0.32 + 0.68 * shade)

            var path = Path()
            path.move(to: CGPoint(x: sx[q[0]], y: sy[q[0]]))
            for k in 1..<4 { path.addLine(to: CGPoint(x: sx[q[k]], y: sy[q[k]])) }
            path.closeSubpath()

            ctx.fill(path, with: .color(colour))
            ctx.stroke(path, with: .color(edge), lineWidth: 0.7)
        }
    }

    private static func ramp(_ x: Double) -> Color {
        let f = min(0.999, max(0, x)) * Double(ramp.count - 1)
        let i = Int(f)
        return ramp[i].mix(with: ramp[i + 1], by: f - Double(i))
    }
}

extension Color {
    /// Linear blend between two colours. `Color.mix(with:by:)` exists from iOS 18; this keeps the
    /// deployment target at 17 without a conditional at every call site.
    func mix(with other: Color, by amount: Double) -> Color {
        let t = min(1, max(0, amount))
        let a = UIColor(self), b = UIColor(other)
        var ar: CGFloat = 0, ag: CGFloat = 0, ab: CGFloat = 0, aa: CGFloat = 0
        var br: CGFloat = 0, bg: CGFloat = 0, bb: CGFloat = 0, ba: CGFloat = 0
        a.getRed(&ar, green: &ag, blue: &ab, alpha: &aa)
        b.getRed(&br, green: &bg, blue: &bb, alpha: &ba)
        return Color(.sRGB,
                     red: Double(ar + (br - ar) * t),
                     green: Double(ag + (bg - ag) * t),
                     blue: Double(ab + (bb - ab) * t),
                     opacity: Double(aa + (ba - aa) * t))
    }
}
