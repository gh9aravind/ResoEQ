package com.tunex.receiver

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.tunex.audio.service.AudioProcessingService

/**
 * Grantable only through Settings > Notification access (Android doesn't
 * allow this one via ADB pm grant - it's a user-toggle, not a normal
 * permission). We only look at whether a notification carries a
 * MediaSession token (Notification.EXTRA_MEDIA_SESSION) - i.e. whether it's
 * a media player's now-playing notification - never notification content,
 * matching Poweramp Equalizer's own description of this permission's use.
 *
 * We don't get a session ID from the notification itself (it doesn't carry
 * one) - this is purely a trigger: "something started/changed playing, go
 * re-check for new sessions now" instead of waiting for the next periodic
 * scan in AudioProcessingService.
 */
class TunexNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "TunexNotifListener"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (isMediaNotification(sbn)) {
            Log.d(TAG, "Media notification posted by ${sbn.packageName}, requesting rescan")
            requestRescan()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (isMediaNotification(sbn)) {
            requestRescan()
        }
    }

    private fun isMediaNotification(sbn: StatusBarNotification): Boolean {
        val extras = sbn.notification?.extras ?: return false
        return extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
    }

    private fun requestRescan() {
        try {
            val intent = Intent(this, AudioProcessingService::class.java).apply {
                action = AudioProcessingService.ACTION_RESCAN_SESSIONS
            }
            startService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request rescan", e)
        }
    }
}
