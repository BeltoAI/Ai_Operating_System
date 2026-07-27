import SwiftUI

/// Powers — the store, matching Compose `StoreScreen`.
///
/// Same anatomy as Android: a wish box, category pills, a featured card, then a grid of powers each
/// carrying a rating and a plain statement of what it needs. The difference is the catalogue. Every
/// power here is something an iPhone can genuinely do, and the ones that need a gateway or an
/// account say so on the card rather than after you tap Get.
struct PowersPanel: View {
    @Environment(\.palette) private var p

    @State private var permissions = Permissions.shared
    @State private var router = ModelRouter.shared
    @State private var wish = ""
    @State private var category: Category = .forYou
    @State private var brainCount = 0
    @State private var googleConnected = false
    @State private var clawConnected = false

    enum Category: String, CaseIterable, Identifiable {
        case forYou = "For you", see = "See", speak = "Speak", create = "Create", know = "Know"
        var id: String { rawValue }
    }

    struct Power: Identifiable {
        let id: String
        let title: String
        let rating: Double
        let category: Category
        let glyph: String
        let tint: Color
        /// What it needs. Empty means it works right now.
        let needs: String
        var ready: Bool { needs.isEmpty }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
            wishBox
            categoryRow

            ScrollView {
                VStack(alignment: .leading, spacing: T.lg) {
                    if let featured { FeaturedCard(power: featured, palette: p) }
                    grid("WORKS ON YOUR PHONE NOW", powers.filter { $0.ready && matches($0) })
                    grid("NEEDS SETUP", powers.filter { !$0.ready && matches($0) })
                    notPossible
                }
                .padding(.top, T.md)
                .padding(.bottom, T.lg)
            }
            .scrollIndicators(.hidden)
        }
        .padding(.horizontal, T.md)
        .task { refresh() }
    }

    // MARK: - Chrome

    private var header: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Heading("Powers")
                Spacer()
                Button("reset") { wish = ""; category = .forYou }
                    .font(.system(size: T.body)).foregroundStyle(p.accent)
            }
            Text("\(powers.filter(\.ready).count) working")
                .font(.system(size: T.small)).foregroundStyle(p.accent)
        }
        .padding(.top, T.md)
    }

    /// The wish box. Android lets you describe a power you want; here it searches the catalogue and
    /// says plainly when nothing matches, rather than pretending to build it.
    private var wishBox: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("give your phone the power to —")
                .font(.system(size: T.small)).foregroundStyle(p.accent)
            TextField("", text: $wish, prompt:
                Text("speak in my voice…").foregroundStyle(p.inkFaint))
                .font(.system(size: T.prompt - 4))
                .foregroundStyle(p.ink)
                .textFieldStyle(.plain)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 16).fill(p.bgElevated))
        .padding(.top, T.md)
    }

    private var categoryRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                ForEach(Category.allCases) { c in
                    let on = category == c
                    Button { category = c } label: {
                        Text(c.rawValue)
                            .font(.system(size: T.body))
                            .foregroundStyle(on ? .white : p.ink)
                            .padding(.horizontal, 20).padding(.vertical, 10)
                            .background(Capsule().fill(on ? p.accent : p.bgElevated))
                    }
                }
            }
            .padding(.vertical, 2)
        }
        .padding(.top, T.md)
    }

    private func matches(_ power: Power) -> Bool {
        let inCategory = category == .forYou || power.category == category
        let inSearch = wish.isEmpty || power.title.localizedCaseInsensitiveContains(wish)
        return inCategory && inSearch
    }

    private var featured: Power? {
        // Whatever is most worth turning on next: the first thing that isn't ready yet.
        powers.first { !$0.ready } ?? powers.first
    }

    private func grid(_ label: String, _ list: [Power]) -> some View {
        Group {
            if !list.isEmpty {
                VStack(alignment: .leading, spacing: 12) {
                    SectionHeader(label)
                    LazyVGrid(columns: [GridItem(.flexible(), spacing: 12),
                                        GridItem(.flexible(), spacing: 12)], spacing: 12) {
                        ForEach(list) { power in
                            PowerCard(power: power, palette: p)
                        }
                    }
                }
            }
        }
    }

    /// Stated outright, as on the rest of the app. Someone comparing the two phones deserves to
    /// know before they buy, not after.
    private var notPossible: some View {
        VStack(alignment: .leading, spacing: T.sm) {
            SectionHeader("NOT POSSIBLE ON IPHONE")
            Text("Apple does not let any app replace your home screen, read other apps' "
                 + "notifications, or operate your phone for you. The Android build does all three. "
                 + "No power here unlocks them, and none pretends to.")
                .font(.system(size: T.caption)).foregroundStyle(p.inkFaint)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    // MARK: - Catalogue

    private var powers: [Power] {
        [
            Power(id: "recall", title: "answer from everything you've ever been told",
                  rating: 4.9, category: .know, glyph: "R", tint: .hex(0xE8642C),
                  needs: brainCount > 0 ? "" : "import something first"),

            Power(id: "voice", title: "answer as you, not as a chatbot",
                  rating: 4.8, category: .speak, glyph: "V", tint: .hex(0x8A6DBE),
                  needs: router.isConfigured ? "" : "an AI key — several are free"),

            Power(id: "look", title: "read anything you point at",
                  rating: 4.8, category: .see, glyph: "L", tint: .hex(0xC85A7C),
                  needs: permissions.state(.camera) == .granted ? "" : "the camera"),

            Power(id: "talk", title: "talk to it instead of typing",
                  rating: 4.6, category: .speak, glyph: "T", tint: .hex(0xE39A3C),
                  needs: permissions.state(.microphone) == .granted
                      && permissions.state(.speech) == .granted ? "" : "the microphone"),

            Power(id: "agenda", title: "know what's on before you ask",
                  rating: 4.7, category: .know, glyph: "N", tint: .hex(0x4285F4),
                  needs: permissions.state(.calendar) == .granted ? "" : "your calendar"),

            Power(id: "meet", title: "put real Meet links in invites people actually get",
                  rating: 4.9, category: .create, glyph: "G", tint: .hex(0x1D8F63),
                  needs: googleConnected ? "" : "Google connected"),

            Power(id: "reply", title: "draft a reply in your voice, anywhere",
                  rating: 4.7, category: .speak, glyph: "D", tint: .hex(0xB0468C),
                  needs: router.isConfigured ? "" : "an AI key"),

            Power(id: "paper", title: "research a topic properly while you do something else",
                  rating: 4.7, category: .create, glyph: "P", tint: .hex(0x6E5AA8),
                  needs: router.isConfigured ? "" : "an AI key"),

            Power(id: "backup", title: "keep your whole brain in your own Drive",
                  rating: 4.5, category: .know, glyph: "B", tint: .hex(0x5DCAA5),
                  needs: googleConnected ? "" : "Google connected"),

            Power(id: "claw", title: "read your WhatsApp and Telegram",
                  rating: 4.4, category: .know, glyph: "W", tint: .hex(0x1FA855),
                  needs: clawConnected ? "" : "an OpenClaw gateway you run")
        ]
    }

    private func refresh() {
        permissions.refresh()
        brainCount = SlyStore.shared.count()
        googleConnected = GoogleAuth.shared.isConnected
        clawConnected = OpenClaw.shared.isConfigured
    }
}

/// The big card at the top — a coloured slab, as on Android.
private struct FeaturedCard: View {
    let power: PowersPanel.Power
    let palette: Palette

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("FEATURED")
                .font(.system(size: 11, weight: .bold)).tracking(2)
                .foregroundStyle(.white.opacity(0.75))

            Text(power.title)
                .font(.system(size: 26, weight: .semibold))
                .foregroundStyle(.white)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 14)

            HStack {
                Text("★ \(power.rating, specifier: "%.1f") · \(power.needs.isEmpty ? "works now" : "needs \(power.needs)")")
                    .font(.system(size: T.small)).foregroundStyle(.white.opacity(0.85))
                Spacer()
                Text(power.ready ? "ON" : "GET")
                    .font(.system(size: T.small, weight: .bold))
                    .foregroundStyle(power.tint)
                    .padding(.horizontal, 22).padding(.vertical, 11)
                    .background(Capsule().fill(.white))
            }
            .padding(.top, 18)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(22)
        .background(
            RoundedRectangle(cornerRadius: 22)
                .fill(LinearGradient(colors: [power.tint, power.tint.mix(with: .black, by: 0.28)],
                                     startPoint: .topLeading, endPoint: .bottomTrailing))
        )
    }
}

/// One power in the grid: a coloured glyph tile, the claim, then the rating and what it needs.
private struct PowerCard: View {
    let power: PowersPanel.Power
    let palette: Palette

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            RoundedRectangle(cornerRadius: 16)
                .fill(power.tint)
                .frame(width: 62, height: 62)
                .overlay(
                    Text(power.glyph)
                        .font(.custom(T.scriptFamily, size: 30))
                        .foregroundStyle(.white)
                )

            Text(power.title)
                .font(.system(size: T.body, weight: .medium))
                .foregroundStyle(palette.ink)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 14)

            Spacer(minLength: 12)

            Text("★ \(power.rating, specifier: "%.1f")  ·  \(power.needs.isEmpty ? "works on your phone" : "needs \(power.needs)")")
                .font(.system(size: T.caption))
                .foregroundStyle(power.ready ? palette.accent : palette.inkFaint)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, minHeight: 210, alignment: .topLeading)
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 18).fill(palette.bgElevated))
    }
}
