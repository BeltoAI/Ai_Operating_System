import Foundation
import Contacts
import EventKit
import Observation

/// Fills the brain from what iOS actually lets an app read.
///
/// Contacts and Calendar are the two sources that need no server, no OAuth and no key — the user
/// grants access once and the brain stops being empty. That matters more on iOS than on Android,
/// where the brain fills itself from notifications; here these are most of what there is.
@Observable
final class Importers {

    static let shared = Importers()

    enum Source: String, CaseIterable, Identifiable {
        case contacts, calendar, mail, openclaw
        var id: String { rawValue }

        var title: String {
            switch self {
            case .contacts: "Contacts"
            case .calendar: "Calendar"
            case .mail: "Mail"
            case .openclaw: "OpenClaw"
            }
        }

        var detail: String {
            switch self {
            case .contacts: "Names, numbers and how you reach people"
            case .calendar: "Who you met, when, and about what"
            case .mail: "Everything you've written and received"
            case .openclaw: "WhatsApp, Telegram and Slack, via your own gateway"
            }
        }

        var symbol: String {
            switch self {
            case .contacts: "person.crop.circle.fill"
            case .calendar: "calendar"
            case .mail: "envelope.fill"
            case .openclaw: "antenna.radiowaves.left.and.right"
            }
        }

        /// Mail is the one source that needs an account rather than a device permission — iOS gives
        /// apps no access to the Mail app at all, so Gmail's API is the only route there is.
        var needsGoogle: Bool { self == .mail }
    }

    /// What happened last time each source ran, so the UI never has to guess.
    struct Status: Equatable {
        var running = false
        var imported = 0
        var lastError: String?
        var everRun = false
    }

    private(set) var status: [Source: Status] = [:]

    func status(for s: Source) -> Status { status[s] ?? Status() }

    var isRunning: Bool { status.values.contains { $0.running } }

    // MARK: - Contacts

    /// Import the address book.
    ///
    /// Stored as one memory per person rather than a blob, so "what's Marta's number?" can match a
    /// single row instead of dragging the whole address book into the model's context.
    @discardableResult
    func importContacts() async -> Int {
        await MainActor.run { status[.contacts, default: Status()].running = true }

        let store = CNContactStore()
        do {
            let granted = try await store.requestAccess(for: .contacts)
            guard granted else { return await finish(.contacts, error: "Contacts access was declined.") }
        } catch {
            return await finish(.contacts, error: error.localizedDescription)
        }

        // No `CNContactNoteKey`. Contact notes need the `com.apple.developer.contacts.notes`
        // entitlement, which Apple grants case by case — and asking for the key without it does not
        // merely omit notes, it fails the whole fetch with "Unauthorized Keys" and imports nothing.
        let keys: [CNKeyDescriptor] = [
            CNContactGivenNameKey, CNContactFamilyNameKey, CNContactOrganizationNameKey,
            CNContactJobTitleKey, CNContactEmailAddressesKey, CNContactPhoneNumbersKey
        ].map { $0 as CNKeyDescriptor }

        var batch: [Memory] = []
        let request = CNContactFetchRequest(keysToFetch: keys)

        do {
            try store.enumerateContacts(with: request) { contact, _ in
                let name = [contact.givenName, contact.familyName]
                    .filter { !$0.isEmpty }.joined(separator: " ")
                guard !name.isEmpty else { return }

                var bits: [String] = []
                if !contact.jobTitle.isEmpty { bits.append(contact.jobTitle) }
                if !contact.organizationName.isEmpty { bits.append("at \(contact.organizationName)") }
                let emails = contact.emailAddresses.map { $0.value as String }
                let phones = contact.phoneNumbers.map { $0.value.stringValue }
                if !emails.isEmpty { bits.append("email: \(emails.joined(separator: ", "))") }
                if !phones.isEmpty { bits.append("phone: \(phones.joined(separator: ", "))") }

                batch.append(Memory(
                    kind: "contact", person: name, title: name,
                    body: bits.isEmpty ? name : bits.joined(separator: " · "),
                    source: "Contacts", date: .now))
            }
        } catch {
            return await finish(.contacts, error: error.localizedDescription)
        }

        SlyStore.shared.insertMany(batch)
        return await finish(.contacts, count: batch.count)
    }

