# SlyOS for iPhone — test script

Run in order. Each test says what **pass** looks like, so a partial result isn't argued about
later. Note the failures rather than stopping — several tests are independent.

**Before starting:** at least one AI provider must have a working key. As of the last run OpenAI
was out of credit and the Gemini key was rejected, so Anthropic was carrying everything alone —
and when it had a bad moment there was no fallback at all. Fix one of the free ones (Gemini or
Groq) before you begin, or half of this script fails for reasons that aren't the app's.

---

## A. The claim — brain and recall

If these fail, nothing else matters. This is the product.

| # | Do this | Pass |
|---|---|---|
| A1 | Memory tab → note the count | A real number, not zero |
| A2 | Ask about a person you know well: *"who is X?"* | Names them from your own data, not a guess |
| A3 | Ask something only your history knows: *"what did X and I agree about Y?"* | Cites the actual exchange, **or says plainly it has no record** — inventing one is a fail |
| A4 | Ask about someone who doesn't exist | Says so. Confidently answering is a **hard fail** |
| A5 | Memory graph — drag, pinch | Rotates, zooms, labels appear past ~1.4× |
| A6 | Search a name in the graph's field | Their memories, ranked by *them*, not by recency |

## B. Honesty — the thing that has failed before

Twice now the app has claimed to do something it hadn't. These tests exist because of that.

| # | Do this | Pass |
|---|---|---|
| B1 | *"send a message to X on WhatsApp saying I'll be late"* | Opens with **"Not sent"** and hands you the draft. Any wording implying it sent is a **hard fail** |
| B2 | *"email X about Y"* (Google connected) | Actually arrives in their inbox. Check the recipient, not the screen |
| B3 | Now → *Sent for you* | Lists what B2 did, with the outcome |
| B4 | Ask *"did you send that?"* right after B2 | Answers from the record, not from the conversation |

## C. Doing things

| # | Do this | Pass |
|---|---|---|
| C1 | *"write me a proposal for X"* | A Google Doc **link** that opens and has real content |
| C2 | *"make a deck about Y"* | Slides link, ≤6 slides, readable |
| C3 | *"build a budget tracker for Z"* | Sheet link, header row bold and frozen |
| C4 | Create a calendar invite with a Meet link for someone else | **They receive it.** Verify on their device, not yours |
| C5 | Research → New paper → write one | Saved, reopenable from the library |
| C6 | Research → open a paper → Publish to Zenodo *(needs a token)* | A real DOI |

## D. Everyday surfaces

| # | Do this | Pass |
|---|---|---|
| D1 | *"what's on today?"* | Real events, or a definite "nothing" — never "I don't have access" |
| D2 | Now tab | Calendar + reminders, soonest first |
| D3 | Now → ↻ on **What you missed** | A summary in your terms, naming people |
| D4 | Swipe a Now card left / right | Left dismisses, right opens. Should feel like it tracks your finger |
| D5 | Now → Reconnect | People who've gone quiet |
| D6 | Home → tap to talk, speak | Transcribes as you speak, sends on stop |

## E. Look

| # | Do this | Pass |
|---|---|---|
| E1 | **Scan receipt** on a real receipt | Reads it. **Works with no API key** — on-device |
| E2 | **Scan doc** on a page of text | Text lands in the brain; search a phrase from it |
| E3 | **Identify** on an object | Names it. Needs a working *vision* key (Anthropic, OpenAI or Gemini) |
| E4 | Flip, Auto | Front camera, torch |

## F. Reply drafting

| # | Do this | Pass |
|---|---|---|
| F1 | Add the keyboard: Settings → General → Keyboard → Keyboards → SlyOS, then **Allow Full Access** | Appears in the globe list |
| F2 | In WhatsApp: copy a message → SlyOS keyboard → **Draft a reply** → **Type it** | Lands in the field, sounds like you. **You** press send |
| F3 | Turn Full Access **off**, use the keyboard | Still types. A dead keyboard is an App Store rejection |
| F4 | Select a message anywhere → Share → SlyOS | Drafts a reply |
| F5 | Tone pills (Warmer / Shorter / Firmer) | Genuinely different |

