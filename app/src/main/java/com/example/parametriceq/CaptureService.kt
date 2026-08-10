package com.example.parametriceq

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
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
 *  2. Runs it through our own ParametricEqEngine (real biquad peaking filters -
 *     see ParametricEqEngine.kt) directly on the PCM samples.
 *  3. Writes the processed audio to an AudioTrack marked ALLOW_CAPTURE_BY_NONE,
 *     so our own output doesn't get captured again (that was the echo/feedback bug).
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

        // Band center frequencies in Hz - shared with MainActivity's sliders.
        val CENTER_FREQS = floatArrayOf(60f, 150f, 400f, 1000f, 2400f, 6000f, 15000f)

        @Volatile private var parametricEq: ParametricEqEngine? = null

        /** Called live from the UI whenever a gain slider moves. */
        fun updateBandGain(bandIndex: Int, gainDb: Float) {
            parametricEq?.setBandGain(bandIndex, gainDb)
        }

        /** Called live from the UI whenever a Q (bandwidth) slider moves. */
        fun updateBandQ(bandIndex: Int, q: Float) {
            parametricEq?.setBandQ(bandIndex, q)
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    @Volatile private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()

        // NOTE: Activity.RESULT_OK is -1, so -1 can't be used as a "missing" sentinel.
        val hasResultCode = intent?.hasExtra(EXTRA_RESULT_CODE) == true
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val resultData = intent?.let { getIntentExtraCompat(it, EXTRA_RESULT_DATA) }
        if (!hasResultCode || resultData == null) {
            Log.e(TAG, "Missing MediaProjection result, stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        Log.i(TAG, "Got MediaProjection result, resultCode=$resultCode")
        val initialGains = intent.getFloatArrayExtra(EXTRA_BAND_GAINS)

        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        try {
            startCapture(initialGains)
        } catch (e: Exception) {
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

        requestAudioFocus()

        val sampleRate = 48000
        val channelInMask = AudioFormat.CHANNEL_IN_STEREO
        val channelOutMask = AudioFormat.CHANNEL_OUT_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT

        val minInBuf = AudioRecord.getMinBufferSize(sampleRate, channelInMask, encoding)
        val minOutBuf = AudioTrack.getMinBufferSize(sampleRate, channelOutMask, encoding)

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
            .setBufferSizeInBytes(minInBuf)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    // Without this, our own EQ'd output gets captured again by
                    // our own AudioRecord - a runaway feedback loop.
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
            .setBufferSizeInBytes(minOutBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioRecord = record
        audioTrack = track

        val eq = ParametricEqEngine(sampleRate, CENTER_FREQS, CHANNEL_COUNT)
        initialGains?.forEachIndexed { i, gain -> eq.setBandGain(i, gain) }
        parametricEq = eq

        record.startRecording()
        track.play()
        running = true
        Log.i(TAG, "Capture pipeline started, sessionId=${track.audioSessionId}")

        thread(name = "eq-capture-thread") {
            val buffer = ShortArray(minInBuf)
            while (running) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    eq.processInPlace(buffer, read)
                    track.write(buffer, 0, read)
                }
            }
        }
    }

    private fun requestAudioFocus() {
        val audioManager = getSystemService(AudioManager::class.java) ?: return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { }
            .build()
        audioFocusRequest = request
        val result = audioManager.requestAudioFocus(request)
        val resultStr = when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> "GRANTED"
            AudioManager.AUDIOFOCUS_REQUEST_FAILED -> "FAILED"
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> "DELAYED"
            else -> "UNKNOWN($result)"
        }
        Log.i(TAG, "Audio focus request result: $resultStr")
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
        parametricEq = null
        audioFocusRequest?.let {
            getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(it)
        }
        mediaProjection?.stop()
        mediaProjection = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
