import SwiftUI
import PhotosUI
import UIKit

/// The SlyOS shell: a full-bleed panel with the persistent bottom bar underneath.
///
/// Deliberately not a `TabView`. UIKit's tab bar imposes its own translucent chrome, its own
/// label-under-icon layout and the system tint — none of which the Android app has. The Compose
/// shell is a column (content, hairline, nav row), so this is one too.
struct AppView: View {
    @Environment(AppState.self) private var appState
    @Environment(SlySettings.self) private var settings

    var body: some View {
        @Bindable var appState = appState
        let p = Palette(dark: settings.dark)

        VStack(spacing: 0) {
            Group {
                switch appState.selectedTab {
                case .home:     HomePanel()
                case .now:      NowPanel()
                case .brain:    BrainPanel()
                case .research: ResearchPanel()
                case .powers:   PowersPanel()
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            // No divider above the bar: on Android the nav sits straight on the background, and a
            // hairline here was the single biggest reason the iOS bottom bar read as a separate
            // chrome strip rather than part of the screen.
            SlyBottomNav(current: appState.selectedTab, nowCount: appState.nowCount) { panel in
                appState.selectedTab = panel
            }
            .padding(.top, T.sm)
            // Measured off the Samsung: its first icon centre sits ~12% in, not the ~6.5% that the
            // bare 6pt row padding gives on a wider iPhone.
            .padding(.horizontal, T.md)
        }
        .background(p.bg.ignoresSafeArea())
        // Every panel: dragging anywhere puts the keyboard away, and a tap outside a field does
        // too. Without this the Memory tab traps you — there is no Done key on a search field and
        // the nav bar sits underneath the keyboard.
        .scrollDismissesKeyboard(.interactively)
        .onTapGesture { dismissKeyboard() }
        .environment(\.palette, p)
        // Android drives dark mode from its own store rather than the OS, so the two devices agree.
        .preferredColorScheme(settings.dark ? .dark : .light)
        // SwiftUI ignores the UIStatusBarHidden Info.plist key — the scene's hosting controller
        // answers for the status bar, and only this modifier reaches it. Without it the iOS clock
        // sits above SlyOS's own status line and the time appears twice.
        .statusBarHidden(true)
        // First run: the wizard, not an empty Home screen that can't answer anything yet.
        .sheet(isPresented: .constant(!settings.hasOnboarded)) { SetupWizard() }
    }
}

/// Home — the greeting is the hero. Matches the Compose Home panel: a status line at the top, then
/// a block of greeting, accent rule, prompt field and the talk affordance sitting above centre.
struct HomePanel: View {
    @Environment(AppState.self) private var appState
    @Environment(SlySettings.self) private var settings
    @Environment(\.palette) private var p
    @State private var prompt = ""
    @State private var answer = ""
    @State private var thinking = false
    @State private var failure: String?
    @State private var copied = false
    @State private var answerHeight: CGFloat = 0
    @State private var reading = false
    @State private var lastQuery = ""
    @State private var voice = VoiceInput.shared
    @State private var showScanner = false
    @State private var showLook = false
    @State private var showPhotoPicker = false
    @State private var pickedPhoto: PhotosPickerItem?
    @FocusState private var promptFocused: Bool

    var body: some View {
        VStack(spacing: 0) {
            statusLine
            // 2:3 split. Measured off the Samsung: the greeting sits ~39% down the screen, so the
            // block needs more room above it than a single spacer against two gives.
            Spacer(); Spacer()
            greeting
            promptRow
            if thinking || !answer.isEmpty || failure != nil { answerBlock } else { talkAffordance }
            Spacer(); Spacer(); Spacer()
        }
        .padding(.horizontal, T.md)
        .onChange(of: voice.transcript) { _, heard in
            if voice.isListening { prompt = heard }
        }
        .fullScreenCover(isPresented: $showLook) { LookScreen() }
        .sheet(isPresented: $showScanner) {
            DocumentScanner { pages in
                Task { await readPages(pages) }
            }
            .ignoresSafeArea()
        }
        .fullScreenCover(isPresented: $reading) { ReaderView(text: answer) }
        .photosPicker(isPresented: $showPhotoPicker, selection: $pickedPhoto, matching: .images)
        .onChange(of: pickedPhoto) { _, item in
            guard let item else { return }
            Task {
                if let data = try? await item.loadTransferable(type: Data.self),
                   let image = UIImage(data: data) {
                    await readPages([image])
                }
                pickedPhoto = nil
            }
        }
    }

    /// Read scanned or picked images into the brain, then say what was found.
    private func readPages(_ pages: [UIImage]) async {
        guard !pages.isEmpty else { return }
        thinking = true; failure = nil; answer = ""
        var total = 0
        for page in pages {
            let read = await LookMode.capture(page)
            if !read.isEmpty { total += 1 }
        }
        thinking = false
        answer = total == 0
            ? "I couldn't read any text in that."
            : "Read \(total) \(total == 1 ? "page" : "pages") into memory. Ask me about it."
    }

    private var statusLine: some View {
        HStack {
            Text(appState.statusLeft)
            Spacer()
            Text(appState.statusRight)
        }
        .font(.system(size: T.body))
        .foregroundStyle(p.inkSoft)
        .padding(.top, T.sm)
    }

    private var greeting: some View {
        VStack(alignment: .leading, spacing: T.sm) {
            Text("what should happen, \(settings.greetingName)?")
                .font(.system(size: T.prompt))
                .foregroundStyle(p.ink)
                .fixedSize(horizontal: false, vertical: true)

            // The short accent rule under the greeting.
            Rectangle().fill(p.accent).frame(width: 96, height: 3)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var promptRow: some View {
        // Tight spacing and an explicit layout priority: with default priority SwiftUI shrinks the
        // text field before the fixed-width icons and the Send pill, truncating the placeholder to
        // "ask me an…". The field is the point of the screen, so it wins the space.
        HStack(spacing: T.sm) {
            VStack(spacing: T.xs) {
                TextField("", text: $prompt, prompt:
                    Text("ask me anything…").foregroundStyle(p.inkFaint))
                    .font(.system(size: T.prompt - 4))
                    .foregroundStyle(p.ink)
                    .textFieldStyle(.plain)
                    .focused($promptFocused)
                    .submitLabel(.send)
                    .onSubmit(send)
                Rectangle().fill(p.hairline).frame(height: 1)
            }
            .layoutPriority(1)

            Button { showPhotoPicker = true } label: {
                Image(systemName: "paperclip").font(.system(size: 22)).foregroundStyle(p.inkSoft)
            }
            .accessibilityLabel("Read a photo")

            Button { showLook = true } label: {
                Image(systemName: "camera.fill").font(.system(size: 22)).foregroundStyle(p.inkSoft)
            }
            .accessibilityLabel("Look with the camera")

            Button(action: send) {
                Text("Send")
                    .font(.system(size: T.body))
                    .foregroundStyle(canSend ? p.ink : p.inkFaint)
                    .fixedSize()                       // never let the greedy field crush it
                    .padding(.horizontal, T.md).padding(.vertical, 10)
                    .background(Capsule().fill(canSend ? p.accent.opacity(0.22)
                                                       : p.bgElevated.opacity(0.5)))
            }
            .disabled(!canSend)
            .fixedSize()
        }
        .padding(.top, T.sm)
    }

    private var talkAffordance: some View {
        VStack(spacing: T.sm) {
            // The dot pulses while listening, so there is never any doubt the mic is live.
            OrangeDot()
                .scaleEffect(voice.isListening ? 1.9 : 1.0)
                .animation(voice.isListening
                           ? .easeInOut(duration: 0.7).repeatForever(autoreverses: true)
                           : .default,
                           value: voice.isListening)
            Text(voice.error ?? (voice.isListening ? "listening — tap to stop" : "tap to talk"))
                .font(.system(size: T.body))
                .foregroundStyle(voice.error == nil ? p.inkSoft : p.danger)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, T.lg)
        .contentShape(Rectangle())
        .onTapGesture { toggleVoice() }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
        .accessibilityLabel(voice.isListening ? "Stop listening" : "Tap to talk")
    }

    /// Start or stop dictation. Stopping sends what was heard, so speaking is a complete path from
    /// question to answer without ever touching the keyboard.
    private func toggleVoice() {
        Task {
            if voice.isListening {
                voice.stop()
                let heard = voice.transcript.trimmingCharacters(in: .whitespacesAndNewlines)
                if !heard.isEmpty { prompt = heard; send() }
            } else {
                await voice.start()
            }
        }
    }

    /// The reply — a card, not loose text.
    ///
    /// This is what makes an answer look considered: `bgElevated` at 16pt with 16pt padding,
    /// swipeable like every other card (left dismisses it, right opens whatever it links to), a
    /// scroll cap so a long reply cannot push the prompt off screen, and a full-screen reader for
    /// anything too long to sit comfortably in it.
    private var answerBlock: some View {
        SlyCard(onOpen: openLink, onClose: { withAnimation { clearAnswer() } }) {
            VStack(alignment: .leading, spacing: 0) {
                if thinking {
                    HStack(spacing: 14) {
                        SlyOrbit(size: 30)
                        Text("thinking…")
                            .font(.system(size: T.body)).foregroundStyle(p.inkFaint)
                    }
                } else if let failure {
                    Text(failure)
                        .font(.system(size: T.body)).foregroundStyle(p.danger)
                        .fixedSize(horizontal: false, vertical: true)
                } else {
                    // A ScrollView always claims its maximum height, and so does a maxHeight frame:
                    // both make a two-line answer 420pt tall. Neither is applied unless the content
                    // genuinely overflows — see ScrollIfNeeded.
                    AnswerView(text: answer)
                        .background(GeometryReader { geo in
                            Color.clear.preference(key: ContentHeight.self, value: geo.size.height)
                        })
                        .modifier(ScrollIfNeeded(height: answerHeight, cap: 420))
                        .onPreferenceChange(ContentHeight.self) { answerHeight = $0 }

                    HStack(spacing: 10) {
                        // A long answer gets a proper reader rather than being trapped in the card.
                        if answer.count > 480 {
                            Button { reading = true } label: {
                                Text("Read ⤢")
                                    .font(.system(size: T.small)).foregroundStyle(p.bgElevated)
                                    .padding(.horizontal, 14).padding(.vertical, 6)
                                    .background(Capsule().fill(p.accent))
                            }
                        }
                        Button {
                            UIPasteboard.general.string = answer
                            copied = true
                        } label: {
                            Text(copied ? "Copied" : "Copy")
                                .font(.system(size: T.small)).foregroundStyle(p.accent)
                                .padding(.horizontal, 14).padding(.vertical, 6)
                                .background(Capsule().fill(p.hairline))
                        }
                        Spacer()
                    }
                    .padding(.top, 10)
                }
            }
            .padding(16)
        }
        .padding(.top, T.lg)
    }

    private func clearAnswer() {
        answer = ""; failure = nil; copied = false
    }

    /// Swiping the answer right opens what it points at — the first link in it, or a search for
    /// what was asked.
    private func openLink() {
        let link = answer.firstMatch(#"https?://[^\s)\]]+"#)?[0]
            .trimmingCharacters(in: CharacterSet(charactersIn: ".,)"))
        let url = link.flatMap(URL.init(string:))
            ?? URL(string: "https://www.google.com/search?q="
                   + (lastQuery.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""))
        if let url { UIApplication.shared.open(url) }
    }

    private var canSend: Bool {
        !prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private func send() {
        guard canSend else { return }
        let asked = prompt
        lastQuery = asked
        copied = false

        // Everything you tell SlyOS is kept. That is the whole premise, so it happens here rather
        // than only when a model round-trip succeeds.
        appState.remember(title: "", body: asked, source: "Home")
        prompt = ""
        promptFocused = false
        answer = ""; failure = nil; thinking = true

        Task {
            do {
                // "write me a proposal" should produce a document, not a description of one.
                if let kind = MakeSomething.Kind.detect(in: asked), GoogleAuth.shared.isConnected {
                    let made = try await MakeSomething.make(kind, from: asked)
                    answer = "Made your \(kind.noun): \(made.title)\n\n\(made.url)"
                    thinking = false
                    appState.remember(title: made.title, body: "\(kind.noun) — \(made.url)",
                                      source: "Google")
                    Outbox.shared.record(what: "Created a \(kind.noun)",
                                         detail: made.title, outcome: "sent")
                    return
                }

                // A request to send something goes through the action router, which either does it
                // or says outright that it didn't. Before this, "send a message to Joslyn" produced
                // the sentence "I'll send a message to Joslyn" and no message — the app lying.
                if let intent = ActionRouter.detect(asked) {
                    let draft = try await AgentClient.complete(
                        system: """
                            Write the message itself, as the owner, in their voice. Output only the \
                            message — no preamble, no "here's your message", no commentary. \
                            \(AgentClient.voiceBlock())
                            """,
                        user: "WHAT YOU KNOW:\n\(AgentClient.corpus(for: asked))\n\nWRITE: \(asked)",
                        tier: .standard)

                    let outcome = await ActionRouter.perform(intent, draft: draft)
                    answer = outcome.text
                    thinking = false
                    appState.remember(title: asked, body: draft, source: "Draft")
                    return
                }

                // Off the main actor: the network call is the whole reason the Android Memory tab
                // used to fail with "-1 couldn't search".
                let reply = try await AgentClient.ask(asked)
                answer = reply
                thinking = false
                // Keep the answer too, so the brain remembers what it told you.
                appState.remember(title: asked, body: reply, source: "SlyOS")
            } catch {
                failure = error.localizedDescription
                thinking = false
            }
        }
    }
}


/// A long answer, full screen and properly scrollable.
///
/// Compose has this for the same reason: a summary that can only be read four lines at a time
/// through a capped card is a summary nobody reads.
struct ReaderView: View {
    let text: String
    @Environment(\.dismiss) private var dismiss
    @Environment(SlySettings.self) private var settings

    var body: some View {
        let p = Palette(dark: settings.dark)
        NavigationStack {
            ScrollView {
                AnswerView(text: text, showHero: false).padding(T.md)
            }
            .background(p.bg.ignoresSafeArea())
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }.tint(p.accent)
                }
            }
        }
        .environment(\.palette, p)
        .preferredColorScheme(settings.dark ? .dark : .light)
    }
}


/// Measured height of an answer's content.
private struct ContentHeight: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = nextValue() }
}

/// Scroll only when the content is actually taller than the cap.
///
/// SwiftUI's ScrollView does not shrink-wrap: put a one-line answer in one with `maxHeight: 420`
/// and you get 420 points of mostly empty card. This keeps short answers hugging their content and
/// only introduces scrolling where it is needed.
private struct ScrollIfNeeded: ViewModifier {
    let height: CGFloat
    let cap: CGFloat

    func body(content: Content) -> some View {
        if height > cap {
            ScrollView { content }
                .frame(height: cap)
                .scrollIndicators(.hidden)
        } else {
            // No frame at all in this branch. `maxHeight` is not a ceiling a small view ignores —
            // given a flexible parent it takes the whole allowance, which is what left a two-line
            // answer sitting in a 420pt box.
            content
        }
    }
}


/// Put the keyboard away from anywhere.
///
/// SwiftUI has no first-class "resign everything", and a search field has no Done key — so on a
/// screen whose only other controls sit behind the keyboard, there is otherwise no way out but to
/// kill the app.
func dismissKeyboard() {
    UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder),
                                    to: nil, from: nil, for: nil)
}
