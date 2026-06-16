# SlyOS

An agent-native phone experience for Android. When you open it, there's no app grid —
just one prompt: **"what should happen?"** The agent is the home screen, the notification
layer, the memory layer, and the system interface. Real apps become tools the agent calls.
A manual fallback always exists.

SlyOS runs as a **custom launcher** on a stock, **locked** phone — no root, no bootloader
unlock, no exploits. Everything uses documented Android APIs and is reversible by changing
the default Home app or uninstalling.

---

## Why a launcher (not a flashed OS)

Reference device: Samsung Galaxy S25 (`SM-S931U`, US Snapdragon) — bootloader locked, OEM
unlock fused off, verified boot enforcing. No official flash path, so a custom system
image/kernel is impossible without exploits (not done here). Instead SlyOS is the launcher and
uses sanctioned capabilities to feel like an OS layer. A true flashed OS / kernel belongs on
AOSP Cuttlefish or an unlockable Pixel (see `docs/`), never the locked S25.

---

## Features

- **Agent home.** Type or speak ("what should happen?"). An LLM understands the request and
  runs one or more actions in order: open apps, web search, dial, SMS a contact, take a photo,
  settings, create calendar events, timers/alarms, add checklist items, compose social posts,
  spicy posts, **write a research paper**, or open the Architect. Replies render in a card and
  can be **spoken aloud** when you used voice (on-device TTS). Camera-on-Home for vision Q&A
  and "save as PDF."
- **Conversation-aware replies.** Incoming messages (WhatsApp, Telegram, SMS, Signal…) are kept
  per contact and persisted; the agent replies with full thread context, in your voice, with a
  review step. Optional autonomous mode (undo window + self-echo guard) that covers **every**
  app exposing a reply action. A **night schedule** can force auto-reply on during a window you
  pick (default 8 PM–6 AM); outside it, your toggle is the default. Email (Gmail) is always
  review-only and bot-filtered.
- **Telegram bot brain.** A foreground service runs your Telegram bot: it reads images
  (vision) and PDFs (ingested as knowledge), answers from your document or conversationally,
  and replies — through Telegram's open Bot API, no notification limits.
- **Document Q&A.** Load a PDF; SlyOS answers from it (Telegram or in-app), with chunked
  retrieval for big documents.
- **Memory graph.** A clean, Obsidian-style native graph built from real data — people +
  message counts, learned facts, checklist, research papers, recent prompts. Pan/zoom, tap a
  node for details + recent messages, **Ask** (natural-language Q&A over memory) which lights
  up the **synapse path** of memories behind the answer, and **Forget** to delete.
- **Research workspace.** Multi-paper library (create / open / delete). Opus writes a full
  academic paper in your name — abstract, numbered sections, references, real equations
  (MathJax). Toggle **web research** (Anthropic web-search tool, with citations) and **use your
  PDF** as source. Edit by prompt or by hand, **Export PDF** (print) or **Share** the PDF to any
  app. A daily Opus **usage cap** guards credits.
- **Social.** Photo post composer (themed LinkedIn/Instagram preview, edit-by-prompt). Spicy
  tech takes for X/Reddit (platform-tuned), optional daily morning draft as a notification with
  one-tap Post-to-X / Post-to-Reddit. Real X API posting if keys are set.
- **Outreach.** Import a CSV of your own recipients → personalized, opt-out-friendly drafts,
  each reviewed and sent by you.
- **The Architect (Opus).** Long-press the wordmark → describe an app → Opus builds a
  self-contained mini-app that runs live in a sandboxed WebView.
- **Total recall (opt-in).** An owner-granted Accessibility service reads on-screen text across
  your apps into a private, on-device searchable log; the agent pulls from it to answer recall
  questions ("what did Anna say about the deck?"). Passwords/secure fields are never captured,
  the log is capped and stays on the phone, and it's fully gated by an in-app toggle.
- **Per-app auto-reply.** A live list of your installed messaging/social apps (WhatsApp,
  Telegram, Instagram, X, Reddit, SMS, Signal, Messenger, Discord…) each with its own switch.
