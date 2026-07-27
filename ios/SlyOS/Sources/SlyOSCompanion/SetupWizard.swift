import SwiftUI

/// First-run setup.
///
/// Three steps, in the order that makes the app work: who you are, a brain to think with, and
/// something in memory to think about. Every step is skippable — a wizard that traps someone on
/// step two because they don't have an API key yet is worse than no wizard.
struct SetupWizard: View {
    @Environment(SlySettings.self) private var settings
    @Environment(\.dismiss) private var dismiss

    @State private var step = 0
    private let lastStep = 2

    var body: some View {
        @Bindable var settings = settings
        let p = Palette(dark: settings.dark)

        VStack(alignment: .leading, spacing: 0) {
            progress(p)

            ScrollView {
                VStack(alignment: .leading, spacing: T.md) {
                    switch step {
                    case 0: whoYouAre(p, settings: settings)
                    case 1: BrainStep(palette: p)
                    default: MemoryStep(palette: p)
                    }
                }
                .padding(.top, T.lg)
            }
            .scrollIndicators(.hidden)

            footer(p)
        }
        .padding(.horizontal, T.md)
        .background(p.bg.ignoresSafeArea())
        .environment(\.palette, p)
        .preferredColorScheme(settings.dark ? .dark : .light)
        .interactiveDismissDisabled()
    }

    // MARK: - Chrome

    private func progress(_ p: Palette) -> some View {
        HStack(spacing: 6) {
            ForEach(0...lastStep, id: \.self) { i in
                Capsule()
                    .fill(i <= step ? p.accent : p.hairline)
                    .frame(height: 3)
            }
        }
        .padding(.top, T.lg)
    }

    private func footer(_ p: Palette) -> some View {
        HStack {
            Button(step == lastStep ? "Finish" : "Skip") {
                advance()
            }
            .font(.system(size: T.body))
            .foregroundStyle(p.inkFaint)

            Spacer()

            Button(step == lastStep ? "Start using SlyOS" : "Next") {
                advance()
            }
            .font(.system(size: T.body))
            .foregroundStyle(p.ink)
            .padding(.horizontal, T.lg).padding(.vertical, 12)
            .background(Capsule().fill(p.accent))
        }
        .padding(.vertical, T.md)
    }

    private func advance() {
        if step < lastStep {
            withAnimation(.easeOut(duration: 0.2)) { step += 1 }
        } else {
            settings.hasOnboarded = true
            dismiss()
        }
    }

    // MARK: - Step 1

    private func whoYouAre(_ p: Palette, settings: SlySettings) -> some View {
        @Bindable var settings = settings
        return VStack(alignment: .leading, spacing: T.md) {
            Text("Who are you?")
                .font(.system(size: 32)).foregroundStyle(p.ink)
            Text("SlyOS answers as you, so it needs to know who that is. All of this is optional and "
                 + "stays on your phone.")
                .font(.system(size: T.body)).foregroundStyle(p.inkFaint)
                .fixedSize(horizontal: false, vertical: true)

            VStack(alignment: .leading, spacing: T.xs) {
                Text("What should it call you?")
                    .font(.system(size: T.caption)).foregroundStyle(p.inkFaint)
                TextField("", text: $settings.name, prompt:
                    Text("your name").foregroundStyle(p.inkFaint))
                    .font(.system(size: T.prompt - 4))
                    .foregroundStyle(p.ink)
                    .textFieldStyle(.plain)
                Rectangle().fill(p.hairline).frame(height: 1)
            }
            .padding(.top, T.sm)

            VStack(alignment: .leading, spacing: T.xs) {
                Text("Anything that makes its answers sound like you")
                    .font(.system(size: T.caption)).foregroundStyle(p.inkFaint)
                WizardCharacterField(palette: p)
            }
            .padding(.top, T.sm)
        }
    }
}

/// Step 2 — a brain to think with.
private struct BrainStep: View {
    let palette: Palette
    private var router: ModelRouter { ModelRouter.shared }

    var body: some View {
        VStack(alignment: .leading, spacing: T.md) {
            Text("Give it a brain")
                .font(.system(size: 32)).foregroundStyle(palette.ink)
            Text("SlyOS uses your own AI account, so your data never passes through anyone else's "
                 + "server. Several of these are free — pick one, tap Get a key, and paste it back.")
                .font(.system(size: T.body)).foregroundStyle(palette.inkFaint)
                .fixedSize(horizontal: false, vertical: true)

            // Free providers first: the fastest route to a working app costs nothing.
            ForEach(ModelRouter.Provider.allCases.sorted { $0.isFree && !$1.isFree }) { provider in
                KeyCard(provider: provider, palette: palette)
            }
        }
    }
}

/// Step 3 — something to remember.
private struct MemoryStep: View {
    let palette: Palette
    @State private var importers = Importers.shared

