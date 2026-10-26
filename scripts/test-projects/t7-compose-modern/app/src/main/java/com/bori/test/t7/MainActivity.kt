package com.bori.test.t7

import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class Note(val id: Int, val title: String, val tags: List<String> = emptyList())

@Parcelize
data class Point(val x: Int, val y: Int) : Parcelable

// Kotlin 1.9+ language features: data objects and enum entries.
sealed interface Shape
data object Circle : Shape
enum class Size { S, M, L }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val checks = linkedMapOf<String, String>()
        fun check(name: String, block: () -> Boolean) {
            checks[name] = try { if (block()) "PASS" else "FAIL" } catch (t: Throwable) { "FAIL(${t.javaClass.simpleName}: ${t.message})" }
        }
        check("serialization") {
            val json = Json.encodeToString(Note(1, "hola", listOf("a")))
            json == """{"id":1,"title":"hola","tags":["a"]}""" && Json.decodeFromString<Note>(json).title == "hola"
        }
        check("parcelize") {
            val parcel = Parcel.obtain()
            try {
                parcel.writeParcelable(Point(3, 4), 0)
                parcel.setDataPosition(0)
                @Suppress("DEPRECATION")
                parcel.readParcelable<Point>(Point::class.java.classLoader) == Point(3, 4)
            } finally { parcel.recycle() }
        }
        check("kotlin19") { Circle.toString() == "Circle" && Size.entries.size == 3 }
        setContent {
            MaterialTheme {
                Text("T7 ${checks.values.count { it == "PASS" }}/${checks.size}")
                LaunchedEffect(Unit) {
                    checks["compose17"] = "PASS"
                    val ok = checks.values.all { it == "PASS" }
                    Log.i("BORI_TEST", "t7 ${if (ok) "OK" else "FAILED"} " + checks.entries.joinToString(" ") { "${it.key}=${it.value}" })
                }
            }
        }
    }
}
