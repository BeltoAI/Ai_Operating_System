<div align="center">

# SlyOS

### What if we gave AGI a phone?

**SlyOS turns your Android into a single agent** — it answers every message in your voice, runs your apps, and builds a memory that's quietly becoming *you*. No app grid. Just one prompt: **"what should happen?"**

[**🌐 slyos.world**](https://slyos.world) · [Download](https://slyos.world/#get) · [Vision](https://slyos.world/#vision)

![platform](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)
![models](https://img.shields.io/badge/models-Claude%20·%20GPT%20·%20Gemini%20·%20on--device-E8642C)
![cost](https://img.shields.io/badge/~255%20tokens%2Freply-runs%20near--free-2E9E5B)
![license](https://img.shields.io/badge/license-MIT-blue)

</div>

---

> **📽️ Demo:** _drop a 30–60s screen recording here as `docs/demo.gif`._ This is the single biggest thing that earns stars — show: open SlyOS → "text Anna I'm running late" (drafts in your voice) → point the camera at a shoe (it names it) → "invest $1000" (it builds a portfolio). One take, no cuts.

## The idea

Your phone is the most personal computer you own — and it's still a grid of icons you operate by hand. SlyOS replaces the launcher with **one agent that IS the phone**. Every message in and every reply out flows through a local **memory brain** that learns how you write, what you care about, and who matters — so over time it doesn't just *assist* you, it starts to *be* you.

It runs as a normal **custom launcher** on a stock, **locked** phone. No root, no bootloader unlock, no exploits — just documented Android APIs, and a manual fallback that's always one tap away.

## What it actually does

| | |
|---|---|
| 💬 **Answers your messages in your voice** | Reads notifications across WhatsApp, Telegram, SMS, email — drafts (or auto-sends) replies that sound like you, because it learned from your real chats. |
| 🧠 **A brain that becomes you** | Every conversation, contact, email and note flows into a local memory + semantic index. Ask it anything about your life; it answers from *your* data. |
| 📷 **Look** | Point the camera at anything — a shoe, a landmark, a dish — tap the object, and it identifies it with one-tap shop / map / search. |
| 📈 **Practice investing** | "Invest $1000" → it designs a real portfolio (stocks, ETFs, gold, crypto), you confirm the buy, and it tracks live performance with a value graph and daily news alerts. |
| 🎯 **Missions & outreach** | "Find 10 buyers for my product" → it web-finds real people, drafts tailored messages with your Calendly, and tracks replies. |
| 💼 **Job hunt** | "Find me a job at X" → tailored résumé + cover letter + outreach email, each a real PDF, ready to send. |
| 📄 **Research → published** | Writes real papers and one-tap publishes to Zenodo with a DOI. |
| 🛠️ **Cowork** | An on-device agent workspace that builds real files and, with Termux, runs them — Python, Node, even a local llama.cpp model. Edit SlyOS from inside SlyOS. |
| 💸 **Ruthlessly cheap** | Routes everyday work to free Gemini / on-device, reserving Claude & GPT for the hard stuff — about **255 tokens per reply**, a fraction of a typical agent. |

## How it works

```
every message in  ─►  ┌───────────────┐  ─►  Claude · GPT · Gemini · on-device
  WhatsApp, SMS,      │   the brain   │       (router picks by cost/quality)
  email, X, camera ─► │ memory · you  │  ─►  every reply out — in your voice
                      └───────────────┘
```

One brain, every model. The memory and persona are assembled by SlyOS and passed **identically** to whichever model runs — so the character is the same on Claude, GPT, or a local model. The router only decides cost, quality, and capability.

## Quick start

Works on most **Android 10+** phones. You install once from a computer over USB; after that it just runs. Nothing is flashed — it's a normal app you can uninstall anytime.

```bash
git clone https://github.com/BeltoAI/Ai_Operating_System.git
cd Ai_Operating_System/agentos/android
./gradlew :AgentShell:assembleDebug
adb install -r AgentShell/build/outputs/apk/debug/AgentShell-debug.apk
```

Then on the phone: open SlyOS, paste an API key (a free **Gemini** key runs the whole thing at ~$0), and set it as your Home app. The full click-by-click guide is [below](#detailed-setup) and at [slyos.world/#get](https://slyos.world/#get).

## Privacy & safety (read this)

SlyOS is powerful because it can see your notifications and screen — so let's be clear:

- **Everything stays on your device.** The memory brain is a local SQLite database. Nothing is uploaded except the specific prompt you send to the model provider *you* configured.
- **Permissions are opt-in and reversible.** Notification access, accessibility (screen reading), contacts, SMS — each is granted by you and revocable anytime. It never captures password fields.
- **It asks before consequential actions.** Sending a message, posting publicly, or spending money is always confirmed — auto-send is opt-in, per-app, and rate-limited.
- **No root, no exploits, fully reversible.** Change your default Home app or uninstall, and your phone is exactly as it was. Your keys live only on the device.

## Tech

Kotlin · Jetpack Compose · SQLite (FTS4 + a local vector index) · CameraX + ML Kit · Android Accessibility & Notification services · multi-provider LLM routing (Anthropic / OpenAI / Gemini / local) · WorkManager.

## Roadmap

- [x] Agent home screen, memory brain, voice-matched replies
- [x] Camera Look, practice investing, missions, job hunt, research → Zenodo
- [x] On-device Cowork + Termux execution
- [ ] Real-time phone-call answering in your cloned voice
- [ ] On-device action layer (tap-through any app, with confirmation)
- [ ] Train a local model to replicate you

## Contributing

Issues and PRs welcome. If SlyOS resonates, a ⭐ genuinely helps — it's how people find it.

**License:** MIT © Belto

---

<h2 id="detailed-setup">Detailed setup (Samsung, step-by-step, no experience needed)</h2>

This works on most Samsung phones running **Android 10 or newer** (Galaxy S20/Note 20 and up).
You'll need a **Mac**, your **phone + its USB cable**, and about 30 minutes. You install it once
from the Mac; after that it just runs on the phone. Nothing is flashed — it's a normal app you
can uninstall anytime.

**1. Install the basics on the Mac (one time).** Open the **Terminal** app and paste these:
```bash
# Homebrew (skip if you already have it):
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
# Java 17 (needed to build):
brew install --cask temurin@17
# Git (skip if you have it):
brew install git
```

**2. Download the project.**
```bash
cd ~/Downloads
git clone https://github.com/BeltoAI/Ai_Operating_System.git
cd Ai_Operating_System/agentos
```

**3. Add your AI key.** The agent needs an Anthropic API key (this is what makes it think).
   - Get one at **https://console.anthropic.com** → API Keys → Create Key (starts with `sk-ant-`).
   - Then create your keys file:
```bash
cp android/apikey.properties.example android/apikey.properties
open -e android/apikey.properties          # opens TextEdit — paste your key after ANTHROPIC_API_KEY=
```
   Save and close. (This file is private and never uploaded.)

**4. Turn on developer mode on the phone (one time).**
   - Settings → **About phone** → **Software information** → tap **Build number** 7 times (it says "You're a developer").
   - Go back to Settings → **Developer options** → turn on **USB debugging**.

**5. Plug the phone into the Mac** with the cable. On the phone, a popup asks "Allow USB
   debugging?" — tap **Allow** (check "always allow from this computer").

**6. Build and install — one command:**
```bash
bash build_and_install.sh
```
   First run takes a while (it downloads the Android tools). When it finishes, on the **phone**
   press the **Home** button and choose **SlyOS → Always**. That's it — SlyOS is now the home screen.
   *(To go back to normal anytime: Settings → Apps → Default apps → Home app → One UI Home. Or
   `adb uninstall com.agentos.shell`.)*

**7. Grant the permissions that make it powerful** (on the phone, Settings):
   - **Notifications access:** Settings → Notifications → Advanced/Special access → **Notification access** → turn on **SlyOS**. (Lets it read & reply to messages.)
   - **Total recall (optional):** open SlyOS → Brain → About → turn on **Total recall**, tap **Grant Accessibility**, enable SlyOS there. (Lets it remember what's on screen.)
   - **Battery:** Settings → Apps → SlyOS → Battery → **Unrestricted** (so it keeps working in the background).

**8. Make it yours.** Open SlyOS → **Brain → About**, and write a few lines about who you are,
   how you text, your work, and (optional) paste your **Calendly/booking link**. The more it knows,
   the more it sounds like you.

---

## Already have SlyOS? Update to the latest version

If a friend installed SlyOS before and you've pushed new changes, here's how they pull the
update onto their phone. **Your settings, memory, imported chats, and API key all stay** —
updating is just reinstalling the app over the old one.

1. Plug the phone into the Mac (USB debugging on, tap **Allow** if it asks).
2. In Terminal:
```bash
cd ~/Downloads/Ai_Operating_System/agentos   # wherever you cloned it
git pull                                       # get the newest code
bash build_and_install.sh                      # rebuild + reinstall over the old app
```
3. On the phone, press **Home → SlyOS → Always** if it asks again. Done — same data, new features.

*Notes:* your `apikey.properties` is never touched by `git pull` (it's private to your machine).
If `git pull` ever complains about local changes, run `git stash` first, then `git pull`. If the
app refuses to install over the old one, `adb uninstall com.agentos.shell` then re-run the build
(this clears on-device data, so only do it as a last resort).

---

## Share it with friends (no terminal for them)

You build once on your Mac and hand friends a single file — they don't touch a terminal, and they
never need your keys (they paste their own Anthropic key on first launch).

```bash
cd ~/Downloads/MADSCIENTIST/agentos
bash package_apk.sh
```

This produces **`SlyOS-latest.apk`** in the `agentos` folder, built with **no keys inside it** (your
`apikey.properties` is moved aside during the build, then restored). Send that APK to anyone
(AirDrop, email, Drive). They:

1. Open it on their Android phone and allow "install unknown apps" when prompted.
2. On first launch, a setup wizard asks for their **own Anthropic API key** (from
   https://console.anthropic.com), then optionally their About, Calendly, Zenodo token, and their
   chat/LinkedIn imports.
3. Press **Home → SlyOS → Always**.

**Republishing after you add features is effortless:** just run `bash package_apk.sh` again and resend
`SlyOS-latest.apk`. The version number auto-bumps every build, so it installs as an update over the
old one. (Note: keep handing out builds from the *same Mac* — Android requires updates to be signed by
the same machine's key; a fresh install on a new phone is always fine.)

---

## The website & public download (slyos.world)

The landing page lives in **`docs/`** (`index.html`, `privacy.html`, `terms.html`, favicons, `og.png`).
It's a plain static site — no build step.

- **Hosting (Vercel):** import the repo in Vercel, set **Root Directory = `docs`**, framework **Other**, no
  build command. Custom domain **slyos.world** is pointed at Vercel via GoDaddy DNS (A `@` → Vercel IP,
  CNAME `www` → Vercel). It redeploys on every `git push`.
- **The download button → GitHub Releases.** Run **`bash publish_release.sh`** (needs `gh` CLI, one-time
  `gh auth login`). It builds the keyless APK and publishes it as the latest release named `SlyOS.apk`; the
  button links to the stable `releases/latest/download/SlyOS.apk`, so republishing = re-run the script.
  **The repo must be public** for that download to work without a login.
- **Live stats.** **`bash pull_stats.sh`** reads your real numbers off the connected phone (message DB +
  time-saved metrics) into `docs/stats.json`, which the page shows. Download count is a zero-setup hosted
  counter; the "what did you use it for today?" wall is backed by Supabase (a public *publishable* key sits
  in `index.html` — the secret key never does).

---

## Load your LinkedIn network (optional but powerful)

This lets SlyOS reach your whole LinkedIn network and draft personalized openers.

1. On a computer, go to LinkedIn → **Settings → Data privacy → Get a copy of your data** →
   check **Connections** (and **Messages** if you want it to know who you've already talked to,
   and **Profile** to auto-fill your About). Request it; LinkedIn emails you a `.zip` in a few
   minutes to a day. Unzip it — you'll get `Connections.csv`, `messages.csv`, `Profile.csv`.
2. Put those files on the phone. With the phone plugged in, from the `agentos` folder:
```bash
adb push ~/Downloads/Connections.csv /sdcard/Download/
adb push ~/Downloads/messages.csv   /sdcard/Download/
adb push ~/Downloads/Profile.csv    /sdcard/Download/
```
   *(Or just email the files to yourself and download them on the phone.)*
3. In SlyOS: **Now → Reconnect → "My network" → Import LinkedIn CSV**, and pick each file once.
   SlyOS detects what each one is. Then it lists everyone you've never messaged with a ready
   opener — tap **Copy → Open profile** to send.

---

## Adding API keys & tokens

SlyOS uses two kinds of keys. Know which goes where:

**A) Build-time keys — in `agentos/android/apikey.properties`** (private file, never uploaded,
git-ignored). After editing this file you must **rebuild** (`bash build_and_install.sh`) for
changes to take effect. Each person uses their **own** keys.
```
ANTHROPIC_API_KEY=sk-ant-...        # REQUIRED — the brain. https://console.anthropic.com
TELEGRAM_BOT_TOKEN=...              # optional — from @BotFather, enables the Telegram bot brain
TWITTER_API_KEY=...                # optional — the 4 X keys below enable direct posting to X
TWITTER_API_SECRET=...
TWITTER_ACCESS_TOKEN=...
TWITTER_ACCESS_SECRET=...
```
Start from the template: `cp android/apikey.properties.example android/apikey.properties`, then
`open -e android/apikey.properties` and paste your values after each `=`.

**B) In-app keys — typed on the phone, stored only on that device** (no rebuild needed):
- **Zenodo (publish papers):** Zenodo → account → **Applications → Personal access tokens →
  New token**, tick scopes **`deposit:write`** and **`deposit:actions`**, copy it. In SlyOS open a
  paper → **View paper → ↑ Zenodo** and paste the token once. After that, one tap publishes the
  paper (open-access, with auto-generated abstract + keywords) and returns a real **DOI**;
  re-publishing the same paper creates a **new version** of the same record.

> Security: never commit `apikey.properties` or paste live keys into chats/screenshots. If a key
> is ever exposed, revoke/regenerate it (Anthropic console / Zenodo applications / X portal).

---

## If something goes wrong
- **"adb: command not found"** → run `export PATH="/opt/homebrew/share/android-commandlinetools/platform-tools:$PATH"` then retry, or just re-run `bash build_and_install.sh`.
- **Phone shows "unauthorized"** → unlock the phone and tap **Allow** on the USB-debugging popup; run `adb devices` — it should say `device`, not `unauthorized`.
- **"JDK 17 missing"** → `brew install --cask temurin@17`.
- **"app not installed" / won't install** → make sure the phone is Android 10+; uninstall any old copy with `adb uninstall com.agentos.shell` and retry.
- **Build fails the first time** → run `bash build_and_install.sh` again (first run downloads dependencies and can time out).

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
  academic paper / white paper / investor memo in your name — abstract, numbered sections,
  references, real equations (MathJax), LaTeX-style typesetting. Works like the Claude web app:
  you **chat** with each paper ("add a chapter on…", "sharpen the abstract"), and it **replies**
  telling you what it wrote and **which web sources it used** (with URLs) — chatter never leaks
  into the document. Always web-researches with real cited links; can also use your loaded PDF as
  source. Full **version history** (restore any prior version). **Share** a clean, selectable-text
  PDF, or **publish to Zenodo** in one tap for a real DOI (see "Adding API keys"). A daily Opus
  **usage cap** guards credits.
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
  docs/        the slyos.world landing page (index/privacy/terms + assets) — deployed on Vercel
  build_and_install.sh    build + install to your own connected phone
  package_apk.sh          build a shareable, keyless SlyOS-latest.apk
  publish_release.sh      build + publish the APK as the latest GitHub Release (download button)
  pull_stats.sh           read real usage stats off the phone into docs/stats.json
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
