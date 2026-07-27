import SwiftUI

/// Settings. Everything here is wired to something real and persists across launches.
struct SettingsPanel: View {
    @Environment(SlySettings.self) private var settings
    @Environment(\.dismiss) private var dismiss

    private var router: ModelRouter { ModelRouter.shared }

    @State private var brainCount = 0
    @State private var sources: [(String, Int)] = []

    var body: some View {
        @Bindable var settings = settings
        let p = Palette(dark: settings.dark)

        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: T.lg) {

                    group("ACCOUNT", p: p) { AccountSection(palette: p) }

                    group("APPEARANCE", p: p) {
                        Toggle(isOn: $settings.dark) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Dark mode")
                                    .font(.system(size: T.body)).foregroundStyle(p.ink)
                                Text("SlyOS remembers this itself rather than following iOS, so "
                                     + "your phones look the same.")
                                    .font(.system(size: T.caption)).foregroundStyle(p.inkFaint)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                        }
                        .tint(p.accent)
                    }

                    group("YOU", p: p) {
                        VStack(alignment: .leading, spacing: T.xs) {
                            Text("Name").font(.system(size: T.body)).foregroundStyle(p.ink)
                            TextField("", text: $settings.name, prompt:
                                Text("what should it call you?").foregroundStyle(p.inkFaint))
                                .font(.system(size: T.body))
                                .foregroundStyle(p.ink)
                                .textFieldStyle(.plain)
                            Rectangle().fill(p.hairline).frame(height: 1)
                        }
                    }

                    group("CHARACTER", p: p) {
                        VStack(alignment: .leading, spacing: T.sm) {
                            Text("What should SlyOS know about you? Tone, work, people who matter — "
                                 + "anything that makes its answers sound like you.")
                                .font(.system(size: T.caption)).foregroundStyle(p.inkFaint)
                                .fixedSize(horizontal: false, vertical: true)
                            CharacterEditor(palette: p)
                        }
                    }

                    group("ABOUT YOU", p: p) {
                        VStack(alignment: .leading, spacing: T.sm) {
                            Text("All optional. Everything here feeds your brain, so SlyOS knows who "
                                 + "it is speaking for.")
                                .font(.system(size: T.caption)).foregroundStyle(p.inkFaint)
                                .fixedSize(horizontal: false, vertical: true)
                            ForEach(SlyProfile.personalFields, id: \.key) { f in
                                ProfileFieldRow(key: f.key, label: f.label, palette: p)
                            }
                        }
                    }

                    group("CONTACT DETAILS", p: p) {
                        VStack(alignment: .leading, spacing: T.sm) {
                            Text("Used verbatim in signatures, letterheads and forms — so a document "
                                 + "SlyOS writes for you carries your real details, not a guess.")
                                .font(.system(size: T.caption)).foregroundStyle(p.inkFaint)
                                .fixedSize(horizontal: false, vertical: true)
                            ForEach(SlyProfile.contactFields, id: \.key) { f in
                                ProfileFieldRow(key: f.key, label: f.label, palette: p)
                            }
                        }
                    }

