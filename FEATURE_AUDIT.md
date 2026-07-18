# SlyOS — Full Feature Audit

A complete, honest inventory of everything SlyOS does, graded for what to **keep, hide, remove, or charge for.**
Graded against one strategic lens (from the VC conversation): **the product is the *kernel* — memory brain, model router, action layer, trust plumbing — plus ONE deep wedge (autonomous communication). Everything else is a founder-built "app" that proves the kernel but shouldn't absorb founder time.**

### Legend
| Tag | Meaning |
|---|---|
| 🟢 **CORE** | A primitive. The actual product. Invest here. |
| ⭐ **WEDGE** | The one vertical to go deep on now (autonomous comms). |
| 🔵 **KEEP** | Table-stakes launcher utility; commodity but expected. Keep, don't over-invest. |
| 🟡 **HIDE** | Impressive demo, off-thesis. Keep the code, move it behind a "Labs" flag; stop building on it. |
| 💰 **PREMIUM** | Real willingness-to-pay. Gate behind a paid tier when there's a wedge. |
| 🔴 **REMOVE** | Pure demo / brand-or-scope risk / near-zero strategic value. Cut from the shipping build. |
| 🔧 **FIX** | Ships today but is a liability (security/ToS). Fix or gate before it's in front of investors/users. |

---

## 1. The Kernel — the four primitives (this IS the product) 🟢

| Feature | What it does | Grade | Notes |
|---|---|---|---|
| **Memory brain** (`MemoryStore`, `MessageStore`, `ConversationStore`, `Entities`, `MemoryGraphStore`) | Local SQLite store of every message in/out, contacts, learned facts, notes — "the thing becoming you." | 🟢 CORE | **The crown jewel.** Only durable moat. Protect + make portable. |
| **Semantic memory** (`VectorStore`, `EmbeddingClient`, `OnDeviceEmbedder`) | Recall-by-meaning over the brain; on-device or cloud embeddings. | 🟢 CORE | Keep on-device path as default so it never goes dark. |
| **Brain context assembly** (`BrainContext`, `ReplyContext`, `SlyFolder`) | Assembles profile + calendar + recall + persona into every prompt. | 🟢 CORE | This is what makes replies "you." |
| **Model router** (`ModelRouter`, `LlmProviders`, `AgentClient`) | Provider-agnostic routing across Claude/GPT/Gemini/Groq/Cerebras/Mistral/GitHub/on-device, with capability + cost logic. | 🟢 CORE | Second-most-defensible asset. The "runs on any brain" layer. |
| **Free-tier cascade** (`ProviderLimit`, `FreeTierMeter`, `CostStore`, budget cap) | Auto-rolls across free tiers on rate-limit; tracks spend; enforces budget. | 🟢 CORE | Keeps it near-$0. Strong for BYO-key story. |
| **Action / tool layer** (`ToolRouter`, `ActionExecutor`, `AgentLoop`) | Turns intent into real device/app actions; multi-step agent loop. | 🟢 CORE | The "it acts, not just answers" primitive. |
| **Screen agent** (`ScreenAgent`, `InteractionLogService`, `Reflex`, `ReflexLearn`, `TapSend`) | Drives any app via Accessibility (read screen, tap, type, send). | 🟢 CORE (⚠️ fragile) | Powerful primitive **but** platform-exposed + ToS-risky. Keep as core, treat as beta. |
| **Trust & undo plumbing** (`OutboxStore`/"Sent for you", `TrustStore`, `AutoReplyGuard`, `OutboundGuard`, `ConfirmActionCard`) | Logs every autonomous action w/ why + recall; gates strangers; rate-limits; holds risky sends. | 🟢 CORE | This is what makes aggressive automation *safe* — a real differentiator. |
| **On-device LLM fallback** (`LocalLlm`) | Small offline model when no cloud key/offline. | 🔵 KEEP | Nice safety net; keep, don't over-invest. |

---

## 2. The Wedge — autonomous communication (go deep here) ⭐

