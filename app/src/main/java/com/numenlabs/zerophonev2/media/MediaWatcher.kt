package com.numenlabs.zerophonev2.media

import android.content.ComponentName
import android.content.Context
import android.app.NotificationManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState

/** Media-playback detection through active MediaSessions (needs notification access). */
object MediaWatcher {
    fun listenerComponent(context: Context): ComponentName =
        ComponentName(context, MediaNotificationListener::class.java)

    fun hasNotificationAccess(context: Context): Boolean =
        try {
            context.getSystemService(NotificationManager::class.java)
                .isNotificationListenerAccessGranted(listenerComponent(context))
        } catch (_: Exception) {
            false
        }

    /** True when the given package has a MediaSession actively PLAYING (e.g. a video). */
    fun isPlaying(
        context: Context,
        packageName: String,
    ): Boolean =
        try {
            val msm = context.getSystemService(MediaSessionManager::class.java) ?: return false
            msm.getActiveSessions(listenerComponent(context)).any { session ->
                session.packageName == packageName &&
                    session.playbackState?.state == PlaybackState.STATE_PLAYING
            }
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
}
