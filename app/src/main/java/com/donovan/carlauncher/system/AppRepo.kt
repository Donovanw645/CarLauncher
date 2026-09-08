package com.donovan.carlauncher.system

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class AppEntry(
    val label: String,
    val packageName: String,
    val icon: ImageBitmap?,
)

/** Enumerates launchable apps for the drawer, and resolves app icons for media sources. */
class AppRepo(private val context: Context) {

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    private val iconCache = HashMap<String, ImageBitmap?>()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = runCatching { pm.queryIntentActivities(intent, 0) }.getOrNull().orEmpty()
        val self = context.packageName
        val list = resolved.asSequence()
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == self) return@mapNotNull null
                val label = runCatching { info.loadLabel(pm).toString() }.getOrNull()
                    ?: return@mapNotNull null
                AppEntry(
                    label = label,
                    packageName = pkg,
                    icon = runCatching { info.loadIcon(pm)?.toImageBitmap(128) }.getOrNull(),
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .toList()
        _apps.value = list
    }

    /** Icon for an arbitrary package (used to badge the current media source). */
    fun iconFor(packageName: String): ImageBitmap? = iconCache.getOrPut(packageName) {
        runCatching {
            context.packageManager.getApplicationIcon(packageName).toImageBitmap(96)
        }.getOrNull()
    }

    fun labelFor(packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName.substringAfterLast('.'))

    fun launch(packageName: String): Boolean {
        val intent = runCatching {
            context.packageManager.getLaunchIntentForPackage(packageName)
        }.getOrNull() ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }

    fun isInstalled(packageName: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(packageName, 0)
        true
    }.getOrDefault(false)
}

private fun Drawable.toImageBitmap(size: Int): ImageBitmap {
    if (this is BitmapDrawable && bitmap != null) {
        return bitmap.copy(Bitmap.Config.ARGB_8888, false).asImageBitmap()
    }
    val w = if (intrinsicWidth > 0) minOf(intrinsicWidth, size) else size
    val h = if (intrinsicHeight > 0) minOf(intrinsicHeight, size) else size
    val bmp = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bmp.asImageBitmap()
}
