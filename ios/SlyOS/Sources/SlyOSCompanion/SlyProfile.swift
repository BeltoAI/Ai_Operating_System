import Foundation
import Observation

/// Who the owner is — the "characteristics" the whole app speaks from.
///
/// This is what makes an answer sound like you rather than like a chatbot, and on Android it was
/// the *only* thing the models reliably knew. Every field is optional and stored locally.
@Observable
final class SlyProfile {

    static let shared = SlyProfile()

    /// Same keys and labels as Android's `PERSONAL_FIELDS`, so a profile means the same thing on
    /// both phones and can eventually sync between them.
    static let personalFields: [(key: String, label: String)] = [
        ("age", "Age"),
        ("gender", "Gender"),
        ("pronouns", "Pronouns"),
        ("ethnicity", "Ethnicity"),
        ("nationality", "Nationality"),
        ("location", "Where I live"),
        ("languages", "Languages I speak"),
        ("occupation", "Occupation / role"),
        ("relationship", "Relationship status"),
        ("religion", "Religion (optional)"),
        ("interests", "Interests & hobbies"),
        ("health", "Health notes (optional)"),
        ("personal_extra", "Anything else about me")
    ]

    /// Real contact details — used verbatim in letterheads, signatures and forms, so they must be
    /// exact rather than paraphrased by a model.
    static let contactFields: [(key: String, label: String)] = [
        ("full_name", "Full name"),
        ("email", "Email"),
        ("phone", "Phone"),
        ("address", "Address"),
        ("booking_link", "Booking / scheduling link")
    ]

    private let defaults = UserDefaults.standard
    private(set) var revision = 0

    /// Free text: tone, work, people who matter — anything that makes answers feel like you.
    var character: String {
        get { access(keyPath: \.character); return defaults.string(forKey: "profile.character") ?? "" }
        set { withMutation(keyPath: \.character) { defaults.set(newValue, forKey: "profile.character") } }
    }

    func value(_ key: String) -> String {
        access(keyPath: \.revision)
        return defaults.string(forKey: "profile.\(key)") ?? ""
    }

    func setValue(_ v: String, for key: String) {
        withMutation(keyPath: \.revision) {
            defaults.set(v.trimmingCharacters(in: .whitespacesAndNewlines), forKey: "profile.\(key)")
            revision &+= 1
        }
    }

    /// Everything non-blank, assembled for the model.
    ///
    /// **Capped.** On Android this block grew to 27,490 characters against a 20,000-character
    /// window, so it consumed the entire context and left no room for the memories the question was
    /// actually about — the app appeared to know nothing about its owner while holding everything.
    func fullProfile(limit: Int = 4_000) -> String {
        var parts: [String] = []

        let contact = Self.contactFields
            .filter { $0.key != "booking_link" }
            .compactMap { f -> String? in
                let v = value(f.key)
                return v.isEmpty ? nil : "\(f.label): \(v)"
            }
        if !contact.isEmpty {
            parts.append("My contact details (use verbatim in signatures, letterheads and forms):\n"
                         + contact.joined(separator: "\n"))
        }

        let booking = value("booking_link")
        if !booking.isEmpty { parts.append("My booking / scheduling link: \(booking)") }

        let personal = Self.personalFields.compactMap { f -> String? in
            let v = value(f.key)
            return v.isEmpty ? nil : "\(f.label): \(v)"
        }
        if !personal.isEmpty { parts.append("About me (personal): " + personal.joined(separator: " · ")) }

        let c = character.trimmingCharacters(in: .whitespacesAndNewlines)
        if !c.isEmpty { parts.append(c) }

        let joined = parts.joined(separator: "\n")
        return joined.count > limit ? String(joined.prefix(limit)) + "…" : joined
    }

    var isEmpty: Bool { fullProfile().isEmpty }

    /// How complete the profile is, so Settings can show it's worth filling in.
    var completeness: Double {
        let all = Self.personalFields.map(\.key) + Self.contactFields.map(\.key)
        let filled = all.filter { !value($0).isEmpty }.count + (character.isEmpty ? 0 : 3)
        return min(1.0, Double(filled) / Double(all.count + 3))
    }
}
