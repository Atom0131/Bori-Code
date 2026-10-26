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
 * through `/bin/sh -c`) to install what the coding CLIs need: Node and npm, git, ripgrep, curl.
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
) {
    /** The archive for this device, or null if the device's ABI isn't supported. */
    fun archiveForDevice(): RootfsArchive? = Build.SUPPORTED_ABIS.firstNotNullOfOrNull { archives[it] }
}

/**
 * The distros offered at setup. Rule (user decision, 2026-10-24): never list a distro that does or
 * plans age verification. Ubuntu, Fedora and Pop!_OS are left out for that reason; Debian stays
 * out until the user OKs it. Dropping a distro later is one edit here.
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
            xbps-install -y bash nodejs git ripgrep curl ca-certificates openssh python3
            xbps-remove -Oy
        """.trimIndent(),
        installedSizeMb = 700,
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
            apk add bash nodejs npm git ripgrep curl ca-certificates openssh-client python3
        """.trimIndent(),
        installedSizeMb = 200,
        musl = true,
    )

    val all: List<Distro> = listOf(VOID, ALPINE)

    fun get(id: String): Distro? = all.firstOrNull { it.id == id }
}
