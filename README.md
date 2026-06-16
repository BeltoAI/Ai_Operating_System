# SlyOS

An agent-native phone experience for Android. When you open it, there's no app grid —
just one prompt: **"what should happen?"** The agent is the home screen, the notification
layer, the people layer, the memory layer, and the system interface. Real apps become tools
the agent calls. A manual fallback always exists.

SlyOS runs as a **custom launcher** on a stock, **locked** phone — no root, no bootloader
unlock, no exploits. Everything it does uses documented, sanctioned Android APIs and is
reversible by changing your default Home app or uninstalling.

---

## Why a launcher (and not a flashed OS)

The reference device is a Samsung Galaxy S25 (`SM-S931U`, US Snapdragon). Its bootloader is
locked with OEM-unlock fused off (`flash.locked=1`, `oem_unlock_allowed=0`, verified boot
`green`). There is **no official way to flash it**, so a true custom system image / kernel
is impossible on this unit without exploits — which this project does not do.

Instead, SlyOS becomes the phone's launcher and uses sanctioned capabilities to feel like an
OS layer:

- A launcher activity registered for `HOME` replaces the stock launcher.
- Optional **Device Owner** provisioning (via `adb dpm`, on a freshly-reset device) enables
  kiosk / lock-task, app hiding, and notification-shade suppression — no root.
- A **NotificationListenerService** (user-granted) reads notifications and can reply through
  their built-in RemoteInput action.

For a literal flashed OS / custom kernel, the portable path is AOSP Cuttlefish or an
officially-unlockable Pixel — documented under `docs/` — never this locked S25.

---

## Features

- **Agent home.** Type or speak; a cloud LLM (your Anthropic key) understands the request and
  executes one or more actions: open an app, web search, dial, send an SMS, take a photo,
  open settings, create a calendar event, set a timer/alarm, add a checklist item, or compose
  a social post. Multi-step requests run in order.
- **Real notification triage.** The Now and People tabs show your actual notifications.
  Repliable messages (WhatsApp, Messages, Signal, Gmail…) get an agent-drafted reply you
  review and send, or an autonomous auto-reply (opt-in, with an undo window and self-echo
  guard). Any message can become a calendar event.
- **Memory graph.** A native, pannable/zoomable graph of your real memories — people you've
  messaged, facts the agent learned, checklist items — with natural-language search (ask a
  question, get an answer over your memories), node details, source traceability, and delete.
- **Checklist.** A persistent to-do list, reachable from the nav and Manual Mode, that the
  main agent can add to ("add milk and eggs to my to-dos").
- **Social post composer.** Take photos, have the agent write a platform-styled caption from
  what it sees, preview it, edit, and hand off to the real app to publish (caption copied to
  clipboard for apps that drop pre-filled text).
- **Calendar awareness.** Reads your schedule to answer "am I free Friday" and creates events.
- **The Architect (Opus 4.8).** Long-press the SlyOS wordmark for a hidden console. Describe an
  app; Opus writes a self-contained mini-app that SlyOS stores and runs live in a sandboxed
  WebView. Create tools on top of SlyOS by prompting — no rebuild.
- **Time-saved metric, personalization memory, brief.** Tracks rough minutes saved; an
  editable "about you" personalizes every reply; the lock screen can summarize what matters.

---

## Repository layout

```
agentos/
  README.md                 you are here
  docs/                     device facts, safety, architecture, UX spec, kernel notes
  android/                  the Android app (Gradle project)
    AgentShell/             the launcher app
      src/main/AndroidManifest.xml
      src/main/java/com/agentos/shell/
        ShellActivity.kt            single-activity screen state machine
        AgentNotificationListener.kt notification capture + auto-reply
        screens/                    Home, Lock, Now, People, Memory, Checklist,
                                    Manual, Compose (posts), Architect, AppView, …
        tools/                      AgentClient (LLM), ToolRouter (actions),
                                    NotificationStore, MemoryStore, MemoryGraphStore,
                                    CalendarTool, ContactsTool, ChecklistStore,
                                    AppStore, PdfTool, ImageUtil, MetricsStore
      build.gradle.kts
    settings.gradle.kts, build.gradle.kts, gradle/
  scripts/                  device interrogation, Device Owner provisioning, build notes
  kernel/                   portable-track custom-kernel demo (Cuttlefish/Pixel only)
```

---

## Build & install

Requirements: a Mac or Linux machine, an Android phone with USB debugging on, and an
Anthropic API key (https://console.anthropic.com).

1. Tools (macOS example):
   ```bash
   brew install --cask temurin@17 android-commandlinetools android-platform-tools
   ```
2. Add your API key (git-ignored, never committed):
   ```bash
   echo 'ANTHROPIC_API_KEY=sk-ant-...' > agentos/android/apikey.properties
   ```
3. Build the APK:
   ```bash
   cd agentos/android
   ./gradlew :AgentShell:assembleDebug
   # → AgentShell/build/outputs/apk/debug/AgentShell-debug.apk
   ```
   (Or open `agentos/android` in Android Studio and press Run.)
4. Install and set as launcher:
   ```bash
   adb install -r AgentShell/build/outputs/apk/debug/AgentShell-debug.apk
   adb shell cmd notification allow_listener com.agentos.shell/com.agentos.shell.AgentNotificationListener
   ```
   Press Home on the phone → choose SlyOS → Always. Reboot to confirm.

To revert: Settings → Apps → Default apps → Home → One UI Home, or `adb uninstall com.agentos.shell`.

A one-shot helper script lives at the repo root: `build_and_install.sh`.

---

## Permissions (all user-granted, runtime)

`INTERNET` (LLM), `RECORD_AUDIO` (voice), `READ/WRITE_CALENDAR`, `READ_CONTACTS`, `SEND_SMS`,
and notification access (granted in Settings or via the adb command above). Nothing is sent
off-device except prompts you trigger, using your own API key.

---

## Safety

See `docs/flashing_safety.md`. Short version: this project never flashes, unlocks, or writes
partitions on the locked S25, and never bypasses Knox / verified boot / FRP / secure boot.
Everything is reversible. Auto-reply is off by default and reviewable.

---

## Honest limitations

- It's a launcher, not a flashed OS — the power-on boot logo stays Samsung's.
- The Architect's mini-apps are real and live but sandboxed; they can't yet reach your
  SlyOS data or change SlyOS's native screens. True native self-modification needs a rebuild
  (the locked phone can't compile itself).
- Memory is built from notifications currently held by the listener plus learned facts and the
  checklist; long-term history persistence is a roadmap item.
- Direct social posting hands off to each platform's app for the final tap; true one-tap
  publishing needs per-platform OAuth APIs.

---

## Roadmap

Long-term memory persistence · richer entity extraction from transcripts · background
proactive briefs · giving Architect mini-apps access to agent tools · per-platform posting
APIs · contacts-aware multi-step actions.

---

## License

Personal project. Add a license of your choice (e.g. MIT) before distributing.
