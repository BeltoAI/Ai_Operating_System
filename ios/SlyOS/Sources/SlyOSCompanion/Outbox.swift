import SwiftUI
import Contacts
import Observation

/// "Sent for you" — a record of everything SlyOS did on the owner's behalf.
///
/// This is the antidote to the worst failure the Android app ever had: it told its owner an invite
/// had been sent to someone who never received one. A log the owner can read, written at the moment
/// the action actually returns, is what makes "did you send it?" a question with a checkable answer
/// instead of something the model reconstructs from a conversation.
@Observable
final class Outbox {

    static let shared = Outbox()

    struct Entry: Identifiable, Codable {
        var id = UUID()
        var what: String          // "Calendar invite — Date Night"
        var detail: String        // who it went to, what happened
        var outcome: String       // "sent" | "failed" | "declined"
        var at: Date = .now

        var succeeded: Bool { outcome == "sent" }
    }

    private(set) var entries: [Entry] = []

    private init() { load() }

    /// Record an action. Called at the point the underlying call returns, never before.
    func record(what: String, detail: String, outcome: String) {
        entries.insert(Entry(what: what, detail: detail, outcome: outcome), at: 0)
        entries = Array(entries.prefix(200))
        save()
    }

    func clear() {
        entries.removeAll()
        save()
    }

    private func save() {
        guard let data = try? JSONEncoder().encode(entries) else { return }
        SharedContainer.defaults.set(data, forKey: "outbox.entries")
    }

    private func load() {
        guard let data = SharedContainer.defaults.data(forKey: "outbox.entries"),
              let decoded = try? JSONDecoder().decode([Entry].self, from: data) else { return }
        entries = decoded
    }
}

struct OutboxView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(SlySettings.self) private var settings
    @State private var outbox = Outbox.shared

    var body: some View {
        let p = Palette(dark: settings.dark)
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    if outbox.entries.isEmpty {
                        Text("Nothing yet. When SlyOS sends or replies for you, it shows up here.")
                            .font(.system(size: T.small)).foregroundStyle(p.inkFaint)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.top, T.md)
                    } else {
                        ForEach(outbox.entries) { entry in
                            VStack(alignment: .leading, spacing: 4) {
                                HStack {
                                    Text(entry.what)
                                        .font(.system(size: T.body)).foregroundStyle(p.ink)
                                    Spacer()
                                    Text(entry.outcome)
                                        .font(.system(size: T.caption))
                                        .foregroundStyle(entry.succeeded ? p.good : p.danger)
                                }
                                Text(entry.detail)
                                    .font(.system(size: T.small)).foregroundStyle(p.inkSoft)
                                    .fixedSize(horizontal: false, vertical: true)
                                Text(entry.at.formatted(date: .abbreviated, time: .shortened))
                                    .font(.system(size: T.caption)).foregroundStyle(p.inkFaint)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(14)
                            .background(RoundedRectangle(cornerRadius: 16).fill(p.bgElevated))
                        }
                    }
                }
                .padding(T.md)
            }
            .background(p.bg.ignoresSafeArea())
            .navigationTitle("Sent for you")
            .navigationBarTitleDisplayMode(.inline)
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