                    group("VOICE", p: p) {
                        Toggle(isOn: $settings.voiceOnHold) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Hold the brain to talk")
                                    .font(.system(size: T.body)).foregroundStyle(p.ink)
                                Text("Press and hold the centre button for three seconds.")
                                    .font(.system(size: T.caption)).foregroundStyle(p.inkFaint)
                            }
                        }
                        .tint(p.accent)
                    }

                    group("INTELLIGENCE", p: p) {
                        VStack(alignment: .leading, spacing: T.sm) {
                            Text(router.isConfigured
                                 ? "SlyOS will use the best of these you've set up, and fall back to the next if one fails."
                                 : "SlyOS can't answer anything until one of these has a key. Groq, Gemini, Cerebras and Mistral all have free tiers.")
                                .font(.system(size: T.caption)).foregroundStyle(p.inkFaint)
                                .fixedSize(horizontal: false, vertical: true)

                            ForEach(ModelRouter.Provider.allCases.sorted { $0.isFree && !$1.isFree }) { provider in
                                KeyCard(provider: provider, palette: p)
                            }
                        }
                    }

                    group("GOOGLE", p: p) { GoogleSection(palette: p) }

                    group("AUTOMATION", p: p) { AutonomySection(palette: p) }

                    group("OPENCLAW", p: p) { OpenClawSection(palette: p) }

                    group("ZENODO", p: p) { ZenodoSection(palette: p) }

                    group("IMPORT", p: p) {
                        VStack(alignment: .leading, spacing: 0) {
                            Text("Everything imported stays on this phone.")
                                .font(.system(size: T.caption)).foregroundStyle(p.inkFaint)
                            ForEach(Importers.Source.allCases) { source in
                                ImportRow(source: source, palette: p)
                            }
                        }
                    }

                    group("BRAIN", p: p) {
                        VStack(alignment: .leading, spacing: T.sm) {
                            HStack {
                                Text("Stored").font(.system(size: T.body)).foregroundStyle(p.ink)
                                Spacer()
                                Text(brainCount.formatted())
                                    .font(.system(size: T.body)).foregroundStyle(p.accent)
                            }
                            ForEach(sources, id: \.0) { name, n in
                                HStack {
                                    Text(name)
                                        .font(.system(size: T.small)).foregroundStyle(p.inkSoft)
                                    Spacer()
                                    Text(n.formatted())
                                        .font(.system(size: T.small)).foregroundStyle(p.inkFaint)
                                }
                            }
                            if sources.isEmpty {
                                Text("Nothing imported yet.")
                                    .font(.system(size: T.small)).foregroundStyle(p.inkFaint)
                            }
                        }
                    }
                }
                .padding(T.md)
            }
            .background(p.bg.ignoresSafeArea())
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }.tint(p.accent)
                }
            }
        }
        .environment(\.palette, p)
        .preferredColorScheme(settings.dark ? .dark : .light)
        .task {
            brainCount = SlyStore.shared.count()
            sources = SlyStore.shared.countsBySource()
        }
    }

    @ViewBuilder
    private func group<Content: View>(_ label: String, p: Palette,
                                      @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: T.sm) {
            Text(label)
                .font(.system(size: T.small, weight: .bold))
                .tracking(2)
                .foregroundStyle(p.inkFaint)
            content()
        }
    }
}

/// One profile field. Each owns its editing state, so typing in one does not rebuild the others.
private struct ProfileFieldRow: View {
    let key: String
    let label: String
    let palette: Palette

    @State private var value = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.system(size: T.caption)).foregroundStyle(palette.inkFaint)
            TextField("", text: $value, prompt:
                Text("—").foregroundStyle(palette.inkFaint))
                .font(.system(size: T.body))
                .foregroundStyle(palette.ink)
                .textFieldStyle(.plain)
                // Written on every change rather than only on Return: tapping straight from one
                // field to the next would otherwise discard what was just typed.
                .onChange(of: value) { _, new in SlyProfile.shared.setValue(new, for: key) }
            Rectangle().fill(palette.hairline).frame(height: 1)
        }
        .padding(.vertical, 2)
        .onAppear { value = SlyProfile.shared.value(key) }
    }
}

/// The free-text character block.
private struct CharacterEditor: View {
    let palette: Palette
    @State private var text = ""

    var body: some View {
        TextEditor(text: $text)
            .font(.system(size: T.body))
            .foregroundStyle(palette.ink)
            .scrollContentBackground(.hidden)
            .frame(minHeight: 140)
            .padding(T.sm)
            .background(RoundedRectangle(cornerRadius: 14).fill(palette.bgElevated))
            .onChange(of: text) { _, new in SlyProfile.shared.character = new }
            .onAppear { text = SlyProfile.shared.character }
    }
}


/// Sign up, sign in, and sync. Matches the cross-client contract in ACCOUNT_AND_SYNC.md, so the
/// same account carries the same brain between this app and the Android one.
private struct AccountSection: View {
    let palette: Palette

    @State private var supabase = SupabaseClient.shared
    @State private var email = ""
    @State private var password = ""
    @State private var busy = false
    @State private var message: String?
    @State private var isError = false
    @State private var confirmingDelete = false

