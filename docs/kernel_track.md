# Phase 4 — Kernel Track (legitimate, staged)

Goal: genuinely build, patch, and **boot your own Linux kernel** — real kernel hacking, real
bragging rights — without touching the locked S25's verified-boot chain.

## The one hard constraint (read once, internalize forever)

The `SM-S931U` bootloader verifies `boot`/`init_boot` signatures (`verifiedbootstate=green`,
`veritymode=enforcing`, `oem_unlock_allowed=0`). A modified kernel fails verification and will
not boot. The only routes past that are secure-boot/Knox bypasses — **out of scope, permanently.**
So custom kernels run on targets that *allow* unsigned boot. That's the whole strategy.

## Stage 1 — Cuttlefish custom kernel (fastest win)  ← start here

Cuttlefish is Google's reference virtual phone. It boots an arbitrary kernel image you point it
at — no signing, no unlock, no risk. This is where you first see *your* kernel boot.

```bash
# Linux host (Ubuntu 22.04+), KVM available. (macOS can't run Cuttlefish — use a Linux VM/box.)
mkdir kernel && cd kernel
repo init -u https://android.googlesource.com/kernel/manifest -b common-android15-6.6
repo sync -j$(nproc)

# 1. Build the stock GKI kernel UNMODIFIED first (always prove the baseline boots).
tools/bazel build //common:kernel_aarch64
# or:  build/build.sh   (older trees)

# 2. Apply the AgentOS demo patch (kernel/agentos_patch/) — see below.
#    Rebuild.

# 3. Boot it in Cuttlefish with your kernel:
launch_cvd --kernel_path=out/.../Image --initramfs_path=out/.../initramfs.img
adb shell dmesg | grep -i agentos        # your banner
adb shell cat /proc/agentos              # your custom kernel node
```

When `dmesg` shows your banner and `/proc/agentos` returns your string, you have compiled,
patched, and booted your own kernel. That's the milestone.

## Stage 2 — S25 GPL source (the trophy reference)

Samsung must publish the exact kernel source under GPLv2. Fetch the source for **your** build
`S931USQS1AYBC` (kernel `6.6.30-android15`), compile it, and diff it against AOSP common.

- Portal: Samsung Open Source Release Center (`opensource.samsung.com`), search model `SM-S931U`.
- See `scripts/fetch_s25_kernel_source.md`.
- You'll build the literal kernel that runs your phone. You **cannot** reflash it onto the
  locked unit — and we don't try. It's a study/compile/diff trophy.

## Stage 3 — Pixel GKI (real silicon, in your pocket)

On an officially-unlockable Pixel: `fastboot flashing unlock`, build a GKI kernel for that
device, `fastboot flash boot boot.img`. Now your custom kernel runs on real phone hardware,
legitimately. This is the "I'm daily-driving my own kernel" endgame.

## What each stage proves

| Stage | You can honestly say | Risk |
|---|---|---|
| Cuttlefish | "I patched and booted my own Android kernel" | none (VM) |
| S25 source | "I compiled my phone's actual kernel from source" | none (no flash) |
| Pixel GKI | "I'm running a custom kernel on real phone silicon" | unlock-only device, reversible |

## Build environment

See `scripts/build_cuttlefish_kernel.md`. Core deps: a Linux host with KVM, `repo`, `git`,
`bazel`/`kleaf`, ~150 GB disk, clang toolchain (pulled by the kernel tree). No macOS for the
boot step — kernel source edits can happen anywhere, but Cuttlefish needs Linux + KVM.
