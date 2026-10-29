#!/bin/sh
# Desktop converter using the app's own Java code (needs ffmpeg and a JDK):
#   tools/convert.sh song.mp3 song.nes [512|1024] ["artist"] ["title"]
set -e
[ $# -ge 2 ] || { echo "usage: $0 input output.nes [512|1024] [artist] [title]" >&2; exit 1; }
here=$(cd "$(dirname "$0")/.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
javac -d "$tmp/cls" "$here/src/com/music2nes/RomBuilder.java" "$here/test/com/music2nes/TestMain.java"
ffmpeg -v error -i "$1" -vn -ac 1 -ar 48000 -f s16le "$tmp/pcm"
name=$(basename "$1"); name=${name%.*}
java -cp "$tmp/cls" com.music2nes.TestMain "$tmp/pcm" 48000 "$2" "${4:-}" "${5:-$name}" "${3:-512}"