## G. Siri — where iPhone beats Android

| # | Do this | Pass |
|---|---|---|
| G1 | *"Hey Siri, ask SlyOS what's on"* — screen off | Speaks the answer without opening the app |
| G2 | *"Hey Siri, ask SlyOS who X is"* | Answers from **your brain**, not Siri's general knowledge |
| G3 | *"Hey Siri, remember this in SlyOS: …"* | Findable in Memory afterwards |

## H. Account, sync, backup

| # | Do this | Pass |
|---|---|---|
| H1 | Settings → Account → create or sign in | Signs in |
| H2 | Sign into the **same** account on Android, sync there, then *Back up now* here | Memory count rises. **Several rounds** — 400 messages per sync |
| H3 | After H2, ask about someone who only exists in WhatsApp | Answers. **This is the "one brain, both phones" claim** |
| H4 | Settings → Google → **Back up brain** | Reports a size |
| H5 | **Restore** | Merges; count doesn't drop |
| H6 | Settings → Account → **Delete account** | Actually deletes *(needs `supabase/delete_account.sql` run once)* |

## I. Look and feel

| # | Do this | Pass |
|---|---|---|
| I1 | Ask something with a one-line answer | Card **hugs the text**. A short answer in a tall box is a fail |
| I2 | Ask something long | Caps and scrolls; **Read ⤢** appears |
| I3 | Swipe the answer left / right | Dismisses / opens its link |
| I4 | Dark ↔ light in Settings | Whole app recolours, survives relaunch |
| I5 | Type in Memory, then try to leave | Keyboard dismisses by dragging or tapping away |
| I6 | Side by side with Android | Same palette, nav, spacing |

## J. Failure behaviour

The app is judged on these when things go wrong, which is when people lose trust.

| # | Do this | Pass |
|---|---|---|
| J1 | Remove every API key, ask something | One readable line naming the fix — not JSON |
| J2 | Airplane mode, ask something | Says it couldn't reach anything |
| J3 | Deny calendar permission, open Now | Explains and offers Settings |
| J4 | Airplane mode → Memory search | **Still works.** Search is local; needing the network here is a fail |

---

## K. Memory that actually works

Added after the audit. These are the difference between a search box and a brain.

| # | Do this | Pass |
|---|---|---|
| K1 | Settings → **Is it working?** → Check everything | Every line reports what it found. A ✕ names the fix; it never just says "error" |
| K2 | Read a PDF in (paperclip on Home), then ask about something on page 4 | Answers from the document, quoting it |
| K3 | Ask something phrased nothing like the memory that answers it — *"who owes me money?"* when nobody used that word | Finds it. This is semantic recall; keyword search cannot do it |
| K4 | Home: upload a photo, let it read the text, then ask *"what was that?"* | Answers about the photo. Forgetting between prompts is the **hard fail** this was built for |
| K5 | Research → Chat → **New chat**, talk, go back, start another | Two separate conversations, both still there after force-quitting |
| K6 | Ask *"what did I do yesterday?"* | Real events from yesterday, with times — not a keyword match on the word "yesterday" |
| K7 | Ask *"who did I email last?"* | The actual most recent sent mail, newest first |
| K8 | Ask about a person you know well | Says how many exchanges, over what span, and how long since you last spoke |
| K9 | After a day of use, Settings → Is it working? → Learned facts | A count above zero. These are distilled from your own history, not typed in |

---

## Scoring

- **Any hard fail in B** — not sellable. Those are trust, not features.
- **A1–A4 failing** — not sellable. That's the product.
- **K1–K5 failing** — the audit fixes did not land.
- **C, F, G failing** — sellable but thin.
- **E, H, I failing** — polish.

## Known going in

- **No auto-send on WhatsApp from the phone.** Not a bug, not fixable — iOS has no API for it. Only an OpenClaw gateway can.
- **Android's WhatsApp history isn't on the iPhone** until H2 has run several times.
- **Builds expire after 7 days** on the free developer account.
- **Cowork and Team don't exist** on iPhone.
