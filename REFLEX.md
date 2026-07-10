# Reflex — reliable phone operation for SlyOS

## The problem with the old approach
The agent asked a language model, on **every step**, to *visually hunt for a button and pick its index* from
a flat list of screen elements. That's the wrong job for an LLM:
- it can't see icon-only buttons (comment/share/like) that carry no text label,
- it picks indices imprecisely and thrashes (like → unlike, tapping empty elements),
- it re-derives everything from scratch each step, with no reliable notion of "did that work?"

No amount of prompt-tuning fixes a fundamentally fragile loop. Reliable automation (real RPA) splits two jobs.

## The Reflex principle
**The planner decides _what_. Reflex reliably does _how_.**

The LLM never hunts for a button again. It issues a high-level **intent** and Reflex grounds it:

```
LLM:      DO comment | Great breakdown of on-device inference
Reflex:   find the comment control (by text OR content-description OR resource-id) →
          tap it → find the comment field → type the text → find & tap Send → verify
```

### 1. Multi-signal grounding (the core)
For an intent like `like`, Reflex scores **every** on-screen element across all signals at once:
- visible **text**,
- **content-description** (icons often label themselves here),
- the internal **resource-id** name (`.../comment_button` → "comment button") — this is how unlabelled
  icons get found, and it's app-agnostic because it reads Android's own view ids.

Negative signals (`Unlike`, `Following`, `Liked`) mean *already done* — Reflex refuses to undo them.

### 2. Skills, not taps
Intents map to deterministic, sometimes multi-step **skills**: `like`, `comment | text`, `reply | text`,
`share`, `follow`, `send`, `message | text`, `save`, `search | query`, `back`, `menu`, `next`, `compose`.
`comment` isn't one tap — it's the whole flow (open → type a specific comment → send), executed reliably.

### 3. Vision only where it belongs
The accessibility tree handles labelled/native UI. When a screen is a **game board or canvas** (no useful
nodes), Reflex falls back to a screenshot + coordinate taps (`TAPXY`/`DRAGXY`). Vision is the exception, not
the every-step default that made it chatty and blind.

### 4. Verify + don't-repeat
Every action is followed by a re-read; goal-aware guards stop it re-liking a liked post or looping an
expand/collapse. A final verification step judges whether the goal was actually achieved before finishing.

## Why this is the right architecture
This is how dependable UI automation is actually built: a thin intelligent planner over a hardened,
verifiable executor. The model's job shrinks to something it's good at (naming the next action); the
error-prone part (finding and hitting the exact element) becomes deterministic and testable.

## Roadmap
- **Reflex Learn** — perform a task once by hand; SlyOS records the exact grounded path and replays it
  perfectly forever (teach-by-demonstration). This is the endgame for repetitive missions.
- **OCR grounding** — add on-screenshot text positions as another signal for pixel-precise taps.
- **Per-app skill packs** — verified recipes for the top apps, auto-selected by package name.
