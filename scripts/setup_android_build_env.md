# Android build environment (macOS)

For building AgentShell (the app) and, on the portable track, AOSP/Cuttlefish.

## App build (AgentShell) — what you need now

```bash
brew install --cask android-platform-tools   # adb/fastboot (done)
brew install --cask temurin@17                # JDK 17 for AGP
brew install --cask android-studio            # SDK manager, emulator, Gradle
```

- In Android Studio: install SDK Platform 35, Build-Tools 35, an arm64 system image for the emulator.
- Open `android/` as a Gradle project once AgentShell source is added.
- `./gradlew :AgentShell:assembleDebug` → `AgentShell-debug.apk` → `adb install -r`.

## Portable track — AOSP Cuttlefish (Linux host or Linux VM)

Cuttlefish does not run natively on macOS; use a Linux box or cloud VM.

```bash
# On Ubuntu 22.04+
sudo apt install -y git curl repo
mkdir aosp && cd aosp
repo init -u https://android.googlesource.com/platform/manifest -b android-15.0.0_r1
repo sync -c -j$(nproc)
source build/envsetup.sh
lunch aosp_cf_x86_64_phone-trunk_staging-userdebug
m -j$(nproc)
launch_cvd            # boots the Cuttlefish virtual phone
```

This is where full system-image AgentOS work happens (custom launcher baked in, privileged
service, framework mods) with zero risk to the S25.

## Pixel GSI track (real silicon, officially unlockable)

Only on a device whose bootloader can be officially unlocked (a Pixel, not the S25). Build a
GSI (`lunch aosp_arm64-...`), then `fastboot flash system` per Google's GSI docs. Out of scope
for the S25; documented here only so the architecture stays portable.
