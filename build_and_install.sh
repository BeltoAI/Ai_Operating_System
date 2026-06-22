#!/usr/bin/env bash
#
# AgentOS — one-shot build & install (command line only, no Android Studio).
# Builds AgentShell, installs it to the connected phone, sets it as the launcher.
# Nothing is flashed. Reversible: Settings > Apps > Default apps > Home > One UI Home.
#
set -euo pipefail

# Portable: locate the repo relative to THIS script, so it works wherever you cloned it.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$SCRIPT_DIR/android"
APK="$REPO/AgentShell/build/outputs/apk/debug/AgentShell-debug.apk"
BREW="$(command -v brew >/dev/null 2>&1 && brew --prefix || echo /opt/homebrew)"

say(){ printf "\n\033[1m==> %s\033[0m\n" "$1"; }

say "1/7  Java 17"
JDK="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
if [ -z "$JDK" ]; then echo "JDK 17 missing. Run: brew install --cask temurin@17"; exit 1; fi
export JAVA_HOME="$JDK"; echo "JAVA_HOME=$JAVA_HOME"

say "2/7  Android command-line SDK"
if ! command -v sdkmanager >/dev/null 2>&1; then
  brew install --cask android-commandlinetools
fi
export ANDROID_HOME="$BREW/share/android-commandlinetools"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
echo "ANDROID_HOME=$ANDROID_HOME"

say "3/7  SDK components (licenses, platform 35, build-tools, platform-tools)"
# Skip entirely if everything's already installed (avoids sdkmanager hanging on a license prompt).
if [ -d "$ANDROID_HOME/platforms/android-35" ] && [ -d "$ANDROID_HOME/build-tools/35.0.0" ] && [ -d "$ANDROID_HOME/platform-tools" ]; then
  echo "SDK components already present — skipping."
else
  yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses >/dev/null 2>&1 || true
  # Pipe 'yes' so any per-component license prompt is auto-accepted (never blocks on input).
  yes | sdkmanager --sdk_root="$ANDROID_HOME" "platform-tools" "platforms;android-35" "build-tools;35.0.0"
fi

say "4/7  Point the project at the SDK"
echo "sdk.dir=$ANDROID_HOME" > "$REPO/local.properties"

say "5/7  Gradle wrapper"
cd "$REPO"
if [ ! -x ./gradlew ]; then
  command -v gradle >/dev/null 2>&1 || brew install gradle
  gradle wrapper --gradle-version 8.9
fi

say "6/7  Build the APK (first run downloads dependencies — be patient)"
./gradlew :AgentShell:assembleDebug --no-daemon

if [ ! -f "$APK" ]; then echo "Build finished but APK not found at $APK"; exit 1; fi
say "APK built: $APK"

say "7/7  Install + set as home"
adb install -r "$APK"
# Try to set AgentOS as default launcher from the command line (best effort).
adb shell cmd role add-role-holder android.app.role.HOME com.agentos.shell 2>/dev/null \
  || echo "(Could not set default launcher via adb — just press HOME on the phone and pick AgentOS > Always.)"
adb shell input keyevent KEYCODE_HOME 2>/dev/null || true

cat <<'DONE'

=========================================================
 SUCCESS. On the phone: press HOME -> choose AgentOS -> Always.
 Reboot to confirm:  adb reboot
 Revert anytime:     Settings > Apps > Default apps > Home > One UI Home
                     (or:  adb uninstall com.agentos.shell)
=========================================================
DONE