    // MARK: - Calendar

    /// Import events either side of today.
    ///
    /// Past events are the valuable half — they are the record of who you actually met — but future
    /// ones are what "what's on this week?" needs, so both are pulled.
    @discardableResult
    func importCalendar(pastDays: Int = 365, futureDays: Int = 120) async -> Int {
        await MainActor.run { status[.calendar, default: Status()].running = true }

        let store = EKEventStore()
        do {
            let granted = try await store.requestFullAccessToEvents()
            guard granted else { return await finish(.calendar, error: "Calendar access was declined.") }
        } catch {
            return await finish(.calendar, error: error.localizedDescription)
        }

        let from = Date().addingTimeInterval(-Double(pastDays) * 86_400)
        let to = Date().addingTimeInterval(Double(futureDays) * 86_400)

        // EventKit refuses predicates spanning more than four years, and long ranges are slow, so
        // the window is walked in chunks rather than requested in one go.
        var events: [EKEvent] = []
        var cursor = from
        while cursor < to {
            let chunkEnd = min(to, cursor.addingTimeInterval(120 * 86_400))
            let predicate = store.predicateForEvents(withStart: cursor, end: chunkEnd, calendars: nil)
            events.append(contentsOf: store.events(matching: predicate))
            cursor = chunkEnd
        }

        let batch: [Memory] = events.compactMap { e in
            guard let title = e.title, !title.isEmpty else { return nil }
            var bits: [String] = [title]
            if let location = e.location, !location.isEmpty { bits.append("at \(location)") }

            let others = (e.attendees ?? [])
                .compactMap { $0.name }
                .filter { !$0.isEmpty }
            if !others.isEmpty { bits.append("with \(others.joined(separator: ", "))") }
            if let notes = e.notes, !notes.isEmpty { bits.append(notes) }

            return Memory(
                kind: "event",
                person: others.first ?? "",
                title: title,
                body: bits.joined(separator: " · "),
                source: "Calendar",
                date: e.startDate ?? .now)
        }

        SlyStore.shared.insertMany(batch)
        return await finish(.calendar, count: batch.count)
    }

    // MARK: - Mail

    /// Import recent Gmail. Requires Google to be connected — there is no device-level alternative.
    @discardableResult
    func importMail(max: Int = 400) async -> Int {
        await MainActor.run { status[.mail, default: Status()].running = true }

        guard GoogleAuth.shared.isConnected else {
            return await finish(.mail, error: "Connect Google first — iOS gives apps no access to the Mail app.")
        }
        do {
            let count = try await GmailImport.run(maxMessages: max)
            return await finish(.mail, count: count)
        } catch {
            return await finish(.mail, error: error.localizedDescription)
        }
    }

    // MARK: - OpenClaw

    /// The only route to WhatsApp and Telegram on iPhone: a gateway the owner runs themselves.
    @discardableResult
    func importOpenClaw() async -> Int {
        await MainActor.run { status[.openclaw, default: Status()].running = true }
        guard OpenClaw.shared.isConfigured else {
            return await finish(.openclaw, error: "Set your OpenClaw address and token in Settings.")
        }
        do {
            let n = try await OpenClaw.shared.importMessages()
            return await finish(.openclaw, count: n)
        } catch {
            return await finish(.openclaw, error: error.localizedDescription)
        }
    }

    // MARK: - Bookkeeping

    @MainActor
    private func finish(_ s: Source, count: Int = 0, error: String? = nil) -> Int {
        status[s] = Status(running: false, imported: count, lastError: error, everRun: true)
        return count
    }
}
