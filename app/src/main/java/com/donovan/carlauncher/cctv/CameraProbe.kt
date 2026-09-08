package com.donovan.carlauncher.cctv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

data class ProbeResult(val ok: Boolean, val detail: String)

/**
 * The "Test" button in Settings. Actually connects and pulls a frame rather than just
 * validating the URL, so a typo or a firewall shows up on the sofa instead of on the road.
 */
suspend fun probeCamera(url: String): ProbeResult = withContext(Dispatchers.IO) {
    val trimmed = url.trim()
    if (trimmed.isBlank()) return@withContext ProbeResult(false, "No URL set")

    runCatching {
        when (kindFor(trimmed)) {
            StreamKind.VIDEO -> probeVideo(trimmed)
            else -> probeHttp(trimmed)
        }
    }.getOrElse { e ->
        ProbeResult(false, e.message ?: e.javaClass.simpleName)
    }
}

private fun probeHttp(url: String): ProbeResult {
    val conn = open(url)
    try {
        val code = conn.responseCode
        if (code !in 200..299) return ProbeResult(false, "HTTP $code")

        val contentType = conn.contentType?.lowercase().orEmpty()
        val input = conn.inputStream

        return when {
            contentType.startsWith("multipart") -> {
                // Pull the first JPEG out of the stream to prove it really is MJPEG.
                val jpeg = firstJpeg(input)
                    ?: return ProbeResult(false, "Multipart stream sent no JPEG")
                val bmp = decode(jpeg, 0, 0)
                    ?: return ProbeResult(false, "Frame would not decode")
                ProbeResult(true, "MJPEG · ${bmp.width}x${bmp.height}")
            }

            contentType.startsWith("image/") -> {
                val bytes = input.readBytes()
                val bmp = decode(bytes, 0, 0)
                    ?: return ProbeResult(false, "Image would not decode")
                ProbeResult(true, "Snapshot · ${bmp.width}x${bmp.height} · polled at 5 fps")
            }

            else -> ProbeResult(
                false,
                "Server sent ${contentType.ifBlank { "no content type" }}, not a camera stream",
            )
        }
    } finally {
        runCatching { conn.disconnect() }
    }
}

/** Reads bytes until a complete JPEG has been seen, with a hard cap so a wrong URL cannot hang. */
private fun firstJpeg(input: java.io.InputStream): ByteArray? {
    val out = java.io.ByteArrayOutputStream(64 * 1024)
    var prev = -1
    var started = false
    var read = 0
    while (read < MAX_PROBE_BYTES) {
        val b = input.read()
        if (b == -1) return null
        read++
        if (!started) {
            if (prev == 0xFF && b == 0xD8) {
                started = true
                out.write(0xFF); out.write(0xD8)
            }
        } else {
            out.write(b)
            if (prev == 0xFF && b == 0xD9) return out.toByteArray()
        }
        prev = b
    }
    return null
}

/**
 * RTSP has no cheap "is this a camera" request, but OPTIONS is the standard handshake
 * opener and tells us the server is alive and speaking RTSP.
 */
private fun probeVideo(url: String): ProbeResult {
    val parsed = URL(url.replaceFirst("rtsp://", "http://").replaceFirst("rtsps://", "https://"))
    val host = parsed.host ?: return ProbeResult(false, "Could not read the host")
    val port = if (parsed.port > 0) parsed.port else if (url.startsWith("rtsp")) 554 else 80

    if (!url.startsWith("rtsp")) {
        // HLS or progressive: a plain HTTP reachability check is enough.
        val conn = open(url)
        try {
            val code = conn.responseCode
            return if (code in 200..299) {
                ProbeResult(true, "Reachable · ${conn.contentType ?: "stream"} · played by ExoPlayer")
            } else {
                ProbeResult(false, "HTTP $code")
            }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    Socket().use { socket ->
        socket.connect(InetSocketAddress(host, port), 5_000)
        socket.soTimeout = 5_000
        val request = buildString {
            append("OPTIONS ").append(url).append(" RTSP/1.0\r\n")
            append("CSeq: 1\r\n")
            append("User-Agent: CarLauncher\r\n\r\n")
        }
        socket.getOutputStream().apply {
            write(request.toByteArray())
            flush()
        }
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        val statusLine = reader.readLine()
            ?: throw IOException("Server closed the connection")
        return if (statusLine.contains("200")) {
            ProbeResult(true, "RTSP OK on $host:$port · played by ExoPlayer")
        } else {
            ProbeResult(false, "RTSP said: $statusLine")
        }
    }
}

private const val MAX_PROBE_BYTES = 4 * 1024 * 1024
