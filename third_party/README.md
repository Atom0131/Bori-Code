# Third-party sources built into the full flavor

The full (GitHub) build ships proot as `libproot.so` and `libproot-loader.so` in
`app/src/full/jniLibs/`. They're built from the upstream sources below (with one added include
line, see below) by
`scripts/build_proot.sh` (Android NDK 28.2.13676358, API 26), which follows Termux's
`packages/proot/build.sh`. The Play flavor ships none of this.

| Source | Version | License | From | SHA-256 |
|---|---|---|---|---|
| `sources/proot-5.1.107.101.zip` | 5.1.107.101 (Termux's proot fork) | GPL-2.0 | https://github.com/termux/proot/archive/v5.1.107.101.zip | `a66743f5…640017f` |
| `sources/talloc-2.5.0.tar.gz` | 2.5.0 | LGPL-3.0-or-later | https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz | `912afa23…0ebab007` |
| `sources/libandroid-shmem-0.7.tar.gz` | 0.7 | BSD-3-Clause | https://github.com/termux/libandroid-shmem/archive/refs/tags/v0.7.tar.gz | `1e5ff845…20b98e867` |

The full hashes are in `sources/SHA256SUMS`; the build script checks them first. They match the
hashes in termux-packages' recipes for the same versions.

## Rebuilding

```
scripts/build_proot.sh          # writes app/src/full/jniLibs/{arm64-v8a,x86_64}/
OUT=/some/dir scripts/build_proot.sh
```

The build is reproducible: the same sources and NDK give byte-identical binaries.

## How it differs from Termux's build
- **talloc and libandroid-shmem are linked in statically**, so the app ships no separate
  `libtalloc.so` or `libandroid-shmem.so`. talloc is built from `talloc.c` alone, with the
  hand-written `talloc-config/config.h` instead of Samba's waf build. `talloc-config/talloc_compat.c`
  holds the one function it needs from Samba's `lib/replace` (copied, LGPL-3.0-or-later).
- Because talloc is LGPL and linked statically, this folder and the build script are what let
  anyone relink proot against a modified talloc.
- **No 32-bit loader** (`loader32`): there are no 32-bit guests.
- **The loader is a separate file.** The app sets `PROOT_LOADER` to `libproot-loader.so`.
- `_PATH_TMP` for libandroid-shmem keeps Termux's value (`/data/data/com.termux/files/usr/tmp/`),
  which Termux normally gets from its patched NDK headers. The app can't write there, which is
  also how the earlier prebuilt Termux binary behaved.
- **One source change:** `extension/ashmem_memfd/ashmem_memfd.c` calls `strcmp` and `memset`
  without `#include <string.h>`, which NDK 28's clang rejects, so the build script adds that one
  include line. Nothing else in the sources is changed.
- **Known limitation:** keyed System V shared memory (`shmget` with a key) can't work, because of
  the `_PATH_TMP` above. Private segments work. This is unchanged from the Termux prebuilt.
- The script checks for NDK 28.2.13676358, since another NDK gives different bytes.

## Source for users (GPL-2.0 §3)
Every full release on GitHub carries `bori-code-full-<version>-proot-source.tar.gz`, which holds
this folder and `scripts/build_proot.sh`. See `scripts/make_full_release.sh`.
