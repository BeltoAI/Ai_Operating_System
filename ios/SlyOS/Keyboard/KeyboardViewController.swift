import UIKit
import SwiftUI

/// The SlyOS keyboard — reply drafting *inside* WhatsApp, LinkedIn, Mail, anywhere you type.
///
/// This is as close as iOS permits to what Android does from a notification. A keyboard extension
/// can read the field it is typing into and insert text; it cannot press send, and no entitlement
/// changes that. So the honest shape is one tap: SlyOS writes the reply in your voice, you send it.
///
/// Needs Full Access (`RequestsOpenAccess`), for two reasons the user deserves stated plainly: the
/// network, to reach the model, and the shared App Group, to read the brain. Without it the
/// keyboard can still type but knows nothing.
final class KeyboardViewController: UIInputViewController {

    private var host: UIHostingController<KeyboardView>?

    override func viewDidLoad() {
        super.viewDidLoad()

        let view = KeyboardView(
            hasFullAccess: hasFullAccess,
            // The text already in the field — usually the reply being composed, sometimes nothing.
            context: { [weak self] in
                let proxy = self?.textDocumentProxy
                return (proxy?.documentContextBeforeInput ?? "") + (proxy?.documentContextAfterInput ?? "")
            },
            insert: { [weak self] text in
                self?.textDocumentProxy.insertText(text)
            },
            deleteAll: { [weak self] in
                guard let proxy = self?.textDocumentProxy else { return }
                // Clear whatever is there before inserting a fresh draft.
                while (proxy.documentContextBeforeInput?.isEmpty == false) { proxy.deleteBackward() }
            },
            nextKeyboard: { [weak self] in
                self?.advanceToNextInputMode()
            },
            deleteBackward: { [weak self] in
                self?.textDocumentProxy.deleteBackward()
            }
        )

        let host = UIHostingController(rootView: view)
        addChild(host)
        host.view.translatesAutoresizingMaskIntoConstraints = false
        self.view.addSubview(host.view)
        NSLayoutConstraint.activate([
            host.view.leadingAnchor.constraint(equalTo: self.view.leadingAnchor),
            host.view.trailingAnchor.constraint(equalTo: self.view.trailingAnchor),
            host.view.topAnchor.constraint(equalTo: self.view.topAnchor),
            host.view.bottomAnchor.constraint(equalTo: self.view.bottomAnchor),
            // Tall enough to show a draft without becoming a whole screen.
            host.view.heightAnchor.constraint(equalToConstant: hasFullAccess ? 268 : 300)
        ])
        host.didMove(toParent: self)
        self.host = host
    }
}