- **Identity guard.** Every outward message — Telegram, WhatsApp/notification replies, comment
  clapbacks, emails, outreach — is sent **as you**, in your name (pulled from memory). The agent
  never breaks character, never announces it's an AI / Claude / a bot, and never "corrects the
  record" if someone mixes it up — it just replies the way you would.
- **Checklist, calendar awareness, personalization memory, time-saved metric, AI lock-screen
  brief, SlyOS lock-screen wallpaper.**

---

## Repository layout

```
agentos/
  README.md
  docs/        device facts, safety, architecture, UX spec, kernel notes
  android/AgentShell/src/main/
    AndroidManifest.xml
    java/com/agentos/shell/
      ShellActivity.kt              screen state machine
      AgentNotificationListener.kt  notification capture + contextual/doc auto-reply
      TelegramService.kt            Telegram bot (foreground service)
      SpicyWorker.kt / SpicyScheduler.kt   daily spicy-take notification
      screens/   Home, Lock, Now, MemoryGraph, MemorySettings, Research, Checklist,
                 Manual, Compose, SpicyPost, Outreach, Architect, AppView, ReplyCard …
      tools/     AgentClient (LLM: chat, actions, vision, paper-writer w/ web search,
                 architect, spicy, revise, doc-QA), ToolRouter, NotificationStore,
                 ConversationStore, MemoryStore, MemoryGraphStore, MemoryLog,
                 KnowledgeStore (PDF), PaperStore, CalendarTool, ContactsTool,
                 ChecklistStore, EmailDraftStore, AppStore, TwitterClient, TelegramClient,
                 PdfTool, ImageUtil, WallpaperTool, UsageLimiter, MetricsStore, BriefStore
    build.gradle.kts ; settings.gradle.kts ; build.gradle.kts ; gradle/
  scripts/     device interrogation, Device Owner provisioning, build notes
  kernel/      portable-track custom-kernel demo (Cuttlefish/Pixel only)
  build_and_install.sh
```

---

## Build & install

1. macOS: `brew install --cask temurin@17 android-commandlinetools android-platform-tools`
2. Keys in `agentos/android/apikey.properties` (git-ignored):
   ```
   ANTHROPIC_API_KEY=sk-ant-...
   TELEGRAM_BOT_TOKEN=...        # optional, from @BotFather, for the bot
   TWITTER_API_KEY=... etc.      # optional, for direct X posting
   ```
3. `bash build_and_install.sh` (or open `android/` in Android Studio → Run).
4. Phone: press Home → choose SlyOS → Always; grant notification access when asked.

Revert: Settings → Apps → Default apps → Home → One UI Home, or `adb uninstall com.agentos.shell`.

---

## Permissions (all user-granted)

INTERNET, RECORD_AUDIO, READ/WRITE_CALENDAR, READ_CONTACTS, SEND_SMS, POST_NOTIFICATIONS,
SET_WALLPAPER, FOREGROUND_SERVICE(+DATA_SYNC), notification access. Nothing leaves the device
except prompts you trigger, using your own keys.

---

## Honest limitations

- It's a launcher, not a flashed OS — the power-on boot logo stays Samsung's.
- Can't read other apps' private files (WhatsApp media, voice notes) or pick up cellular calls
  — OS-blocked on a locked phone. Telegram works fully via its open bot API.
- Email/social auto-send isn't possible without each platform's API; SlyOS drafts and hands off
  for a one-tap send (X has a real-API path if keys are set).
- The Architect's mini-apps are sandboxed (no access to SlyOS data yet).
- Document Q&A needs a text-layer PDF (scanned PDFs need OCR, not yet added).
- True LaTeX typesetting needs a TeX engine; papers render via HTML+MathJax → PDF.
- The memory "synapse path" is reconstructed by relevance matching, not a token-level trace.

---

## License

**Proprietary — all rights reserved.** See [LICENSE](LICENSE). No permission is granted to
use, copy, modify, or distribute this software without a written agreement. To request a
license: eshir010@ucr.edu
