package com.bori.test.t9

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.TextView

/** Runs the Java 17 checks in Shapes.java and uses a Java record from Kotlin. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val checks = linkedMapOf<String, String>()
        fun check(name: String, block: () -> Boolean) {
            checks[name] = try { if (block()) "PASS" else "FAIL" } catch (t: Throwable) { "FAIL(${t.javaClass.simpleName}: ${t.message})" }
        }
        check("record") { Shapes.records() }
        check("sealed") { Shapes.sealed() }
        check("switch") { Shapes.name(1) == "one" && Shapes.name(5) == "many" }
        check("textBlock") { Shapes.textBlock() == "Hello\n  Java 17" }
        check("kotlinInterop") { Shapes.Point(2, 3).let { it.x() + it.y() == 5 && it.sum() == 5 } }
        val ok = checks.values.all { it == "PASS" }
        val text = (if (ok) "t9 OK " else "t9 FAIL ") + checks.entries.joinToString(" ") { "${it.key}=${it.value}" }
        Log.i("BORI_TEST", text)
        setContentView(TextView(this).apply { this.text = text; textSize = 20f })
    }
}
