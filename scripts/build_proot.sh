#!/usr/bin/env bash
# Builds proot (GPL-2.0) for the full flavor from the pinned sources in third_party/, the
# way Termux's packages/proot/build.sh does, with talloc (LGPL-3.0) and libandroid-shmem
# (BSD-3-Clause) linked in statically. See third_party/README.md.
# Output: app/src/full/jniLibs/<abi>/libproot.so and libproot-loader.so (or $OUT/<abi>/).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TP="$ROOT/third_party"
NDK_VERSION=28.2.13676358 # a different NDK gives different bytes, so make_full_release.sh would refuse
NDK="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/$NDK_VERSION}"
grep -qx "Pkg.Revision = $NDK_VERSION" "$NDK/source.properties" || {
    echo "Needs Android NDK $NDK_VERSION (found: $(grep Pkg.Revision "$NDK/source.properties" 2>/dev/null))" >&2
    exit 1
}
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
API=26 # the app's minSdk
OUT="${OUT:-$ROOT/app/src/full/jniLibs}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

PROOT_VERSION=5.1.107.101
TALLOC_VERSION=2.5.0
SHMEM_VERSION=0.7

( cd "$TP/sources" && sha256sum -c --quiet SHA256SUMS )

cd "$WORK"
unzip -q "$TP/sources/proot-$PROOT_VERSION.zip"
tar xzf "$TP/sources/talloc-$TALLOC_VERSION.tar.gz"
tar xzf "$TP/sources/libandroid-shmem-$SHMEM_VERSION.tar.gz"

# Same output for the same sources: no build paths in the binaries.
REPRO="-ffile-prefix-map=$WORK=."

for ABI in arm64-v8a x86_64; do
    case "$ABI" in
        arm64-v8a) TRIPLE=aarch64-linux-android ;;
        x86_64)    TRIPLE=x86_64-linux-android ;;
    esac
    CC="$TC/$TRIPLE$API-clang"
    B="$WORK/build-$ABI"
    mkdir -p "$B/lib" "$B/include/sys"

    # talloc: talloc.c alone with a hand-written config.h, instead of Samba's waf build.
    "$CC" -O2 -fPIC $REPRO -D_GNU_SOURCE -D__STDC_WANT_LIB_EXT1__=1 -DHAVE_CONFIG_H=1 \
        -I"$TP/talloc-config" -I"talloc-$TALLOC_VERSION" -I"talloc-$TALLOC_VERSION/lib/replace" \
        -c "talloc-$TALLOC_VERSION/talloc.c" -o "$B/talloc.o"
    "$CC" -O2 -fPIC $REPRO -c "$TP/talloc-config/talloc_compat.c" -o "$B/talloc_compat.o"
    "$TC/llvm-ar" rcsD "$B/lib/libtalloc.a" "$B/talloc.o" "$B/talloc_compat.o"
    cp "talloc-$TALLOC_VERSION/talloc.h" "$B/include/"

    # libandroid-shmem: System V shared memory on ashmem, as Termux builds proot with it.
    # _PATH_TMP comes from Termux's patched NDK headers; it keeps Termux's value so this
    # behaves like the Termux build it replaces (Bori Code can't write there either way).
    "$CC" -O2 -fPIC -std=c11 $REPRO '-D_PATH_TMP="/data/data/com.termux/files/usr/tmp/"' \
        -c "libandroid-shmem-$SHMEM_VERSION/shmem.c" -o "$B/shmem.o"
    "$TC/llvm-ar" rcsD "$B/lib/libandroid-shmem.a" "$B/shmem.o"
    cp "libandroid-shmem-$SHMEM_VERSION/shm.h" "$B/include/sys/shm.h"

    # proot with Termux's flags. Flags go in the environment so the makefile's own += flags
    # stay; LD, GIT and HAS_LOADER_32BIT are plain assignments there, so they're set on the
    # command line. GIT=false keeps this repo's git out of the version string. The loader is
    # shipped separately (PROOT_LOADER points at libproot-loader.so); no 32-bit loader.
    # The one change to proot's source: extension/ashmem_memfd/ashmem_memfd.c calls strcmp and
    # memset without including <string.h>, which NDK 28's clang rejects, so the include is added.
    cp -r "proot-$PROOT_VERSION" "$B/proot"
    sed -i '1i #include <string.h> /* added by Bori Code scripts/build_proot.sh */' \
        "$B/proot/src/extension/ashmem_memfd/ashmem_memfd.c"
    env CC="$CC" STRIP="$TC/llvm-strip" OBJCOPY="$TC/llvm-objcopy" OBJDUMP="$TC/llvm-objdump" \
        CPPFLAGS="-I$B/include -DARG_MAX=131072 -DVERSION=\\\"$PROOT_VERSION\\\"" \
        CFLAGS="$REPRO" \
        LDFLAGS="-L$B/lib -llog -landroid" \
        PROOT_UNBUNDLE_LOADER=/nonexistent \
        make -s -C "$B/proot/src" \
            LD="$CC -Wl,-z,max-page-size=16384" GIT=false HAS_LOADER_32BIT= \
            PROOT_WITH_LIBANDROID_SHMEM=true \
            proot loader/loader

    mkdir -p "$OUT/$ABI"
    "$TC/llvm-strip" -o "$OUT/$ABI/libproot.so" "$B/proot/src/proot"
    "$TC/llvm-strip" -o "$OUT/$ABI/libproot-loader.so" "$B/proot/src/loader/loader"
    echo "$ABI: $(sha256sum "$OUT/$ABI/libproot.so" | cut -c1-16) libproot.so, $(sha256sum "$OUT/$ABI/libproot-loader.so" | cut -c1-16) libproot-loader.so"
done
