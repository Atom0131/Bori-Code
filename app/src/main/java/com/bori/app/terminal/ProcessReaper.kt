package com.bori.app.terminal

import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File

/**
 * Ends everything a terminal tab started, not just its shell.
 *
 * A Linux tab's shell is proot. Killing proot with SIGKILL (Termux's `finishIfRunning`) gives it no
 * chance to run `--kill-on-exit`, so the programs it was tracing are left behind with init as
 * parent and a deleted PTY. bash then spins on that dead PTY at 100% CPU: on the Fold an orphaned
 * `bash -lc claude` ran for an hour and heated the phone. So a closed tab's whole process tree
 * (and its session: Termux's subprocess calls setsid, so the session id is the shell's pid) gets
 * SIGHUP, then SIGKILL, and [sweepOrphans] catches anything left over from before.
 */
object ProcessReaper {
    private const val TAG = "ProcessReaper"
    private const val GRACE_MS = 300L

    private class Proc(val pid: Int, val ppid: Int, val sid: Int)

    /** Hang up on [shellPid]'s process tree, then kill whatever is still there. Call before killing the shell. */
    fun killTree(shellPid: Int) {
        if (shellPid <= 0) return
        val first = tree(shellPid)
        first.forEach { signal(it, OsConstants.SIGHUP) }
        Thread({
            Thread.sleep(GRACE_MS)
            // The shell may be gone by now (its children then have init as parent), so they're
            // found again by session id, which they keep.
            val alive = tree(shellPid).filter { it == shellPid || File("/proc/$it").exists() }
            alive.forEach { signal(it, OsConstants.SIGKILL) }
            if (alive.isNotEmpty()) Log.i(TAG, "killed ${alive.size} leftover process(es) of $shellPid")
        }, "ProcessReaper").start()
    }

    /** Kills our processes that lost both their parent (now init) and their terminal (a deleted PTY). */
    fun sweepOrphans() {
        Thread({
            val me = Process.myPid()
            val orphans = scan().filter { it.ppid == 1 && it.pid != me && onDeletedPty(it.pid) }
            orphans.forEach { signal(it.pid, OsConstants.SIGKILL) }
            if (orphans.isNotEmpty()) Log.i(TAG, "killed ${orphans.size} orphaned process(es)")
        }, "ProcessReaper").start()
    }

    /** [root] and every process under it or in its session. */
    private fun tree(root: Int): Set<Int> {
        val procs = scan()
        val out = mutableSetOf(root)
        procs.filter { it.sid == root }.forEach { out += it.pid }
        var grew = true
        while (grew) {
            grew = false
            for (p in procs) if (p.ppid in out && out.add(p.pid)) grew = true
        }
        return out
    }

    /** This app's processes (same uid), from /proc. */
    private fun scan(): List<Proc> {
        val uid = Process.myUid()
        val dirs = File("/proc").list() ?: return emptyList()
        return dirs.mapNotNull { name ->
            val pid = name.toIntOrNull() ?: return@mapNotNull null
            runCatching {
                if (Os.stat("/proc/$pid").st_uid != uid) return@mapNotNull null
                // "pid (comm) state ppid pgrp session ...": comm may hold spaces, so split after ')'.
                val stat = File("/proc/$pid/stat").readText()
                val f = stat.substring(stat.lastIndexOf(')') + 2).split(' ')
                Proc(pid, f[1].toInt(), f[3].toInt())
            }.getOrNull()
        }
    }

    private fun onDeletedPty(pid: Int): Boolean = runCatching {
        val tty = Os.readlink("/proc/$pid/fd/0")
        tty.startsWith("/dev/pts/") && tty.endsWith("(deleted)")
    }.getOrDefault(false)

    private fun signal(pid: Int, sig: Int) {
        runCatching { Os.kill(pid, sig) }
    }
}
