# Contributing a Power to SlyOS

A **Power** gives someone's phone an ability it did not have. This is how you add one.

Nobody could contribute before this file existed. There was no published format, so the only
way a Power got into the store was for someone with commit access to type it into
`Power.kt` — which is why the curated list is 33 entries long and had not grown.

---

## The shortest possible version

Most Powers need **no code in this repo at all**. The store already searches GitHub live,
sorted by stars. If your project has a README that explains what it does, it can already be
found and installed as a **Skill** — SlyOS reads the docs and distils them into instructions
for the assistant.

So before opening a PR, check whether your repo is already discoverable: open the store,
search for it by name. If it appears, you are done. Open a PR only to get it **curated** —
placed in a category, given a tagline, and shown to people who were not searching for it.

---

## The three kinds

| Type | What it means | Runs where |
|---|---|---|
| `SKILL` | Knowledge injected into the assistant's brain. No install, no server — the AI simply knows how to do the thing. | Nowhere. It is text. |
| `TOOL` | A program that does work. Needs somewhere to execute. | Termux on the phone, or a computer |
| `CONNECT` | A link to a service that already exists — an API, an account, a self-hosted app. | Their server |

**Prefer `SKILL`.** It is the only one that works for everybody with one tap, and a
surprising number of "tools" are really a set of instructions wearing a binary.

---

## The format

One entry in `tools/Power.kt`:

```kotlin
Power(
    id       = "kebab-case-unique",
    name     = "Deep Research",              // shown on the card — say what it IS
    tagline  = "research any topic while you sleep",
                                             // completes "…the power to ___". lowercase, no full stop
    type     = PowerType.SKILL,
    category = "Create",                     // For you · See · Speak · Create · Remember
    icon     = "⚡",
    repo     = "owner/name",                 // real GitHub repo. Docs are read from it
    stars    = "",                           // leave EMPTY. Filled from GitHub, never by hand
    description = "One or two plain sentences. What it does, not why it is exciting.",
    onPhone  = true                          // true ONLY if it needs no server and no Termux
)
```

### Rules that will get a PR rejected

- **`stars` must be empty.** It comes from GitHub. A hand-written number is a fabricated
  number, and the store previously showed an invented `★ 4.8` on every card — which is
  exactly the detail a sceptical person checks first.
- **`onPhone = true` only if it genuinely needs nothing.** Not "works on a phone with
  Termux and a Python install". That flag is a promise of one tap.
- **`tagline` finishes the sentence "give your phone the power to…"** — so `"see the live
  web, with sources"`, not `"Web Search Tool"`.
- **A real `repo` that has a README.** Skills are built by reading it. No docs, no skill:
  the install now fails out loud rather than silently adding an empty one.
- **No emoji in `name` or `description`.**

---

## Writing a Skill that actually works

When someone installs a `SKILL`, SlyOS fetches your repo's docs and distils them into
instructions injected into the assistant's system prompt. The quality of that distillation is
entirely the quality of your README.

What makes a README distil well:

- **Say what the thing does in the first two sentences.** The distiller reads the top of the
  file most closely, like everyone else.
- **Give concrete examples of input and output.** "Ask it to X and it will Y" survives
  distillation; a feature bullet list does not.
- **State the limits.** A skill that knows what it cannot do is worth more than one that
  confidently attempts everything, because the assistant will otherwise promise it.

You can test yours before opening a PR: install it from the store by searching your repo
name, then ask the assistant to do the thing. If the answer is vague, the README is vague.

---

## Termux: Powers that run on the phone

Termux gives an Android phone a real Linux shell, which is how a `TOOL` runs locally
instead of on a server. This is the setup a contributor should assume, and the one to
document in your own README if your Power needs it.

```bash
# 1. Install Termux from F-Droid, NOT the Play Store.
#    The Play Store build is frozen at an old version and its packages no longer resolve.
#    https://f-droid.org/packages/com.termux/

# 2. Bootstrap
pkg update && pkg upgrade -y
pkg install -y python git openssh

# 3. Let it keep running when the screen is off, or Android will kill it mid-task
termux-wake-lock

# 4. Your Power
git clone https://github.com/owner/name && cd name
pip install -r requirements.txt

# 5. Serve it on localhost so SlyOS can reach it
python -m http.server 8080     # or whatever your project uses
```

Then in the store, install your Power as a `TOOL` and give it the endpoint
`http://127.0.0.1:8080`.

### What to be honest about in your README

- **Storage.** A model that needs 4GB will not run on a phone that has 3GB free, and
  saying so in the README saves somebody a very slow download.
- **Heat and battery.** Sustained inference on a phone throttles within minutes. If your
  Power is only usable in short bursts, say that.
- **Android kills background processes.** `termux-wake-lock` helps and does not fully
  solve it. A Power that must run for an hour unattended belongs on a computer.

---

## Opening the PR

1. Add your entry to the list in `tools/Power.kt`.
2. Keep it in a sensible category — the five are deliberately few.
3. In the PR description: what it does, why it belongs in the store, and **whether you
   have run it on a phone yourself**.

That last question is the one that matters. A Power that has never been installed on real
hardware will be found out by the first person who tries it, and the store's only asset is
that the things in it work.
