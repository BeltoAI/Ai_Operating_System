#!/usr/bin/env bash
#
# AgentOS — Phase 0 device interrogation (READ-ONLY)
# Samsung Galaxy S25, owner device, macOS + USB.
#
# SAFETY CONTRACT:
#   - This script ONLY READS. It never flashes, unlocks, wipes, reboots to
#     bootloader, or changes any setting.
#   - No fastboot. No `adb reboot`. No `setprop`. No partition writes.
#   - Every command is a getprop / read / list. Worst case = a denied read.
#
# USAGE:
#   1. On the phone: Settings > About phone > Software info > tap "Build number"
#      7x to enable Developer options, then Settings > Developer options >
#      enable "USB debugging".
#   2. Plug phone into MacBook via USB. On the phone tap "Allow" for USB debugging.
#   3. chmod +x collect_device_info.sh && ./collect_device_info.sh
#   4. Send me the generated agentos_device_report_*.txt
#
set -uo pipefail

OUT="agentos_device_report_$(date +%Y%m%d_%H%M%S).txt"

# Locate adb (Homebrew, Android SDK platform-tools, or PATH).
ADB="$(command -v adb || true)"
for c in "$HOME/Library/Android/sdk/platform-tools/adb" "/opt/homebrew/bin/adb" "/usr/local/bin/adb"; do
  [ -z "$ADB" ] && [ -x "$c" ] && ADB="$c"
done
if [ -z "$ADB" ]; then
  echo "ERROR: adb not found. Install with:  brew install --cask android-platform-tools"
  exit 1
fi

log() { echo "$@" | tee -a "$OUT"; }
run() {  # run <label> <adb shell command...>
  local label="$1"; shift
  log ""
  log "===== $label ====="
  "$ADB" shell "$@" 2>&1 | tee -a "$OUT"
}
prop() { # prop <getprop key>
  log ""
  log "--- getprop $1 ---"
  "$ADB" shell getprop "$1" 2>&1 | tee -a "$OUT"
}

: > "$OUT"
log "AgentOS Phase 0 device report  —  $(date)"
log "adb binary: $ADB"

log ""
log "===== 1. Connection ====="
"$ADB" devices -l 2>&1 | tee -a "$OUT"

if ! "$ADB" get-state >/dev/null 2>&1; then
  log ""
  log "No device in 'device' state. Confirm USB-debugging prompt was accepted, then rerun."
  exit 1
fi

# ---- Identity ----
prop ro.product.model
prop ro.product.device
prop ro.product.name
prop ro.product.manufacturer
prop ro.product.board
prop ro.boot.hardware
prop ro.hardware

# ---- SoC / ABI ----
prop ro.product.cpu.abi
prop ro.product.cpu.abilist
prop ro.board.platform

# ---- Android / One UI / build ----
prop ro.build.version.release
prop ro.build.version.sdk
prop ro.build.version.incremental
prop ro.build.display.id
prop ro.build.id
prop ro.build.version.security_patch
prop ro.build.version.oneui          # One UI version, if exposed
prop ro.build.fingerprint
prop ro.system.build.fingerprint
prop ro.vendor.build.fingerprint
prop ro.product.build.fingerprint

# ---- Bootloader / verified boot / AVB ----
prop ro.boot.verifiedbootstate
prop ro.boot.flash.locked
prop ro.boot.vbmeta.device_state
prop ro.boot.warranty_bit
prop ro.warranty_bit
prop ro.boot.veritymode
prop ro.boot.bootloader
prop ro.bootloader

# ---- OEM unlocking exposure (read-only; does NOT change anything) ----
prop sys.oem_unlock_allowed
prop ro.oem_unlock_supported
log ""
log "--- OEM unlock setting (Settings.Global, read-only) ---"
"$ADB" shell settings get global oem_unlock_supported 2>&1 | tee -a "$OUT"
"$ADB" shell settings get global oem_unlock_allowed   2>&1 | tee -a "$OUT"

# ---- Treble / GSI compatibility ----
prop ro.treble.enabled
prop ro.vndk.version
prop ro.product.first_api_level
prop ro.boot.dynamic_partitions
prop ro.boot.dynamic_partitions_retrofit
prop ro.virtual_ab.enabled
prop ro.boot.slot_suffix

# ---- Kernel ----
run "Kernel (uname -a)" uname -a

# ---- SELinux ----
run "SELinux mode (getenforce)" getenforce

# ---- Partition scheme (read-only listing) ----
run "Partitions: /dev/block/by-name" "ls -al /dev/block/by-name 2>/dev/null || echo 'not readable without root'"
run "Partitions: /dev/block/bootdevice/by-name" "ls -al /dev/block/bootdevice/by-name 2>/dev/null || echo 'not readable without root'"

# ---- Full getprop dump (most useful single artifact) ----
run "FULL getprop dump" getprop

log ""
log "===== DONE ====="
log "Report written to: $OUT"
log "Send me this file. I will NOT recommend flashing, unlocking, or rebooting until I have reviewed it."