/// "Reconnect" — people who have gone quiet.
///
/// Android reads this from message history across every platform it watches. iOS has no such feed,
/// so this is built from what it does have: who is in Contacts, and when they last appear in mail
/// or on the calendar. Narrower, and honest about being narrower.
struct ReconnectView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(SlySettings.self) private var settings

    @State private var quiet: [Person] = []
    @State private var loading = true
    @State private var drafting: String?
    @State private var draft = ""

    struct Person: Identifiable {
        var id: String { name }
        let name: String
        let lastSeen: Date?
        let via: String

        var quietFor: String {
            guard let lastSeen else { return "no contact on record" }
            let days = Calendar.current.dateComponents([.day], from: lastSeen, to: .now).day ?? 0
            if days > 365 { return "over a year ago" }
            if days > 60 { return "\(days / 30) months ago" }
            return "\(days) days ago"
        }
    }

    var body: some View {
        let p = Palette(dark: settings.dark)
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    Text("People you haven't spoken with in a while, from your mail and calendar.")
                        .font(.system(size: T.small)).foregroundStyle(p.inkFaint)
                        .fixedSize(horizontal: false, vertical: true)

                    if loading {
                        SlyWaiting("looking through your brain").padding(.top, T.md)
                    } else if quiet.isEmpty {
                        Text("Nobody's gone quiet — or there isn't enough imported yet to tell.")
                            .font(.system(size: T.small)).foregroundStyle(p.inkFaint)
                            .padding(.top, T.md)
                    } else {
                        ForEach(quiet) { person in
                            personCard(person, p)
                        }
                    }
                }
                .padding(T.md)
            }
            .background(p.bg.ignoresSafeArea())
            .navigationTitle("Quiet contacts")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }.tint(p.accent)
                }
            }
        }
        .environment(\.palette, p)
        .preferredColorScheme(settings.dark ? .dark : .light)
        .task { await load() }
    }

    private func personCard(_ person: Person, _ p: Palette) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 12) {
                SlyAvatar(name: person.name, tint: p.accent)
                VStack(alignment: .leading, spacing: 2) {
                    Text(person.name).font(.system(size: T.body)).foregroundStyle(p.ink)
                    Text("last \(person.via) · \(person.quietFor)")
                        .font(.system(size: T.caption)).foregroundStyle(p.inkFaint)
                }
                Spacer()
            }

            if drafting == person.name {
                if draft.isEmpty {
                    SlyWaiting("writing in your voice")
                } else {
                    Text(draft)
                        .font(.system(size: T.small)).foregroundStyle(p.ink)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(12)
                        .background(RoundedRectangle(cornerRadius: 10).fill(p.bg))
                    Button {
                        UIPasteboard.general.string = draft
                    } label: {
                        Text("Copy")
                            .font(.system(size: T.small)).foregroundStyle(p.bgElevated)
                            .padding(.horizontal, T.md).padding(.vertical, 8)
                            .background(Capsule().fill(p.accent))
                    }
                }
            } else {
                Button { write(to: person) } label: {
                    Text("✦ Write something")
                        .font(.system(size: T.small)).foregroundStyle(p.accent)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 16).fill(p.bgElevated))
    }

    /// Who is in the address book but absent from recent memory.
    private func load() async {
        let found: [Person] = await Task.detached {
            let store = SlyStore.shared
            // Contacts are the roster; mail and calendar say when each was last around.
            let contacts = store.search("contact", limit: 300).filter { $0.kind == "contact" }
            let cutoff = Date().addingTimeInterval(-14 * 86_400)

            return contacts.compactMap { contact -> Person? in
                let name = contact.person
                guard !name.isEmpty else { return nil }
                let mentions = store.search(name, limit: 8)
                    .filter { $0.kind == "mail" || $0.kind == "event" }
                let last = mentions.map(\.date).max()
                guard let last else { return nil }          // never seen: not "gone quiet"
                guard last < cutoff else { return nil }     // recent: not quiet
                return Person(name: name, lastSeen: last,
                              via: mentions.first?.kind == "event" ? "met" : "emailed")
            }
            .sorted { ($0.lastSeen ?? .distantPast) < ($1.lastSeen ?? .distantPast) }
            .prefix(25)
            .map { $0 }
        }.value

        quiet = found
        loading = false
    }

    private func write(to person: Person) {
        drafting = person.name
        draft = ""
        Task {
            do {
                draft = try await AgentClient.ask(
                    "Write a short, warm message to \(person.name), who I haven't spoken to since "
                    + "\(person.quietFor). Use what you know about them. Output only the message.")
            } catch {
                draft = error.localizedDescription
            }
        }
    }
}
