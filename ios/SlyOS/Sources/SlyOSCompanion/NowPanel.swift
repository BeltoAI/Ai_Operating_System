import SwiftUI
import EventKit
import Observation
import UIKit

/// What needs you — the iOS answer to Android's notification queue.
///
/// Android fills this from notifications it reads as they arrive. iOS cannot do that at any
/// entitlement level, so Now is built from what iOS *does* expose: what's on your calendar today,
/// and what you've asked to be reminded of. That is a smaller promise, and it is one the app can
/// actually keep.
@Observable
final class NowFeed {

    struct Item: Identifiable {
        let id: String
        let title: String
        let detail: String
        let when: Date?
        let kind: Kind
        var done = false

        enum Kind { case event, reminder }
    }

    private(set) var items: [Item] = []
    private(set) var loading = false
    private(set) var blocked: String?

    private let store = EKEventStore()

    /// Everything between now and the end of tomorrow, plus anything overdue.
    @MainActor
    func load() async {
        loading = true
        blocked = nil
        var found: [Item] = []

        let calendarOK = (try? await store.requestFullAccessToEvents()) ?? false
        let remindersOK = (try? await store.requestFullAccessToReminders()) ?? false

        if !calendarOK && !remindersOK {
            blocked = "Now needs calendar or reminder access to show you anything."
            loading = false
            return
        }

        if calendarOK {
            let start = Date()
            let end = Calendar.current.date(byAdding: .day, value: 2, to: start) ?? start
            let predicate = store.predicateForEvents(withStart: start, end: end, calendars: nil)
            found += store.events(matching: predicate).compactMap { e in
                guard let title = e.title, !title.isEmpty else { return nil }
                var bits: [String] = []
                if let start = e.startDate {
                    bits.append(start.formatted(date: .omitted, time: .shortened))
                    if !Calendar.current.isDateInToday(start) { bits.append("tomorrow") }
                }
                if let location = e.location, !location.isEmpty { bits.append(location) }
                let people = (e.attendees ?? []).compactMap(\.name).filter { !$0.isEmpty }
                if !people.isEmpty { bits.append("with \(people.joined(separator: ", "))") }
                return Item(id: e.eventIdentifier ?? UUID().uuidString,
                            title: title, detail: bits.joined(separator: " · "),
                            when: e.startDate, kind: .event)
            }
        }

        if remindersOK {
            let predicate = store.predicateForIncompleteReminders(
                withDueDateStarting: nil,
                ending: Calendar.current.date(byAdding: .day, value: 2, to: Date()),
                calendars: nil)
            let reminders: [EKReminder] = await withCheckedContinuation { cont in
                store.fetchReminders(matching: predicate) { cont.resume(returning: $0 ?? []) }
            }
            found += reminders.map { r in
                let due = r.dueDateComponents.flatMap(Calendar.current.date(from:))
                let overdue = due.map { $0 < Date() } ?? false
                return Item(id: r.calendarItemIdentifier,
                            title: r.title ?? "Reminder",
                            detail: overdue ? "overdue"
                                            : (due?.formatted(date: .omitted, time: .shortened) ?? "no time set"),
                            when: due, kind: .reminder)
            }
        }

        // Soonest first; anything without a time sinks to the bottom rather than jumping the queue.
        items = found
            .filter { !dismissed.contains($0.id) }
            .sorted { ($0.when ?? .distantFuture) < ($1.when ?? .distantFuture) }
        loading = false
    }

    // MARK: - Digest

    private(set) var digest = ""
    private(set) var digesting = false

    /// Summarise what is on, in the owner's own terms.
    ///
    /// Written from the same brain as everything else, so it can say "Ronan's LP call" rather than
    /// "Event at 17:00".
    @MainActor
    func catchUp() async {
        guard !digesting else { return }
        digesting = true
        defer { digesting = false }

        guard !items.isEmpty else { digest = "You're all caught up."; return }
        let lines = items.prefix(20).map { "\($0.title) — \($0.detail)" }.joined(separator: "\n")
        do {
            digest = try await AgentClient.complete(
                system: "You are SlyOS summarising the owner's next two days in three sentences or "
                      + "fewer. Plain, specific, no preamble and no bullet list. Say what actually "
                      + "needs them, and name people.",
                user: "WHAT'S ON:\n\(lines)",
                tier: .cheap)
        } catch {
            digest = ""
        }
    }

    // MARK: - Actions

    /// Dismissals are remembered, not just hidden.
    ///
    /// The underlying event or reminder still exists — SlyOS has no business deleting someone's
    /// calendar because they swiped a card away. What it records is that they have dealt with it, so
    /// it stops coming back on every reload.
    @ObservationIgnored
    private var dismissed: Set<String> {
        get { Set(SharedContainer.defaults.stringArray(forKey: "now.dismissed") ?? []) }
        set { SharedContainer.defaults.set(Array(newValue), forKey: "now.dismissed") }
    }

    @MainActor
    func dismiss(_ item: Item) {
        dismissed.insert(item.id)
        withAnimation(.easeOut(duration: 0.2)) { items.removeAll { $0.id == item.id } }
    }

    @MainActor
    func clearAll() {
        dismissed.formUnion(items.map(\.id))
        withAnimation(.easeOut(duration: 0.2)) { items.removeAll() }
    }

    /// Open the thing itself, in the app that owns it.
    @MainActor
    func open(_ item: Item) {
        let url = item.kind == .event
            ? URL(string: "calshow://")           // Calendar at today
            : URL(string: "x-apple-reminderkit://")
        if let url, UIApplication.shared.canOpenURL(url) { UIApplication.shared.open(url) }
    }
}

