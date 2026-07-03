# SlyOS launch kit

Everything you need to fire a coordinated launch that spikes stars enough to hit GitHub Trending, then compounds. Post the platform sections **the same morning** (weekday, ~8:00–9:30am US Eastern is best for Hacker News). Copy is ready to paste; tweak the numbers/links to reality before posting.

> Rule of thumb: the launch itself is ~2 hours of your attention. The single biggest ranking factor after the post goes up is **how fast and thoughtfully you reply to every comment in the first 2 hours.** SlyOS drafts those for you (see "Automate the majority" at the bottom).

---

## 0. Pre-launch checklist (do these once, the day before)

- [ ] Repo **topics** set: `android`, `ai-agent`, `agent`, `llm`, `local-llm`, `kotlin`, `launcher`, `jetpack-compose`, `personal-assistant`. (Repo → About → gear → Topics.)
- [ ] Repo **description**: "An agent that IS your phone — answers every message in your voice, runs your apps, and builds a memory that's becoming you. Android."
- [ ] Repo **social preview image** set (Settings → General → Social preview) — use `docs/og.png`.
- [ ] README renders with the animated `docs/demo.svg` at the top. ✅ (done)
- [ ] A real **release** exists with the APK attached (`bash publish_release.sh`), so "Download" works.
- [ ] License present (MIT). ✅
- [ ] Pin the repo on your profile.
- [ ] Line up 5–10 friends who will genuinely star + comment in the first hour (real accounts, real interest — this seeds momentum without breaking any rules).

---

## 1. Hacker News — "Show HN"

**Title** (HN is allergic to hype — keep it plain and concrete):
```
Show HN: SlyOS – an agent that replaces your Android launcher and answers your texts
```
**URL:** `https://github.com/BeltoAI/Ai_Operating_System`

