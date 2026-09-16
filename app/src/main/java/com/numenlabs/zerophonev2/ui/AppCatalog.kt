package com.numenlabs.zerophonev2.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/** Queries launchable apps with labels/icons (needs QUERY_ALL_PACKAGES). */
object AppCatalog {
    private const val ICON_SIZE_PX = 144
    /** Off the main thread: label/icon resolution + rasterization for every app is heavy. */
    suspend fun queryLaunchableApps(context: Context): List<AppInfo> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            queryLaunchableAppsBlocking(context)
        }

    private fun queryLaunchableAppsBlocking(context: Context): List<AppInfo> =
        try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            context.packageManager
                .queryIntentActivities(intent, 0)
                .asSequence()
                .mapNotNull { resolveInfo ->
                    val activity = resolveInfo.activityInfo ?: return@mapNotNull null
                    val label =
                        try {
                            activity.loadLabel(context.packageManager).toString()
                        } catch (_: Exception) {
                            activity.packageName
                        }
                    AppInfo(
                        packageName = activity.packageName,
                        label = label,
                        icon = drawableToBitmap(
                            try {
                                activity.loadIcon(context.packageManager)
                            } catch (_: Exception) {
                                null
                            },
                        ),
                    )
                }
                // One package may expose several launcher activities (e.g.
                // googlequicksearchbox) — pickers key by packageName, so dedupe.
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
                .toList()
        } catch (_: Exception) {
            emptyList()
        }

    /**
     * Rasterize at a FIXED pixel size: adaptive icons report bogus (or -1)
     * intrinsic dimensions, which produced 1×1 slivers in lists. The UI scales
     * the bitmap anyway, so one generous size fits everywhere.
     */
    private fun drawableToBitmap(drawable: Drawable?): Bitmap? {
        if (drawable == null) return null
        return try {
            if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap
            val size = ICON_SIZE_PX
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            bitmap
        } catch (_: Exception) {
            null
        }
    }
}
