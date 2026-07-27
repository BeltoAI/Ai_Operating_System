import SwiftUI

/// Turns a plain answer into something worth looking at — a port of Compose `RichOutput`.
///
/// When an answer has a shape SlyOS recognises — a countdown, a price, a conversion, a quotation, a
/// straight yes or no — it gets a headline card above the prose. Everything else renders as prose,
/// because a card around an ordinary paragraph is decoration, not design.
enum Hero: Equatable {

    /// One big number with a label and optional unit: countdowns, conversions, measurements, times.
    case metric(eyebrow: String, big: String, unit: String, sub: String)
    /// A price with a movement.
    case stock(eyebrow: String, price: String, delta: String, up: Bool, sub: String)
    /// A straight answer to a straight question.
    case yesNo(yes: Bool, sub: String)
    /// A quotation with attribution.
    case quote(text: String, author: String)

    /// Find a headline in an answer, if there honestly is one.
    ///
    /// Every rule here is deliberately narrow. A wrong card is far worse than no card: it is the
    /// most assertive thing the app can put on screen, and it sits directly above prose that may
    /// contradict it.
    static func detect(in raw: String) -> Hero? {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, text.count < 900 else { return nil }
        let firstLine = text.split(separator: "\n").first.map(String.init) ?? text

        // Quotation with attribution — “…” — Author
        if let m = firstLine.firstMatch(#"^[“"](.{10,220})[”"]\s*[—–-]\s*(.{2,60})$"#) {
            return .quote(text: m[1], author: m[2].trimmingCharacters(in: .whitespaces))
        }

        // Countdown — "3 days until launch", "2 weeks left"
        if let m = firstLine.firstMatch(#"(?i)\b(\d+)\s+(second|minute|hour|day|week|month|year)s?\b\s*(until|till|to go|left|remaining)"#) {
            return .metric(eyebrow: "Countdown", big: m[1], unit: m[2] + (m[1] == "1" ? "" : "s"),
                           sub: firstLine)
        }

        // Currency conversion — "€100 = $108"
        if let m = firstLine.firstMatch(#"([€£$¥]\s?[\d,.]+)\s*(?:=|≈|is)\s*([€£$¥]\s?[\d,.]+)"#) {
            return .metric(eyebrow: m[1], big: m[2], unit: "", sub: firstLine)
        }

        // A price with a movement — stock-shaped answers.
        if let price = firstLine.firstMatch(#"([€£$¥]\s?[\d,]+(?:\.\d+)?)"#),
           let delta = firstLine.firstMatch(#"([+-]\s?\d+(?:\.\d+)?\s?%)"#) {
            let up = !delta[1].contains("-")
            return .stock(eyebrow: subject(of: firstLine), price: price[1],
                          delta: delta[1].replacingOccurrences(of: " ", with: ""), up: up, sub: firstLine)
        }

        // Time in a place — "3:45 PM in Tokyo"
        if let m = firstLine.firstMatch(#"(?i)\b(\d{1,2}:\d{2}\s?(?:AM|PM)?)\b.*?\bin\s+([A-Z][\w\s]{2,24})"#) {
            return .metric(eyebrow: "Time in " + m[2].trimmingCharacters(in: .whitespaces),
                           big: m[1], unit: "", sub: "")
        }

        // A bare calculation — a short reply ending in "= number".
        if firstLine.count < 60, let m = firstLine.firstMatch(#"=\s*([\d,]+(?:\.\d+)?)\s*$"#) {
            return .metric(eyebrow: "Result", big: m[1], unit: "", sub: firstLine)
        }

        if let yesNo = detectYesNo(text, firstLine: firstLine) { return yesNo }
        return nil
    }

    /// Yes / No, with the two guards that stopped it being wrong.
    ///
    /// 1. **Nuance.** "No, he's not in your contacts, but I did find him in your messages" opens with
    ///    "No" and would get a giant NO sitting directly above a body saying the person *was* found.
    ///    Any pivot word means the answer is qualified, and a qualified answer has no business being
    ///    reduced to one word.
    /// 2. **Idiom.** "No problem", "No worries", "Yes please" are not answers to a yes/no question at
    ///    all — they are filler that happens to start with the right token.
    private static func detectYesNo(_ text: String, firstLine: String) -> Hero? {
        guard text.count < 320 else { return nil }
        let lower = firstLine.lowercased()

        for idiom in ["no problem", "no worries", "yes please", "no idea", "yes and no", "no longer",
                      "no need", "not sure", "no doubt"] where lower.hasPrefix(idiom) {
            return nil
        }
        for pivot in [" but ", " however", " although", " though ", " except", " unless ",
                      " technically", " sort of", " kind of", " depends"] where lower.contains(pivot) {
            return nil
        }

        guard let m = lower.firstMatch(#"^(yes|no|yep|nope|correct|incorrect)\b[,.!]?\s*(.*)$"#) else {
            return nil
        }
        let yes = ["yes", "yep", "correct"].contains(m[1])
        // The rest of the first line becomes the subtitle, so the card explains itself.
        let sub = String(firstLine.dropFirst(m[1].count)
            .trimmingCharacters(in: CharacterSet(charactersIn: " ,.!")))
        return .yesNo(yes: yes, sub: sub)
    }

    /// A rough label for what a price answer is about — whatever precedes the number.
    private static func subject(of line: String) -> String {
        let head = line.prefix { !$0.isNumber && $0 != "€" && $0 != "£" && $0 != "$" && $0 != "¥" }
        let cleaned = head.trimmingCharacters(in: CharacterSet(charactersIn: " ,:—-"))
        return cleaned.isEmpty ? "Price" : String(cleaned.prefix(40))
    }
}

/// The headline card.
struct HeroCardView: View {
    let hero: Hero
    @Environment(\.palette) private var p

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            switch hero {
            case let .metric(eyebrow, big, unit, sub):
                Eyebrow(eyebrow)
                HStack(alignment: .lastTextBaseline, spacing: 4) {
                    Text(big)
                        .font(.system(size: 52, weight: .light))
                        .tracking(-1)
                        .foregroundStyle(p.ink)
                    if !unit.isEmpty {
                        Text(unit).font(.system(size: T.body)).foregroundStyle(p.inkSoft)
                    }
                }
                .padding(.top, 10)
                if !sub.isEmpty && sub.lowercased() != eyebrow.lowercased() {
                    Text(sub).font(.system(size: T.small)).foregroundStyle(p.inkSoft).padding(.top, 8)
                }

            case let .stock(eyebrow, price, delta, up, sub):
                Eyebrow(eyebrow)
                HStack(spacing: 14) {
                    Text(price)
                        .font(.system(size: 46, weight: .light))
                        .tracking(-1)
                        .foregroundStyle(p.ink)
                    Text(delta)
                        .font(.system(size: T.small, weight: .medium))
                        .foregroundStyle(up ? p.good : p.danger)
                        .padding(.horizontal, 10).padding(.vertical, 4)
                        .background(Capsule().fill((up ? p.good : p.danger).opacity(0.15)))
                }
                .padding(.top, 10)
                if !sub.isEmpty {
                    Text(sub).font(.system(size: T.small)).foregroundStyle(p.inkSoft).padding(.top, 8)
                }

            case let .yesNo(yes, sub):
                Eyebrow("Answer")
                Text(yes ? "Yes" : "No")
                    .font(.system(size: 48, weight: .bold))
                    .tracking(-1)
                    .foregroundStyle(yes ? p.good : p.danger)
                    .padding(.top, 8)
                if !sub.isEmpty {
                    Text(sub).font(.system(size: T.small)).foregroundStyle(p.inkSoft).padding(.top, 8)
                }

            case let .quote(text, author):
                HStack(alignment: .top, spacing: 14) {
                    Capsule().fill(p.accent).frame(width: 3).frame(minHeight: 30)
                    VStack(alignment: .leading, spacing: 8) {
                        Text("“\(text)”")
                            .font(.system(size: 20, weight: .light))
                            .italic()
                            .foregroundStyle(p.ink)
                        Text("— \(author)")
                            .font(.system(size: T.small, weight: .medium))
                            .foregroundStyle(p.accent)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 24).padding(.vertical, 22)
        .background(
            RoundedRectangle(cornerRadius: 22)
                .fill(LinearGradient(colors: [p.bgElevated, p.accentSoft.opacity(0.22)],
                                     startPoint: .top, endPoint: .bottom))
        )
        .overlay(RoundedRectangle(cornerRadius: 22).stroke(p.hairline, lineWidth: 1))
    }

    private func Eyebrow(_ s: String) -> some View {
        Text(s.uppercased())
            .font(.system(size: 11, weight: .bold))
            .tracking(2)
            .foregroundStyle(p.inkFaint)
    }
}

/// An answer, rendered properly: headline card when there is one, then formatted prose.
///
/// The prose is not dumped as one block. Headings, bullets and bold are picked out, because a wall
/// of unbroken text is the difference between an answer that looks considered and one that looks
/// like a log line.
struct AnswerView: View {
    let text: String
    var showHero: Bool = true

