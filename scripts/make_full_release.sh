#!/usr/bin/env bash
# Packages a full-flavor (GitHub) release into dist/full-<version>/:
#   bori-code-full-<version>.apk                 signed fullRelease APK
#   bori-code-full-<version>-proot-source.tar.gz proot/talloc/libandroid-shmem sources + build script
#   SHA256SUMS
# The source tarball goes up with every release: it's how the full build meets GPL-2.0 §3(a) for
# proot and LGPL-3.0 for talloc (see third_party/README.md).
#
# Usage: JAVA_HOME=<JDK 21> scripts/make_full_release.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 21 (see CLAUDE.md)}"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
BUILD_TOOLS="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"

VERSION="$(sed -n 's/^ *versionName *= *"\(.*\)"/\1/p' app/build.gradle.kts | head -1)"
DIST="dist/full-$VERSION"

# The tarball comes from git, so what's shipped must be what's committed.
if [ -n "$(git status --porcelain -- third_party scripts/build_proot.sh app/src/full/jniLibs)" ]; then
    echo "third_party/, scripts/build_proot.sh or app/src/full/jniLibs has uncommitted changes; commit first." >&2
    exit 1
fi

# The committed proot must be exactly what the committed sources build (reproducible build).
CHECK="$(mktemp -d)"
trap 'rm -rf "$CHECK"' EXIT
OUT="$CHECK" scripts/build_proot.sh >/dev/null
for abi in arm64-v8a x86_64; do
    for lib in libproot.so libproot-loader.so; do
        cmp -s "$CHECK/$abi/$lib" "app/src/full/jniLibs/$abi/$lib" || {
            echo "app/src/full/jniLibs/$abi/$lib doesn't match a fresh build of third_party/; run scripts/build_proot.sh" >&2
            exit 1
        }
    done
done

./gradlew -q -Dorg.gradle.java.home="$JAVA_HOME" :app:assembleFullRelease

mkdir -p "$DIST"
APK="$DIST/bori-code-full-$VERSION.apk"
SRC="$DIST/bori-code-full-$VERSION-proot-source.tar.gz"
cp app/build/outputs/apk/full/release/app-full-release.apk "$APK"
"$BUILD_TOOLS/apksigner" verify "$APK"
git archive --format=tar.gz --prefix="bori-code-full-$VERSION-proot-source/" -o "$SRC" HEAD \
    third_party scripts/build_proot.sh
( cd "$DIST" && sha256sum "$(basename "$APK")" "$(basename "$SRC")" > SHA256SUMS )

echo "Release files in $DIST:"
ls -l "$DIST"
