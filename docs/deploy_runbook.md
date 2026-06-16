# Deploy Runbook — get AgentOS onto the S25 (no flashing)

What this achieves: after normal boot, the phone lands on **AgentOS** instead of One UI Home,
every time. With the optional Device Owner step, the notification shade and recents are
suppressed too. No bootloader unlock, no kernel, no flashing.

What it does NOT change: the Samsung power-on/verified-boot logo (locked bootloader — untouchable).

---

## Part A — Build the APK (on the Mac, ~15 min first time)

```bash
# 1. JDK 17 (AGP 8.5 needs 17; your sandbox had 11 — install on the Mac)
brew install --cask temurin@17
/usr/libexec/java_home -V        # confirm 17 is present

# 2. Android command-line SDK (or just install Android Studio, which bundles it)
brew install --cask android-commandlinetools
yes | sdkmanager --licenses
sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
export ANDROID_HOME="$HOME/Library/Android/sdk"   # adjust if cmdline-tools put it elsewhere

# 3. Generate the Gradle wrapper jar (only the .properties is in the repo)
cd ~/Downloads/MADSCIENTIST/agentos/android
gradle wrapper --gradle-version 8.9      # uses brew's gradle once: `brew install gradle`

# 4. Build the debug APK
./gradlew :AgentShell:assembleDebug
# -> AgentShell/build/outputs/apk/debug/AgentShell-debug.apk
```

Easier alternative: open `~/Downloads/MADSCIENTIST/agentos/android` in **Android Studio**,
let it sync, press Run with the phone connected. Studio handles SDK + wrapper automatically.

---

## Part B — Install + become the home screen (the moment you've been waiting for)

```bash
adb install -r AgentShell/build/outputs/apk/debug/AgentShell-debug.apk

# Make it the default launcher:
#   Option 1 (UI): press Home -> Android asks which launcher -> pick AgentOS -> Always.
#   Option 2 (adb): set the HOME role directly
adb shell cmd role add-role-holder android.app.role.HOME com.agentos.shell

# See it now:
adb shell input keyevent KEYCODE_HOME      # phone jumps to AgentOS
```

Reboot the phone (`adb reboot`) → it comes up, plays Samsung's boot logo, then **boots straight
into AgentOS**. That's your agent phone.

To revert anytime: Settings → Apps → Default apps → Home app → One UI Home.

---

## Part C — Optional: full kiosk takeover (Device Owner)

Do this only on a **factory-reset phone with no accounts added** (the safety gate). Full steps
in `scripts/provision_device_owner.md`. Summary:

```bash
adb shell dpm set-device-owner com.agentos.shell/.AdminReceiver
```
AgentShell then enables lock-task/kiosk: no shade, no recents, locked-in launcher — the
"this is a different phone" experience. Reversible by `dpm remove-active-admin` or factory reset.

---

## Troubleshooting
- `assembleDebug` fails on JDK → confirm `JAVA_HOME` points at 17, not 11.
- `role add-role-holder` not found on older shells → just use the UI launcher picker.
- Wordmark looks plain → drop a script `.ttf` into `AgentShell/src/main/res/font/` and point
  `T.scriptFamily` at it (see theme/Tokens.kt note).
- App installs but home button still opens One UI → you didn't set AgentOS as default Home yet.
