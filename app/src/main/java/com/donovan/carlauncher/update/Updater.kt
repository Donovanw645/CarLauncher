package com.donovan.carlauncher.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.pm.PackageInfoCompat
import com.donovan.carlauncher.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** [HttpURLConnection] has disconnect() rather than close(), so `use` does not fit it. */
private inline fun <R> HttpURLConnection.useConn(block: (HttpURLConnection) -> R): R =
    try {
        block(this)
    } finally {
        runCatching { disconnect() }
    }

/**
 * Over-the-air updates for the launcher itself.
 *
 * Flow: fetch a small JSON manifest, compare its versionCode with ours, download the
 * APK, verify its SHA-256, then hand it to [PackageInstaller]. Android shows one
 * confirmation dialog at the end - a normal app cannot install silently, and that is
 * deliberate on Android's part.
 *
 * The install is an *in-place* update, so every setting in [Prefs], every runtime
 * permission, notification-listener access and the default-home-launcher role all
 * survive it. That only holds while the APK is signed with the same key, which is
 * why release builds must always come from carlauncher.jks and never the debug key.
 */
class Updater(
    private val context: Context,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var job: Job? = null
    private var staged: File? = null

    val installedVersionCode: Long = runCatching {
        PackageInfoCompat.getLongVersionCode(
            context.packageManager.getPackageInfo(context.packageName, 0)
        )
    }.getOrDefault(0L)

    val installedVersionName: String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"

    // ------------------------------------------------------------------- checking

    /**
     * Looks for a newer build. Manual checks always run; automatic ones are throttled
     * and skipped entirely when the user has turned update checks off.
     */
    fun check(manual: Boolean = false) {
        if (!manual) {
            if (!prefs.current.updateCheckEnabled) return
            val age = System.currentTimeMillis() - prefs.current.updateLastCheckMs
            if (age in 0 until CHECK_INTERVAL_MS) return
            // Never talk over a download or an install that is already under way.
            if (_state.value !is UpdateState.Idle && _state.value !is UpdateState.UpToDate) return
        }
        if (job?.isActive == true) return

        job = scope.launch {
            _state.value = UpdateState.Checking
            val result = runCatching {
                val body = withContext(Dispatchers.IO) {
                    httpGet(prefs.current.updateManifestUrl).useConn { conn ->
                        conn.inputStream.bufferedReader().use { it.readText() }
                    }
                }
                UpdateManifest.parse(body)
            }

            prefs.update { it.copy(updateLastCheckMs = System.currentTimeMillis()) }

            val manifest = result.getOrElse { e ->
                _state.value = UpdateState.Failed(friendly(e))
                return@launch
            }

            when {
                manifest.versionCode <= installedVersionCode ->
                    _state.value = UpdateState.UpToDate(System.currentTimeMillis())

                manifest.minSdk > Build.VERSION.SDK_INT ->
                    _state.value = UpdateState.Failed(
                        "Version ${manifest.versionName} needs Android API ${manifest.minSdk}; " +
                            "this tablet is on ${Build.VERSION.SDK_INT}."
                    )

                manifest.versionCode.toInt() == prefs.current.updateSkippedVersion && !manual ->
                    _state.value = UpdateState.UpToDate(System.currentTimeMillis())

                else -> {
                    val metered = isMetered()
                    _state.value = UpdateState.Available(manifest, meteredHold = metered)
                    // Pull it down in the background at home, never over the hotspot.
                    if (prefs.current.updateAutoDownload && !metered) download()
                }
            }
        }
    }

    // ----------------------------------------------------------------- downloading

    fun download() {
        val manifest = _state.value.manifest ?: return
        if (job?.isActive == true && _state.value is UpdateState.Downloading) return

        job = scope.launch {
            _state.value = UpdateState.Downloading(manifest, 0, manifest.sizeBytes)
            val file = runCatching { fetchApk(manifest) }.getOrElse { e ->
                _state.value = UpdateState.Failed(friendly(e), manifest)
                return@launch
            }
            staged = file
            _state.value = UpdateState.Ready(manifest)
        }
    }

    private suspend fun fetchApk(manifest: UpdateManifest): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // Only ever keep the build we are actually installing.
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "CarLauncher-${manifest.versionCode}.apk")

        val digest = MessageDigest.getInstance("SHA-256")
        httpGet(manifest.apkUrl).useConn { conn ->
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: manifest.sizeBytes
            var done = 0L
            var lastPublish = 0L

            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        // Repainting a progress bar per 64 KB chunk is wasted work.
                        if (done - lastPublish > 256 * 1024) {
                            lastPublish = done
                            _state.value = UpdateState.Downloading(manifest, done, total)
                        }
                    }
                }
            }
        }

        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != manifest.sha256) {
            target.delete()
            throw IOException("Download is corrupt - checksum did not match. Try again.")
        }
        target
    }

    // ------------------------------------------------------------------ installing

    /** True once the user has allowed this app to install packages (a one-time grant). */
    fun canInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** Deep link to the "Install unknown apps" screen for this app. */
    fun unknownSourcesIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun install() {
        val manifest = _state.value.manifest ?: return
        val file = staged?.takeIf { it.exists() } ?: run {
            _state.value = UpdateState.Available(manifest)
            return
        }
        if (!canInstallPackages()) {
            _state.value = UpdateState.Failed(
                "Allow Car Launcher to install apps first, then press Install again.",
                manifest,
            )
            return
        }

        scope.launch {
            _state.value = UpdateState.Installing(manifest)
            runCatching { withContext(Dispatchers.IO) { commit(file) } }
                .onFailure { _state.value = UpdateState.Failed(friendly(it), manifest) }
        }
    }

    private fun commit(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(context.packageName)
            runCatching { setSize(file.length()) }
        }

        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("carlauncher", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }

            // PackageInstaller fills extras into this intent, so it must be mutable.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
            val callback = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(InstallReceiver.ACTION).setPackage(context.packageName),
                flags,
            )
            session.commit(callback.intentSender)
        }
    }

    /** Called by [InstallReceiver] with whatever PackageInstaller reported. */
    fun onInstallStatus(status: Int, message: String?, userAction: Intent?) {
        val manifest = _state.value.manifest
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // The system's "Update this app?" dialog. One tap, and it is unavoidable
                // for an app that is not the device owner.
                runCatching {
                    context.startActivity(
                        userAction?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
                    )
                }.onFailure {
                    _state.value = UpdateState.Failed("Could not open the installer.", manifest)
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                // The process is about to be replaced; nothing left to show.
                staged?.delete()
                staged = null
                _state.value = UpdateState.Idle
            }

            PackageInstaller.STATUS_FAILURE_ABORTED ->
                _state.value = manifest?.let { UpdateState.Ready(it) } ?: UpdateState.Idle

            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                _state.value = UpdateState.Failed(
                    "Signature mismatch - this APK was signed with a different key than the " +
                        "installed app, so Android will not update in place.",
                    manifest,
                )

            else ->
                _state.value = UpdateState.Failed(
                    message?.takeIf { it.isNotBlank() } ?: "Install failed (code $status).",
                    manifest,
                )
        }
    }

    // ---------------------------------------------------------------------- misc

    /** Stop offering this particular version until a newer one appears. */
    fun skip() {
        val code = _state.value.manifest?.versionCode?.toInt() ?: return
        prefs.update { it.copy(updateSkippedVersion = code) }
        _state.value = UpdateState.UpToDate(System.currentTimeMillis())
    }

    fun dismissError() {
        if (_state.value is UpdateState.Failed) _state.value = UpdateState.Idle
    }

    private fun isMetered(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
            ?: return true
        // An iPhone hotspot reports as Wi-Fi but without NOT_METERED, which is exactly
        // the case we care about: do not spend cellular data on a 15 MB APK.
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun friendly(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "No internet connection."
        is java.net.SocketTimeoutException -> "The update server timed out."
        is IllegalArgumentException -> "The update manifest is malformed: ${e.message}"
        else -> e.message ?: e.javaClass.simpleName
    }

    private companion object {
        const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000 // six hours
        const val MAX_REDIRECTS = 5

        /**
         * GitHub answers /releases/latest/download/... with a chain of redirects onto a
         * CDN host. HttpURLConnection will not follow those on its own once the host
         * changes, so walk the chain by hand - and refuse to leave https while doing it.
         */
        fun httpGet(url: String): HttpURLConnection {
            var current = url
            repeat(MAX_REDIRECTS) {
                val parsed = URL(current)
                require(parsed.protocol == "https") { "updates must be served over https" }
                val conn = (parsed.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "CarLauncher-Updater")
                    setRequestProperty("Accept", "*/*")
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val next = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (next.isNullOrBlank()) throw IOException("Redirect with no target")
                    current = URL(parsed, next).toString()
                    return@repeat
                }
                if (code !in 200..299) {
                    conn.disconnect()
                    throw IOException(
                        if (code == 404) {
                            "No release published yet (HTTP 404)."
                        } else {
                            "Update server returned HTTP $code."
                        }
                    )
                }
                return conn
            }
            throw IOException("Too many redirects fetching the update")
        }
    }
}
