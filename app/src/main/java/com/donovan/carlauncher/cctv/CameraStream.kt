package com.donovan.carlauncher.cctv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.donovan.carlauncher.data.CameraFeed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

enum class StreamKind {
    /** multipart/x-mixed-replace - mjpg-streamer, ustreamer, motion. */
    MJPEG,

    /** A single JPEG re-fetched on a timer. */
    SNAPSHOT,

    /** RTSP / HLS / progressive - handed to ExoPlayer. */
    VIDEO,
}

/** Best guess from the URL alone; the HTTP kinds are confirmed by content type. */
fun kindFor(url: String): StreamKind {
    val u = url.trim().lowercase()
    return when {
        u.startsWith("rtsp://") || u.startsWith("rtsps://") -> StreamKind.VIDEO
        u.contains(".m3u8") || u.endsWith(".mp4") || u.endsWith(".ts") -> StreamKind.VIDEO
        u.endsWith(".jpg") || u.endsWith(".jpeg") ||
            u.contains("snapshot") || u.contains("action=snapshot") -> StreamKind.SNAPSHOT
        else -> StreamKind.MJPEG
    }
}

/**
 * One camera tile's worth of MJPEG or snapshot decoding.
 *
 * Frames are downscaled to the tile they are drawn in and decoded to RGB_565. Four
 * 1080p feeds decoded at full size into ARGB bitmaps is exactly how you make a cheap
 * tablet crawl, and the tile is a few hundred pixels wide anyway.
 */
class CameraStream(val feed: CameraFeed) {

    enum class Status { CONNECTING, LIVE, ERROR }

    private val _frame = MutableStateFlow<ImageBitmap?>(null)
    val frame: StateFlow<ImageBitmap?> = _frame.asStateFlow()

    private val _status = MutableStateFlow(Status.CONNECTING)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _fps = MutableStateFlow(0)
    val fps: StateFlow<Int> = _fps.asStateFlow()

    private var job: Job? = null

    fun start(scope: CoroutineScope, targetWidth: Int, targetHeight: Int) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { run(targetWidth, targetHeight) }
    }

    fun stop() {
        job?.cancel()
        job = null
        _frame.value = null
        _fps.value = 0
    }

    private suspend fun run(tw: Int, th: Int) {
        while (currentCoroutineContext().isActive) {
            var conn: HttpURLConnection? = null
            try {
                _status.value = Status.CONNECTING
                conn = open(feed.url)
                val code = conn.responseCode
                if (code !in 200..299) throw IOException("HTTP $code from the camera")

                val contentType = conn.contentType?.lowercase().orEmpty()
                when {
                    contentType.startsWith("multipart") -> readMultipart(conn, tw, th)
                    contentType.startsWith("image/") -> {
                        conn.disconnect()
                        conn = null
                        pollSnapshots(tw, th)
                    }
                    else -> throw IOException(
                        "Not a camera stream (server sent ${contentType.ifBlank { "no type" }})"
                    )
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                _status.value = Status.ERROR
                _message.value = e.message ?: e.javaClass.simpleName
                _fps.value = 0
            } finally {
                runCatching { conn?.disconnect() }
            }
            delay(RECONNECT_MS)
        }
    }

    // ------------------------------------------------------------------ transports

    private suspend fun readMultipart(conn: HttpURLConnection, tw: Int, th: Int) {
        val input = BufferedInputStream(conn.inputStream, 64 * 1024)
        var frames = 0
        var window = System.currentTimeMillis()

        while (currentCoroutineContext().isActive) {
            val declared = readPartHeaders(input)
            val jpeg = if (declared > 0) {
                readExactly(input, declared)
            } else {
                scanJpeg(input) ?: throw IOException("Stream ended")
            }

            val bitmap = decode(jpeg, tw, th)
            if (bitmap != null) {
                _frame.value = bitmap.asImageBitmap()
                _status.value = Status.LIVE
                _message.value = null
                frames++
            }

            val now = System.currentTimeMillis()
            if (now - window >= 1000) {
                _fps.value = frames
                frames = 0
                window = now
            }
        }
    }

    private suspend fun pollSnapshots(tw: Int, th: Int) {
        while (currentCoroutineContext().isActive) {
            val conn = open(feed.url)
            try {
                val bytes = conn.inputStream.use { it.readBytes() }
                val bitmap = decode(bytes, tw, th)
                if (bitmap != null) {
                    _frame.value = bitmap.asImageBitmap()
                    _status.value = Status.LIVE
                    _message.value = null
                    _fps.value = SNAPSHOT_FPS
                }
            } finally {
                runCatching { conn.disconnect() }
            }
            delay(1000L / SNAPSHOT_FPS)
        }
    }

    private companion object {
        const val RECONNECT_MS = 2500L
        const val SNAPSHOT_FPS = 5
    }
}

