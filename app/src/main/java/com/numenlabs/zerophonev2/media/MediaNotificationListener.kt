package com.numenlabs.zerophonev2.media

import android.service.notification.NotificationListenerService

/**
 * Holds the notification-listener access (granted in Settings → Special access
 * → Notification access, or with one adb command). The listener itself does
 * nothing — MediaWatcher reads active MediaSessions through it, which is how
 * "video is playing in app X" is detected.
 */
class MediaNotificationListener : NotificationListenerService()
