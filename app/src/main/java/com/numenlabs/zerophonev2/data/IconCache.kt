package com.numenlabs.zerophonev2.data

import android.content.Context
import android.graphics.Bitmap
import java.io.File

/**
 * Persistent icon cache for distracting apps: one PNG per package under
 * files/icons. Kept OUT of the AppState JSON on purpose — the enforcement
 * engine snapshots that state constantly, and base64 icons would bloat every
 * read; small PNG files cost nothing.
 */
object IconCache {
    private const val DIR = "icons"

    private fun dir(context: Context): File = File(context.filesDir, DIR)

    fun load(
        context: Context,
        packageName: String,
    ): Bitmap? =
        try {
            val file = File(dir(context), fileName(packageName))
            if (file.isFile) android.graphics.BitmapFactory.decodeFile(file.absolutePath) else null
        } catch (_: Exception) {
            null
        }

    fun save(
        context: Context,
        packageName: String,
        icon: Bitmap,
    ) {
        try {
            val d = dir(context)
            if (!d.exists()) d.mkdirs()
            val tmp = File(d, "${fileName(packageName)}.tmp")
            tmp.outputStream().use { icon.compress(Bitmap.CompressFormat.PNG, 90, it) }
            val target = File(d, fileName(packageName))
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }

    /** Drop cached icons of packages that are no longer distracting. */
    fun prune(
        context: Context,
        validPackages: Set<String>,
    ) {
        try {
            val valid = validPackages.map { fileName(it) }.toSet()
            dir(context).listFiles()?.forEach { file ->
                if (file.name !in valid) file.delete()
            }
        } catch (_: Exception) {
        }
    }

    private fun fileName(packageName: String): String = packageName.replace('/', '_') + ".png"
}