    var body: some View {
        VStack(alignment: .leading, spacing: T.sm) {
            if !supabase.isConfigured {
                Text("Accounts aren't set up in this build. Add SupabaseURL and SupabaseAnonKey to "
                     + "the project's Info.plist keys.")
                    .font(.system(size: T.caption)).foregroundStyle(palette.inkFaint)
                    .fixedSize(horizontal: false, vertical: true)
            } else if supabase.isSignedIn {
                signedIn
            } else {
                signedOut
            }

            if let message {
                Text(message)
                    .font(.system(size: T.caption))
                    .foregroundStyle(isError ? palette.danger : palette.good)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .alert("Delete your account?", isPresented: $confirmingDelete) {
            Button("Cancel", role: .cancel) {}
            Button("Delete", role: .destructive) { deleteAccount() }
        } message: {
            Text("This removes your account and everything synced to it, permanently. What's on "
                 + "this phone stays until you delete the app.")
        }
    }

    private func deleteAccount() {
        busy = true; message = nil
        Task {
            do {
                try await SupabaseClient.shared.deleteAccount()
                isError = false; message = "Account deleted."
            } catch {
                isError = true; message = error.localizedDescription
            }
            busy = false
        }
    }

    private var signedIn: some View {
        VStack(alignment: .leading, spacing: T.sm) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(supabase.email ?? "Signed in")
                        .font(.system(size: T.body)).foregroundStyle(palette.ink)
                    Text(syncLine)
                        .font(.system(size: T.caption)).foregroundStyle(palette.inkFaint)
                }
                Spacer()
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 16)).foregroundStyle(palette.good)
            }

            HStack(spacing: T.sm) {
                Button {
                    Task { await supabase.sync() }
                } label: {
                    Text(supabase.syncing ? "Syncing…" : "Back up now")
                        .font(.system(size: T.small, weight: .medium))
                        .foregroundStyle(palette.ink)
                        .padding(.horizontal, T.md).padding(.vertical, 9)
                        .background(Capsule().fill(palette.accent))
                }
                .disabled(supabase.syncing)

                Button("Sign out") { supabase.signOut() }
                    .font(.system(size: T.small))
                    .foregroundStyle(palette.inkFaint)
                Spacer()
                // App Review requires account deletion to be reachable from inside the app.
                Button("Delete account") { confirmingDelete = true }
                    .font(.system(size: T.small))
                    .foregroundStyle(palette.danger)
            }
        }
    }

    private var syncLine: String {
        if let error = supabase.lastError { return error }
        guard let last = supabase.lastSync else { return "Not backed up yet" }
        return "Backed up \(last.formatted(.relative(presentation: .named)))"
    }

    private var signedOut: some View {
        VStack(alignment: .leading, spacing: T.sm) {
            Text("An account backs your brain up and carries it to your other devices. Same account "
                 + "as the Android app.")
                .font(.system(size: T.caption)).foregroundStyle(palette.inkFaint)
                .fixedSize(horizontal: false, vertical: true)

            field("Email", text: $email, secure: false)
            field("Password", text: $password, secure: true)

            HStack(spacing: T.sm) {
                Button { run(signingUp: false) } label: {
                    Text("Sign in")
                        .font(.system(size: T.small, weight: .medium))
                        .foregroundStyle(palette.ink)
                        .padding(.horizontal, T.md).padding(.vertical, 9)
                        .background(Capsule().fill(palette.accent))
                }
                Button { run(signingUp: true) } label: {
                    Text("Create account")
                        .font(.system(size: T.small))
                        .foregroundStyle(palette.ink)
                        .padding(.horizontal, T.md).padding(.vertical, 9)
                        .background(Capsule().fill(palette.accent.opacity(0.22)))
                }
                Spacer()
            }
            .disabled(busy || email.isEmpty || password.isEmpty)
        }
    }

    private func field(_ label: String, text: Binding<String>, secure: Bool) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.system(size: T.caption)).foregroundStyle(palette.inkFaint)
            Group {
                if secure { SecureField("", text: text) } else { TextField("", text: text) }
            }
            .font(.system(size: T.body))
            .foregroundStyle(palette.ink)
            .textFieldStyle(.plain)
            .autocorrectionDisabled()
            .textInputAutocapitalization(.never)
            .keyboardType(secure ? .default : .emailAddress)
            Rectangle().fill(palette.hairline).frame(height: 1)
        }
    }

    private func run(signingUp: Bool) {
        busy = true; message = nil
        Task {
            do {
                if signingUp {
                    try await SupabaseClient.shared.signUp(email: email, password: password)
                } else {
                    try await SupabaseClient.shared.signIn(email: email, password: password)
                }
                password = ""
                isError = false
                message = signingUp ? "Account created." : nil
                // First sign-in on a device should pull the brain down without being asked.
                await SupabaseClient.shared.sync()
            } catch {
                isError = true
                message = error.localizedDescription
            }
            busy = false
        }
    }
}


