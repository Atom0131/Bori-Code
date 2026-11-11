#!/usr/bin/env python3
"""
Slims an unpacked ARMtix (Artix Linux for aarch64) rootfs for Bori Code's proot Linux: removes the
packages a phone container never uses (kernels, firmware, initramfs, boot-loader and network
daemons, filesystem tools for disks), the way `pacman -R` would (their files and their entries in
pacman's local database) but without running anything from the aarch64 rootfs, so it works on an
x86_64 PC as a normal user. A package is only removed when nothing left depends on it.

    scripts/slim_artix_rootfs.py <unpacked-rootfs-dir>

Then pack it with `bsdtar --uid 0 --gid 0 --numeric-owner -cJf <out>.tar.xz -C <dir> .` (see
scripts/build_artix_rootfs.sh).
"""
import os
import re
import shutil
import sys

# Removed when nothing else needs them. Prefix match on the package name.
DROP_PREFIXES = [
    "linux-aarch64", "linux-firmware", "mkinitcpio", "kexec-tools",
    "btrfs-progs", "xfsprogs", "f2fs-tools", "dosfstools", "e2fsprogs",
    "wpa_supplicant", "dhcpcd", "haveged", "ntp", "openssh-runit",
    "iptables", "nftables", "libnftnl", "libnetfilter_conntrack", "libnfnetlink",
    "pciutils", "hwdata", "kbd", "libusb", "pcsclite", "tpm2-tss",
]


def read_desc(path):
    """%SECTION% blocks of a pacman desc file -> {section: [lines]}."""
    out, key = {}, None
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.rstrip("\n")
            if line.startswith("%") and line.endswith("%"):
                key = line.strip("%")
                out[key] = []
            elif line and key:
                out[key].append(line)
    return out


def dep_name(dep):
    return re.split(r"[<>=:]", dep, maxsplit=1)[0].strip()


def main(root):
    local = os.path.join(root, "var/lib/pacman/local")
    pkgs = {}
    for entry in os.listdir(local):
        desc_path = os.path.join(local, entry, "desc")
        if not os.path.isfile(desc_path):
            continue
        desc = read_desc(desc_path)
        name = desc["NAME"][0]
        pkgs[name] = {
            "dir": os.path.join(local, entry),
            "depends": {dep_name(d) for d in desc.get("DEPENDS", [])},
            "provides": {dep_name(p) for p in desc.get("PROVIDES", [])} | {name},
        }

    drop = {n for n in pkgs if any(n.startswith(p) for p in DROP_PREFIXES)}
    # Keep anything a kept package depends on (directly or through what it provides).
    changed = True
    while changed:
        changed = False
        needed = set()
        for n, p in pkgs.items():
            if n not in drop:
                needed |= p["depends"]
        for n in list(drop):
            if pkgs[n]["provides"] & needed:
                drop.discard(n)
                changed = True

    kept_files = set()
    for n, p in pkgs.items():
        if n in drop:
            continue
        files_path = os.path.join(p["dir"], "files")
        if os.path.isfile(files_path):
            kept_files.update(read_desc(files_path).get("FILES", []))

    removed_bytes = 0
    for n in sorted(drop):
        p = pkgs[n]
        files = read_desc(os.path.join(p["dir"], "files")).get("FILES", [])
        # Files first (deepest first), then directories no kept package lists and that are now empty.
        for rel in sorted(files, key=len, reverse=True):
            path = os.path.join(root, rel.rstrip("/"))
            if rel.endswith("/"):
                if rel not in kept_files and os.path.isdir(path) and not os.path.islink(path):
                    try:
                        os.rmdir(path)
                    except OSError:
                        pass
            elif rel not in kept_files and (os.path.islink(path) or os.path.exists(path)):
                if not os.path.islink(path):
                    removed_bytes += os.path.getsize(path)
                os.remove(path)
        shutil.rmtree(p["dir"])
        print(f"removed {n}")

    # Not owned by any package: built at install time by mkinitcpio / kernel hooks, and pacman's cache.
    for leftover in ["boot", "usr/lib/modules", "var/cache/pacman/pkg"]:
        path = os.path.join(root, leftover)
        if os.path.isdir(path):
            for child in os.listdir(path):
                c = os.path.join(path, child)
                if os.path.isdir(c) and not os.path.islink(c):
                    shutil.rmtree(c)
                else:
                    os.remove(c)
    print(f"{len(drop)} packages removed, {removed_bytes // (1024 * 1024)} MB of their files")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
