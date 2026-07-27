import SwiftUI

/// The brain — the same screen as Android's Memory tab: a rotating graph of everything SlyOS knows,
/// with an Ask box above it and a legend of what the colours mean.
///
/// When it is empty it says so and says exactly why, because an empty brain that shows a spinner or
/// a fake constellation is the single most misleading thing this app could do.
struct BrainPanel: View {
    @Environment(\.palette) private var p

    @State private var model = MemoryGraphModel()
    @State private var query = ""
    @State private var selected: Int?
    @State private var typeFilter: String?
    @State private var total = 0
    @State private var showSettings = false
    @State private var building = true
    @State private var answer = ""
    @State private var asking = false
    @State private var failure: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
            askRow
            legend

            if building {
                VStack { Spacer(); SlyWaiting("mapping your brain", orbit: 34); Spacer() }.frame(maxWidth: .infinity)
            } else if total == 0 {
                emptyBrain
            } else {
                if asking || !answer.isEmpty || failure != nil { answerBlock }
                MemoryGraphView(model: model, selected: $selected,
                                typeFilter: typeFilter, query: query)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                selectionCard
            }
        }
        .padding(.horizontal, T.md)
        .scrollDismissesKeyboard(.interactively)
        .task { await load() }
        .sheet(isPresented: $showSettings) { SettingsPanel() }
    }

    // MARK: - Chrome

    private var header: some View {
        VStack(alignment: .leading, spacing: T.xs) {
            Heading("Memory")
            Text(subtitle)
                .font(.system(size: T.body))
                .foregroundStyle(p.inkFaint)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.top, T.md)
    }

    private var subtitle: String {
        total == 0
            ? "nothing mapped yet"
            : "\(total.formatted()) memories mapped · on-device · drag to rotate, pinch to zoom"
    }

    private var askRow: some View {
        HStack(spacing: T.sm) {
            TextField("", text: $query, prompt:
                Text("Ask your memory…").foregroundStyle(p.inkFaint))
                .font(.system(size: T.body))
                .foregroundStyle(p.ink)
                .textFieldStyle(.plain)
                .padding(.horizontal, T.md).padding(.vertical, 12)
                .background(RoundedRectangle(cornerRadius: 12).fill(p.bgElevated.opacity(0.5)))

            Button {
                dismissKeyboard()
                runAsk()
            } label: {
                Text(asking ? "…" : "Ask")
                    .font(.system(size: T.body))
                    .foregroundStyle(p.ink)
                    .frame(minWidth: 28)
                    .padding(.horizontal, T.lg).padding(.vertical, 12)
                    .background(Capsule().fill(p.accent))
            }
            .fixedSize()
            .disabled(asking || query.trimmingCharacters(in: .whitespaces).isEmpty)

            Button { showSettings = true } label: {
                HStack(spacing: T.xs) {
                    Image(systemName: "gearshape").font(.system(size: 17))
                    Text("Settings").font(.system(size: T.body))
                }
                .foregroundStyle(p.ink)
            }
            .fixedSize()
        }
        .padding(.top, T.md)
    }

    /// Tapping a swatch isolates that kind, exactly as on Android.
    private var legend: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: T.md) {
                ForEach(GraphPalette.legend, id: \.type) { item in
                    let on = typeFilter == item.type
                    Button {
                        typeFilter = on ? nil : item.type
                    } label: {
                        HStack(spacing: 6) {
                            Circle()
                                .fill(GraphPalette.type(item.type))
                                .frame(width: 9, height: 9)
                            Text(item.label)
                                .font(.system(size: T.body))
                                .foregroundStyle(on ? p.accent : p.inkSoft)
                        }
                    }
                }
            }
            .padding(.vertical, T.sm)
        }
        .padding(.top, T.xs)
    }

    /// What you tapped. Android shows a card at the bottom; this is the same idea, kept small so it
    /// never covers the cloud.
    @ViewBuilder
    private var selectionCard: some View {
        if let id = selected, let node = model.nodes.first(where: { $0.id == id }) {
            VStack(alignment: .leading, spacing: T.xs) {
                HStack {
                    Text(node.label)
                        .font(.system(size: T.body, weight: .medium))
                        .foregroundStyle(p.ink)
                    Spacer()
                    Button { selected = nil } label: {
                        Image(systemName: "xmark").font(.system(size: 14))
                            .foregroundStyle(p.inkFaint)
                    }
                }
                if !node.source.isEmpty {
                    Text(node.source).font(.system(size: T.caption)).foregroundStyle(p.accent)
                }
            }
            .padding(T.md)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 12).fill(p.bgElevated))
            .padding(.bottom, T.sm)
        }
    }

    /// What the brain answered. Capped in height so the graph stays visible underneath it.
    private var answerBlock: some View {
        VStack(alignment: .leading, spacing: T.xs) {
            if asking {
                SlyWaiting("searching your brain")
            } else if let failure {
                Text(failure).font(.system(size: T.body)).foregroundStyle(p.danger)
                    .fixedSize(horizontal: false, vertical: true)
            } else {
                ScrollView { AnswerView(text: answer) }
                .frame(maxHeight: 220)
                .scrollIndicators(.hidden)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(T.md)
        .background(RoundedRectangle(cornerRadius: 12).fill(p.bgElevated.opacity(0.6)))
        .padding(.bottom, T.sm)
    }

    private func runAsk() {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return }
        dismissKeyboard()
        answer = ""; failure = nil; asking = true
        Task {
            do { answer = try await AgentClient.ask(q) }
            catch { failure = error.localizedDescription }
            asking = false
        }
    }

    private func centred(_ s: String) -> some View {
        VStack {
            Spacer()
            Text(s).font(.system(size: T.body)).foregroundStyle(p.inkFaint)
            Spacer()
        }
        .frame(maxWidth: .infinity)
    }

    /// The honest empty state: nothing has been imported, and it says which sources would fill it.
    private var emptyBrain: some View {
        VStack(alignment: .leading, spacing: T.sm) {
            Text("The brain is empty.")
                .font(.system(size: 28)).foregroundStyle(p.ink)
                .padding(.top, T.lg)
            Text("Nothing has been imported yet. Connect a source and everything it holds becomes "
                 + "part of this map — and every answer SlyOS gives starts using it.")
                .font(.system(size: T.body)).foregroundStyle(p.inkFaint)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.bottom, T.sm)

            // Only sources iOS can genuinely provide are listed. Nothing here is aspirational.
            ForEach(ImportSource.available) { s in
                HStack(spacing: T.sm) {
                    Image(systemName: s.symbol)
                        .font(.system(size: 18)).foregroundStyle(p.accent).frame(width: 24)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(s.title).font(.system(size: T.body)).foregroundStyle(p.ink)
                        Text(s.detail).font(.system(size: T.caption)).foregroundStyle(p.inkFaint)
                    }
                    Spacer()
                }
                .padding(.vertical, T.xs)
            }
            Spacer()
        }
    }

    // MARK: - Work

    /// Build off the main thread: the layout is O(n²) per pass over 60 passes, which is fine on a
    /// background thread and a visible freeze on the main one.
    private func load() async {
        building = true
        let count = await Task.detached { SlyStore.shared.count() }.value
        let m = MemoryGraphModel()
        if count > 0 { await Task.detached { m.build() }.value }
        model = m
        total = count
        building = false
    }
}

