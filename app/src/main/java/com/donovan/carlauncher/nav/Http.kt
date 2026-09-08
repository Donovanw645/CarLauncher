package com.donovan.carlauncher.nav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Minimal HTTP GET. The OSM services we use require a descriptive User-Agent and
 * will hand back 403s without one.
 */
object Http {

    const val USER_AGENT = "CarLauncher/1.0 (Android dash launcher; personal use)"

    suspend fun getString(url: String): String = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Encoding", "gzip")
        }
        try {
            val code = conn.responseCode
            val raw = if (code in 200..299) conn.inputStream else conn.errorStream
            val stream = if (conn.contentEncoding?.contains("gzip", true) == true && raw != null) {
                GZIPInputStream(raw)
            } else {
                raw
            }
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IOException("HTTP $code from ${URL(url).host}: ${body.take(180)}")
            }
            body
        } finally {
            runCatching { conn.disconnect() }
        }
    }
}
