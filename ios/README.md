# SlyOS for iPhone

Native SwiftUI, in `SlyOSCompanion/`.

This replaces the earlier WebKit approach in `platforms/apple/SlyOSNative`, which wrapped the
desktop shell's web build in a `WKWebView`. That could never match the Compose design — it would
only ever match whatever the web app looked like — and App Review treats thin web wrappers as
minimum-functionality rejections under Guideline 4.2.

Design tokens are shared with the Android app in `MADSCIENTIST/agentos`: the Kotlin
`theme/Tokens.kt` is the source of truth, mirrored in `SlyTheme.swift` and in
`shared/design-tokens/tokens.json`. Change a colour in one, change it in all three, or the two
phones stop looking like the same product.

## Building

The Xcode project is generated from `project.yml`:

```bash
brew install xcodegen
cd platforms/ios/SlyOSCompanion && xcodegen generate && open SlyOS.xcodeproj
```

Minimum iOS 17 — `@Observable` and the SF Symbols 5 glyphs both require it.

## What it needs before it does anything

None of this is in the repo, because none of it is ours to ship:

| Thing | Where it goes | Without it |
|---|---|---|
| **AI provider key** | Settings → Intelligence, in the app | Nothing answers. Groq, Gemini, Cerebras and Mistral have free tiers |
| **Google iOS OAuth client id** | `project.yml` → `INFOPLIST_KEY_GoogleOAuthClientID` | No Calendar, Meet or Gmail. Must be an **iOS** client — Google ties the redirect scheme to the client type, so the Android id is rejected |
| **Apple Team ID** | `project.yml` → `DEVELOPMENT_TEAM` | Simulator only; cannot install on a device or submit |
| **App icon** | `Assets.xcassets` | The App Store rejects the build |

Tokens live in the Keychain, never `UserDefaults` — a refresh token is a long-lived key to
someone's mail and calendar, and `UserDefaults` ends up in unencrypted backups.

## What the brain is made of

`SlyStore` is SQLite with a real FTS5 index, filled by:

- **Contacts** and **Calendar** — on-device, no account needed (`Importers.swift`)
- **Look mode** — camera or photo, read on-device with Vision (`LookMode.swift`)
- **Anything typed or spoken**, from Home or Siri
- **Gmail and Google Calendar**, once the OAuth client id is set

Two Android bugs are fixed here by construction, with comments explaining why: integers are bound as
integers (SQLite's type affinity sorts every integer below every string, so a numeric comparison
bound as TEXT is silently always false, which returned zero rows against a 24,000-message table),
and search ranks by **who** rather than by **when**.

## What iOS cannot do

Three things the Android build does are impossible here at any entitlement level:

- **Read other apps' notifications.** There is no `NotificationListenerService` equivalent. This is
  why the Android brain fills itself from WhatsApp, Instagram and LinkedIn and this one cannot.
- **Replace the home screen.** iOS has no launcher concept.
- **Operate the phone for you.** No accessibility automation of other apps.

The Powers panel states all three outright rather than quietly omitting them, and the brain's empty
state lists only sources iOS can genuinely provide.

Reply drafting is still reachable, one tap away rather than automatically — via a keyboard
extension, the share sheet, or screenshot OCR. Those are not built yet.

## Permissions

Declared in `project.yml`, requested at the point of need in `Permissions.swift`, and each one backs
a feature that visibly uses it. Requesting permissions the app does not obviously use is a
Guideline 5.1.1 rejection, so the Powers panel exists partly to make each grant's purpose visible.

Speech recognition is forced on-device wherever the language supports it. The default recogniser
uploads audio to Apple, which would contradict the entire premise of the product.
