#!/usr/bin/env bash
# Builds Bori Code's slim Artix Linux rootfs (aarch64) from ARMtix's official image:
#   download + SHA-256 check -> unpack -> drop kernels/firmware/boot/daemons (slim_artix_rootfs.py)
#   -> drop man pages, docs and translations (and tell pacman to skip them from now on)
#   -> pacman.conf tweaks for proot -> pack as tar.xz owned by root.
# Artix's developers declared they will never require verification or identification from users,
# which is Bori Code's rule for offering a distro (see DistroCatalog.kt).
#
#   scripts/build_artix_rootfs.sh <work-dir>
#
# Prints the output file, its SHA-256 and size for DistroCatalog.ARTIX. Runs as a normal user on
# any Linux PC (nothing from the aarch64 rootfs is executed).
set -euo pipefail

IMAGE_DATE=20260921
IMAGE=armtix-runit-$IMAGE_DATE.tar.xz
IMAGE_SHA256=8e1de041c64cb8bd8c1766d8f9048e75544de1c162769c02a626ce484aad4465
OUT=bori-artix-aarch64-$IMAGE_DATE.tar.xz

work=${1:?usage: $0 <work-dir>}
here=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$work"
cd "$work"

if [ ! -f "$IMAGE" ]; then
  curl -fL --progress-bar -o "$IMAGE.part" "https://armtix.artixlinux.org/images/$IMAGE"
  mv "$IMAGE.part" "$IMAGE"
fi
echo "$IMAGE_SHA256  $IMAGE" | sha256sum -c -

rm -rf root && mkdir root
# Device nodes can't be made as a normal user; proot binds the device's /dev anyway.
bsdtar -xpf "$IMAGE" -C root 2>/dev/null || true
test -x root/usr/bin/pacman

python3 -I "$here/slim_artix_rootfs.py" root

rm -rf root/usr/share/man/* root/usr/share/doc/* root/usr/share/info/* root/usr/share/gtk-doc \
  root/usr/share/locale/*
conf=root/etc/pacman.conf
# pacman's Landlock/seccomp sandbox and its free-space check don't work under proot.
sed -i -e 's/^#DisableSandboxFilesystem/DisableSandboxFilesystem/' \
  -e 's/^#DisableSandboxSyscalls/DisableSandboxSyscalls/' \
  -e 's/^CheckSpace/#CheckSpace (off in Bori Code: proot)/' "$conf"
grep -q '^NoExtract' "$conf" || sed -i '/^\[options\]/a # Bori Code: no man pages, docs or translations on the phone.\nNoExtract = usr/share/man/* usr/share/doc/* usr/share/info/* usr/share/gtk-doc/* usr/share/locale/*' "$conf"

# A few files are root-only (e.g. dbus-daemon-launch-helper 4750); everything is "root" in proot,
# and Bori Code's own extractor needs to read them, so give the owner read/write (other bits kept).
chmod -R u+rwX root
rm -f "$OUT"
bsdtar --uid 0 --gid 0 --numeric-owner --options xz:compression-level=9 -cJf "$OUT" -C root .
echo
echo "$PWD/$OUT"
sha256sum "$OUT"
stat -c '%s bytes' "$OUT"
du -sh root
