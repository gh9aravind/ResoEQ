package com.example.parametriceq

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Stage 3: no capture, no replay, no second AudioTrack - so no possible echo.
 *
 * Cooperative media apps broadcast AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION
 * when they create a player/audio session, specifically inviting third-party
 * equalizer apps to attach effects to THAT session. We listen for it and attach
 * DynamicsProcessing directly there - the EQ then runs inside the source app's
 * own audio pipeline, before its audio ever reaches the speaker.
 *
 * Limitation: only works with apps that actually send this broadcast. Many
 * standalone music players do. Some apps (YouTube, and some that use the
 * newer AAudio native API) create a session but don't broadcast it - those
 * won't be affected by this service. CaptureService remains as a fallback
 * for those apps.
 */
class SessionEqService : Service() {

    companion object {
        private const val TAG = "SessionEqService"
        private const val CHANNEL_ID = "session_eq_channel"
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_COUNT = 2

        val CENTER_FREQS = floatArrayOf(60f, 150f, 400f, 1000f, 2400f, 6000f, 15000f)

        // One DynamicsProcessing instance per currently-open cooperating session.
        private val activeEffects = mutableMapOf<Int, DynamicsProcessing>()
        private val currentGains = FloatArray(CENTER_FREQS.size)

        /** Called live from the UI whenever a gain slider moves - applies to every open session. */
        fun updateBandGain(bandIndex: Int, gainDb: Float) {
            currentGains[bandIndex] = gainDb
            synchronized(activeEffects) {
                for (dp in activeEffects.values) {
                    applyGain(dp, bandIndex, gainDb)
                }
            }
        }

        private fun applyGain(dp: DynamicsProcessing, bandIndex: Int, gainDb: Float) {
            for (ch in 0 until CHANNEL_COUNT) {
                dp.setPreEqBandByChannelIndex(
                    ch, bandIndex,
                    DynamicsProcessing.EqBand(true, CENTER_FREQS[bandIndex], gainDb)
                )
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
            val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: "unknown"
            if (sessionId == -1) return

            when (intent.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                    Log.i(TAG, "Session opened by $pkg: id=$sessionId")
                    attachTo(sessionId)
                }
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                    Log.i(TAG, "Session closed by $pkg: id=$sessionId")
                    detachFrom(sessionId)
                }
            }
        }
    }

    private fun attachTo(sessionId: Int) {
        synchronized(activeEffects) {
            if (activeEffects.containsKey(sessionId)) return
            try {
                val bandCount = CENTER_FREQS.size
                val config = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    CHANNEL_COUNT,
                    true, bandCount,
                    false, 0,
                    false, 0,
                    false
                ).build()
                val dp = DynamicsProcessing(0, sessionId, config)
                for (i in 0 until bandCount) {
                    applyGain(dp, i, currentGains[i])
                }
                dp.setEnabled(true)
                activeEffects[sessionId] = dp
                Log.i(TAG, "EQ attached to session $sessionId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to attach EQ to session $sessionId", e)
            }
        }
    }

    private fun detachFrom(sessionId: Int) {
        synchronized(activeEffects) {
            activeEffects.remove(sessionId)?.release()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Equalizer (session tracking)", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Parametric EQ (session tracking) running")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(receiver)
        } catch (e: Exception) {
            // Wasn't registered - fine.
        }
        synchronized(activeEffects) {
            activeEffects.values.forEach { it.release() }
            activeEffects.clear()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
