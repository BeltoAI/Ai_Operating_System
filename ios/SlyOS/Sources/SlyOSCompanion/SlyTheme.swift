import SwiftUI

/// Design tokens — a literal translation of the Android `theme/Tokens.kt` object `T`.
///
/// Every value here has a counterpart in Kotlin and in `shared/design-tokens/tokens.json`.
/// The three must never drift: if you change a colour, change it in all three, or the iOS app
/// stops looking like the same product as the Android one. Kotlin is the source of truth
/// because that is where the design was actually made.
enum T {

    // Appearance lives in `SlySettings`, not here — it has to be observable to redraw the UI.
    // These colour functions stay pure so a view can render either palette on demand.

    // MARK: - Colour  (light | dark)

    static func bg(_ d: Bool) -> Color         { d ? .hex(0x12100C) : .hex(0xF4EFE6) }
    static func bgElevated(_ d: Bool) -> Color { d ? .hex(0x201B15) : .hex(0xFBF8F2) }
    static func ink(_ d: Bool) -> Color        { d ? .hex(0xF4EFE6) : .hex(0x1A1714) }
    static func inkSoft(_ d: Bool) -> Color    { d ? .hex(0xC7BEB0) : .hex(0x5C544B) }
    static func inkFaint(_ d: Bool) -> Color   { d ? .hex(0x8C8275) : .hex(0x9A9085) }
    static func accentSoft(_ d: Bool) -> Color { d ? .hex(0x5A3120) : .hex(0xF2C7AE) }
    static func hairline(_ d: Bool) -> Color   { d ? .hex(0x352D24) : .hex(0xE2DACB) }
    static func danger(_ d: Bool) -> Color     { d ? .hex(0xE06A5C) : .hex(0xB23A2E) }
    /// Positive / value delivered.
    static func good(_ d: Bool) -> Color       { d ? .hex(0x5DCAA5) : .hex(0x1D8F63) }

    /// Brand orange — identical in both themes, so it needs no parameter.
    static let accent = Color.hex(0xE8642C)

    // MARK: - Type sizes  (Android sp → iOS pt, 1:1)

    static let wordmark: CGFloat = 30
    static let wordmarkBig: CGFloat = 46
    static let time: CGFloat = 60
    static let prompt: CGFloat = 26
    static let body: CGFloat = 17
    static let small: CGFloat = 14
    static let caption: CGFloat = 12

    // MARK: - Spacing  (Android dp → iOS pt, 1:1)

    static let xs: CGFloat = 4
    static let sm: CGFloat = 8
    static let md: CGFloat = 16
    static let lg: CGFloat = 24
    static let xl: CGFloat = 40
    static let xxl: CGFloat = 72

    /// The handwritten wordmark face.
    ///
    /// Android asks for `FontFamily.Cursive`, which resolves to whatever script face the device
    /// ships — so Android and iOS would only match by luck. Bundling Caveat (the face named in
    /// `tokens.json`) on both platforms is what actually makes the wordmark identical; until the
    /// TTF is in the bundle this falls back to Snell Roundhand, the closest system script.
    static let scriptFamily = "Caveat"
    static let scriptFallback = "SnellRoundhand-Black"
}

extension Color {
    /// Builds a colour from the same `0xRRGGBB` literals the Kotlin tokens use, so the two files
    /// can be diffed by eye.
    static func hex(_ rgb: UInt32) -> Color {
        Color(
            .sRGB,
            red: Double((rgb >> 16) & 0xFF) / 255.0,
            green: Double((rgb >> 8) & 0xFF) / 255.0,
            blue: Double(rgb & 0xFF) / 255.0,
            opacity: 1.0
        )
    }
}

/// Reads `T.dark` and hands the current palette to a view, so screens read `t.ink` the way the
/// Compose code reads `T.ink` instead of threading a boolean through every call site.
struct Palette {
    let dark: Bool
    var bg: Color         { T.bg(dark) }
    var bgElevated: Color { T.bgElevated(dark) }
    var ink: Color        { T.ink(dark) }
    var inkSoft: Color    { T.inkSoft(dark) }
    var inkFaint: Color   { T.inkFaint(dark) }
    var accent: Color     { T.accent }
    var accentSoft: Color { T.accentSoft(dark) }
    var hairline: Color   { T.hairline(dark) }
    var danger: Color     { T.danger(dark) }
    var good: Color       { T.good(dark) }
}

private struct PaletteKey: EnvironmentKey {
    static let defaultValue = Palette(dark: false)
}

extension EnvironmentValues {
    var palette: Palette {
        get { self[PaletteKey.self] }
        set { self[PaletteKey.self] = newValue }
    }
}

// MARK: - Shared chrome  (mirrors screens/CommonUi.kt)

/// Cursive "SlyOS" wordmark. Same proportions as the Compose `Wordmark`.
struct Wordmark: View {
    var big: Bool = false
    @Environment(\.palette) private var p

    var body: some View {
        let size = big ? T.wordmarkBig : T.wordmark
        Text("SlyOS")
            .font(.custom(T.scriptFamily, size: size).weight(.medium))
            .foregroundStyle(p.ink)
    }
}

/// The 7pt accent dot used as a status marker throughout the Android UI.
struct OrangeDot: View {
    var body: some View {
        Circle().fill(T.accent).frame(width: 7, height: 7)
    }
}

/// 22pt medium heading — matches Compose `Heading`.
struct Heading: View {
    let text: String
    @Environment(\.palette) private var p

    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text).font(.system(size: 22, weight: .medium)).foregroundStyle(p.ink)
    }
}

/// 1pt divider in the hairline colour — matches Compose `Hairline`.
struct Hairline: View {
    @Environment(\.palette) private var p

    var body: some View {
        Rectangle().fill(p.hairline).frame(height: 1).frame(maxWidth: .infinity)
    }
}
