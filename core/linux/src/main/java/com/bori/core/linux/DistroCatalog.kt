package com.bori.core.linux

import android.os.Build

/** A rootfs tarball for one CPU ABI, pinned by SHA-256 so a tampered or changed file is refused. */
data class RootfsArchive(
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
) {
    val fileName: String get() = url.substringAfterLast('/')
}

/**
 * A Linux distro Bori Code can install. [setupScript] runs once inside the fresh rootfs (as root,
 * through `/bin/sh -c`) to install what the coding CLIs need: Node and npm, git, ripgrep, curl, Python.
 */
data class Distro(
    val id: String,
    val name: String,
    val summary: String,
    /** Per Android ABI ("arm64-v8a", "x86_64"); the two ABIs Bori Code ships proot for. */
    val archives: Map<String, RootfsArchive>,
    val setupScript: String,
    /** Rough disk use after setup, for the "you'll need" line before installing. */
    val installedSizeMb: Int,
    /** musl libc (Alpine) instead of glibc: prebuilt CLIs need their musl builds. */
    val musl: Boolean = false,
    /** Installs the packages named after it, without asking (for CLI install scripts that need one). */
    val installPackages: String,
    /**
     * False for a distro no longer offered for new installs (see [DistroCatalog]); one already
     * installed keeps working and is still listed so it can be used, reinstalled or deleted.
     */
    val offered: Boolean = true,
) {
    /** The archive for this device, or null if the device's ABI isn't supported. */
    fun archiveForDevice(): RootfsArchive? = Build.SUPPORTED_ABIS.firstNotNullOfOrNull { archives[it] }
}

/**
 * The distros offered at setup. Rule (user decision, 2026-10-24, tightened 2026-11-11): only distros
 * that have publicly declared they won't do age verification. Void and Artix have developer
 * statements; Alpine never said either way, so it's no longer offered ([Distro.offered] false; existing installs
 * keep working). Ubuntu, Fedora, Pop!_OS, Debian and Arch are left out. Dropping a distro later is one
 * edit here.
 */
object DistroCatalog {
    val VOID = Distro(
        id = "void",
        name = "Void Linux",
        summary = "glibc, no systemd. Node, Python and the coding CLIs behave as on a PC. Recommended.",
        archives = mapOf(
            "x86_64" to RootfsArchive(
                url = "https://repo-default.voidlinux.org/live/20250202/void-x86_64-ROOTFS-20250202.tar.xz",
                sha256 = "3f48e6673ac5907a897d913c97eb96edbfb230162731b4016562c51b3b8f1876",
                sizeBytes = 48_895_416,
            ),
            "arm64-v8a" to RootfsArchive(
                url = "https://repo-default.voidlinux.org/live/20250202/void-aarch64-ROOTFS-20250202.tar.xz",
                sha256 = "01a30f17ae06d4d5b322cd579ca971bc479e02cc284ec1e5a4255bea6bac3ce6",
                sizeBytes = 45_789_416,
            ),
        ),
        // xbps must update itself before the first full update; -O drops the downloaded packages.
        setupScript = """
            set -e
            xbps-install -Syuy xbps
            xbps-install -Syuy
            xbps-install -y bash nodejs git github-cli ripgrep curl ca-certificates openssh python3
            xbps-remove -Oy
        """.trimIndent(),
        installedSizeMb = 700,
        installPackages = "xbps-install -Sy",
    )

    val ALPINE = Distro(
        id = "alpine",
        name = "Alpine Linux",
        summary = "musl, no systemd, tiny base. The small option; some prebuilt tools need musl builds.",
        archives = mapOf(
            "x86_64" to RootfsArchive(
                url = "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/x86_64/alpine-minirootfs-3.24.3-x86_64.tar.gz",
                sha256 = "f8e1cafba9b1b283b5d30d06f9885c8d2bc161ffe9083ade8e2e157813a53407",
                sizeBytes = 3_704_080,
            ),
            "arm64-v8a" to RootfsArchive(
                url = "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.3-aarch64.tar.gz",
                sha256 = "4d3f80afaebd447c5591a63a73bde380a149fe8b2d85488cafabb2ff97551f50",
                sizeBytes = 4_029_999,
            ),
        ),
        setupScript = """
            set -e
            apk update
            apk upgrade
            apk add bash nodejs npm git github-cli ripgrep curl ca-certificates openssh-client python3
        """.trimIndent(),
        installedSizeMb = 200,
        musl = true,
        installPackages = "apk add",
        offered = false,
    )

    /**
     * Artix Linux (Arch-based, no systemd) for yay and the AUR. Its developers declared they will never
     * require verification or identification from users. The rootfs is Bori Code's slim repack of
     * ARMtix's official aarch64 image (kernels, firmware and disk/network daemons removed, about 90 MB
     * instead of 1.17 GB), made by `scripts/build_artix_rootfs.sh` and hosted on Bori Code's GitHub
     * releases. arm64 only: Artix publishes no x86_64 rootfs.
     *
     * yay is its official release binary, pinned by SHA-256. makepkg runs through GuestPolicy's
     * proot wrapper (no root refusal, no fakeroot). ARMtix's repos are unsigned (SigLevel = Never in its
     * own pacman.conf), so pacman's keyring isn't set up; packages come over HTTPS from its mirrors.
     */
    val ARTIX = Distro(
        id = "artix",
        name = "Artix Linux",
        summary = "Arch-based, glibc, no systemd. yay for the AUR. arm64 only.",
        archives = mapOf(
            "arm64-v8a" to RootfsArchive(
                url = "https://github.com/Atom0131/Bori-Code/releases/download/rootfs-artix-20260921/bori-artix-aarch64-20260921.tar.xz",
                sha256 = "8c3b4b78fec39a978a17bd6547913d1795c93d07e2b267c74cd2b151b4f7cf4f",
                sizeBytes = 90_253_984,
            ),
        ),
        setupScript = """
            set -e
            pacman -Syu --noconfirm
            pacman -S --needed --noconfirm bash nodejs npm git github-cli ripgrep curl ca-certificates openssh python base-devel
            cd /tmp && rm -rf bori-yay && mkdir bori-yay && cd bori-yay
            curl -fL --progress-bar -o yay.tar.gz '$YAY_URL'
            echo '$YAY_SHA256  yay.tar.gz' | sha256sum -c -
            tar -xzf yay.tar.gz
            install -m 755 yay_*/yay /usr/local/bin/yay
            cd /tmp && rm -rf bori-yay
            yes | pacman -Scc >/dev/null 2>&1 || true
            yay --version
        """.trimIndent(),
        installedSizeMb = 1400,
        installPackages = "pacman -S --needed --noconfirm",
    )

    /** yay's official aarch64 release (github.com/Jguer/yay), checked 2026-11-11. Static apart from libc. */
    private const val YAY_URL = "https://github.com/Jguer/yay/releases/download/v13.1.0/yay_13.1.0_aarch64.tar.gz"
    private const val YAY_SHA256 = "be9bde82b0ae601365bf38515a4bfe60840a0b9945994cc8631a974c0492aa2c"

    val all: List<Distro> = listOf(VOID, ARTIX, ALPINE)

    fun get(id: String): Distro? = all.firstOrNull { it.id == id }
}
