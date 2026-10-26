package com.bori.test.t5

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import com.bori.test.t5.core.Greeter
import com.bori.test.t5.core.PingService

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val checks = linkedMapOf<String, () -> Boolean>(
            "coreResource" to { Greeter.greeting(this) == "hello from core" },
            "okhttp" to { Greeter.host("https://example.org/a?b=c") == "example.org" },
            "libraryService" to {
                packageManager.resolveService(Intent().setComponent(ComponentName(this, PingService::class.java)), 0) != null
            },
        )
        val results = checks.map { (name, check) ->
            val ok = try { check() } catch (t: Throwable) { Log.e("BORI_TEST", "t5 $name threw", t); false }
            "$name=${if (ok) "PASS" else "FAIL"}" to ok
        }
        val summary = results.joinToString(" ") { it.first }
        Log.i("BORI_TEST", (if (results.all { it.second }) "t5 OK " else "t5 FAIL ") + summary)
        setContentView(TextView(this).apply { text = summary.replace(' ', '\n'); textSize = 20f })
    }
}
