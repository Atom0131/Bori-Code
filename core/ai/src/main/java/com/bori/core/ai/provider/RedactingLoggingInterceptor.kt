package com.bori.core.ai.provider

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response

private val SENSITIVE_HEADERS = setOf(
    "authorization",
    "x-api-key",
    "api-key",
)

/**
 * OkHttp interceptor that logs requests and responses while redacting
 * any headers that might contain API keys or bearer tokens.
 *
 * This prevents credentials from appearing in logcat or crash reports.
 */
class RedactingLoggingInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            val headers = request.headers.toMultimap().entries.joinToString { (name, _) ->
                if (name.lowercase() in SENSITIVE_HEADERS) "$name: [REDACTED]"
                else "$name: ${request.headers[name]}"
            }
            Log.d(TAG, "--> ${request.method} ${request.url}\n$headers")
        }

        val response = chain.proceed(request)

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "<-- ${response.code} ${response.request.url}")
        }

        return response
    }

    companion object {
        private const val TAG = "Bori.Http"
    }
}