/// A place the brain can be filled from.
///
/// Deliberately only lists what iOS actually permits. Android fills its brain largely from
/// notification access — reading WhatsApp, Instagram and LinkedIn as they arrive — and iOS has no
/// equivalent and never will, so promising those here would be a lie the app could not keep.
struct ImportSource: Identifiable {
    let id: String
    let title: String
    let detail: String
    let symbol: String

    static let available: [ImportSource] = [
        .init(id: "mail", title: "Mail",
              detail: "Everything you've written and received", symbol: "envelope.fill"),
        .init(id: "calendar", title: "Calendar",
              detail: "Who you met, when, and about what", symbol: "calendar"),
        .init(id: "contacts", title: "Contacts",
              detail: "Names, addresses, and how you reach them", symbol: "person.crop.circle.fill"),
        .init(id: "docs", title: "Documents",
              detail: "Files you point it at", symbol: "doc.text.fill"),
        .init(id: "typed", title: "Anything you tell it",
              detail: "Straight from Home, or from Siri", symbol: "text.bubble.fill")
    ]
}

/// One memory, rendered as a row. Shared by any screen that lists recall — the brain's own results,
/// and Research's earlier findings.
struct MemoryRow: View {
    let memory: Memory
    @Environment(\.palette) private var p

    var body: some View {
        VStack(alignment: .leading, spacing: T.xs) {
            HStack(spacing: T.sm) {
                if !memory.person.isEmpty {
                    Text(memory.person)
                        .font(.system(size: T.body, weight: .medium))
                        .foregroundStyle(p.ink)
                }
                if !memory.source.isEmpty {
                    Text(memory.source)
                        .font(.system(size: T.caption))
                        .foregroundStyle(p.accent)
                }
                Spacer()
                Text(memory.date.formatted(.dateTime.day().month(.abbreviated)))
                    .font(.system(size: T.caption))
                    .foregroundStyle(p.inkFaint)
            }
            if !memory.title.isEmpty {
                Text(memory.title)
                    .font(.system(size: T.body))
                    .foregroundStyle(p.ink)
                    .lineLimit(1)
            }
            Text(memory.body)
                .font(.system(size: T.small))
                .foregroundStyle(p.inkSoft)
                .lineLimit(2)
                .multilineTextAlignment(.leading)
        }
        .padding(.vertical, T.sm)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
