# SlyOS — Active Bug / Fix List

Captured from testing. **Not yet fixed** — this is the working queue. Rough priority noted.

---

## 1. Home AI — raw JSON/markup leaks into the UI  · HIGH (very visible)
The weather answer rendered the **WEATHER 72°F card correctly**, but *also* dumped the model's raw output as visible text — twice (in the card subtitle **and** a separate message with a Copy button):
`{"say":"[[card:stat;Current Temp;72°F;New Jersey]]","actions":["web_search","look"],"remember":"Weather check for New Jersey"}`
→ The response parser must render the card only and **never** show the raw JSON / `[[card:...]]` markup.

## 2. Telegram agents — crossed routing + hallucinated capabilities  · HIGH (trust)
> Correction: **"Ronan" was correct** — he's the real external person who messaged; not a hallucination.
- **Q/A crossed:** "what are your limitations" and a pasted TikTok-code question got answered by the wrong agents / out of order.
- **Hallucinated capabilities:** Bastardi claims it "lives inside Gmail, calendar, contacts… reads inbox, sends emails, schedules meetings"; Dex offers to "flag it for Bastardi to review with TikTok's trust & safety team." None of that is real.
- **Harmful-code handling:** the pasted tool is a **TikTok mass-reporting/abuse tool** — the agent analyzed it helpfully instead of declining/flagging it.

## 3. Research → Chat — layout + capabilities  · MEDIUM
- User-prompt messages render as **ugly bubbles** → make the chat layout much prettier.
- Chat should be able to **use the internet / web search** when the right endpoint/tool is enabled in Settings.
- **Attachments** must work in chat — all formats.
- Chat + attachments must still **feed the memory brain** (as everywhere else).

## 4. Alarms & Timers — broken end-to-end  · HIGH
- Don't read the **current time**, so they can't correctly compute when to fire.
- At the scheduled time, **nothing fires** (no alarm/notification).
- The alarm/timer **widgets look bad** → redesign.

## 5. Telegram — can't relay a received attachment  · MEDIUM
Someone sends a **PDF to the bot and asks to forward it to someone else** → doesn't work. Need attachment relay (receive → send to a third party).

## 6. Reconnect — near done, remaining gaps  · MEDIUM
- Unclear whether it **reads the past messages visible in the chat** (should use existing conversation context so the opener fits history).
- Auto-continue to the next person was addressed (back-out-after-send). **Verify on the latest build** it no longer needs a manual LinkedIn close + list removal.

## 7. Mission tab — wrong outreach channel  · HIGH (feature win)
- It mostly used **guessed emails**, and **nearly all were invalid** (bounced).
- Should reach out via **LinkedIn tap-send** (the now-working engine): message → close LinkedIn → next person → repeat — same loop as Reconnect.

## 8. "Auto" disabled in Per-app responses — REGRESSION I introduced  · MEDIUM
The "honest auto-reply" feature flags an app as **draft-only** (greys out **Auto**) after seeing **any** notification from it with no inline reply box. But apps post many non-message notifications (group summaries, "liked your post," etc.), so a single one flags the whole app and disables Auto even though its **message** notifications support replying.
→ Fix: only treat an app as draft-only if it's *never* exposed a reply action on message notifications (or make it a hint, not a hard block).

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
