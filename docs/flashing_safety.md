# Do-Not-Brick Safety Protocol

This project touches a daily-driver phone. The single most important rule:

> **The `SM-S931U` bootloader is locked and cannot be officially unlocked.
> We never flash, unlock, or write partitions on it. Full stop.**

## Hard rules

1. **No `fastboot` against the S25.** Not flash, not erase, not `oem unlock`, not vbmeta.
2. **No partition writes.** No `dd` to `/dev/block/*`, no custom recovery, no boot.img edits.
3. **No exploit / Knox / FRP / secure-boot / dm-verity circumvention.** Out of scope forever.
4. **No `adb reboot bootloader`** on the S25 as part of any modification flow. (Reading state
   in Android is fine; entering download/bootloader to *write* is not.)
5. Everything we do is **reversible by a factory reset** — that is the safety net.

## What IS allowed (and why it's safe)

| Action | Why it can't brick |
|---|---|
| Install AgentShell as default launcher | Normal app install; revert by changing default Home |
| `dpm set-device-owner` (Device Owner) | Sanctioned provisioning; removed by factory reset |
| Lock Task / kiosk mode | Policy flag; cleared with owner removal |
| Shizuku (ADB-privileged calls) | Runs as shell UID, no system partition change |
| Reading props / logs / `getprop` | Read-only |

## Pre-flight checklist before Device Owner provisioning

- [ ] Phone factory-reset and **no Google/Samsung account added yet** (DO provisioning
      refuses if accounts exist).
- [ ] AgentShell APK installed via `adb install`.
- [ ] You know the un-provision command (`dpm remove-active-admin`) AND that a factory
      reset always restores stock.
- [ ] Backups of anything you care about are done (DO setup starts from a clean device).

## Reversal / panic recovery

- Remove launcher default: Settings → Apps → Default apps → Home → One UI Home.
- Remove Device Owner: `adb shell dpm remove-active-admin com.agentos/.AdminReceiver`.
- Nuclear restore: factory reset → fully stock One UI. The locked bootloader guarantees
  the stock OS image is intact; nothing we did can corrupt it.

## Portable track (Cuttlefish / Pixel) — separate safety regime

Real flashing only happens on an **officially unlockable** device (AOSP Cuttlefish VM or a
Pixel with OEM unlock enabled). That track has its own flashing rules and never involves the S25.
