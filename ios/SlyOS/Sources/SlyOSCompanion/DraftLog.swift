import Foundation

/// Every reply SlyOS drafted, and what the owner did with it.
///
/// This is how it learns to sound like them. A draft that was typed unchanged is a good example of
/// their voice; one that was rewritten is a better one, because the edit shows the gap. Over time
/// these become the style examples fed back into drafting, which is the difference between an
/// assistant that writes competent English and one that writes like *you*.
///
/// Stored in the shared container so the keyboard, the share extension and the app all write to the
/// same history.
enum DraftLog {

    static let shared = Log()

    struct Entry: Codable, Identifiable {
        var id = UUID()
        var source: String        // what was being replied to
        var draft: String         // what SlyOS wrote
        var tone: String
        var sent: String?         // what the owner actually used, when we can tell
        var at: Date = .now

        var wasEdited: Bool {
            guard let sent else { return false }
            return sent.trimmingCharacters(in: .whitespacesAndNewlines)
                != draft.trimmingCharacters(in: .whitespacesAndNewlines)
        }
    }

    final class Log {
        private let key = "draftlog.entries"

        private(set) var entries: [Entry] {
            get {
                guard let data = SharedContainer.defaults.data(forKey: key),
                      let decoded = try? JSONDecoder().decode([Entry].self, from: data) else { return [] }
                return decoded
            }
            set {
                guard let data = try? JSONEncoder().encode(Array(newValue.prefix(300))) else { return }
                SharedContainer.defaults.set(data, forKey: key)
            }
        }

        func record(source: String, draft: String, tone: String) {
            entries.insert(Entry(source: String(source.prefix(600)),
                                 draft: draft, tone: tone), at: 0)
        }

        /// Note what was actually sent, so an edit can be compared against the draft.
        func markSent(_ text: String, forDraftID id: UUID) {
            var all = entries
            guard let i = all.firstIndex(where: { $0.id == id }) else { return }
            all[i].sent = text
            entries = all
        }

        /// A few real examples of how the owner writes, for the drafting prompt.
        ///
        /// Edited drafts first: the owner's own rewrite is a truer sample of their voice than
        /// anything a model produced and they merely tolerated.
        func styleExamples(limit: Int = 6) -> String {
            let edited = entries.filter(\.wasEdited).compactMap(\.sent)
            let accepted = entries.filter { $0.sent != nil && !$0.wasEdited }.compactMap(\.sent)
            let picked = (edited + accepted).prefix(limit)
            guard !picked.isEmpty else { return "" }
            return "\n\nHOW THE OWNER ACTUALLY WRITES — match this:\n"
                + picked.map { "· \($0.prefix(240))" }.joined(separator: "\n")
        }

        /// How well drafting is doing, for the app to show over time.
        var stats: (drafted: Int, used: Int, edited: Int) {
            let all = entries
            return (all.count,
                    all.filter { $0.sent != nil }.count,
                    all.filter(\.wasEdited).count)
        }
    }
}