// ---------------------------------------------------------------------- HTTP helpers

internal fun open(url: String): HttpURLConnection {
    val parsed = URL(url)
    val conn = parsed.openConnection() as HttpURLConnection
    conn.connectTimeout = 6_000
    conn.readTimeout = 15_000
    conn.requestMethod = "GET"
    conn.setRequestProperty("User-Agent", "CarLauncher/1.0")
    conn.setRequestProperty("Connection", "keep-alive")
    // http://user:pass@host/... - common on IP cameras.
    parsed.userInfo?.takeIf { it.isNotBlank() }?.let { info ->
        val token = Base64.encodeToString(info.toByteArray(), Base64.NO_WRAP)
        conn.setRequestProperty("Authorization", "Basic $token")
    }
    conn.connect()
    return conn
}

/** Reads the boundary and part headers, returning Content-Length or -1. */
private fun readPartHeaders(input: InputStream): Int {
    var length = -1
    var sawHeader = false
    while (true) {
        val line = readAsciiLine(input) ?: return length
        if (line.isEmpty()) {
            if (sawHeader) return length
            continue // leading blank lines between parts
        }
        sawHeader = true
        val lower = line.lowercase()
        if (lower.startsWith("content-length:")) {
            length = lower.substringAfter(':').trim().toIntOrNull() ?: -1
        }
    }
}

private fun readAsciiLine(input: InputStream): String? {
    val sb = StringBuilder(64)
    while (true) {
        val b = input.read()
        if (b == -1) return if (sb.isEmpty()) null else sb.toString()
        if (b == '\n'.code) return sb.toString().trimEnd('\r')
        sb.append(b.toInt().toChar())
    }
}

private fun readExactly(input: InputStream, count: Int): ByteArray {
    val out = ByteArray(count)
    var read = 0
    while (read < count) {
        val r = input.read(out, read, count - read)
        if (r == -1) throw IOException("Stream ended mid-frame")
        read += r
    }
    return out
}

/** Fallback for servers that omit Content-Length: read from JPEG SOI to EOI. */
private fun scanJpeg(input: InputStream): ByteArray? {
    val out = ByteArrayOutputStream(64 * 1024)
    var prev = -1
    var started = false
    while (true) {
        val b = input.read()
        if (b == -1) return null
        if (!started) {
            if (prev == 0xFF && b == 0xD8) {
                started = true
                out.write(0xFF)
                out.write(0xD8)
            }
        } else {
            out.write(b)
            if (prev == 0xFF && b == 0xD9) return out.toByteArray()
        }
        prev = b
    }
}

internal fun decode(jpeg: ByteArray, targetW: Int, targetH: Int): Bitmap? {
    if (jpeg.isEmpty()) return null
    return runCatching {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        if (targetW > 0 && targetH > 0) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
            opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetW, targetH)
        }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
    }.getOrNull()
}

private fun sampleSize(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Int {
    if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return 1
    var sample = 1
    while (srcW / (sample * 2) >= dstW && srcH / (sample * 2) >= dstH) sample *= 2
    return sample
}
