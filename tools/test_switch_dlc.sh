#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
tmp=$(mktemp -d "${TMPDIR:-/tmp}/botw-switch-dlc.XXXXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM
mkdir -p "$tmp/full/System" "$tmp/full/Pack" "$tmp/full/Movie" "$tmp/no-pack/System" "$tmp/no-version/Pack" \
    "$tmp/installed/romfs" "$tmp/failing/romfs" "$tmp/failing/aoc.bin"
printf '3.0.0\n' > "$tmp/full/System/AocVersion.txt"
printf 'pack content' > "$tmp/full/Pack/Aoc.pack"
printf 'new movie' > "$tmp/full/Movie/new.webm"
printf '3.0.0\n' > "$tmp/no-pack/System/AocVersion.txt"
printf 'pack content' > "$tmp/no-version/Pack/Aoc.pack"
printf 'old movie' > "$tmp/installed/romfs/old.webm"
printf 'old image' > "$tmp/installed/aoc.bin"
printf 'keep' > "$tmp/installed/unrelated.txt"
printf 'old movie' > "$tmp/failing/romfs/old.webm"
cc -O1 -std=gnu11 -I"$root/runtime" "$root/tools/test_switch_dlc.c" \
    "$root/runtime/romfs_build.c" "$root/runtime/dlc_install.c" -o "$tmp/test"
"$tmp/test" "$tmp/full" "$tmp/no-pack" "$tmp/no-version" "$tmp/dlc.bin" "$tmp/installed" "$tmp/failing"