struct NowPanel: View {
    @Environment(\.palette) private var p
    @State private var feed = NowFeed()
    @State private var showOutbox = false
    @State private var showReconnect = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header

            if feed.loading {
                waiting("reading your day")
            } else if let blocked = feed.blocked {
                blockedState(blocked)
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        digestCard.padding(.top, T.lg)
                        if feed.items.isEmpty { emptyState } else { list }
                    }
                    .padding(.bottom, T.md)
                }
                .scrollIndicators(.hidden)
            }

            Spacer(minLength: 0)
        }
        .padding(.horizontal, T.md)
        .task { await feed.load() }
        .sheet(isPresented: $showOutbox) { OutboxView() }
        .sheet(isPresented: $showReconnect) { ReconnectView() }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: T.xs) {
            Heading("Now")
            HStack(spacing: 14) {
                Text(Date.now.formatted(.dateTime.weekday(.wide).month(.abbreviated).day()))
                    .font(.system(size: T.caption)).foregroundStyle(p.inkFaint)
                Spacer()
                Button("Sent for you") { showOutbox = true }
                    .font(.system(size: T.caption)).foregroundStyle(p.inkSoft)
                Button("Reconnect") { showReconnect = true }
                    .font(.system(size: T.caption)).foregroundStyle(p.inkSoft)
            }
        }
        .padding(.top, T.md)
    }

    /// "WHAT YOU MISSED" — a written summary of the day so far, on demand.
    ///
    /// 18pt corners and 18pt padding, wider than the list cards, exactly as Compose sets it: this is
    /// a panel you read, not an item you act on.
    private var digestCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(title: "WHAT YOU MISSED") {
                if feed.digesting { SlyOrbit(size: 12) }
                else {
                    Text("↻").font(.system(size: T.small)).foregroundStyle(p.accent)
                        .padding(4)
                        .onTapGesture { Task { await feed.catchUp() } }
                }
            }
            if feed.digesting && feed.digest.isEmpty {
                SlyWaiting("reading your day")
            } else if feed.digest.isEmpty {
                Text(feed.items.isEmpty ? "You're all caught up." : "Tap ↻ for a summary.")
                    .font(.system(size: T.small)).foregroundStyle(p.inkSoft)
            } else {
                Text(feed.digest)
                    .font(.system(size: T.small)).foregroundStyle(p.ink)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(18)
        .background(RoundedRectangle(cornerRadius: 18).fill(p.bgElevated))
    }

    private var list: some View {
        VStack(alignment: .leading, spacing: 0) {
            SectionHeader(title: "WAITING · \(feed.items.count)") {
                Button("Clear all") { feed.clearAll() }
                    .font(.system(size: T.caption))
                    .foregroundStyle(p.danger)
            }
            .padding(.top, T.lg).padding(.bottom, 10)

            LazyVStack(alignment: .leading, spacing: 10) {
                ForEach(feed.items) { item in
                    SlyCard(onOpen: { feed.open(item) },
                            onClose: { feed.dismiss(item) }) {
                        itemBody(item)
                    }
                }
            }
        }
    }

    /// One card. Avatar, who, where it came from, then the detail — the same anatomy as Android's
    /// notification card, which is what makes the two feeds read as the same screen.
    private func itemBody(_ item: NowFeed.Item) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .top, spacing: 12) {
                SlyAvatar(name: item.title,
                          tint: item.kind == .event ? .hex(0x4285F4) : p.accent,
                          badge: item.kind == .event ? "calendar" : "checkmark")
                VStack(alignment: .leading, spacing: 3) {
                    Text(item.title)
                        .font(.system(size: T.body)).foregroundStyle(p.ink)
                        .lineLimit(1)
                    Text(item.kind == .event ? "in your calendar" : "a reminder")
                        .font(.system(size: T.caption))
                        .foregroundStyle(item.kind == .event ? .hex(0x4285F4) : p.accent)
                    Text(item.detail)
                        .font(.system(size: T.small))
                        .foregroundStyle(item.detail == "overdue" ? p.danger : p.inkSoft)
                        .lineLimit(6)
                }
                Spacer(minLength: 0)
            }
            .padding(14)

            HStack {
                Spacer()
                Text("Open ↗")
                    .font(.system(size: T.small)).foregroundStyle(p.inkSoft)
                    .padding(4)
                    .onTapGesture { feed.open(item) }
            }
            .padding(.horizontal, 14).padding(.bottom, 12)
        }
    }

    private var emptyState: some View {
        VStack(alignment: .leading, spacing: T.sm) {
            SectionHeader("WAITING · 0").padding(.top, T.lg)
            Text("All caught up.")
                .font(.system(size: 28)).foregroundStyle(p.ink)
            Text("Nothing on your calendar or reminders for the next two days.")
                .font(.system(size: T.body)).foregroundStyle(p.inkFaint)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func blockedState(_ message: String) -> some View {
        VStack(alignment: .leading, spacing: T.sm) {
            Text("Nothing to show yet.")
                .font(.system(size: 28)).foregroundStyle(p.ink)
                .padding(.top, T.lg)
            Text(message)
                .font(.system(size: T.body)).foregroundStyle(p.inkFaint)
                .fixedSize(horizontal: false, vertical: true)
            Button("Open Settings") { Permissions.shared.openSettings() }
                .font(.system(size: T.body))
                .foregroundStyle(p.ink)
                .padding(.horizontal, T.lg).padding(.vertical, 10)
                .background(Capsule().fill(p.accent))
                .padding(.top, T.sm)
        }
    }

    private func waiting(_ s: String) -> some View {
        VStack { Spacer(); SlyWaiting(s, orbit: 34); Spacer() }
            .frame(maxWidth: .infinity)
    }
}