**First comment** (post this yourself immediately after submitting — it's where you tell the story):
```
I got tired of my phone being a grid of icons I operate by hand, so I built SlyOS: a custom
Android launcher where the home screen is just one prompt — "what should happen?" — and one
agent is the interface. It reads your notifications and drafts (or auto-sends) replies in your
voice, keeps a local memory of your conversations/contacts/emails, and can run multi-step tasks
across your apps.

A few things I cared about:
- Runs on a stock, locked phone. No root, no exploits — documented Android APIs only, reversible
  by changing the default Home app.
- Everything stays on device. The memory is a local SQLite DB; the only thing that leaves is the
  prompt you send to whichever model provider you configure (free Gemini works, or your own
  Claude/OpenAI key).
- It's lean — ~255 tokens per reply on average, because everyday work routes to cheap/free models.

It also does some fun stuff: point the camera at an object and it identifies it (on-device ML Kit
box) with one-tap shop/map; "invest $1000" builds a practice portfolio with live prices; a Cowork
mode that builds+runs real code via Termux.

It's early and rough in places. Honest about the tradeoffs: it needs notification + (optional)
accessibility permissions to be useful, which I know is a big ask — happy to go deep on how that's
scoped and why nothing is uploaded. Repo + build instructions in the README. Feedback very welcome.
```

---

## 2. r/LocalLLaMA (your best-fit community)

**Title:**
```
I built an Android launcher where one agent IS the phone — runs free on Gemini or a local model, ~255 tokens/reply
```
**Body:**
```
Open source, Kotlin/Compose. The home screen is a single prompt; the agent reads your
notifications, replies in your voice from a local memory brain, and routes each task to the
cheapest capable model — everyday stuff goes to free Gemini or on-device, and it only reaches for
Claude/GPT on heavy tasks. Averages ~255 tokens per reply.

Everything (the memory, the vector index, the persona) is local SQLite; the only thing that leaves
the device is the prompt you send to the provider you pick. No root, no exploits, reversible by
switching your Home app.

Roadmap includes training a local model on your own message history to actually replicate your
voice offline. Repo, build steps, and a demo are in the README — would love feedback from this
crowd specifically on the local-model direction.

[link]
```

---

## 3. r/androiddev

**Title:**
```
Show & tell: a custom launcher that replaces the app grid with a single agent prompt (Compose, no root)
```
Lead with the *engineering* here — how it hooks NotificationListenerService, the FTS4 + local vector index for memory, CameraX + ML Kit for the camera, and the multi-provider router. This crowd stars technical depth.

## 4. r/selfhosted

**Title:**
```
SlyOS: an on-device AI phone agent — local memory, your own API key, nothing uploaded
```
Emphasize the privacy/ownership angle and "runs on your own key / free Gemini." This crowd cares about data staying home.

## 5. r/SideProject

**Title:**
```
I gave AGI a phone — one agent that answers my texts, runs my apps, and is becoming "me"
```
Founder-story tone; the animated demo does the work.

---

## 6. Product Hunt

- **Name:** SlyOS
- **Tagline:** The phone where one agent is the whole interface
- **Description:** SlyOS replaces your Android launcher with a single agent that answers every message in your voice, runs your apps, and builds a local memory that's quietly becoming you. Runs free on Gemini or your own key. Open source.
- **First comment (maker):** short version of the HN story + "AMA about the on-device architecture."
- Launch at **12:01am PT**; line up hunters/friends to upvote through the morning.

---

## 7. X thread

```
1/ I gave AGI a phone.

SlyOS replaces your Android home screen with one agent. No app grid — just: "what should happen?"
It answers your texts in your voice, runs your apps, and builds a memory that's becoming you.

Open source 👇 [link]  [attach demo.gif]

2/ It reads your notifications and drafts (or auto-sends) replies that sound like you — because it
learned from your real chats. WhatsApp, Telegram, SMS, email, all through one brain.

3/ Point the camera at anything → it names it with a box (on-device) and one-tap shop/map.
Say "invest $1000" → it builds a live practice portfolio.
Say "find me a job at X" → résumé + cover letter + outreach, as PDFs.

4/ It's ruthlessly cheap: ~255 tokens per reply. Everyday work routes to free Gemini or on-device;
Claude/GPT only for the hard stuff. Runs on your own key. Nothing leaves the phone except the
prompt you send.

5/ No root, no exploits — a normal launcher on a locked phone, reversible anytime.
Repo + build steps: [link]
If it resonates, a ⭐ genuinely helps others find it.
```

---

## 8. Durable, semi-passive distribution (keeps trickling for months)

Open a PR / submit to each:
- **awesome-android** — https://github.com/JStumpp/awesome-android
- **awesome-agents / awesome-ai-agents** — search GitHub for the current top one
- **awesome-selfhosted** — https://github.com/awesome-selfhosted/awesome-selfhosted
- **awesome-llm-apps** — https://github.com/Shubhamsaboo/awesome-llm-apps
- Newsletters (submit your repo): **TLDR AI**, **Ben's Bites**, **Console.dev**, **Changelog News**.
- **alternativeto.net** and a **Hacker News "Launch"** follow-up if it does well.

---

## 9. Launch-day runbook (hour by hour)

1. **T-0 (8:00am ET):** submit Show HN + post your first comment. Fire the subreddits and the X thread within ~15 min of each other. Ping your 5–10 seed people.
2. **T+0 to T+2h:** camp the comments. Reply to **every** one within minutes — substantive, not defensive. This is the whole game for HN/Reddit ranking.
3. **T+2h:** post the PH maker comment; nudge hunters.
4. **Rest of day:** reply to everything, thank people, fix any "it won't build" issues fast and comment that you fixed it.
5. **Next day:** submit to the awesome-lists + newsletters. Post a short "we hit trending / X stars, here's what I learned" follow-up.

---

## 10. Automate the majority (with SlyOS itself)

You can't (and shouldn't) auto-post to HN/Reddit — it's against their rules and reads as spam. But the recurring effort is **replying and monitoring**, and you can automate that with SlyOS's ordinary features (nothing repo-specific baked into the app):

- **Reply drafting:** paste any HN/Reddit/PH comment into the SlyOS home prompt → it drafts a sharp, on-voice reply to approve and paste. During launch this is your superpower — keep up with 50 comments without burning out.
- **Build-in-public loop:** each time you ship, tell SlyOS "post a build-in-public update about X" → it writes it in your voice for X/Reddit. Consistent presence = a slow, compounding star trickle.
- **Daily thread finder:** ask SlyOS to "find threads about AI phones / agents / local LLMs worth weighing in on" → it web-searches and drafts a genuinely useful comment (with a repo link where it fits).
- **Scheduled check:** schedule a daily task in SlyOS like "check the star count on github.com/BeltoAI/Ai_Operating_System and tell me the number + how it changed." Uses the generic scheduler + web fetch — no special build.

The honest split: **content = written once (this file). Posting = ~2 hours, manual, one day. Replies + ongoing presence = drafted by SlyOS, approved by you.** That's the vast majority automated without a single fake star, and without turning the app into a growth-hacking tool for one repo.
```
