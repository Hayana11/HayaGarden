#!/usr/bin/env bash
# P2H-3D r3 audio diagnostic: capture dumpsys logs when the raw tone probe plays.
# Run on your host while adb is connected to the device during a Telecom call:
#   bash scripts/diagnose-p2h3d-audio.sh [output-dir]
#
# When prompted, press the "诊断：原始音轨 440Hz（绕开 Media3）" button in NativeCallProbeActivity.
# The script waits for the ElpisCallEngine "raw tone playing:" marker, then captures:
#   - dumpsys audio: PlaybackConfiguration, routing, device state
#   - dumpsys telecom: endpoint state, call state, communication device
#   - dumpsys media.audio_flinger: Audio HAL, track state, device routing

set -euo pipefail

OUT="${1:-.}"
mkdir -p "$OUT"
STAMP="$(date +%Y%m%d-%H%M%S)"
MARKER_LOG="$OUT/raw-tone-log-$STAMP.txt"
LOGCAT_PID=""

cleanup() {
  if [[ -n "${LOGCAT_PID:-}" ]]; then
    kill "$LOGCAT_PID" 2>/dev/null || true
    wait "$LOGCAT_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

# The marker is the synchronization point. Clear the old buffer so a previous
# diagnostic cannot satisfy the wait before the user taps this run's button.
adb logcat -c
adb logcat -v threadtime -s ElpisCallEngine:I '*:S' > "$MARKER_LOG" 2>&1 &
LOGCAT_PID=$!

echo "Ready to capture audio diagnostic logs."
echo "Press ENTER when you are about to tap the raw tone diagnostic button..."
read -r
echo "Waiting for the raw tone playing marker..."

while ! grep -Fq "raw tone playing:" "$MARKER_LOG"; do
  if ! kill -0 "$LOGCAT_PID" 2>/dev/null; then
    echo "The logcat watcher exited before the raw tone marker appeared." >&2
    exit 1
  fi
  sleep 0.1
done

CAPTURE_STAMP="$(date +%Y%m%d-%H%M%S)"
echo "Marker received; starting all dumpsys captures concurrently."

declare -a pids=()
declare -a names=()
declare -a files=()
for name in audio telecom media.audio_flinger; do
  file="$OUT/dumpsys-$name-$CAPTURE_STAMP.txt"
  echo "Capturing $name -> $file"
  adb shell dumpsys "$name" > "$file" 2>&1 &
  pids+=("$!")
  names+=("$name")
  files+=("$file")
done

status=0
for i in "${!pids[@]}"; do
  if wait "${pids[$i]}"; then
    echo "Saved: ${files[$i]}"
  else
    echo "Failed: dumpsys ${names[$i]}" >&2
    status=1
  fi
done

echo ""
echo "Logs captured. Check:"
for i in "${!pids[@]}"; do
  echo "  - ${files[$i]}"
done
echo "  - $MARKER_LOG for the raw tone trigger"
echo ""
echo "Look for:"
echo "  - audio: activePlaybackConfigurations, playback device (should match Telecom)"
echo "  - telecom: currentCallEndpoint, communication device"
echo "  - media.audio_flinger: track routing decision, device assignment"

exit "$status"