| Feature | What it does | Grade | Notes |
|---|---|---|---|
| **Reply in your voice** (`AgentNotificationListener`, `SmartReply`, per-app responses, `ReplyContext`) | Reads notifications across apps, drafts/auto-sends replies that sound like you. | ⭐ WEDGE | The magic moment. This is the product to nail. |
| **Voice/persona learning** (`VoiceSampleStore`, style profiles, per-app persona) | Learns your writing from real chats; per-platform tone. | ⭐ WEDGE | Core to "sounds like me." |
| **Outreach drip** (`OutreachQueue`, spam-safe pacing) | Human-paced email outreach with caps + logging. | ⭐ WEDGE | Monetizable over email/CRM. **Prefer this channel over Accessibility scraping.** |
| **Autonomous Reconnect / Missions** (`NetworkOutreach`, `MissionStore`, `MissionScreen`, `ReconnectScreen`, `MissionWorker`) | "Message N of my network / find buyers" — drafts + sends, tracks replies, morning report. | ⭐ WEDGE (🔧 ToS) | Great demo. Push people toward email; LinkedIn tap-send is ban-risk + fragile. |
| **Telegram control** (`TelegramService`, `TeamChat`, `TelegramClient`) | Run your brain + agents from Telegram. | 🔵 KEEP | Best "remote control" surface; low ToS risk (official Bot API). |
| **AI employees / team** (`EmployeeStore`, `EmployeeRunner`, `TeamScreen`, `TeamInbox`, `EmployeeWorker`) | Hire persistent agents with goals that run shifts. | 🟡 HIDE→⭐ | Compelling but sprawling. Fold into the wedge as "always-on outreach/inbox agents," don't ship as a generic staff sim yet. |

---

## 3. Vertical "apps" — founder-built demos (freeze / hide / cut) 🟡🔴💰

| Feature | What it does | Grade | Notes |
|---|---|---|---|
| **Job hunt** (`JobScreen`, `JobDoc`, `JobStore`) | Résumé + cover letter + outreach as real PDFs. | 💰 PREMIUM candidate | Recruiting/job-search is monetizable — could be a *second* wedge. Otherwise hide. |
| **Practice investing** (`TradeScreen`, `TradeStore`, `QuoteClient`, `TradeWorker`) | Paper portfolio + live tracking + news. | 🟡 HIDE | Fun demo, off-thesis, data-cost. Park behind Labs. |
| **Chess coach** (`ChessCoachService`, `ChessBoard`, `ChessEngine`) | Live best-move hints over any chess app. | 🔴 REMOVE | Pure tech-flex, zero strategic value. Cut from shipping build. |
| **Song ID / Shazam** (`SongId`) | Identify playing music. | 🔴 REMOVE | Commodity demo; every phone does it. |
| **Look / visual shopping** (`LookScreen`, `PhotoVision`) | Point camera → identify → shop/map/search. | 🟡 HIDE | Nice demo, off-thesis. |
| **Paper writing → Zenodo** (`PaperStore`, `ProposalStore`, `ZenodoClient`) | Write + publish papers with a DOI. | 🟡 HIDE | Very niche (academics). Keep for the founder's own use; hide from product. |
| **Spicy posts / X** (`SpicyPostScreen`, `SpicyWorker`, `SpicyScheduler`, `TwitterClient`) | Auto-generate + schedule edgy tweets. | 🔴 REMOVE | Brand/safety risk, off-thesis. |
| **Cowork / Termux** (`CoworkScreen`, `TermuxBridge`, `PowerBuilder`, `WorkspaceStore`) | On-device file/code workspace; runs Python/Node. | 🟡 HIDE | Dev toy; impressive, niche. Labs. |
| **Website builder + hosting** (`SiteHost`, `NetlifyClient`, `DeployClient`, `LovableClient`, `DesignStore`) | Agent builds + ships a live website. | 🔧 FIX → 🟡 HIDE | **Baked shared Netlify/Vercel tokens = security liability (VC-flagged).** Remove baked tokens (make BYO) then hide. |
| **Expenses / receipts** (`ExpensesScreen`, `ExpenseStore`) | Receipt parsing + expense log. | 🟡 HIDE | Off-thesis; commodity. |
| **Live location** (`LiveLocationService`) | Share live location via endpoint. | 🟡 HIDE (privacy) | Niche + privacy surface. Off by default; hide. |
| **Call screening / AI answering** (`SlyCallScreeningService`, `CallAgentService`, `CallHandling`) | AI screens/answers calls, texts back. | 🟡 HIDE | Heavy, carrier/ToS-adjacent, niche. Labs. |
| **Bank vault** (`BankVault`, `BiometricAuth`) | PIN-locked local secrets store. | 🟡 HIDE | Niche; nice but not the product. |
| **Face / people index** (`FaceScreen`, `PeopleStore`, `PhotoIndex`, `PhotoVision`, `PhotoScanWorker`) | On-device photo understanding + person memory. | 🟡 HIDE → 🟢 (feeds brain) | Keep the *brain-feeding* part quietly; hide the standalone Face UI. |

