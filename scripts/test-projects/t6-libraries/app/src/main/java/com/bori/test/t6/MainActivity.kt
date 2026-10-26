package com.bori.test.t6

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.bori.test.locallib.Greeter
import com.bori.test.t6.databinding.ActivityMainBinding
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import okhttp3.OkHttpClient
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val checks = linkedMapOf<String, String>()
        fun check(name: String, block: () -> Boolean) {
            checks[name] = try { if (block()) "PASS" else "FAIL" } catch (t: Throwable) { "FAIL(${t.javaClass.simpleName}: ${t.message})" }
        }
        check("viewBinding") { binding.statusText != null && binding.rootLayout === binding.root }
        // Desugared to j$.time on minSdk 24.
        check("javaTime") { LocalDate.of(2026, 10, 26).plusDays(6).format(DateTimeFormatter.ISO_LOCAL_DATE) == "2026-11-01" }
        check("localJar") { Greeter.greet("Bori") == "Hola, Bori" }
        check("okhttpBom") { OkHttpClient.Builder().build().connectTimeoutMillis == 10_000 }
        check("jitpackChart") {
            val set = LineDataSet(listOf(Entry(0f, 1f), Entry(1f, 3f)), "t6")
            binding.chart.data = LineData(set)
            binding.chart.data.entryCount == 2
        }
        check("appcompat17") { delegate.javaClass.name.startsWith("androidx.appcompat") }
        val ok = checks.values.all { it == "PASS" }
        val line = "t6 ${if (ok) "OK" else "FAILED"} " + checks.entries.joinToString(" ") { "${it.key}=${it.value}" }
        binding.statusText.text = line
        Log.i("BORI_TEST", line)
    }
}
