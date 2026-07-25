# SlyOS — Active Bug / Fix List

Captured from testing. Rough priority noted.

**Status legend:** ❌ open · 🔧 fixed in code, needs a device pass · ✅ verified on device.

> Audited 2026-07-24 against the source. Several items below were already fixed by later commits but
> never struck off, which cost a full session re-diagnosing work that was already done. Keep the
> status markers current — an inaccurate queue is worse than no queue.

---

## 1. Home AI — raw JSON/markup leaks into the UI  · HIGH (very visible) · 🔧
The weather answer rendered the **WEATHER 72°F card correctly**, but *also* dumped the model's raw output as visible text — twice (in the card subtitle **and** a separate message with a Copy button):
`{"say":"[[card:stat;Current Temp;72°F;New Jersey]]","actions":["web_search","look"],"remember":"Weather check for New Jersey"}`
→ The response parser must render the card only and **never** show the raw JSON / `[[card:...]]` markup.

**Root cause (two compounding defects, both fixed):**
1. `RichParse.fromTag` is anchored at `^\[\[card:`, so a string starting with `{` never matched — but
   `render()` then fell through to `detect()`, which happily built a weather card out of the `72°F`
   *inside the envelope*, leaving the envelope itself as both the card subtitle and the copyable body.
   The envelope is now unwrapped through `AgentClient.sanitizeForUi` at the top of `fromTag`, the one
   choke point every UI path (Home, Chat, Converse, card renderer, speech, clipboard) passes through.
2. `cleanSay` scrubbed `[`/`]` from anything with a leading bracket — which shredded our *own*
   `[[card:…]]` markup into visible `card:stat;Current Temp;72°F`. The tag is now lifted clear before
   the JSON scrub and restored after.

Covered by a `FeatureHealth` self-test ("Card envelope renders as a card, not text") so it can't regress.

## 2. Telegram agents — crossed routing + hallucinated capabilities  · HIGH (trust) · 🔧 (partial)
> Correction: **"Ronan" was correct** — he's the real external person who messaged; not a hallucination.
- **Q/A crossed:** "what are your limitations" and a pasted TikTok-code question got answered by the wrong agents / out of order.
- **Hallucinated capabilities:** Bastardi claims it "lives inside Gmail, calendar, contacts… reads inbox, sends emails, schedules meetings"; Dex offers to "flag it for Bastardi to review with TikTok's trust & safety team." None of that is real.
- **Harmful-code handling:** the pasted tool is a **TikTok mass-reporting/abuse tool** — the agent analyzed it helpfully instead of declining/flagging it.

**Done:** the reply persona now carries a `CAPABILITIES` rule (never claim access to email/calendar/
contacts/accounts, never claim to send mail or book meetings, never commit another person or team to
an action) and an `ABUSE` rule (decline mass-report/brigade/scrape tooling in one line rather than
analysing it). Both hallucinations in this report were capability/commitment inventions.
**Still open:** the crossed Q/A routing between agents — that's a dispatch bug, not a prompt bug.

## 3. Research → Chat — layout + capabilities  · MEDIUM · 🔧 (partial)
Attachments ARE implemented (`ChatScreen` reads images → b64 and PDF/txt/code → text, with an error
chip when a file can't be read), and chat exchanges DO feed the brain (`MessageStore.insertOne` on
both sides of each turn).
**Done:** plain chat now answers through `answerWell` — the same path as Home AI — so it gets web
search on live/factual questions plus the grounding rules, instead of the thinner `chat()` prompt with
no web tool at all. That was both this bug's "chat should use the internet" and the reason the same
question answered well on Home and vaguely in chat.
**Still open:** user-prompt bubbles render ugly → restyle the chat layout.
- User-prompt messages render as **ugly bubbles** → make the chat layout much prettier.
- Chat should be able to **use the internet / web search** when the right endpoint/tool is enabled in Settings.
- **Attachments** must work in chat — all formats.
- Chat + attachments must still **feed the memory brain** (as everywhere else).

## 4. Alarms & Timers — broken end-to-end  · HIGH · 🔧
- ~~Don't read the **current time**~~ → every planning prompt now carries `Current time: <EEE yyyy-MM-dd HH:mm>`
  (`AgentClient`, `AgentLoop`), and `parseClockTime` resolves am/pm, bare hours, noon/midnight and "in N min".
- ~~At the scheduled time, **nothing fires**~~ → three separate causes were fixed: timers only started an
  on-screen countdown and never scheduled anything; the `reminders` notification channel was created silent
  and channels are immutable (hence `reminders_v2_alarm`); and the inexact `setAndAllowWhileIdle` was being
  delayed/dropped in Doze (now `setExactAndAllowWhileIdle`, with both `SCHEDULE_EXACT_ALARM` and
  `USE_EXACT_ALARM` in the manifest and an inexact fallback). Alarms also get a backup ring because some OEM
  clock apps create an `EXTRA_SKIP_UI` alarm **disabled**.
- **Still open:** the alarm/timer **widgets look bad** → redesign. This is the only part of #4 left.

## 5. Telegram — can't relay a received attachment  · MEDIUM
Someone sends a **PDF to the bot and asks to forward it to someone else** → doesn't work. Need attachment relay (receive → send to a third party).

## 6. Reconnect — near done, remaining gaps  · MEDIUM
- Unclear whether it **reads the past messages visible in the chat** (should use existing conversation context so the opener fits history).
- Auto-continue to the next person was addressed (back-out-after-send). **Verify on the latest build** it no longer needs a manual LinkedIn close + list removal.

