#!/bin/sh
# Run the Android container parser with minimal host-side Android I/O shims.
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
tmp=$(mktemp -d "${TMPDIR:-/tmp}/botw-switch-container.XXXXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM
javac -d "$tmp/classes" "$root"/tools/testdata/switch_container/android/system/*.java \
    "$root/tools/testdata/switch_container/android/os/ParcelFileDescriptor.java" \
    "$root/android/botwrecompandroid/app/src/main/java/com/botwrecompandroid/SwitchContainerInspector.java" \
    "$root/tools/testdata/switch_container/com/botwrecompandroid/ContainerCheck.java"
java -cp "$tmp/classes" com.botwrecompandroid.ContainerCheck "$tmp"
