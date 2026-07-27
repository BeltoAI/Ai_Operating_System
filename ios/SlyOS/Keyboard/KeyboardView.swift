import SwiftUI

/// What the keyboard shows: the message being replied to, a draft, and the tones to redo it in.
struct KeyboardView: View {

    let hasFullAccess: Bool
    let context: () -> String
    let insert: (String) -> Void
    let deleteAll: () -> Void
    let nextKeyboard: () -> Void
    let deleteBackward: () -> Void

    @State private var draft = ""
    @State private var working = false
    @State private var failure: String?
    @State private var tone: Tone = .same
    @State private var source = ""

    enum Tone: String, CaseIterable, Identifiable {
        case same = "Your voice", warm = "Warmer", short = "Shorter", firm = "Firmer"
        var id: String { rawValue }

        var instruction: String {
            switch self {
            case .same: "Match how they normally write."
            case .warm: "Warmer and more personal than usual, without gushing."
            case .short: "As short as it can be while still answering."
            case .firm: "Direct and firm, but not rude."
            }
        }
    }

    private var p: Palette { Palette(dark: SlySettings.shared.dark) }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            header

            if !hasFullAccess {
                needsAccess
                BasicKeys(insert: insert, delete: deleteBackward)
            } else if let failure {
                Text(failure)
                    .font(.system(size: T.small)).foregroundStyle(p.danger)
                    .fixedSize(horizontal: false, vertical: true)
            } else if working {
                SlyWaiting("writing in your voice")
            } else if draft.isEmpty {
                Text(source.isEmpty
                     ? "Copy the message you're replying to, then tap Draft."
                     : "Ready to reply to: “\(source.prefix(90))…”")
                    .font(.system(size: T.small)).foregroundStyle(p.inkFaint)
                    .fixedSize(horizontal: false, vertical: true)
            } else {
                ScrollView {
                    Text(draft)
                        .font(.system(size: T.body)).foregroundStyle(p.ink)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .frame(maxHeight: 96)
            }

            Spacer(minLength: 0)
            controls
        }
        .padding(10)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(p.bg)
        .environment(\.palette, p)
        .onAppear { source = bestSource() }
    }

    private var header: some View {
        HStack(spacing: 8) {
            Wordmark()
                .scaleEffect(0.62, anchor: .leading)
                .frame(height: 22)
            Spacer()
            if hasFullAccess {
                ForEach(Tone.allCases) { t in
                    Button { tone = t; if !draft.isEmpty { write() } } label: {
                        Text(t.rawValue)
                            .font(.system(size: 11))
                            .foregroundStyle(tone == t ? p.bgElevated : p.inkSoft)
                            .padding(.horizontal, 9).padding(.vertical, 5)
                            .background(Capsule().fill(tone == t ? p.accent : p.hairline))
                    }
                }
            }
        }
    }

    private var needsAccess: some View {
        Text("Drafting needs Full Access — Settings → General → Keyboard → Keyboards → SlyOS. "
             + "It's used for two things only: the network, to reach your model, and the shared "
             + "container, to read your brain. You can keep typing without it.")
            .font(.system(size: T.caption)).foregroundStyle(p.inkFaint)
            .fixedSize(horizontal: false, vertical: true)
    }

    private var controls: some View {
        HStack(spacing: 8) {
            Button(action: nextKeyboard) {
                Image(systemName: "globe")
                    .font(.system(size: 16)).foregroundStyle(p.ink)
                    .frame(width: 40, height: 38)
                    .background(RoundedRectangle(cornerRadius: 9).fill(p.hairline))
            }

            Button { write() } label: {
                Text(draft.isEmpty ? "Draft a reply" : "Redo")
                    .font(.system(size: T.small, weight: .medium))
                    .foregroundStyle(p.bgElevated)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 11)
                    .background(Capsule().fill(p.accent))
            }
            .disabled(!hasFullAccess || working)

            Button {
                guard !draft.isEmpty else { return }
                // Replace whatever is in the field rather than appending to it.
                deleteAll()
                insert(draft)
                DraftLog.shared.record(source: source, draft: draft, tone: tone.rawValue)
            } label: {
                Text("Type it")
                    .font(.system(size: T.small, weight: .medium))
                    .foregroundStyle(draft.isEmpty ? p.inkFaint : p.accent)
                    .padding(.horizontal, 16).padding(.vertical, 11)
                    .background(Capsule().fill(p.hairline))
            }
            .disabled(draft.isEmpty)
        }
    }

    /// What we're replying to.
    ///
    /// The keyboard can only see its own text field, never the conversation above it — so the
    /// message being answered has to come from the clipboard, which is why the empty state tells
    /// people to copy it. Whatever is already typed is used as a hint when there is no clipboard.
    private func bestSource() -> String {
        let pasted = UIPasteboard.general.string?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if pasted.count > 12 { return pasted }
        return context().trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func write() {
        let incoming = bestSource()
        source = incoming
        guard !incoming.isEmpty else {
            failure = "Copy the message you want to reply to first."
            return
        }
        working = true; failure = nil

        Task {
            do {
                let brain = AgentClient.corpus(for: incoming)
                var system = """
                    You are drafting a reply *as the owner*, to the message below. Output only the \
                    reply itself — no preamble, no quotes, no explanation, no subject line. \
                    \(tone.instruction)
                    """
                system += Untrusted.clause
                var user = "MESSAGE TO REPLY TO:\n\(incoming)"
                if !brain.isEmpty { user = "WHAT YOU KNOW:\n\(brain)\n\n" + user }

                draft = try await AgentClient.complete(system: system, user: user, tier: .cheap)
            } catch {
                failure = error.localizedDescription
            }
            working = false
        }
    }
}


/// A plain keyboard, shown when Full Access is off.
///
/// App Review requires a keyboard extension to be a usable keyboard without Full Access — an
/// extension that only shows a "turn this on" notice is a rejection. It is also simply correct:
/// someone who declines the permission should still be able to type.
private struct BasicKeys: View {
    let insert: (String) -> Void
    let delete: () -> Void

    @State private var shifted = true
    @Environment(\.palette) private var p

    private let rows = ["qwertyuiop", "asdfghjkl", "zxcvbnm"]

    var body: some View {
        VStack(spacing: 6) {
            ForEach(rows, id: \.self) { row in
                HStack(spacing: 5) {
                    if row == "zxcvbnm" {
                        key(shifted ? "⇧" : "⇧", wide: true) { shifted.toggle() }
                    }
                    ForEach(Array(row), id: \.self) { c in
                        let ch = shifted ? String(c).uppercased() : String(c)
                        key(ch) { insert(ch); shifted = false }
                    }
                    if row == "zxcvbnm" {
                        key("⌫", wide: true) { delete() }
                    }
                }
            }
            HStack(spacing: 5) {
                key("space", wide: true) { insert(" ") }
                key(".") { insert(".") }
                key(",") { insert(",") }
                key("return", wide: true) { insert("\n") }
            }
        }
    }

    private func key(_ label: String, wide: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.system(size: label.count > 1 ? 12 : 17))
                .foregroundStyle(p.ink)
                .frame(maxWidth: .infinity)
                .frame(height: 38)
                .background(RoundedRectangle(cornerRadius: 6).fill(p.bgElevated))
        }
        .frame(maxWidth: wide ? .infinity : nil)
    }
}