---

## 4. Launcher / device utilities — table stakes 🔵

| Feature | What it does | Grade |
|---|---|---|
| Flashlight / torch (`Torch`) · Timer/Alarm/Reminder (`TimerStore`, `ReminderReceiver`, `AlarmPlanner`) · Media control (`MediaControls`) · Navigate/dial/SMS/open-app (`ToolRouter`) · Calendar (`CalendarTool`) · Checklist (`ChecklistStore`) · Translate (`Translate`) · App search/launch (`AppScanner`, `AppsScreen`) · Floating nav (`OverlayNavService`) · Wallpaper/lock (`WallpaperTool`, `LockScreen`) | Expected launcher commodity actions. | 🔵 KEEP |

*Rationale: a launcher must do these, but they're not differentiators. Keep them working, spend no roadmap on them.*

---

## 5. Integrations & data flows (keep as optional connectors) 🔵💰🔧

| Integration | Grade | Notes |
|---|---|---|
| Google (Gmail read+send, Calendar, Drive backup) — `GmailClient`, `GoogleAuth`, `DriveBackup` | 🔵 KEEP | Central to the wedge (email). |
| Cross-device brain sync / cloud backup — `BrainCloud`, `BrainSync`, `BrainBackup`, `SupabaseClient`, `AccountStore` | 💰 PREMIUM | Real WTP: "your brain, everywhere, backed up." Natural paid tier. |
| Cloned voice — `ElevenLabs` | 💰 PREMIUM | Clear upsell (BYO key today; managed = paid). |
| Reviews/ratings wall + analytics — `Analytics`, Supabase | 🟢 KEEP | Analytics now rich; keep (opt-out honored). |
| GitHub push / Cowork — `GitHubSearch`, git | 🟡 HIDE | Dev-only. |
| Finnhub / AudD / Twilio / Twitter | 🟡 HIDE | Tied to hidden verticals. |
| Baked hosting tokens (Netlify/Vercel) | 🔧 FIX | **Remove from shipped APK** — shared secret liability. |

---

## 6. Safety, trust & disclosure (recent hardening — keep) 🟢

| Feature | Grade | Notes |
|---|---|---|
| "Sent for you" audit log + Recall (`OutboxStore`) | 🟢 CORE | Keep front-and-center — it's the safety story. |
| Trust tiers / stranger-drafting (`TrustStore`) · rate rails (`AutoReplyGuard`, `UsageLimiter`) · outbound injection filter (`OutboundGuard`) | 🟢 CORE | Keep; get independently audited before scale. |
| Honest AI disclosure (persona + `discloseAi` setting) | 🟢 CORE | Truthful-when-asked, on by default. Keep. |
| OTP auto-fill (`OtpReader`) | 🔧 FIX/💰 | Now opt-in + off by default (good). Account-takeover-grade — keep gated + audited; consider removing for consumer builds. |
| Password-field typing (Accessibility) | 🔧 FIX | Disclosed now. Consider a confirm-before-fill gate. |

---

## Bottom line

- **The product = Section 1 (kernel) + Section 2 (autonomous comms wedge).** That's ~15 files doing the real work.
- **Freeze/hide Section 3** (chess, investing, song ID, look, papers, spicy, cowork, site-builder, expenses, call-screening, vault) behind a "Labs" flag. They're your proof reel, not your roadmap.
- **Cut outright:** Chess coach, Song ID, Spicy posts (🔴).
- **Fix now (liability):** baked hosting tokens, OTP/password gating, get the safety filters audited.
- **Monetize later (💰):** cloud brain sync/backup, cloned voice, the outreach engine as a B2B tool, job-hunt as a vertical.
- **The one strategic question to answer crisply:** you're not building "a launcher OS" (a platform on a platform Android can revoke) — you're building the **portable personal-context + agent layer**, shipped as a launcher for distribution. Grade every future feature by "does this strengthen the kernel or is it another app I'm hand-building for an ecosystem that doesn't exist yet?"