## 7. Mission tab — wrong outreach channel  · HIGH (feature win) · 🔧
- It mostly used **guessed emails**, and **nearly all were invalid** (bounced).
- Should reach out via **LinkedIn tap-send** (the now-working engine): message → close LinkedIn → next person → repeat — same loop as Reconnect.

**Done:** `MissionScreen.startLinkedInOutreach()` runs the same `NetworkOutreach` loop as Reconnect, and the
email path now refuses to send to guessed addresses, pointing at LinkedIn outreach instead.

## 8. "Auto" disabled in Per-app responses — REGRESSION I introduced  · MEDIUM · 🔧
The "honest auto-reply" feature flags an app as **draft-only** (greys out **Auto**) after seeing **any** notification from it with no inline reply box. But apps post many non-message notifications (group summaries, "liked your post," etc.), so a single one flags the whole app and disables Auto even though its **message** notifications support replying.
→ Fix: only treat an app as draft-only if it's *never* exposed a reply action on message notifications (or make it a hint, not a hard block).

**Done:** `AgentNotificationListener.maybeAutoReply` only sets the draft-only flag when
`!appEverInlineReply(pkg)`, and *clears* a stale flag the moment the app does expose one.

---

## 9. Memory search ranked by recency, not relevance  · HIGH (the "brain knows nothing" symptom) · 🔧
"Who is Carlos" returned three copies of a learned fact typed today plus two echoes of the user's own
earlier questions — zero of the 10,864 real messages in his threads. `MessageStore.search` scored hits by
distinct-term count over a single `contact + " " + body` haystack, so a one-term query scored every hit
identically and ordering collapsed to the `ts DESC` tie-break, where today's learned facts win by definition.
→ Coverage still filters noise, but ordering is now `contactHits × 8 + bodyHits`: being *in* someone's thread
outranks merely mentioning them, while body terms still stack on top so the on-topic message inside a big
thread stays at the top. Contact matching is prefix-of-word, not raw substring (`%elon%` also matched
"Mont-elon-go", which merged unrelated threads).

## 10. Screen recall writes zero rows  · MEDIUM · ❓ likely not a bug
`InteractionLogService` returns early unless `MemoryStore.recallEnabled(ctx)`, and that pref
(`recall_capture`) **defaults to false**. The accessibility config itself is correct (right event types,
`canRetrieveWindowContent="true"`), so there is no structural defect in the capture path.
→ `-e mode health` now prints a SCREEN RECALL block: setting on/off, service granted y/n, row count, top
apps, and a verdict naming the first failed precondition. This stops being a mystery after one probe.

## 11. Memory tab answered worse than Home AI on the same data  · HIGH · 🔧
Three independent causes, all fixed:
- **Ranking** — see #9.
- **Prompt** — `askMemory` lacked the clause Home AI has ("if a person appears anywhere in the memories,
  you KNOW them; never say you can't find them"), so it disclaimed its way out of evidence in front of it.
  It now also gets the current time and a rule distinguishing conversations WITH a person from mentions OF
  their name.
- **Corpus** — real messages were third in line behind 40 profile lines and 30 semantic hits, and semantic
  recall is only partially built, so those slots were mostly noise eating the budget. Messages now come
  first, semantic hits follow, and the budget is 20,000 chars to match Home AI's (was 14,000).
  **Restore semHits ahead of dbHits once the re-embed completes.**

## 12. Now-questions repeat instead of exploring the brain  · MEDIUM · 🔧
The generator is correctly aimed (it asks for what would most improve future answers, from `BrainDigest`
+ learned facts + real contacts). The repetition was mechanical:
- The "these subjects are CLOSED" / "BANNED SUBJECTS" block was appended **last** in `known`, which is then
  truncated with `take(...)` — on a full brain the exclusions were cut off before the model ever saw them.
  Exclusions now go FIRST, and the budget went 6,000 → 9,000.
- Dedupe only compared against PENDING and ANSWERED questions, never `askedLog` — so a question shown, then
  wiped by a refresh without being answered, could return verbatim. `add()` now hard-rejects anything whose
  normalised text was already asked; the prompt-level ban is advice, this is enforcement.
- The asked-log held 25 entries (~6 batches, less than two passes of the 5-way focus rotation) → 60.
- `refresh()` cleared the pending batch BEFORE generating, so one failed generation blanked the Now card for
  45 minutes. The clear is now deferred until replacements exist.
- Logs `focus=N generated X, Y new after repeat-filter` — a batch of 4 yielding 0 new is the signal that the
  generator is circling.

---

## Stat pull — how we verify the Models & Spending card

Run `pull_brain_stats.sh` (connected debug phone). It **redacts API keys** (SET/empty only) and prints:
- **Semantic memory:** embed setting, on-device embedder present?, indexed vs pending counts.
- **Requests per AI today:** ok / fail per provider (+ last error).
- **Free-tier used today:** used / cap per provider, and which are **PARKED** (rate-limited).
- **Cost/usage:** month cost, calls, tokens, lifetime.
- **Routing/config:** preferred provider, monthly budget, tier overrides.
- **Keys present** (hidden values) + analytics status.

**What "correct" looks like at limits:** on a 429/quota, `ProviderLimit` parks that brain for a 10-min cooldown and the router sorts it to the back → rolls to the next keyed brain automatically; re-probes after cooldown; if all parked, still tries. `FreeTierMeter used/cap` is display-only (never blocks). Monthly-budget crossing forces free-brains-only.
