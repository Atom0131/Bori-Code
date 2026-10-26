#!/bin/bash
# Rebuilds libtermux.so (the PTY JNI from src/main/cpp/termux.c) into src/main/jniLibs.
# Run by hand after changing termux.c; the outputs are committed. Not wired into Gradle because
# ndk-build (make) can't cope with the space in this repo's path.
# Needs NDK r28+ (16 KB page alignment): ANDROID_NDK_HOME or ~/Android/Sdk/ndk/28.2.13676358.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
ndk="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/28.2.13676358}"
bin="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
for pair in arm64-v8a:aarch64-linux-android26 x86_64:x86_64-linux-android26; do
  abi=${pair%%:*}; target=${pair##*:}
  out="$here/src/main/jniLibs/$abi"
  mkdir -p "$out"
  "$bin/clang" --target="$target" -shared -fPIC -std=c11 -Wall -Wextra -Werror -Os \
    -fno-stack-protector -Wl,--gc-sections -Wl,-z,max-page-size=16384 -Wl,--build-id=none \
    -o "$out/libtermux.so" "$here/src/main/cpp/termux.c" -llog
  "$bin/llvm-strip" --strip-unneeded "$out/libtermux.so"
  echo "$abi: $(readelf -lW "$out/libtermux.so" | awk '/LOAD/{print $NF}' | sort -u | tr '\n' ' ')"
done
