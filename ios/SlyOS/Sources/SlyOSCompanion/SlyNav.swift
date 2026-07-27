import SwiftUI

/// The five panels of SlyOS, in the order they sit on the bar.
///
/// These are the Android screens, not the placeholder tabs the companion app shipped with
/// (home/memory/actions/settings). The order matters: Brain is index 2 so it lands dead centre.
enum Panel: String, CaseIterable, Identifiable {
    case home, now, brain, research, powers

    var id: String { rawValue }

    var title: String {
        switch self {
        case .home: "Home"
        case .now: "Now"
        case .brain: "Memory"
        case .research: "Research"
        case .powers: "Powers"
        }
    }

    /// SF Symbols chosen to read as the same glyph as the Material icon Android uses — checked
    /// against the device screenshot, not guessed: Research is a conical flask and Powers is a
    /// shop awning, so `flask.fill` and `storefront.fill` are the matches (both SF Symbols 5 /
    /// iOS 17, which `@Observable` already requires anyway).
    var symbol: String {
        switch self {
        case .home: "house.fill"
        case .now: "bolt.fill"
        // `cpu`, not `memorychip`. Material's Memory glyph is a square chip with legs on all four
        // sides around an inner square; `memorychip` only has legs top and bottom, and the `.fill`
        // variant rendered as a featureless blob inside the accent circle.
        case .brain: "cpu"
        case .research: "flask.fill"
        case .powers: "storefront.fill"
        }
    }
}

/// One bar item. Mirrors the private Compose `NavTab`: a 26pt icon and nothing else — the label is
/// an accessibility name, not visible text — accent when active, faint when not, and a press that
/// scales to 0.84 over 130ms.
private struct NavTab: View {
    let panel: Panel
    let active: Bool
    var badge: Int = 0
    let onTap: () -> Void

    @Environment(\.palette) private var p
    @State private var pressed = false

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Image(systemName: panel.symbol)
                .font(.system(size: 26))
                .foregroundStyle(active ? p.accent : p.inkFaint)
                .frame(width: 26, height: 26)

            if badge > 0 {
                let wide = badge > 9
                Text(wide ? "9+" : "\(badge)")
                    .font(.system(size: 8, weight: .bold))
                    .foregroundStyle(.white)
                    .frame(width: wide ? 16 : 14, height: wide ? 16 : 14)
                    .background(Circle().fill(p.accent))
                    .offset(x: 7, y: -7)
            }
        }
        // No clip: a rounded clip would crop the badge that overhangs the icon.
        .padding(.horizontal, 8).padding(.top, 8).padding(.bottom, 4)
        .scaleEffect(pressed ? 0.84 : 1.0)
        .animation(.easeInOut(duration: 0.13), value: pressed)
        .contentShape(Rectangle())
        .onLongPressGesture(minimumDuration: .infinity, maximumDistance: .infinity) {
            // Never fires — `.infinity` means this is only here to expose the pressing callback.
        } onPressingChanged: { isDown in
            pressed = isDown
            if !isDown { onTap() }
        }
        .accessibilityLabel(panel.title)
        .accessibilityAddTraits(active ? [.isButton, .isSelected] : .isButton)
    }
}

/// Persistent bottom navigation shared by every main panel, matching Compose `SlyBottomNav`.
///
/// The brain sits dead centre in a 50pt circle and is always emphasised — filled accent when you
/// are on it, a 16% accent wash when you are not. Holding it for three seconds opens conversational
/// voice mode, exactly as on Android; a plain tap opens Memory.
struct SlyBottomNav: View {
    let current: Panel
    var nowCount: Int = 0
    var onBrainHold: () -> Void = {}
    let onNav: (Panel) -> Void

    @Environment(\.palette) private var p

    var body: some View {
        HStack(alignment: .center) {
            NavTab(panel: .home, active: current == .home) { onNav(.home) }
            Spacer()
            NavTab(panel: .now, active: current == .now, badge: nowCount) { onNav(.now) }
            Spacer()
            brain
            Spacer()
            NavTab(panel: .research, active: current == .research) { onNav(.research) }
            Spacer()
            NavTab(panel: .powers, active: current == .powers) { onNav(.powers) }
        }
        .padding(.horizontal, 6)
    }

    private var brain: some View {
        let active = current == .brain
        return ZStack {
            Circle()
                .fill(active ? p.accent : p.accent.opacity(0.16))
                .frame(width: 56, height: 56)
            Image(systemName: Panel.brain.symbol)
                .font(.system(size: 30))
                .foregroundStyle(active ? Color.white : p.accent)
        }
        .contentShape(Circle())
        // Tap → Brain. Press-and-hold ~3s → conversational voice mode.
        .onTapGesture { onNav(.brain) }
        .onLongPressGesture(minimumDuration: 3.0) { onBrainHold() }
        .accessibilityLabel(Panel.brain.title)
        .accessibilityHint("Double tap to open memory. Touch and hold to talk.")
    }
}
