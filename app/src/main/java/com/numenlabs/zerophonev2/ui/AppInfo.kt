package com.numenlabs.zerophonev2.ui

import android.graphics.Bitmap

/** A launchable app as shown in pickers/grids. */
data class AppInfo(
    val packageName: String,
    val label: String,
    val icon: Bitmap?,
)