    var body: some View {
        VStack(alignment: .leading, spacing: T.md) {
            Text("Fill the brain")
                .font(.system(size: 32)).foregroundStyle(palette.ink)
            Text("Everything imported stays on this phone and becomes searchable — and every answer "
                 + "SlyOS gives starts using it.")
                .font(.system(size: T.body)).foregroundStyle(palette.inkFaint)
                .fixedSize(horizontal: false, vertical: true)

            ForEach(Importers.Source.allCases) { source in
                ImportRow(source: source, palette: palette)
            }

            Text("Mail needs Google connected — you can do that in Settings any time.")
                .font(.system(size: T.caption)).foregroundStyle(palette.inkFaint)
                .padding(.top, T.sm)
        }
    }
}

/// A provider, with the one button that matters made obvious.
struct KeyCard: View {
    let provider: ModelRouter.Provider
    let palette: Palette

    @State private var value = ""
    @State private var saved = false

    var body: some View {
        VStack(alignment: .leading, spacing: T.sm) {
            HStack(spacing: T.sm) {
                Text(provider.label)
                    .font(.system(size: T.body, weight: .medium)).foregroundStyle(palette.ink)
                if provider.isFree {
                    Text("FREE")
                        .font(.system(size: 10, weight: .bold)).tracking(1)
                        .foregroundStyle(palette.good)
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(Capsule().fill(palette.good.opacity(0.15)))
                }
                Spacer()
                if saved || ModelRouter.shared.hasKey(for: provider) {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 16)).foregroundStyle(palette.good)
                }
            }

            HStack(spacing: T.sm) {
                // The whole point of this screen: one tap to the page that issues the key.
                Link(destination: URL(string: provider.keyURL)!) {
                    HStack(spacing: 6) {
                        Image(systemName: "key.fill").font(.system(size: 12))
                        Text(provider.isFree ? "Get a free key" : "Get a key")
                            .font(.system(size: T.small, weight: .medium))
                    }
                    .foregroundStyle(palette.ink)
                    .padding(.horizontal, T.md).padding(.vertical, 9)
                    .background(Capsule().fill(palette.accent.opacity(0.9)))
                }

                SecureField("", text: $value, prompt:
                    Text("paste it here").foregroundStyle(palette.inkFaint))
                    .font(.system(size: T.small))
                    .foregroundStyle(palette.ink)
                    .textFieldStyle(.plain)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .onChange(of: value) { _, new in
                        // Save as it is pasted — nobody should have to find a Save button after
                        // already having done the hard part.
                        ModelRouter.shared.setKey(new, for: provider)
                        saved = !new.isEmpty
                    }
            }
            Rectangle().fill(palette.hairline).frame(height: 1)
        }
        .padding(.vertical, T.xs)
        .onAppear { value = ModelRouter.shared.key(for: provider) }
    }
}

/// One import source with its live state.
struct ImportRow: View {
    let source: Importers.Source
    let palette: Palette

    @State private var importers = Importers.shared

    var body: some View {
        let state = importers.status(for: source)

        HStack(spacing: T.md) {
            Image(systemName: source.symbol)
                .font(.system(size: 20)).foregroundStyle(palette.accent).frame(width: 26)

            VStack(alignment: .leading, spacing: 2) {
                Text(source.title).font(.system(size: T.body)).foregroundStyle(palette.ink)
                Text(subtitle(state))
                    .font(.system(size: T.caption))
                    .foregroundStyle(state.lastError == nil ? palette.inkFaint : palette.danger)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Spacer()

            Button {
                Task {
                    switch source {
                    case .contacts: await importers.importContacts()
                    case .calendar: await importers.importCalendar()
                    case .mail: await importers.importMail()
                    case .openclaw: await importers.importOpenClaw()
                    }
                }
            } label: {
                Text(state.running ? "…" : (state.everRun && state.lastError == nil ? "Again" : "Import"))
                    .font(.system(size: T.small, weight: .medium))
                    .foregroundStyle(palette.ink)
                    .frame(minWidth: 24)
                    .padding(.horizontal, T.md).padding(.vertical, 8)
                    .background(Capsule().fill(palette.accent.opacity(0.22)))
            }
            .disabled(state.running)
        }
        .padding(.vertical, T.sm)
    }

    private func subtitle(_ s: Importers.Status) -> String {
        if s.running { return "Reading…" }
        if let e = s.lastError { return e }
        if s.everRun { return "\(s.imported.formatted()) added" }
        return source.detail
    }
}

/// The character box on step one, kept separate so its state doesn't rebuild the wizard.
private struct WizardCharacterField: View {
    let palette: Palette
    @State private var text = ""

    var body: some View {
        TextEditor(text: $text)
            .font(.system(size: T.body))
            .foregroundStyle(palette.ink)
            .scrollContentBackground(.hidden)
            .frame(minHeight: 120)
            .padding(T.sm)
            .background(RoundedRectangle(cornerRadius: 14).fill(palette.bgElevated))
            .onChange(of: text) { _, new in SlyProfile.shared.character = new }
            .onAppear { text = SlyProfile.shared.character }
    }
}
