# AgentOS Architecture

## Boot-to-agent chain

```
Power on
  └─ (locked, verified) Samsung bootloader        ← untouched, stock
      └─ Android 15 / One UI vendor + framework    ← untouched, stock
          └─ AgentOS layer (our code):
              AgentSystemService  (long-running, Device-Owner-backed)
                └─ AgentShell      (default HOME / launcher = the boot face)
                    └─ Tool Registry
                        └─ Permission Manager (5 tiers)
                            └─ LLM Router (local + cloud)
                                └─ Action Confirmation Layer
                                    └─ Audit Log (action receipts)
                    └─ Manual Fallback (always reachable)
```

On the locked S25 we don't replace anything below the framework — we **sit on top** of it
and use Device Owner policy to make AgentShell the dominant, near-exclusive experience.

## Component responsibilities

| Component | What it is | On locked S25 | On AOSP/GSI |
|---|---|---|---|
| **AgentShell** | Launcher activity (category `HOME` + `DEFAULT`) | Normal app set as default Home | Can be baked in as the system launcher |
| **AgentSystemService** | Foreground/bound service: priorities, memory, notification ingestion | Normal app + `NotificationListenerService` + Device Owner powers | Can become a privileged system service |
| **AgentPermissions** | The 5-tier permission + receipt engine | App-local policy store | Platform-signed for system perms |
| **Tool Registry** | Declarative map of "tools" (Phone, Messages, Camera, Browser, Files, Settings, plus skills) the agent may invoke | Intents + Shizuku for elevated calls | Direct framework APIs |

## Capability ladder (what runs where)

1. **Normal app (today, no privilege):** launcher, UI, prompt, on-device summaries,
   intent-based tool calls (open dialer, compose SMS, launch camera).
2. **NotificationListener + Accessibility (user-granted):** read/triage notifications →
   powers the "Now" quiet summary and "People" layer.
3. **Device Owner (ADB-provisioned, our aggressive tier):** lock-task/kiosk, hide apps,
   suppress the shade, force launcher, set restrictions — *no root*.
4. **Shizuku (ADB-privileged):** hidden `IPackageManager`/`IActivityManager` calls, granting
   runtime perms, app ops — root-like reach without root.
5. **Platform-signed / system app:** only achievable on AOSP/GSI builds, not the locked S25.
6. **AOSP source mod / custom image / kernel:** portable track only (Cuttlefish, Pixel).
7. **Unrealistic on Samsung without vendor cooperation:** modifying One UI vendor blobs,
   Knox-protected subsystems, secure-boot chain. Not attempted.

## LLM router

- **Local first:** on-device small model for intent parsing, summarization, redaction of
  what's allowed to leave the device.
- **Cloud escalation:** only for tasks the user's permission tier allows, only after the
  redaction pass. Every cloud call is logged as a receipt.

## Action confirmation + audit

Every agent action produces a **receipt**: `{timestamp, tool, args (redacted), permission_tier,
result, reversible?}`. The Audit Log is append-only and viewable from the Memory screen.
"Ask before action" tier blocks execution until the user confirms.
