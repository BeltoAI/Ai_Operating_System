import Foundation
import Observation

/// Everything the owner has chosen, persisted.
///
/// This is the iOS counterpart of the settings Android keeps in `MemoryStore`. It is `@Observable`
/// rather than a static flag because appearance has to *drive* the UI: a plain `static var dark`
/// can be flipped all day and SwiftUI will never redraw, so the toggle would move and nothing
/// would change.
///
/// **Why the properties look like this.** The obvious version — a stored property with a `didSet`
/// that writes to `UserDefaults` — silently does not work. The `@Observable` macro leaves any
/// property carrying a `willSet`/`didSet` observer alone, so it never registers with the
/// observation registrar and SwiftUI is never told it changed. The dark-mode toggle wrote the new
/// value, persisted it correctly, and redrew nothing; it looked like a dead switch.
///
/// So each setting is a computed property over `@ObservationIgnored` storage, with the explicit
/// `access` / `withMutation` calls the macro would otherwise have generated. Persisting inside
/// `withMutation` keeps the write and the notification together.
@Observable
final class SlySettings {

    static let shared = SlySettings()

    private enum Key {
        static let dark = "slyos.dark"
        static let name = "slyos.name"
        static let voiceOnHold = "slyos.voiceOnHold"
        static let onboarded = "slyos.onboarded"
    }

    @ObservationIgnored private var _dark: Bool
    @ObservationIgnored private var _name: String
    @ObservationIgnored private var _voiceOnHold: Bool
    @ObservationIgnored private var _hasOnboarded: Bool
    @ObservationIgnored private let defaults: UserDefaults

    /// Dark mode. SlyOS remembers this itself instead of following the system, so a user's Android
    /// and iOS devices agree.
    var dark: Bool {
        get { access(keyPath: \.dark); return _dark }
        set { withMutation(keyPath: \.dark) { _dark = newValue; defaults.set(newValue, forKey: Key.dark) } }
    }

    /// The name in the Home greeting. Empty means we do not know it yet and should not pretend to.
    var name: String {
        get { access(keyPath: \.name); return _name }
        set { withMutation(keyPath: \.name) { _name = newValue; defaults.set(newValue, forKey: Key.name) } }
    }

    /// Whether holding the brain button opens conversational voice mode.
    var voiceOnHold: Bool {
        get { access(keyPath: \.voiceOnHold); return _voiceOnHold }
        set { withMutation(keyPath: \.voiceOnHold) { _voiceOnHold = newValue; defaults.set(newValue, forKey: Key.voiceOnHold) } }
    }

    /// Whether first-run setup has been completed or skipped.
    var hasOnboarded: Bool {
        get { access(keyPath: \.hasOnboarded); return _hasOnboarded }
        set { withMutation(keyPath: \.hasOnboarded) { _hasOnboarded = newValue; defaults.set(newValue, forKey: Key.onboarded) } }
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        // `bool(forKey:)` cannot distinguish "false" from "never set", so the first launch is
        // detected explicitly rather than silently defaulting everyone to light.
        self._dark = defaults.object(forKey: Key.dark) as? Bool ?? true
        self._name = defaults.string(forKey: Key.name) ?? ""
        self._voiceOnHold = defaults.object(forKey: Key.voiceOnHold) as? Bool ?? true
        self._hasOnboarded = defaults.bool(forKey: Key.onboarded)
    }

    /// How the greeting addresses the owner — **first name only**.
    ///
    /// People are greeted by the name their friends use, not by what is on their passport: "what
    /// should happen, Emil?" reads like a person talking, "what should happen, Emil Shirokikh-
    /// Barragan?" reads like a bank letter. Derived rather than stored, so it works for any user
    /// without asking them to enter their name twice.
    ///
    /// Takes the first whitespace-separated word and leaves it otherwise untouched — hyphenated and
    /// multi-part given names stay intact, because splitting on a hyphen would turn "Jean-Luc" into
    /// "Jean" and "Ana-Sofía" into "Ana".
    var greetingName: String {
        let first = name
            .split(whereSeparator: \.isWhitespace)
            .first
            .map(String.init) ?? ""
        return first.isEmpty ? "you" : first
    }
}