/// Google: sign-in, and the full-brain backup into the owner's own Drive.
private struct GoogleSection: View {
    let palette: Palette

    @State private var auth = GoogleAuth.shared
    @State private var backup = DriveBackup.shared
    @State private var busy = false
    @State private var note: String?
    @State private var isError = false

    var body: some View {
        VStack(alignment: .leading, spacing: T.sm) {
            if !auth.isConfigured {
                Text("Google isn't set up in this build. Add an iOS OAuth client id to "
                     + "Secrets.xcconfig — the Android one won't work, because Google ties the "
                     + "redirect scheme to the client type.")
                    .font(.system(size: T.caption)).foregroundStyle(palette.inkFaint)
                    .fixedSize(horizontal: false, vertical: true)
            } else if auth.isConnected {
                connected
            } else {
                Text("Connect Google for calendar invites with real Meet links, and to back your "
                     + "brain up to your own Drive.")
                    .font(.system(size: T.caption)).foregroundStyle(palette.inkFaint)
                    .fixedSize(horizontal: false, vertical: true)
                pill("Connect Google") { connect() }
            }

            if let note {
                Text(note)
                    .font(.system(size: T.caption))
                    .foregroundStyle(isError ? palette.danger : palette.good)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private var connected: some View {
        VStack(alignment: .leading, spacing: T.sm) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(auth.connectedEmail ?? "Connected")
                        .font(.system(size: T.body)).foregroundStyle(palette.ink)
                    Text(backupLine)
                        .font(.system(size: T.caption)).foregroundStyle(palette.inkFaint)
                }
                Spacer()
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 16)).foregroundStyle(palette.good)
            }

            Text("Backups use the drive.file scope, so SlyOS can only ever see the one file it "
                 + "created — never the rest of your Drive.")
                .font(.system(size: T.caption)).foregroundStyle(palette.inkFaint)
                .fixedSize(horizontal: false, vertical: true)

            HStack(spacing: T.sm) {
                pill(backup.working ? "Working…" : "Back up brain") {
                    Task { await backup.backUp(); report() }
                }
                Button("Restore") {
                    Task {
                        let n = await backup.restore()
                        report(success: "Restored \(n) \(n == 1 ? "memory" : "memories").")
                    }
                }
                .font(.system(size: T.small)).foregroundStyle(palette.ink)
                .padding(.horizontal, T.md).padding(.vertical, 9)
                .background(Capsule().fill(palette.accent.opacity(0.22)))

                Button("Disconnect") { auth.signOut() }
                    .font(.system(size: T.small)).foregroundStyle(palette.inkFaint)
                Spacer()
            }
            .disabled(backup.working)
        }
    }

    private var backupLine: String {
        if let last = backup.lastBackup {
            let size = backup.backupSize.map { " · \($0 / 1024) KB" } ?? ""
            return "Backed up \(last.formatted(.relative(presentation: .named)))\(size)"
        }
        return "Not backed up yet"
    }

    private func pill(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: T.small, weight: .medium))
                .foregroundStyle(palette.ink)
                .padding(.horizontal, T.md).padding(.vertical, 9)
                .background(Capsule().fill(palette.accent))
        }
        .disabled(busy)
    }

    private func connect() {
        busy = true; note = nil
        Task {
            do { try await auth.signIn(); isError = false; note = "Google connected." }
            catch { isError = true; note = error.localizedDescription }
            busy = false
        }
    }

    private func report(success: String? = nil) {
        if let e = backup.lastError { isError = true; note = e }
        else { isError = false; note = success ?? "Backed up to your Drive." }
    }
}


