package com.bori.app.terminal

/** A shell prompt at the start of the cursor's line (bash's `root@void:~# `, Bori's `bori:~$ `...). */
object ShellPrompt {
    val REGEX = Regex("^(bori:\\S*[\$#] |\\S*[\$#] |\\S+@\\S+ ?[\$#] )")
}
