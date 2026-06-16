# Device Interrogation — decoded facts (Phase 0)

Collected read-only via `scripts/collect_device_info.sh` on 2026-06-15.
Raw report archived alongside this file.

## Identity

| Field | Value |
|---|---|
| Model | `SM-S931U` (US Galaxy S25) |
| Device / board | `pa1q` / `sun` |
| Manufacturer | samsung |
| SoC family | Qualcomm Snapdragon ("sun" = 8 Elite), `arm64-v8a` |
| Android | 15 (SDK 35) |
| One UI | 7.0 (`70000`) |
| Build | `S931USQS1AYBC` / `AP3A.240905.015.A2` |
| Security patch | 2025-03-01 |
| Kernel | `6.6.30-android15-8 ... aarch64` |

## Boot / security state (the decisive part)

| Property | Value | Meaning |
|---|---|---|
| `ro.boot.flash.locked` | `1` | Bootloader **locked** |
| `ro.boot.vbmeta.device_state` | `locked` | AVB **locked** |
| `ro.boot.verifiedbootstate` | `green` | Verified-boot chain intact |
| `ro.boot.veritymode` | `enforcing` | dm-verity enforcing |
| `sys.oem_unlock_allowed` | `0` | **OEM unlock not permitted** |
| `ro.oem_unlock_supported` | *(empty)* | No unlock capability exposed |
| `settings global oem_unlock_*` | `null` | No unlock toggle in Settings |
| SELinux | Enforcing | — |

**Conclusion:** `SM-S931U` is a US carrier/unlocked Samsung variant with a permanently
locked bootloader. There is **no official unlock path**. → **Feasibility Path C.**
No flashing on this device. Build the takeover with sanctioned, no-unlock features instead.

## Treble / partition facts (useful for the portable GSI path)

- `ro.treble.enabled = true`, `ro.product.first_api_level = 35`
- `ro.boot.dynamic_partitions = true`, `ro.virtual_ab.enabled = true`, slot `_a`
- A/B partitioning confirmed (`boot_a/b`, `init_boot_a/b`, `super`, `vbmeta`...).
- These matter only for the *portable* Cuttlefish/Pixel-GSI track — not for the S25 itself.

## Facts still needed before ANY hardware-level claim (kept for the record)

- Whether the phone has any accounts/MDM (blocks Device Owner provisioning).
- Recovery image availability for the exact build (Samsung firmware archives).
- For the portable track only: a Pixel (officially unlockable) as flash target.
