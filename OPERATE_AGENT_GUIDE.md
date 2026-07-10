# Operating your phone with SlyOS — how to use the agent

SlyOS can take over your screen and do things for you across **any app and the web**, using the Android
accessibility layer. It perceives the live screen, plans, acts, and **verifies** the goal was actually met.

## Turn it on (one-time)
Settings → enable **Accessibility → SlyOS**, and the in-app "Screen control" toggle. Without this it can't act.

## How to trigger it
Just tell SlyOS in plain language, starting with an operate-style verb:
> "**operate:** turn on Bluetooth and connect my AirPods"
> "**go into** Settings and turn on Do Not Disturb"
> "**sign me up** for Notion with email me@x.com and password Hunter2!"

There's always a **STOP** notification while it runs — tap it any time to take back control.

## The one hard rule: money
It will do everything end-to-end **except spend money**. At any Pay / Buy / Checkout / Subscribe / Transfer
button it stops and hands *you* the final tap. Everything else (sign-ups, forms, posts, toggles) it finishes.

---

## What to say — good examples

### System toggles & settings (most reliable)
- "turn on Bluetooth" / "turn off Wi-Fi" / "enable airplane mode"
- "set my ringer to silent" / "turn on Do Not Disturb"
- "turn my brightness down" / "turn on the battery saver"
- "open my app settings for Instagram and clear its cache"
> It jumps *straight* to the right Settings page in one hop, so these are fast and solid.

### Sign-ups & logins (end-to-end, incl. email verification)
- "sign up for &lt;app&gt; with my email me@x.com and password &lt;pw&gt;"
- "log into &lt;app&gt; with &lt;email&gt; / &lt;password&gt;"
> It fills the password box (never reads or logs it), submits, and if the app emails a confirmation link it
> pulls that link from your Gmail and opens it automatically. **Tip:** give the exact email + password in the
> command, and make sure Google is connected for the auto-verify step.

### Setting things up from your brain
- "in Notion, create a page for my current project with my bio and key details"
- "set up my profile in &lt;app&gt; using my info"
- "make a new contact for &lt;name&gt; with the details you have"
> It draws real details from your brain (name, work, projects, contacts, dates) and types them in — no
> placeholders. The richer your Brain → About and imported data, the better these get.

### Web / anywhere
- "open Chrome, go to &lt;site&gt;, and fill the contact form with my details"
- "search Google for &lt;x&gt; and open the first result"
> Web pages are operable too — links, fields and buttons are all elements to it.

### Repetitive / batch (ambitious — works, but not 100%)
- "open LinkedIn and connect with 10 people who do product design"
- "like the last 15 posts in my Instagram feed"
- "message 5 friends on WhatsApp I haven't talked to in a while"
> It loops one item at a time, scrolls for more, and counts progress until the number is met. For
> "haven't talked to in a while" it pulls the actual people from your message history automatically. These
> depend on the app's layout and anti-automation, so expect a strong attempt rather than a guarantee.

---

## Tips for best results
- **Be specific about the end state.** "turn ON Bluetooth" beats "Bluetooth". Give a number for batches ("connect with 20").
- **Provide secrets in the command** for sign-ups (email + password). They're used to fill fields, and passwords are never read back or stored by the agent.
- **Keep the phone unlocked and on-screen** while it works; it acts on the foreground app.
- **It self-verifies** before saying done, and **re-plans once** if it gets stuck — so if it stops, it'll tell you honestly what's left.
- **Two triggers = STOP.** Firing the same operate command again while it's running stops it.

## What it won't / can't always do
- **Spend money** — by design; it hands you that final tap.
- **CAPTCHAs, OTP codes from SMS, and heavily-obfuscated screens** can block a step.
- **Aggressive anti-bot apps** may rate-limit or interrupt long batch runs.
- **Secure fields it can fill but not read** — so it can log you in, but can't tell you your own saved password.
