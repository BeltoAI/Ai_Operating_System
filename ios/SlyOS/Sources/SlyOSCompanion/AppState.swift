import Foundation
import Observation
import UIKit

@Observable
final class AppState {
    var selectedTab: Panel = .home
    /// Badge on the Now tab — unhandled notifications, as on Android.
    var nowCount: Int = 0
    /// Greeting name. Android reads this from the profile in its brain; iOS will too once the
    /// brain lands, and until then it is whatever onboarding captured.
    var userName: String = "there"

    /// The two ends of the Home status line, e.g. "Sat 8:17 PM" and "100%".
    var statusLeft: String {
        Date.now.formatted(.dateTime.weekday(.abbreviated).hour().minute())
    }

    /// Battery percentage, as Android shows it. `batteryMonitoringEnabled` has to be switched on
    /// or `batteryLevel` reports -1 forever; the simulator reports -1 regardless, hence the guard.
    var statusRight: String {
        let level = UIDevice.current.batteryLevel
        guard level >= 0 else { return "" }
        return "\(Int((level * 100).rounded()))%"
    }
    var memories: [MemoryEntry] = []
    var actionQueue: [PlannedAction] = []

    init() {
        // Without this, `batteryLevel` returns -1 forever.
        UIDevice.current.isBatteryMonitoringEnabled = true
    }

    func plan(prompt: String) {
        let lower = prompt.lowercased()
        var actions: [PlannedAction] = []

        if lower.contains("send") || lower.contains("message") || lower.contains("email") {
            actions.append(PlannedAction(type: "find_contact", title: "Find contact", risk: "read_only", requiresConfirmation: false))
            actions.append(PlannedAction(type: "message", title: "Draft outbound message", risk: "external_send", requiresConfirmation: true))
        }

        if lower.contains("receipt") || lower.contains("expense") || lower.contains("invoice") {
            actions.append(PlannedAction(type: "expense_record", title: "Extract expense", risk: "local_write", requiresConfirmation: false))
        }

        if lower.contains("remember") || lower.contains("memory") {
            actions.append(PlannedAction(type: "memory_search", title: "Search memory", risk: "read_only", requiresConfirmation: false))
        }

        if actions.isEmpty {
            actions.append(PlannedAction(type: "memory_search", title: "Gather memory context", risk: "read_only", requiresConfirmation: false))
        }

        actionQueue = actions
    }

    /// Commit something to the brain. Writes to the real store — an in-memory array looked like it
    /// worked right up until you relaunched the app and everything you had told it was gone.
    func remember(title: String, body: String, source: String = "typed") {
        let text = body.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        SlyStore.shared.insert(kind: "note", title: title, body: text, source: source)
    }

    func handle(intent: AppIntentRouter.HandledIntent) {
        switch intent.route {
        case .openCommand(let prompt):
            selectedTab = .home
            if let prompt {
                plan(prompt: prompt)
            }
        case .openMemory:
            selectedTab = .brain
        case .remember(let text):
            remember(title: "From Shortcuts", body: text)
            selectedTab = .brain
        }
    }
}

struct MemoryEntry: Identifiable, Equatable {
    let id = UUID()
    var title: String
    var body: String
}

struct PlannedAction: Identifiable, Equatable {
    let id = UUID()
    var type: String
    var title: String
    var risk: String
    var requiresConfirmation: Bool
}

