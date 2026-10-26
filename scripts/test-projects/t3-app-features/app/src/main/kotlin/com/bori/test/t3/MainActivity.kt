package com.bori.test.t3

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.TextView

/** Each check prints PASS or FAIL; the line "t3 OK" means all of them passed. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val info = packageManager.getPackageInfo(packageName, 0)
        val checks = linkedMapOf<String, () -> Boolean>(
            "assets" to { assets.open("hello.txt").bufferedReader().readText().trim() == "hello from assets" },
            "buildConfig" to { BuildConfig.APPLICATION_ID == "com.bori.test.t3app" && BuildConfig.VERSION_NAME == "3.1" && BuildConfig.GREETING == "hola" },
            "applicationId" to { packageName == "com.bori.test.t3app" },
            "version" to { info.versionName == "3.1" && @Suppress("DEPRECATION") info.versionCode == 7 },
            "icon" to { applicationInfo.icon != 0 },
            "native" to { Native.answer() == 42 },
            "startupProvider" to { packageManager.resolveContentProvider("$packageName.androidx-startup", 0) != null },
        )
        val results = checks.map { (name, check) ->
            val ok = try { check() } catch (t: Throwable) { Log.e("BORI_TEST", "t3 $name threw", t); false }
            "$name=${if (ok) "PASS" else "FAIL"}" to ok
        }
        val summary = results.joinToString(" ") { it.first }
        Log.i("BORI_TEST", (if (results.all { it.second }) "t3 OK " else "t3 FAIL ") + summary)
        setContentView(TextView(this).apply { text = summary.replace(' ', '\n'); textSize = 20f })
    }
}
