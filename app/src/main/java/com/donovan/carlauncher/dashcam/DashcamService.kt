package com.donovan.carlauncher.dashcam

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.donovan.carlauncher.MainActivity
import com.donovan.carlauncher.R
import com.donovan.carlauncher.data.CameraFacing
import com.donovan.carlauncher.data.DashcamQuality
import com.donovan.carlauncher.data.Prefs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Owns the camera and writes the loop. Runs as a foreground service so Android keeps
 * granting camera access while the driver is looking at another screen.
 *
 * The camera is held whenever the Dashcam tab is open (live preview) or a recording is
 * running. Keeping one owner for both avoids handing the camera between the activity and
 * the service when record is pressed, which would otherwise drop the preview for a beat.
 */
class DashcamService : LifecycleService() {

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private var cameraReady = false

    /** Distinguishes "segment rolled over" from "user pressed stop" in onFinalize. */
    private var wantRecording = false
    private var wantPreview = false

    private lateinit var prefs: Prefs

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_PREVIEW -> beginPreview()
            ACTION_STOP_PREVIEW -> endPreview()
            ACTION_START -> beginSession()
            ACTION_STOP -> endSession()
            else -> endSession()
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------- camera

    private fun ensureCamera(onReady: () -> Unit) {
        if (cameraReady) {
            onReady()
            return
        }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = runCatching { future.get() }.getOrNull()
            if (provider == null) {
                fail("Could not open the camera")
                return@addListener
            }
            cameraProvider = provider
            if (!bindUseCases(provider)) return@addListener
            cameraReady = true
            onReady()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindUseCases(provider: ProcessCameraProvider): Boolean {
        val settings = prefs.current

        val quality = when (settings.dashQuality) {
            DashcamQuality.SD -> Quality.SD
            DashcamQuality.HD -> Quality.HD
            DashcamQuality.FHD -> Quality.FHD
            DashcamQuality.UHD -> Quality.UHD
        }
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(
                    quality,
                    // Cheap tablet cameras often cannot do the requested size; take the
                    // nearest thing they can rather than failing to record at all.
                    FallbackStrategy.lowerQualityOrHigherThan(quality),
                )
            )
            .build()
        val capture = VideoCapture.withOutput(recorder)
        val preview = Preview.Builder().build()

        val selector = if (settings.dashFacing == CameraFacing.FRONT) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        // Without this the clip is encoded at whatever rotation the display happened to
        // be in when the service bound, which is how you end up with sideways footage.
        runCatching { capture.targetRotation = currentDisplayRotation() }

        return runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, capture, preview)
            videoCapture = capture
            Dashcam.previewUseCase = preview
            Dashcam.bindPendingPreview()
            true
        }.getOrElse { e ->
            fail(e.message ?: "Camera is unavailable")
            false
        }
    }

    // ------------------------------------------------------------------- preview

    private fun beginPreview() {
        wantPreview = true
        goForeground()
        if (wantRecording) return
        Dashcam.update { it.copy(status = DashcamStatus.STARTING, message = null) }
        ensureCamera {
            if (!wantRecording) {
                Dashcam.update { it.copy(status = DashcamStatus.PREVIEW) }
            }
        }
    }

    private fun endPreview() {
        wantPreview = false
        if (wantRecording) return
        releaseCamera()
        Dashcam.update { it.copy(status = DashcamStatus.IDLE) }
        stopSelf()
    }

    // ------------------------------------------------------------------- session

    private fun beginSession() {
        if (wantRecording) return
        wantRecording = true
        goForeground()
        Dashcam.update {
            it.copy(status = DashcamStatus.STARTING, message = null, segmentCount = 0)
        }
        ensureCamera { startSegment() }
    }

    @SuppressLint("MissingPermission")
    private fun startSegment() {
        val capture = videoCapture ?: return
        if (!wantRecording) return

        val settings = prefs.current
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val file = File(Dashcam.loopDir(this), "$stamp.mp4")

        val options = FileOutputOptions.Builder(file)
            .setDurationLimitMillis(settings.dashSegmentMinutes.coerceIn(1, 30) * 60_000L)
            .build()

        val pending = capture.output.prepareRecording(this, options)
        if (settings.dashRecordAudio && Dashcam.hasMicPermission(this)) {
            runCatching { pending.withAudioEnabled() }
        }

        recording = runCatching {
            pending.start(ContextCompat.getMainExecutor(this)) { event -> onRecordEvent(event) }
        }.getOrElse { e ->
            fail(e.message ?: "Could not start recording")
            null
        }
    }

    private fun onRecordEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> {
                Dashcam.update {
                    it.copy(status = DashcamStatus.RECORDING, message = null, elapsedMs = 0L)
                }
                goForeground()
            }

            is VideoRecordEvent.Status -> {
                val stats = event.recordingStats
                Dashcam.update {
                    it.copy(
                        elapsedMs = stats.recordedDurationNanos / 1_000_000L,
                        segmentBytes = stats.numBytesRecorded,
                    )
                }
            }

            is VideoRecordEvent.Finalize -> {
                recording = null
                val code = event.error
                val recoverable = code == VideoRecordEvent.Finalize.ERROR_NONE ||
                    code == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED ||
                    code == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED

                val cap = prefs.current.dashMaxStorageGb.coerceIn(1, 512) * 1_000_000_000L
                Dashcam.prune(this, cap)

                if (!recoverable) {
                    fail(event.cause?.message ?: "Recording stopped unexpectedly (code $code)")
                    return
                }

                Dashcam.update { it.copy(segmentCount = it.segmentCount + 1, elapsedMs = 0L) }

                if (wantRecording) startSegment() else afterRecordingStopped()
            }
        }
    }

    private fun endSession() {
        if (!wantRecording) {
            if (!wantPreview) {
                releaseCamera()
                Dashcam.update { it.copy(status = DashcamStatus.IDLE) }
                stopSelf()
            }
            return
        }
        wantRecording = false
        val active = recording
        if (active == null) {
            afterRecordingStopped()
        } else {
            // The Finalize event finishes the teardown.
            runCatching { active.stop() }
        }
    }

    /** Recording has finished: fall back to preview if the tab is still open. */
    private fun afterRecordingStopped() {
        Dashcam.update { it.copy(elapsedMs = 0L, segmentBytes = 0L) }
        if (wantPreview) {
            Dashcam.update { it.copy(status = DashcamStatus.PREVIEW) }
            goForeground()
        } else {
            releaseCamera()
            Dashcam.update { it.copy(status = DashcamStatus.IDLE) }
            stopSelf()
        }
    }

    private fun fail(message: String) {
        wantRecording = false
        wantPreview = false
        recording = null
        releaseCamera()
        Dashcam.update { it.copy(status = DashcamStatus.ERROR, message = message) }
        stopSelf()
    }

    private fun currentDisplayRotation(): Int = runCatching {
        val dm = getSystemService(DisplayManager::class.java)
        dm?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
    }.getOrDefault(Surface.ROTATION_0)

    private fun releaseCamera() {
        cameraReady = false
        Dashcam.previewUseCase = null
        runCatching { cameraProvider?.unbindAll() }
        cameraProvider = null
        videoCapture = null
        Dashcam.refreshClips(this)
    }

    override fun onDestroy() {
        runCatching { recording?.stop() }
        recording = null
        releaseCamera()
        super.onDestroy()
    }

    // -------------------------------------------------------------- notification

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Dashcam", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while the dashcam camera is in use"
                setShowBadge(false)
            }
        )
    }

    private fun goForeground() {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (wantRecording) "Dashcam recording" else "Dashcam preview")
            .setContentText("Tap to open Car Launcher")
            .setSmallIcon(R.drawable.ic_dashcam_notification)
            .setOngoing(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (wantRecording &&
                prefs.current.dashRecordAudio &&
                Dashcam.hasMicPermission(this)
            ) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val ACTION_START = "com.donovan.carlauncher.dashcam.START"
        const val ACTION_STOP = "com.donovan.carlauncher.dashcam.STOP"
        const val ACTION_PREVIEW = "com.donovan.carlauncher.dashcam.PREVIEW"
        const val ACTION_STOP_PREVIEW = "com.donovan.carlauncher.dashcam.STOP_PREVIEW"
        private const val CHANNEL_ID = "dashcam"
        private const val NOTIFICATION_ID = 4201
    }
}
