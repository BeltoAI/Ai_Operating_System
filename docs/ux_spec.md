# AgentOS — UX Specification

## Design language

- **Background:** warm ivory `#F4EFE6`.
- **Typography:** near-black `#1A1714`, generous spacing, no chrome.
- **Accent:** one subtle orange `#E8642C`, used sparingly (active state, the dot before priorities, the talk ring).
- **Wordmark:** "agentOS" in a cursive/handwritten style (script font), lowercase, small.
  *Inspired by* a handwritten energy — **not** a copy of any existing logo.
- **No app grid. No notification chaos.** Whitespace is the feature.
- **Motion:** slow, calm fades. Nothing bounces.

## Screens

### 1. Boot
- Centered wordmark `agentOS`.
- Subtext: `waking up...`
- Ivory field, accent hairline pulse.

### 2. Lock
- Top: time (large, light weight), battery (small).
- Center: `You have 3 things that matter.`
- Three summarized priorities, each prefixed by a small orange dot.
- Bottom: `hold to speak` with a thin talk ring.

### 3. Home (the heart)
- Small `agentOS` wordmark, top-left.
- Center prompt: **`what should happen?`**
- One text input (underline only, no box).
- `hold to talk` control.
- Small `Today` summary line beneath.

### 4. Now
- Heading: `Now`.
- Important items only, as quiet lines.
- A single collapsed `Quiet — 12 muted` summary for everything suppressed.

### 5. People
- Heading: `People`.
- People who need attention, one per row: name + one-line reason.
- Each row offers **one** suggested next action (draft reply / call / snooze).

### 6. Memory
- Heading: `Memory`.
- `Active projects` (chips).
- `Recent files` (last touched).
- `Remembered context` (facts the agent is holding).

### 7. Manual Mode
- Heading: `Manual Mode` with a muted `Agent paused.` line.
- Six tools as a calm list, not a grid: Phone, Messages, Camera, Browser, Files, Settings.
- Persistent `Resume agent` affordance.

## Permission tiers (surfaced in UI as plain language)

| Tier | Label shown | Behavior |
|---|---|---|
| read only | "can look" | reads data, never writes |
| draft only | "can draft" | prepares actions, never sends |
| ask before action | "asks first" | executes only after explicit confirm |
| autonomous | "can act" | executes within scope, logs a receipt |
| blocked | "off-limits" | tool disabled entirely |

## Action receipts

Every action shows a receipt card: what was done, which tier authorized it, whether it's
reversible, and an undo affordance when possible. Receipts accumulate in Memory.

## Always-true invariants

- Manual Mode is reachable from every screen (long-press wordmark).
- The agent can be paused at any time; pausing is instant and obvious.
- Nothing leaves the device without matching a permission tier + passing redaction.
