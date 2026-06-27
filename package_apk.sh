#!/usr/bin/env bash
#
# Build a SHAREABLE SlyOS APK to send to friends — with NO keys compiled in.
# Recipients install it and paste their OWN Anthropic key on first launch (the setup screen).
# This protects your keys: your apikey.properties is moved aside during the build, then restored.
#
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$SCRIPT_DIR/android"
BREW="$(command -v brew >/dev/null 2>&1 && brew --prefix || echo /opt/homebrew)"

say(){ printf "\n\033[1m==> %s\033[0m\n" "$1"; }

say "Java 17"
export JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
[ -z "${JAVA_HOME:-}" ] && { echo "JDK 17 missing: brew install --cask temurin@17"; exit 1; }

say "Android SDK"
export ANDROID_HOME="$BREW/share/android-commandlinetools"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
echo "sdk.dir=$ANDROID_HOME" > "$REPO/local.properties"

# Strip keys so the shared APK contains none of yours. Restored automatically on exit.
KEYFILE="$REPO/apikey.properties"
if [ -f "$KEYFILE" ]; then
  mv "$KEYFILE" "$KEYFILE.bak"
  trap '[ -f "$KEYFILE.bak" ] && mv "$KEYFILE.bak" "$KEYFILE"' EXIT
  echo "(your keys moved aside for this build — recipients add their own)"
fi

say "Gradle wrapper"
cd "$REPO"
[ -x ./gradlew ] || { command -v gradle >/dev/null 2>&1 || brew install gradle; gradle wrapper --gradle-version 8.9; }

say "Building shareable APK (no keys baked in)"
./gradlew :AgentShell:assembleDebug --no-daemon

APK="$REPO/AgentShell/build/outputs/apk/debug/AgentShell-debug.apk"
[ -f "$APK" ] || { echo "Build finished but APK not found at $APK"; exit 1; }
OUT="$SCRIPT_DIR/SlyOS-latest.apk"
cp "$APK" "$OUT"

cat <<DONE

=========================================================
 SHAREABLE APK READY:  $OUT
 Send this file to anyone. They:
   1. Open it on their Android phone (allow "install unknown apps").
   2. On first launch, paste their OWN Anthropic API key.
   3. Press Home -> choose SlyOS -> Always.
 No keys of yours are inside it.

 To REPUBLISH after changes: just run this script again and
 resend SlyOS-latest.apk — the version auto-bumps so it
 installs as an update over the old one.
=========================================================
DONE
