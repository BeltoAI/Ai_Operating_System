import UIKit
import SwiftUI
import UniformTypeIdentifiers

/// Reply drafting — the closest iOS gets to what Android does automatically.
///
/// Android reads the incoming message from the notification and drafts a reply on its own. iOS
/// forbids that outright, so the honest equivalent is this: select the message anywhere — WhatsApp,
/// LinkedIn, Mail — hit Share, and SlyOS drafts a reply in your voice from your brain. One tap
/// instead of none, which is the real cost of the platform.
final class ShareViewController: UIViewController {

    override func viewDidLoad() {
        super.viewDidLoad()
        Task { await load() }
    }

    private func load() async {
        let text = await incomingText() ?? ""
        let view = ShareDraftView(
            incoming: text,
            onClose: { [weak self] in self?.finish() }
        )
        let host = UIHostingController(rootView: view)
        addChild(host)
        host.view.frame = self.view.bounds
        host.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        self.view.addSubview(host.view)
        host.didMove(toParent: self)
    }

    /// Pull whatever text was shared. Handles plain text and URLs — a shared link is still context
    /// worth replying about.
    private func incomingText() async -> String? {
        guard let items = extensionContext?.inputItems as? [NSExtensionItem] else { return nil }
        for item in items {
            for provider in item.attachments ?? [] {
                for type in [UTType.plainText, UTType.url] {
                    guard provider.hasItemConformingToTypeIdentifier(type.identifier) else { continue }
                    if let loaded = try? await provider.loadItem(
                        forTypeIdentifier: type.identifier), let any = loaded as? NSSecureCoding {
                        if let s = any as? String { return s }
                        if let u = any as? URL { return u.absoluteString }
                    }
                }
            }
        }
        // Some apps put the message in the item's own text rather than an attachment.
        return (items.first?.attributedContentText?.string).flatMap { $0.isEmpty ? nil : $0 }
    }

    private func finish() {
        extensionContext?.completeRequest(returningItems: nil)
    }
}

/// The drafting sheet.
struct ShareDraftView: View {
    let incoming: String
    let onClose: () -> Void

    @State private var draft = ""
    @State private var working = false
    @State private var failure: String?
    @State private var copied = false

    private var p: Palette { Palette(dark: SlySettings.shared.dark) }

    var body: some View {
        VStack(alignment: .leading, spacing: T.md) {
            HStack {
                Wordmark()
                Spacer()
                Button("Close", action: onClose)
                    .font(.system(size: T.body)).foregroundStyle(p.inkFaint)
            }

            if !incoming.isEmpty {
                Text("THEY SAID")
                    .font(.system(size: T.small, weight: .bold)).tracking(2)
                    .foregroundStyle(p.inkFaint)
                ScrollView {
                    Text(incoming)
                        .font(.system(size: T.small)).foregroundStyle(p.inkSoft)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .frame(maxHeight: 110)
            }

            Text("YOUR REPLY")
                .font(.system(size: T.small, weight: .bold)).tracking(2)
                .foregroundStyle(p.inkFaint)

            if working {
                Text("drafting…").font(.system(size: T.body)).foregroundStyle(p.inkFaint)
            } else if let failure {
                Text(failure).font(.system(size: T.body)).foregroundStyle(p.danger)
                    .fixedSize(horizontal: false, vertical: true)
            } else {
                TextEditor(text: $draft)
                    .font(.system(size: T.body))
                    .foregroundStyle(p.ink)
                    .scrollContentBackground(.hidden)
                    .padding(T.sm)
                    .background(RoundedRectangle(cornerRadius: 12).fill(p.bgElevated))
            }

            HStack(spacing: T.sm) {
                Button(action: redraft) {
                    Text(draft.isEmpty ? "Draft a reply" : "Try again")
                        .font(.system(size: T.body)).foregroundStyle(p.ink)
                        .padding(.horizontal, T.lg).padding(.vertical, 11)
                        .background(Capsule().fill(p.accent))
                }
                .disabled(working)

                Button {
                    UIPasteboard.general.string = draft
                    copied = true
                } label: {
                    Text(copied ? "Copied" : "Copy")
                        .font(.system(size: T.body))
                        .foregroundStyle(copied ? p.good : p.ink)
                        .padding(.horizontal, T.lg).padding(.vertical, 11)
                        .background(Capsule().fill(p.accent.opacity(0.22)))
                }
                .disabled(draft.isEmpty)

                Spacer()
            }
        }
        .padding(T.md)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(p.bg.ignoresSafeArea())
        .environment(\.palette, p)
        .task { if !incoming.isEmpty { redraft() } }
    }

    private func redraft() {
        working = true; failure = nil; copied = false
        Task {
            do {
                // The brain answers here exactly as it does in the app — same store, same profile,
                // because both live in the shared App Group container.
                let context = AgentClient.corpus(for: incoming)
                // The message being replied to was written by someone else, by definition. This is
                // the single most injectable surface in the app: whatever arrives here is text a
                // stranger chose, handed straight to a model.
                let system = """
                    You are drafting a reply *as the owner*, in their voice, to the message below. \
                    Output only the reply itself — no preamble, no quotes, no explanation, no \
                    subject line. Match how they actually write: same length, same register, same \
                    directness. If the message needs a decision only they can make, write the reply \
                    that asks for what you'd need rather than inventing an answer.
                    """ + Untrusted.clause
                var user = "MESSAGE TO REPLY TO:\n\(incoming)"
                if !context.isEmpty { user = "WHAT YOU KNOW:\n\(context)\n\n" + user }

                draft = try await AgentClient.complete(system: system, user: user, tier: .standard)
            } catch {
                failure = error.localizedDescription
            }
            working = false
        }
    }
}
