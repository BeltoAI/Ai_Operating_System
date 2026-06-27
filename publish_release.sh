#!/usr/bin/env bash
#
# Build the shareable (no-keys) APK and publish it as the LATEST GitHub Release, named SlyOS.apk.
# The download button on the website points to releases/latest/download/SlyOS.apk — a stable link —
# so republishing = run this script again. No website change needed.
#
# One-time setup:  brew install gh  &&  gh auth login
#
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

command -v gh >/dev/null 2>&1 || { echo "GitHub CLI missing. Run: brew install gh && gh auth login"; exit 1; }

# 1) Build the APK with none of your keys inside it.
bash "$SCRIPT_DIR/package_apk.sh"
cp "$SCRIPT_DIR/SlyOS-latest.apk" "$SCRIPT_DIR/SlyOS.apk"

# 2) Publish as a new GitHub Release (becomes "latest"). Asset name stays SlyOS.apk for the stable link.
TAG="v$(date +%Y.%m.%d-%H%M)"
gh release create "$TAG" "$SCRIPT_DIR/SlyOS.apk" \
  --title "SlyOS $TAG" \
  --notes "Android APK. Install, then paste your own Anthropic API key on first launch. Android 10+."

echo ""
echo "========================================================="
echo " Published release $TAG."
echo " Stable download link (what the website button uses):"
echo "   https://github.com/BeltoAI/Ai_Operating_System/releases/latest/download/SlyOS.apk"
echo " To republish later: just run this script again."
echo "========================================================="
