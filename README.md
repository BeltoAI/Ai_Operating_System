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
`green`). There is **no official way to flash it**, so a custom system image / kernel is
impossible on this unit without exploits — which this project does not do.

Instead, SlyOS becomes the phone's launcher and uses sanctioned capabilities to feel like an
OS layer: a `HOME` launcher activity, an optional Device Owner provisioning for kiosk, and a
user-granted `NotificationListenerService` that reads notifications and replies through their
built-in inline-reply actions. For a literal flashed OS / custom kernel, the portable path is
AOSP Cuttlefish or an officially-unlockable Pixel (see `docs/`), never the locked S25.

---

## Features

- **Agent home.** Type or speak; an LLM (your Anthropic key) understands the request and runs
  one or more actions in order: open an app, web search, dial, send an SMS to a contact, take a
  photo, open settings, create a calendar event, set a timer/alarm, add a checklist item,
  compose a social post, or open the Architect. Replies render in a clean card; it can suggest
  facts to remember.
- **Camera + vision on Home.** Snap a photo and ask about it, or say "save as PDF" to turn
  photos into a PDF.
- **Conversation-aware replies.** Incoming messages (WhatsApp, Telegram, SMS, Signal…) are kept
  per contact and persisted. When the agent replies — reviewed or autonomous — it sends the
  whole thread for that person, so it holds real, contextual conversations. Optional autonomous
  mode auto-replies after an undo window, with a strict self-echo guard so it never answers
  itself. Best-effort image understanding when a picture is in the notification.
- **Telegram Document Q&A.** Load a PDF; SlyOS extracts its text and auto-answers Telegram
  messages using only that document (focused retrieval, so huge PDFs work).
- **Memory graph.** A native, pannable/zoomable graph built from your real data — people you've
  messaged and their message chains, facts the agent learned, checklist items, and your
  prompts/replies. Natural-language "Ask" answers over your memories; tap a node for details and
  source; Forget to delete.
- **Checklist.** Persistent to-do list (in Manual Mode) the agent can add to ("add milk and eggs
  to my to-dos").
- **Social post composer.** Take photos → the agent writes a platform-styled caption from what
  it sees → themed LinkedIn/Instagram preview → edit by prompt ("make it punchier") → post.
- **Spicy takes.** Generate savage-but-constructive tech posts (Opus) for X and Reddit
  (Reddit gets a headline + long body), edit by prompt, and post. Optional **daily** morning
  draft delivered as a notification with one-tap Post-to-X / Post-to-Reddit. Real X API posting
  if you add keys; otherwise a one-tap hand-off to the app.
- **The Architect (Opus 4.8).** Long-press the SlyOS wordmark for a hidden console. Describe an
  app; Opus writes a self-contained mini-app that SlyOS stores and runs live in a sandboxed
  WebView. Build tools on top of SlyOS by prompting — no rebuild.
- **Calendar awareness, personalization memory, time-saved metric, lock-screen brief.**

---

## Repository layout

```
agentos/
  README.md                 you are here
  docs/                     device facts, safety, architecture, UX spec, kernel notes
  android/                  the Android app (Gradle project)
    AgentShell/src/main/
      AndroidManifest.xml
      java/com/agentos/shell/
        ShellActivity.kt            single-activity screen state machine
        AgentNotificationListener.kt notification capture + contextual / doc auto-reply
        SpicyWorker.kt, SpicyScheduler.kt  daily spicy-take notification (WorkManager)
        screens/    Home, Lock, Now, People, MemoryGraph, MemorySettings, Checklist,
                    Manual, Compose (posts), SpicyPost, Architect, AppView, ReplyCard, …
        tools/      AgentClient (LLM + Architect + spicy + revise + doc QA),
                    ToolRouter (actions), NotificationStore, ConversationStore,
                    MemoryStore, MemoryGraphStore, MemoryLog, KnowledgeStore (PDF),
                    CalendarTool, ContactsTool, ChecklistStore, AppStore, TwitterClient,
                    PdfTool, ImageUtil, MetricsStore, BriefStore
      build.gradle.kts
    settings.gradle.kts, build.gradle.kts, gradle/
  scripts/                  device interrogation, Device Owner provisioning, build notes
  kernel/                   portable-track custom-kernel demo (Cuttlefish/Pixel only)
  build_and_install.sh      one-shot build + install helper (run from project root)
```

---

## Build & install

Requirements: a Mac/Linux machine, an Android phone with USB debugging on, and an Anthropic
API key (https://console.anthropic.com).

1. Tools (macOS): `brew install --cask temurin@17 android-commandlinetools android-platform-tools`
2. Keys (git-ignored, never committed) in `agentos/android/apikey.properties`:
   ```
   ANTHROPIC_API_KEY=sk-ant-...
   # optional, for direct X posting:
   TWITTER_API_KEY=...
   TWITTER_API_SECRET=...
   TWITTER_ACCESS_TOKEN=...
   TWITTER_ACCESS_SECRET=...
   ```
3. Build + install: `bash build_and_install.sh` (or open `android/` in Android Studio → Run).
4. Press Home on the phone → choose SlyOS → Always. Grant notification access when prompted.

Revert anytime: Settings → Apps → Default apps → Home → One UI Home, or `adb uninstall com.agentos.shell`.

---

## Permissions (all user-granted)

INTERNET, RECORD_AUDIO (voice), READ/WRITE_CALENDAR, READ_CONTACTS, SEND_SMS, POST_NOTIFICATIONS,
and notification access. Nothing leaves the device except prompts you trigger, using your own keys.

---

## Safety

Never flashes, unlocks, or writes partitions on the locked S25; never bypasses Knox / verified
boot / FRP. Everything is reversible. Autonomous reply and daily posting are opt-in. See
`docs/flashing_safety.md`.

---

## Honest limitations

- It's a launcher, not a flashed OS — the power-on boot logo stays Samsung's.
- Architect mini-apps are real and live but sandboxed; they can't yet reach SlyOS data or
  change SlyOS's native screens. Native self-modification needs a rebuild.
- Conversation/Document features depend on each app exposing an inline reply action in its
  notification (WhatsApp/Telegram do; X/Reddit often don't), and learn going forward — they
  can't import an app's private history retroactively.
- Document Q&A needs a text-layer PDF (scanned/image PDFs need OCR, not yet added).
- X's API write tier is paid as of Feb 2026; the no-cost path hands off to the app for a one-tap post.

---

## License

Personal project. Add a license of your choice (e.g. MIT) before distributing.
