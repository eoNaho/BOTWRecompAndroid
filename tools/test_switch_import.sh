#!/bin/sh
# Exercise the Android JNI importer on synthetic NCAs using the host JVM.
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
tmp=$(mktemp -d "${TMPDIR:-/tmp}/botw-switch-import.XXXXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM
java_bin=$(command -v javac)
java_root=$(dirname "$(dirname "$(readlink -f "$java_bin")")")
cmake -S "$root/third_party/hactool/mbedtls" -B "$tmp/mbedtls" \
    -DENABLE_PROGRAMS=OFF -DENABLE_TESTING=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5 >/dev/null
cmake --build "$tmp/mbedtls" --target mbedcrypto -j 4 >/dev/null
src="$root/third_party/hactool"
set --
for name in save sha aes extkeys rsa npdm bktr kip packages pki pfs0 hfs0 nca0_romfs romfs utils nax0 nso lz4 nca xci filepath ConvertUTF cJSON; do
    set -- "$@" "$src/$name.c"
done
cc -shared -fPIC -D_FILE_OFFSET_BITS=64 -Dexit=switch_hactool_exit -I"$java_root/include" -I"$java_root/include/linux" \
    -I"$src" -I"$src/mbedtls/include" \
    "$root/android/botwrecompandroid/app/src/main/cpp/switch_import_jni.c" "$@" \
    "$tmp/mbedtls/library/libmbedcrypto.a" -o "$tmp/libswitch_import_test.so"
cc -I"$src" -I"$src/mbedtls/include" "$root/tools/testdata/switch_import/make_fixture.c" \
    -L"$tmp" -lswitch_import_test -Wl,-rpath,"$tmp" -o "$tmp/make_fixture"
"$tmp/make_fixture" "$tmp"
javac -d "$tmp/classes" "$root"/tools/testdata/switch_container/android/system/*.java \
    "$root/tools/testdata/switch_container/android/os/ParcelFileDescriptor.java" \
    "$root/android/botwrecompandroid/app/src/main/java/com/botwrecompandroid/SwitchContainerInspector.java" \
    "$root/tools/testdata/switch_container/com/botwrecompandroid/ContainerCheck.java" \
    "$root/tools/testdata/switch_import/com/botwrecompandroid/BOTWRecompAndroidActivity.java" \
    "$root/tools/testdata/switch_import/com/botwrecompandroid/PipelineCheck.java"
java -Dbotw.test.lib="$tmp/libswitch_import_test.so" -cp "$tmp/classes" \
    com.botwrecompandroid.BOTWRecompAndroidActivity "$tmp" 2>"$tmp/diagnostics"
if rg -q 'SYNTHETIC_SECRET_MARKER' "$tmp/diagnostics"; then
    echo "Key value was exposed in diagnostics" >&2; exit 1
fi
if [ "$(rg -c 'Invalid key entry master_key_01' "$tmp/diagnostics")" != 1 ]; then
    echo "Malformed key diagnostic was missing or repeated" >&2; exit 1
fi
cat "$tmp/diagnostics"
java -Dbotw.test.lib="$tmp/libswitch_import_test.so" -cp "$tmp/classes" \
    com.botwrecompandroid.PipelineCheck "$tmp"
