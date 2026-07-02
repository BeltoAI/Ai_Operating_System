#!/usr/bin/env bash
#
# Pull your REAL usage stats off the connected (debug) phone and write docs/stats.json,
# which the website reads. Refresh anytime: run this, then commit + push.
#   Needs: phone connected with USB debugging, and sqlite3 (built into macOS).
#
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PKG="com.agentos.shell"
export PATH="$(command -v brew >/dev/null 2>&1 && brew --prefix || echo /opt/homebrew)/share/android-commandlinetools/platform-tools:$PATH"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT

command -v adb >/dev/null 2>&1 || { echo "adb not found. Run build_and_install.sh once, or add platform-tools to PATH."; exit 1; }
adb get-state >/dev/null 2>&1 || { echo "No phone detected. Plug it in, unlock, allow USB debugging (adb devices)."; exit 1; }

# Pull the message DB and the metrics prefs from the app's private storage (works on debug builds).
adb exec-out run-as "$PKG" cat databases/slyos_msgs.db > "$TMP/m.db" 2>/dev/null \
  || { echo "Couldn't read app data. Is this the debug build of SlyOS installed?"; exit 1; }
adb exec-out run-as "$PKG" cat shared_prefs/slyos_metrics.xml > "$TMP/metrics.xml" 2>/dev/null || echo "" > "$TMP/metrics.xml"

MSGS=$(sqlite3 "$TMP/m.db" "SELECT count(*) FROM messages;" 2>/dev/null || echo 0)
PEOPLE=$(sqlite3 "$TMP/m.db" "SELECT count(DISTINCT contact) FROM messages;" 2>/dev/null || echo 0)
DAY24=$(sqlite3 "$TMP/m.db" "SELECT count(*) FROM messages WHERE ts > (strftime('%s','now')*1000 - 86400000);" 2>/dev/null || echo 0)

# Sum "saved seconds" from the metrics prefs: total, and the last 7 day-keys for a weekly figure.
val_for(){ grep -oE "name=\"saved_$1\" value=\"[0-9]+\"" "$TMP/metrics.xml" | sed -E 's/.*value="([0-9]+)".*/\1/' | head -1; }
TOTAL_SEC=$(grep -oE 'name="saved_[0-9-]+" value="[0-9]+"' "$TMP/metrics.xml" | sed -E 's/.*value="([0-9]+)".*/\1/' | awk '{s+=$1} END{print s+0}')
WEEK_SEC=0
for i in 0 1 2 3 4 5 6; do
  d=$(date -v-"${i}"d +%F 2>/dev/null || date -d "-$i day" +%F)
  v=$(val_for "$d"); WEEK_SEC=$((WEEK_SEC + ${v:-0}))
done

# Hours (one decimal for the week, whole for total).
WEEK_H=$(awk "BEGIN{printf \"%.1f\", $WEEK_SEC/3600}")
TOTAL_H=$(awk "BEGIN{printf \"%d\", $TOTAL_SEC/3600}")

# Practice-portfolio growth: read the last snapshot the phone saved (slyos_trade.xml). null if none yet.
adb exec-out run-as "$PKG" cat shared_prefs/slyos_trade.xml > "$TMP/trade.xml" 2>/dev/null || echo "" > "$TMP/trade.xml"
xval(){ grep -oE "name=\"$1\" value=\"[-0-9.]+\"" "$TMP/trade.xml" | sed -E 's/.*value="([-0-9.]+)".*/\1/' | head -1; }
PGROWTH=$(xval growth_pct); PVALUE=$(xval last_value)
[ -z "$PGROWTH" ] && PGROWTH=null
[ -z "$PVALUE" ] && PVALUE=null || PVALUE=$(awk "BEGIN{printf \"%d\", $PVALUE}")

cat > "$SCRIPT_DIR/docs/stats.json" <<JSON
{
  "messages": $MSGS,
  "people": $PEOPLE,
  "last24h": $DAY24,
  "savedHoursWeek": $WEEK_H,
  "savedHoursTotal": $TOTAL_H,
  "portfolioGrowth": $PGROWTH,
  "portfolioValue": $PVALUE,
  "updated": "$(date +%F)"
}
JSON

echo "Wrote docs/stats.json:"
cat "$SCRIPT_DIR/docs/stats.json"
echo ""
echo "Now publish:  git add docs/stats.json && git commit -m 'refresh stats' && git push"
