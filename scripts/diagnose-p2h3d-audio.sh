#!/usr/bin/env bash
# P2H-3D r3 audio diagnostic: capture dumpsys logs when the raw tone probe plays.
# Run on your host while adb is connected to the device during a Telecom call:
#   bash scripts/diagnose-p2h3d-audio.sh [output-dir]
#
# When prompted, press the "诊断：原始音轨 440Hz（绕开 Media3）" button in NativeCallProbeActivity.
# The script will capture:
#   - dumpsys audio: PlaybackConfiguration, routing, device state
#   - dumpsys telecom: endpoint state, call state, communication device
#   - dumpsys media.audio_flinger: Audio HAL, track state, device routing

set -euo pipefail

OUT="${1:-.}"
mkdir -p "$OUT"
STAMP="$(date +%Y%m%d-%H%M%S)"

echo "Ready to capture audio diagnostic logs."
echo "Press ENTER when you are about to tap the raw tone diagnostic button..."
read -r

echo "Waiting for probe to run..."
sleep 0.5

for name in audio telecom media.audio_flinger; do
  file="$OUT/dumpsys-$name-$STAMP.txt"
  adb shell dumpsys "$name" > "$file"
  echo "Saved: $file"
done

echo ""
echo "Logs captured. Check:"
for name in audio telecom media.audio_flinger; do
  echo "  - dumpsys-$name-$STAMP.txt for device routing and communication device"
done
echo ""
echo "Look for:"
echo "  - audio: activePlaybackConfigurations, playback device (should match Telecom)"
echo "  - telecom: currentCallEndpoint, communication device"
echo "  - media.audio_flinger: track routing decision, device assignment"