/// The owner's own OpenClaw gateway.
///
/// The copy here is deliberately blunt about the risk. Thousands of gateways sit on the public
/// internet with no token, and anyone who finds one can read its history or instruct it. Someone
/// pointing SlyOS at theirs should know what they are connecting to.
private struct OpenClawSection: View {
    let palette: Palette

    @State private var claw = OpenClaw.shared
    @State private var host = ""
    @State private var token = ""
    @State private var allowActions = false

    var body: some View {
        VStack(alignment: .leading, spacing: T.sm) {
            Text("Run OpenClaw on your own machine and SlyOS can read what it sees — WhatsApp, "
                 + "Telegram, Slack — which iOS itself will never hand over. It can also answer "
                 + "through it, so nothing leaves your hardware.")
                .font(.system(size: T.caption)).foregroundStyle(palette.inkFaint)
                .fixedSize(horizontal: false, vertical: true)

            field("Address", text: $host, placeholder: "192.168.1.20:18789", secure: false)
            field("Gateway token", text: $token, placeholder: "required", secure: true)

            Text("A token is not optional here. Gateways left without one can be read and "
                 + "instructed by anyone who finds them, and SlyOS won't connect to one.")
                .font(.system(size: T.caption)).foregroundStyle(palette.danger)
                .fixedSize(horizontal: false, vertical: true)

            Toggle(isOn: $allowActions) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Let it act, not just read")
                        .font(.system(size: T.body)).foregroundStyle(palette.ink)
                    Text("Off by default. Even on, every action asks you first and is recorded in "
                         + "Sent for you.")
                        .font(.system(size: T.caption)).foregroundStyle(palette.inkFaint)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .tint(palette.accent)
            .onChange(of: allowActions) { _, on in claw.allowActions = on }

            HStack(spacing: T.sm) {
                Button {
                    claw.host = host
                    claw.token = token
                    Task { await claw.check() }
                } label: {
                    Text(claw.checking ? "Checking…" : "Save & test")
                        .font(.system(size: T.small, weight: .medium))
                        .foregroundStyle(palette.bgElevated)
                        .padding(.horizontal, T.md).padding(.vertical, 9)
                        .background(Capsule().fill(palette.accent))
                }
                .disabled(claw.checking)
                Spacer()
            }

            if let status = claw.status {
                Text(status)
                    .font(.system(size: T.caption))
                    .foregroundStyle(claw.connected ? palette.good : palette.danger)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .onAppear {
            host = claw.host
            token = claw.token
            allowActions = claw.allowActions
        }
    }

    private func field(_ label: String, text: Binding<String>,
                       placeholder: String, secure: Bool) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.system(size: T.caption)).foregroundStyle(palette.inkFaint)
            Group {
                if secure {
                    SecureField("", text: text, prompt:
                        Text(placeholder).foregroundStyle(palette.inkFaint))
                } else {
                    TextField("", text: text, prompt:
                        Text(placeholder).foregroundStyle(palette.inkFaint))
                }
            }
            .font(.system(size: T.body))
            .foregroundStyle(palette.ink)
            .textFieldStyle(.plain)
            .autocorrectionDisabled()
            .textInputAutocapitalization(.never)
            Rectangle().fill(palette.hairline).frame(height: 1)
        }
    }
}


/// How much SlyOS may do on its own — and an honest statement of what each level can reach.
private struct AutonomySection: View {
    let palette: Palette

    @State private var autonomy = Autonomy.shared
    @State private var level: Autonomy.Level = .draft
    @State private var knownOnly = true
    @State private var limit = 10

