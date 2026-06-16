# GSI / system-image notes (portable track — NOT the S25)

The S25 (`SM-S931U`) is locked and cannot be flashed. These notes apply only to an
officially-unlockable target (AOSP Cuttlefish VM, or a Pixel with OEM unlock enabled).

## Why the S25 facts still matter

The S25 is Treble-enabled (`ro.treble.enabled=true`, first API level 35, dynamic partitions,
virtual A/B). So an AgentOS GSI designed against Treble/Android-15 ABI will be architecturally
compatible with this class of device — we just flash it on hardware that *allows* it.

## GSI flow (unlockable device only)

1. Confirm `OEM unlocking` toggle is present and enabled (the S25 lacks this — that's the
   whole reason for the portable track).
2. `fastboot flashing unlock` (official).
3. Build or download an Android 15 arm64 GSI.
4. `fastboot flash system system.img`, disable AVB only as Google's GSI docs prescribe.
5. Boot, then layer AgentShell/AgentSystemService as the system launcher/service.

## Kernel track (Phase 4) — evidence-gated

For Samsung kernel work you would need the exact kernel source Samsung publishes for the build
(`S931USQS1AYBC`) from their open-source release portal, matched to `6.6.30-android15`. But with
the bootloader locked you cannot boot a modified `boot.img` on the S25 regardless — so kernel
work also belongs to the portable/unlockable track, not this phone. Do not attempt to boot
custom kernels on the S25.
