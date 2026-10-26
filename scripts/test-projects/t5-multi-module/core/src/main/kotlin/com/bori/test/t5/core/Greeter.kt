package com.bori.test.t5.core

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrl

object Greeter {
    fun greeting(context: Context): String = context.getString(R.string.core_greeting)
    fun host(url: String): String = url.toHttpUrl().host
}
