#!/usr/bin/env bash
# Phase 6 device acceptance sequence. Runs against a booted emulator or device with the libre debug app and
# its androidTest APK installed, and a server prepared by server-fixture.sh.
#
# Usage: run-device-sequence.sh <server url as seen from the device> <user> <password> <item id>
# The device log is saved to $C203_LOGCAT_OUT (default logcat.txt) when the script exits.
# Sequence: download online -> airplane mode on -> kill the app -> browse Downloads, play, seek, switch audio
# and subtitles, finish (offline) -> airplane mode off -> progress synced exactly once.
set -euo pipefail

server_url=$1
user=$2
password=$3
item_id=$4
app=org.jellyfin.mobile.debug
runner=$app.test/androidx.test.runner.AndroidJUnitRunner
logcat_out=${C203_LOGCAT_OUT:-logcat.txt}
trap 'adb logcat -d > "$logcat_out" || true' EXIT

run_step() {
    local output
    output=$(adb shell am instrument -w \
        -e c203ServerUrl "$server_url" -e c203User "$user" -e c203Password "$password" -e c203ItemId "$item_id" \
        -e class "org.jellyfin.mobile.acceptance.$1" "$runner" | tr -d '\r')
    echo "$output"
    grep -q '^OK (1 test)$' <<< "$output" || { echo "FAILED: $1" >&2; exit 1; }
}

airplane() {
    adb shell cmd connectivity airplane-mode "$1"
    sleep 10
    echo "airplane mode $1: $(adb shell settings get global airplane_mode_on | tr -d '\r')"
}

adb shell pm grant "$app" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
airplane disable
run_step Phase6Step1DownloadOnlineTest

airplane enable
adb shell am force-stop "$app"
if adb shell pidof "$app" > /dev/null; then
    echo "app process still running after force-stop" >&2
    exit 1
fi
run_step Phase6Step2OfflinePlaybackTest

airplane disable
run_step Phase6Step3ReconnectSyncTest
echo "Phase 6 device sequence passed"