    var body: some View {
        VStack(alignment: .leading, spacing: T.sm) {
            ForEach(Autonomy.Level.allCases) { l in
                Button { level = l; autonomy.level = l } label: {
                    HStack(alignment: .top, spacing: 10) {
                        Image(systemName: level == l ? "largecircle.fill.circle" : "circle")
                            .font(.system(size: 18))
                            .foregroundStyle(level == l ? palette.accent : palette.inkFaint)
                        VStack(alignment: .leading, spacing: 2) {
                            HStack(spacing: 6) {
                                Text(l.title)
                                    .font(.system(size: T.body)).foregroundStyle(palette.ink)
                                if l.needsGateway && !OpenClaw.shared.isConfigured {
                                    Text("needs gateway")
                                        .font(.system(size: 10, weight: .bold))
                                        .foregroundStyle(palette.danger)
                                        .padding(.horizontal, 6).padding(.vertical, 2)
                                        .background(Capsule().fill(palette.danger.opacity(0.15)))
                                }
                            }
                            Text(l.detail)
                                .font(.system(size: T.caption)).foregroundStyle(palette.inkFaint)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        Spacer(minLength: 0)
                    }
                }
                .padding(.vertical, 2)
            }

            if level == .autonomous {
                Divider().overlay(palette.hairline).padding(.vertical, 4)

                Toggle(isOn: $knownOnly) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Only people you've spoken with")
                            .font(.system(size: T.body)).foregroundStyle(palette.ink)
                        Text("A confident reply to a stranger is the expensive mistake, not a "
                             + "clumsy one to a friend.")
                            .font(.system(size: T.caption)).foregroundStyle(palette.inkFaint)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .tint(palette.accent)
                .onChange(of: knownOnly) { _, v in autonomy.onlyKnownContacts = v }

                VStack(alignment: .leading, spacing: 2) {
                    HStack {
                        Text("At most \(limit) a day")
                            .font(.system(size: T.body)).foregroundStyle(palette.ink)
                        Spacer()
                        Text("\(autonomy.sentToday) sent today")
                            .font(.system(size: T.caption)).foregroundStyle(palette.inkFaint)
                    }
                    Slider(value: Binding(get: { Double(limit) },
                                          set: { limit = Int($0); autonomy.dailyLimit = Int($0) }),
                           in: 1...50, step: 1)
                        .tint(palette.accent)
                    Text("An agent stuck in a loop costs you your reputation, not your money.")
                        .font(.system(size: T.caption)).foregroundStyle(palette.inkFaint)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .onAppear {
            level = autonomy.level
            knownOnly = autonomy.onlyKnownContacts
            limit = autonomy.dailyLimit
        }
    }
}


/// Zenodo — the token that turns a paper into a citable record with a DOI.
private struct ZenodoSection: View {
    let palette: Palette
    @State private var token = ""
    @State private var saved = false

    var body: some View {
        VStack(alignment: .leading, spacing: T.sm) {
            Text("Publish papers to CERN's open repository with a real DOI. Needs a personal access "
                 + "token with deposit:write and deposit:actions.")
                .font(.system(size: T.caption)).foregroundStyle(palette.inkFaint)
                .fixedSize(horizontal: false, vertical: true)

            HStack(spacing: T.sm) {
                Link(destination: URL(string: "https://zenodo.org/account/settings/applications/tokens/new/")!) {
                    HStack(spacing: 6) {
                        Image(systemName: "key.fill").font(.system(size: 12))
                        Text("Get a token").font(.system(size: T.small, weight: .medium))
                    }
                    .foregroundStyle(palette.bgElevated)
                    .padding(.horizontal, T.md).padding(.vertical, 9)
                    .background(Capsule().fill(palette.accent))
                }
                SecureField("", text: $token, prompt:
                    Text("paste it here").foregroundStyle(palette.inkFaint))
                    .font(.system(size: T.small))
                    .foregroundStyle(palette.ink)
                    .textFieldStyle(.plain)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .onChange(of: token) { _, new in
                        Zenodo.shared.token = new
                        saved = !new.isEmpty
                    }
                if saved || Zenodo.shared.isConfigured {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 15)).foregroundStyle(palette.good)
                }
            }
            Rectangle().fill(palette.hairline).frame(height: 1)
        }
        .onAppear { token = Zenodo.shared.token }
    }
}