    @Environment(\.palette) private var p

    var body: some View {
        VStack(alignment: .leading, spacing: T.md) {
            if showHero, let hero = Hero.detect(in: text) {
                HeroCardView(hero: hero)
            }
            VStack(alignment: .leading, spacing: 10) {
                ForEach(Array(blocks.enumerated()), id: \.offset) { _, block in
                    block.view(p)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .textSelection(.enabled)
    }

    private enum Block {
        case heading(String)
        case bullet(String)
        case numbered(String, String)
        case paragraph(String)

        @ViewBuilder
        func view(_ p: Palette) -> some View {
            switch self {
            case .heading(let s):
                Text(inline(s, p))
                    .font(.system(size: T.body, weight: .semibold))
                    .foregroundStyle(p.ink)
                    .padding(.top, 4)
            case .bullet(let s):
                HStack(alignment: .top, spacing: 10) {
                    Circle().fill(p.accent).frame(width: 5, height: 5).padding(.top, 7)
                    Text(inline(s, p)).font(.system(size: T.body)).foregroundStyle(p.ink)
                }
            case .numbered(let n, let s):
                HStack(alignment: .top, spacing: 10) {
                    Text(n).font(.system(size: T.small, weight: .semibold)).foregroundStyle(p.accent)
                        .frame(minWidth: 16, alignment: .trailing)
                    Text(inline(s, p)).font(.system(size: T.body)).foregroundStyle(p.ink)
                }
            case .paragraph(let s):
                Text(inline(s, p)).font(.system(size: T.body)).foregroundStyle(p.ink)
            }
        }

        /// `**bold**` and `*italic*` become real emphasis rather than visible asterisks — models
        /// emit markdown whether or not you ask them to.
        private func inline(_ s: String, _ p: Palette) -> AttributedString {
            (try? AttributedString(
                markdown: s,
                options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace)))
                ?? AttributedString(s)
        }
    }

    private var blocks: [Block] {
        text.split(separator: "\n", omittingEmptySubsequences: false).compactMap { raw in
            let line = raw.trimmingCharacters(in: .whitespaces)
            guard !line.isEmpty else { return nil }

            if let m = line.firstMatch(#"^#{1,6}\s+(.*)$"#) { return .heading(m[1]) }
            if let m = line.firstMatch(#"^[-•*]\s+(.*)$"#) { return .bullet(m[1]) }
            if let m = line.firstMatch(#"^(\d{1,2})[.)]\s+(.*)$"#) { return .numbered(m[1] + ".", m[2]) }
            // A short line that is entirely bold reads as a heading, which is how models write them.
            if let m = line.firstMatch(#"^\*\*(.{2,60})\*\*:?$"#) { return .heading(m[1]) }
            return .paragraph(line)
        }
    }
}

extension StringProtocol {
    /// Regex capture groups by index, with `[0]` the whole match. Returns nil when there is no match,
    /// so call sites read as `if let m = … { m[1] }`.
    func firstMatch(_ pattern: String) -> [String]? {
        let s = String(self)
        guard let re = try? NSRegularExpression(pattern: pattern),
              let m = re.firstMatch(in: s, range: NSRange(s.startIndex..., in: s))
        else { return nil }
        return (0..<m.numberOfRanges).map { i in
            guard let r = Range(m.range(at: i), in: s) else { return "" }
            return String(s[r])
        }
    }
}
