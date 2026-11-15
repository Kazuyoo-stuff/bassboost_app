#!/system/bin/sh
# BassBoost by Kazuyoo
# Copyright (c) 2026 Kazuyoo
# Licensed under the GNU General Public License v3.0 or later.
# See LICENSE file for details.
# Open-source powered — with appreciation to GL-DP and all contributors.
#
# Simple setup: non-root setup + one preset. Needs BassBoost v1.4 or newer.
#   adb shell sh /sdcard/Download/bassboost.sh        (root shell: sh /sdcard/Download/bassboost.sh)
#
# Set the DTS equalizer in the phone settings to match (it stacks with the app):
#   100 Hz +3 | 300 Hz 0 | 1K +1 | 3K 0 | 10K 0

# Preset:
#   0 = off        effects disabled
#   1 = soft       light boost, safest
#   2 = balanced   strong, punchy bass, clean and tight at high volume (recommended)
#   3 = heavy      more bass; may stutter at high volume on weaker speakers
PRESET=2

P="dev.kazuyoo.bassboost"

# Setup (safe to repeat): permissions, background rights, first start.
pm grant "$P" android.permission.POST_NOTIFICATIONS 2>/dev/null
cmd appops set "$P" RUN_IN_BACKGROUND allow 2>/dev/null
cmd appops set "$P" RUN_ANY_IN_BACKGROUND allow 2>/dev/null
dumpsys deviceidle whitelist +"$P" > /dev/null 2>&1
am start-foreground-service -n "$P/.BassService" > /dev/null 2>&1
sleep 2

# Apply the preset.
am startservice -n "$P/.BassService" -a "$P.SET_PRESET" --ei value "$PRESET" > /dev/null 2>&1
sleep 1

PID="$(pidof "$P")"
if [ -n "$PID" ]; then
  echo "Service running (pid $PID). Preset $PRESET applied."
else
  echo "Service NOT running. Is the APK installed?  pm list packages | grep kazuyoo"
fi
echo "Dynamics Processing status (Enabled must be y; n is normal for preset 0, or while headphones/Bluetooth are connected):"
dumpsys media.audio_flinger | grep -B10 "name: Dynamics Processing" | grep -A1 "Session State"
echo
echo "Manual step on the phone: allow Autostart and set Battery to 'No restrictions' for BassBoost."
