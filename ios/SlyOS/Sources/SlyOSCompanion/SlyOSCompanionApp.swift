import SwiftUI

@main
struct SlyOSCompanionApp: App {
    @State private var appState = AppState()
    @State private var settings = SlySettings.shared
    @State private var intentRouter = AppIntentRouter.shared

    init() {
        // Location reaches UIKit APIs an extension cannot use, so it is handed to the client here
        // rather than referenced from it.
        AgentClient.placeResolver = { await LocationProvider.shared.describe() }
        AgentClient.agendaResolver = {
            let feed = await NowFeed()
            await feed.load()
            let items = await feed.items
            guard !items.isEmpty else { return "Nothing on for the next two days." }
            return items.prefix(15)
                .map { "· \($0.title) — \($0.detail)" }
                .joined(separator: "\n")
        }
        // Same reason: ModelRouter is shared with the extension, which carries no gateway client.
        ModelRouter.openClaw = (baseURL: { OpenClaw.shared.baseURL },
                                token: { OpenClaw.shared.token })
    }

    var body: some Scene {
        WindowGroup {
            AppView()
                .environment(appState)
                .environment(settings)
                .environment(intentRouter)
                .onChange(of: intentRouter.handledIntent) { _, handled in
                    guard let handled else { return }
                    appState.handle(intent: handled)
                }
        }
    }
}

