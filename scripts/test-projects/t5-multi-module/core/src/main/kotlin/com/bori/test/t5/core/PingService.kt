package com.bori.test.t5.core

import android.app.Service
import android.content.Intent
import android.os.IBinder

class PingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
