package com.bori.test.t1

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.TextView

/** Uses the Kotlin stdlib (collections, lambdas, string templates) with no other dependencies. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val squares = (1..5).map { it * it }.filter { it % 2 == 1 }
        val text = "squares=${squares.joinToString()} sum=${squares.sum()}"
        Log.i("BORI_TEST", "t1 OK $text")
        setContentView(TextView(this).apply { this.text = text; textSize = 24f })
    }
}
