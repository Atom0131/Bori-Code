package com.bori.test.t8

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.squareup.moshi.Moshi
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var greeter: Greeter
    @Inject lateinit var db: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val checks = linkedMapOf<String, String>()
        fun check(name: String, block: () -> Boolean) {
            checks[name] = try { if (block()) "PASS" else "FAIL" } catch (t: Throwable) { "FAIL(${t.javaClass.simpleName}: ${t.message})" }
        }
        check("hilt") { ::greeter.isInitialized && greeter.greet("Bori") == "Hola, Bori" }
        check("room") {
            db.items().insert(Item(name = "uno"))
            db.items().insert(Item(name = "dos"))
            db.items().all().map { it.name } == listOf("uno", "dos")
        }
        check("moshiCodegen") {
            // moshi.adapter() wraps the generated adapter in a null-safe one; its toString names it.
            val adapter = Moshi.Builder().build().adapter(Message::class.java)
            adapter.toString().contains("GeneratedJsonAdapter(Message)") && adapter.fromJson(adapter.toJson(Message("hi", 2))) == Message("hi", 2)
        }
        val ok = checks.values.all { it == "PASS" }
        val line = "t8 ${if (ok) "OK" else "FAILED"} " + checks.entries.joinToString(" ") { "${it.key}=${it.value}" }
        setContentView(TextView(this).apply { text = line })
        Log.i("BORI_TEST", line)
    }
}
