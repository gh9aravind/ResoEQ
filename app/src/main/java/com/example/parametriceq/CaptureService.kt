package com.example.parametriceq

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.audiofx.DynamicsProcessing
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlin.concurrent.thread

/**
 * Foreground service that:
 *  1. Captures the system's audio output using AudioPlaybackCaptureConfiguration
 *     (the same official, non-root API RootlessJamesDSP/Wavelet-style apps use).
 *  2. Copies the captured PCM straight into an AudioTrack.
 *  3. Attaches Android's built-in DynamicsProcessing effect to that AudioTrack's
 *     session, so the EQ is applied automatically as audio leaves the device.
 *
 * NOTE on "parametric": DynamicsProcessing's EQ bands are defined by cutoff
 * (edge) frequencies, closer to a graphic EQ than a true parametric EQ with
 * adjustable Q per band. That's the Stage 2 upgrade — replace this class's EQ
 * with your own biquad filters run over the PCM buffer before track.write().
 */
class CaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_BAND_GAINS = "band_gains"
        private const val TAG = "CaptureService"
        private const val CHANNEL_ID = "eq_service_channel"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_COUNT = 2

        // Band edges in Hz. Must stay increasing - DynamicsProcessing requires it.
        val CENTER_FREQS = floatArrayOf(60f, 150f, 400f, 1000f, 2400f, 6000f, 15000f)

        @Volatile private var dynamicsProcessing: DynamicsProcessing? = null

        /** Called live from the UI whenever a slider moves. */
        fun updateBandGain(bandIndex: Int, gainDb: Float) {
            val dp = dynamicsProcessing ?: return
            for (ch in 0 until CHANNEL_COUNT) {
                dp.setPreEqBandByChannelIndex(
                    ch, bandIndex,
                    DynamicsProcessing.EqBand(true, CENTER_FREQS[bandIndex], gainDb)
                )
            }
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    @Volatile private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()

        val hasResultCode = intent?.hasExtra(EXTRA_RESULT_CODE) == true
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val resultData = intent?.let { getIntentExtraCompat(it, EXTRA_RESULT_DATA) }
        if (!hasResultCode || resultData == null) {
            Log.e(TAG, "Missing MediaProjection result, stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        val initialGains = intent.getFloatArrayExtra(EXTRA_BAND_GAINS)

        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        try {
            startCapture(initialGains)
        } catch (e: Exception) {
            // Common causes: capture config rejected (rare on properly-permissioned
            // devices), or no other app currently playing audio with a matching usage.
            Log.e(TAG, "Failed to start capture", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun getIntentExtraCompat(intent: Intent, key: String): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(key)
        }
    }

    private fun startCapture(initialGains: FloatArray?) {
        val projection = mediaProjection ?: return

        val sampleRate = 48000
        val channelInMask = AudioFormat.CHANNEL_IN_STEREO
        val channelOutMask = AudioFormat.CHANNEL_OUT_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT

        val minInBuf = AudioRecord.getMinBufferSize(sampleRate, channelInMask, encoding)
        val minOutBuf = AudioTrack.getMinBufferSize(sampleRate, channelOutMask, encoding)

        // What we're allowed to capture. USAGE_MEDIA covers most music/video apps;
        // USAGE_GAME and USAGE_UNKNOWN widen coverage. Apps that opt out (Spotify,
        // Chrome) will simply not appear in the captured stream - see README.
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val recordFormat = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(channelInMask)
            .build()

        val record = AudioRecord.Builder()
            .setAudioFormat(recordFormat)
            .setBufferSizeInBytes(minInBuf * 2)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelOutMask)
                    .build()
            )
            .setBufferSizeInBytes(minOutBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioRecord = record
        audioTrack = track

        // Attach the EQ to the OUTPUT track's session. We never touch the audio
        // samples ourselves - the platform's effect chain applies the EQ as the
        // track plays, which is simpler and lower-latency than doing it by hand.
        setupEq(track.audioSessionId, initialGains)

        record.startRecording()
        track.play()
        running = true

        thread(name = "eq-capture-thread") {
            val buffer = ShortArray(minInBuf)
            while (running) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    track.write(buffer, 0, read)
                }
            }
        }
    }

    private fun setupEq(sessionId: Int, initialGains: FloatArray?) {
        val bandCount = CENTER_FREQS.size
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            CHANNEL_COUNT,
            true, bandCount, // preEq: in use, this many bands
            false, 0,        // multi-band compressor: off for now
            false, 0,        // postEq: off for now
            false            // limiter: off for now
        ).build()

        val dp = DynamicsProcessing(0, sessionId, config)
        for (ch in 0 until CHANNEL_COUNT) {
            for (i in 0 until bandCount) {
                val gain = initialGains?.getOrNull(i) ?: 0f
                dp.setPreEqBandByChannelIndex(
                    ch, i, DynamicsProcessing.EqBand(true, CENTER_FREQS[i], gain)
                )
            }
        }
        dp.setEnabled(true)
        dynamicsProcessing = dp
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Equalizer", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        running = false
        audioRecord?.stop()
        audioRecord?.release()
        audioTrack?.stop()
        audioTrack?.release()
        dynamicsProcessing?.release()
        dynamicsProcessing = null
        mediaProjection?.stop()
        mediaProjection = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
