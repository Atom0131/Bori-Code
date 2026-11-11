package com.bori.core.linux

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The guest's DNS follows the phone's. proot shares the phone's network but not Android's resolver, so
 * the guest reads /etc/resolv.conf itself. Fixed public servers there bypass what Android actually uses
 * (a VPN's DNS, such as Tailscale's, or a network that blocks outside DNS), and lookups failed with the
 * connection itself fine. So each rootfs gets the active network's DNS servers first, the Wi-Fi or
 * mobile network's next, and a public one last, rewritten whenever the network changes. musl reads the
 * file on every lookup and glibc re-reads it when it changes, so running programs pick it up.
 */
@Singleton
class GuestNetwork @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val cm: ConnectivityManager? get() = context.getSystemService(ConnectivityManager::class.java)
    private val watching = AtomicBoolean(false)

    /** Writes [rootfs]'s resolv.conf now, and keeps every rootfs in [rootfsDirs] current from then on. */
    fun prepare(rootfs: File, rootfsDirs: () -> List<File>) {
        write(rootfs)
        if (!watching.compareAndSet(false, true)) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = writeAll()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = writeAll()
            private fun writeAll() = rootfsDirs().forEach(::write)
        }
        runCatching { cm?.registerDefaultNetworkCallback(callback) }
            .onFailure { Log.w(TAG, "Watching the network", it); watching.set(false) }
    }

    /** The resolv.conf for the phone's network right now. */
    fun resolvConf(): String {
        val servers = LinkedHashSet<String>()
        runCatching {
            val cm = cm ?: return@runCatching
            cm.activeNetwork?.let(cm::getLinkProperties)?.dnsServers?.forEach { usable(it)?.let(servers::add) }
            // A VPN's DNS may only answer for its own names; the network under it covers the rest.
            @Suppress("DEPRECATION")
            cm.allNetworks.filter { n ->
                cm.getNetworkCapabilities(n)?.let {
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                } == true
            }.forEach { n -> cm.getLinkProperties(n)?.dnsServers?.forEach { usable(it)?.let(servers::add) } }
        }.onFailure { Log.w(TAG, "Reading the phone's DNS", it) }
        // glibc and musl use the first three. Keep a public server last as a fallback.
        val chosen = (servers.take(MAX_SERVERS - 1) + FALLBACK).distinct().take(MAX_SERVERS)
            .let { if (it.size < 2) (it + FALLBACK_2).distinct() else it }
        return buildString {
            append("# Written by Bori Code from the phone's DNS; rewritten when the network changes.\n")
            chosen.forEach { append("nameserver ").append(it).append('\n') }
            append("options timeout:2 attempts:2\n")
        }
    }

    private fun write(rootfs: File) {
        val etc = File(rootfs, "etc")
        if (!etc.isDirectory) return
        val file = File(etc, "resolv.conf")
        val text = resolvConf()
        runCatching {
            if (file.isFile && !java.nio.file.Files.isSymbolicLink(file.toPath()) && file.readText() == text) return
            val tmp = File(etc, "resolv.conf.bori-tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }.onFailure { Log.w(TAG, "Writing ${file.path}", it) }
    }

    /** Link-local IPv6 servers (fe80::…%wlan0) need a scope the guest's resolver can't use. */
    private fun usable(addr: InetAddress): String? =
        if (addr is Inet6Address && (addr.isLinkLocalAddress || addr.scopeId != 0)) null
        else addr.hostAddress?.substringBefore('%')

    private companion object {
        const val TAG = "GuestNetwork"
        const val MAX_SERVERS = 3
        const val FALLBACK = "1.1.1.1"
        const val FALLBACK_2 = "8.8.8.8"
    }
}
