#!/usr/bin/env bash
# Phase 6 fixture: generated media and a throwaway SlopTank server for the device acceptance sequence.
#
# Usage: server-fixture.sh <published server dir> <work dir> <env output file>
# Starts the server on port 8096 with a fresh data directory, completes the startup wizard, creates a
# throwaway admin and the test user, adds the generated media as a movie library and writes
# C203_USER, C203_PASSWORD and C203_ITEM_ID to the env output file. The server keeps running.
#
# Fixture step (not product behaviour): SlopTank keeps scanned items PlaybackReadiness=Pending until the
# c182 preparation worker has run. The generated media is already portable H.264/AAC MP4, so the items are
# marked Ready directly in the throwaway database while the server is stopped.
set -euo pipefail

server_dir=$1
work=$2
env_out=$3
url=http://127.0.0.1:8096
media=$work/media/movies
data=$work/data
log=$work/server.log
mkdir -p "$media/Test Pattern (2026)" "$data" "$work/cache" "$work/log"

random_secret() { head -c 24 /dev/urandom | base64 | tr -dc A-Za-z0-9 | head -c 20; }

# Generated media only: 90 s test pattern, two AAC tracks (eng, fre), chapters and two SRT sidecars.
item_dir="$media/Test Pattern (2026)"
cat > "$work/chapters.txt" <<'META'
;FFMETADATA1
title=Test Pattern
[CHAPTER]
TIMEBASE=1/1000
START=0
END=45000
title=Opening
[CHAPTER]
TIMEBASE=1/1000
START=45000
END=90000
title=Ending
META
ffmpeg -y -loglevel error \
    -f lavfi -i "testsrc2=size=640x360:rate=24:duration=90" \
    -f lavfi -i "sine=frequency=440:duration=90" \
    -f lavfi -i "sine=frequency=660:duration=90" \
    -i "$work/chapters.txt" \
    -map 0:v -map 1:a -map 2:a -map_metadata 3 -map_chapters 3 \
    -c:v libx264 -preset veryfast -pix_fmt yuv420p -profile:v main -c:a aac -b:a 96k \
    -metadata:s:a:0 language=eng -metadata:s:a:0 title=English \
    -metadata:s:a:1 language=fre -metadata:s:a:1 title=French \
    -movflags +faststart "$item_dir/Test Pattern (2026).mp4"
printf '1\n00:00:02,000 --> 00:00:06,000\nEnglish subtitle line one\n\n2\n00:00:40,000 --> 00:00:50,000\nEnglish subtitle line two\n' \
    > "$item_dir/Test Pattern (2026).en.srt"
printf '1\n00:00:02,000 --> 00:00:06,000\nSous-titre un\n\n2\n00:00:40,000 --> 00:00:50,000\nSous-titre deux\n' \
    > "$item_dir/Test Pattern (2026).fr.srt"

start_server() {
    : > "$log"
    "$server_dir/jellyfin" --datadir "$data" --cachedir "$work/cache" --logdir "$work/log" \
        --nowebclient --ffmpeg "$(command -v ffmpeg)" > "$log" 2>&1 &
    echo $! > "$work/server.pid"
    for _ in $(seq 1 120); do
        grep -q "Startup complete" "$log" && return 0
        sleep 2
    done
    echo "server did not start" >&2
    tail -50 "$log" >&2
    return 1
}

stop_server() {
    local pid
    pid=$(cat "$work/server.pid")
    kill "$pid"
    while kill -0 "$pid" 2>/dev/null; do sleep 1; done
}

json='Content-Type: application/json'
identity='MediaBrowser Client="c203-fixture", Device="fixture", DeviceId="c203-fixture", Version="1.0"'
admin_password=$(random_secret)
user_password=$(random_secret)

start_server
curl -fsS -X POST "$url/Startup/Configuration" -H "$json" \
    -d '{"UICulture":"en-US","MetadataCountryCode":"US","PreferredMetadataLanguage":"en"}'
curl -fsS "$url/Startup/User" > /dev/null
curl -fsS -X POST "$url/Startup/User" -H "$json" -d "{\"Name\":\"c203admin\",\"Password\":\"$admin_password\"}"
curl -fsS -X POST "$url/Startup/Complete"
token=$(curl -fsS -X POST "$url/Users/AuthenticateByName" -H "$json" -H "Authorization: $identity" \
    -d "{\"Username\":\"c203admin\",\"Pw\":\"$admin_password\"}" | jq -r .AccessToken)
auth="Authorization: $identity, Token=\"$token\""
curl -fsS -X POST "$url/Users/New" -H "$auth" -H "$json" \
    -d "{\"Name\":\"c203user\",\"Password\":\"$user_password\"}" > /dev/null
curl -fsS -X POST "$url/Library/VirtualFolders?name=Movies&collectionType=movies&paths=$media&refreshLibrary=true" \
    -H "$auth" -H "$json" -d '{"LibraryOptions":{}}'

# Wait for the scan to store the movie (it is hidden from item queries while Pending).
for _ in $(seq 1 90); do
    count=$(sqlite3 "$data/data/jellyfin.db" \
        "select count(*) from BaseItems where Path like '$media/%' and Type like '%Movie'" 2>/dev/null || echo 0)
    [ "$count" -ge 1 ] && break
    sleep 2
done
[ "$count" -ge 1 ] || { echo "library scan stored no movie" >&2; exit 1; }
sleep 10
stop_server
sqlite3 "$data/data/jellyfin.db" \
    "update BaseItems set PlaybackReadiness=0 where Path like '$media/%' and Type like '%Movie'"
start_server

user_auth=$(curl -fsS -X POST "$url/Users/AuthenticateByName" -H "$json" -H "Authorization: $identity" \
    -d "{\"Username\":\"c203user\",\"Pw\":\"$user_password\"}")
user_token=$(jq -r .AccessToken <<< "$user_auth")
user_id=$(jq -r .User.Id <<< "$user_auth")
item_id=$(curl -fsS "$url/Items?userId=$user_id&Recursive=true&IncludeItemTypes=Movie&SearchTerm=Test%20Pattern" \
    -H "Authorization: $identity, Token=\"$user_token\"" | jq -r '.Items[0].Id')
[ -n "$item_id" ] && [ "$item_id" != null ] || { echo "test item not visible to the user" >&2; exit 1; }

{
    echo "C203_USER=c203user"
    echo "C203_PASSWORD=$user_password"
    echo "C203_ITEM_ID=$item_id"
} >> "$env_out"
echo "fixture ready: item $item_id"
