# Provisioning AgentOS as Device Owner (S25, no root, no unlock, reversible)

> This is the "most aggressive legal" takeover. Device Owner is an **official Android
> Enterprise feature**, not a hack of the security model. It is removed by a factory reset.
> Read `docs/flashing_safety.md` first.

## Why this works on a locked phone

Device Owner gives a normal (unprivileged) app a large set of `DevicePolicyManager` powers —
lock-task/kiosk, app hide/suspend, user restrictions, default-launcher enforcement — **without
root and without unlocking the bootloader.** The catch: it can only be set on a device with
**no accounts provisioned** (i.e. right after a factory reset).

## Hard precondition

- Phone is **factory reset** and you have **not** added any Google/Samsung account.
- USB debugging enabled, phone authorized to this Mac.
- AgentShell APK built and installed.

## Steps

```bash
# 0. Confirm clean device, single device attached
adb devices -l

# 1. Install AgentShell (built from android/AgentShell)
adb install -r AgentShell-debug.apk

# 2. Make AgentOS the Device Owner. FAILS if any account exists — that's the safety gate.
adb shell dpm set-device-owner com.agentos/.AdminReceiver

# 3. (After AgentShell is set as Home in its first-run flow) verify ownership
adb shell dumpsys device_policy | grep -i "Device Owner"
```

## What AgentShell then does in-app (via DevicePolicyManager)

These are **app code**, not adb, once Device Owner is granted:

- `setLockTaskPackages()` + `startLockTask()` → kiosk: suppresses the notification shade,
  recents, and system nav, delivering the "no notification chaos" experience.
- `addPersistentPreferredActivity(HOME)` → AgentShell is the locked-in launcher.
- `setApplicationHidden()` / `setPackagesSuspended()` → apps become hidden tools.
- `addUserRestriction()` → lock down whichever surfaces you choose.

## Reversal (always available)

```bash
# Remove Device Owner / admin
adb shell dpm remove-active-admin com.agentos/.AdminReceiver
# Or the guaranteed restore: factory reset → fully stock One UI.
```

## If provisioning is refused

`set-device-owner` will error if an account exists or the device was already set up.
That's expected and protective — factory reset and retry **before** signing into anything.
Do NOT look for a way around this gate; there isn't a legal one and we don't want one.
