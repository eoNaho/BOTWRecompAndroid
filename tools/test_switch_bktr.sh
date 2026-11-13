#!/bin/sh
# Build and run a self-contained BKTR relocation and AES-CTR regression test on the host.
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
tmp=$(mktemp -d "${TMPDIR:-/tmp}/botw-bktr.XXXXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM
cmake -S "$root/third_party/hactool/mbedtls" -B "$tmp/mbedtls" \
    -DENABLE_PROGRAMS=OFF -DENABLE_TESTING=OFF -DCMAKE_POLICY_VERSION_MINIMUM=3.5 >/dev/null
cmake --build "$tmp/mbedtls" --target mbedcrypto -j 4 >/dev/null
src="$root/third_party/hactool"
set --
for name in save sha aes extkeys rsa npdm bktr kip packages pki pfs0 hfs0 nca0_romfs romfs utils nax0 nso lz4 nca xci filepath ConvertUTF cJSON; do
    set -- "$@" "$src/$name.c"
done
cc -D_FILE_OFFSET_BITS=64 -I"$src" -I"$src/mbedtls/include" "$root/tools/test_switch_bktr.c" \
    "$@" "$tmp/mbedtls/library/libmbedcrypto.a" -o "$tmp/test_switch_bktr"
"$tmp/test_switch_bktr"
