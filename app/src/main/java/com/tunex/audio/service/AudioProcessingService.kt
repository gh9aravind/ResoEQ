package com.tunex.audio.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.audiofx.AudioEffect
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.tunex.MainActivity
import com.tunex.R
import com.tunex.audio.engine.AudioEngineController
import com.tunex.audio.engine.AudioSessionManager
import com.tunex.data.model.AdvancedAudioSettings
import com.tunex.data.model.SoundProfile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AudioProcessingService : Service() {
    
    companion object {
        private const val TAG = "AudioProcessingService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "tunex_audio_channel"
        private const val CHANNEL_NAME = "Tunex Audio Processing"
        
        const val ACTION_START = "com.tunex.action.START"
        const val ACTION_STOP = "com.tunex.action.STOP"
        const val ACTION_TOGGLE = "com.tunex.action.TOGGLE"
        const val ACTION_UPDATE_PROFILE = "com.tunex.action.UPDATE_PROFILE"
        
        fun startService(context: Context) {
            val intent = Intent(context, AudioProcessingService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stopService(context: Context) {
            val intent = Intent(context, AudioProcessingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
    
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private lateinit var audioEngine: AudioEngineController
    private lateinit var sessionManager: AudioSessionManager
    
    private val _serviceState = MutableStateFlow(ServiceState())
    val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()
    
    private var currentProfile: SoundProfile? = null
    private var currentBands: List<Float> = List(10) { 0f }
    private var isInitialized = false

    // Direct, context-registered receiver (belt-and-suspenders alongside the
    // manifest-declared AudioEffectReceiver - Android 8+ doesn't reliably
    // deliver implicit broadcasts to manifest receivers, so we also listen
    // here directly while the service is alive).
    private val sessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
            if (sessionId <= 0) return
            when (action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> attachSession(sessionId)
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> detachSession(sessionId)
            }
        }
    }
    
    data class ServiceState(
        val isRunning: Boolean = false,
        val isEnabled: Boolean = false,
        val currentProfileName: String? = null,
        val activeSessionCount: Int = 0,
        val outputDevice: String = "Speaker"
    )
    
    inner class LocalBinder : Binder() {
        fun getService(): AudioProcessingService = this@AudioProcessingService
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        
        audioEngine = AudioEngineController(applicationContext)
        sessionManager = AudioSessionManager(applicationContext)
        
        createNotificationChannel()

        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(sessionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(sessionReceiver, filter)
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_START -> startAudioProcessing()
            ACTION_STOP -> stopAudioProcessing()
            ACTION_TOGGLE -> toggleAudioProcessing()
            "com.tunex.action.ATTACH_SESSION" -> {
                val sessionId = intent.getIntExtra("session_id", -1)
                if (sessionId > 0) attachSession(sessionId)
            }
            "com.tunex.action.DETACH_SESSION" -> {
                val sessionId = intent.getIntExtra("session_id", -1)
                if (sessionId > 0) detachSession(sessionId)
            }
            else -> startAudioProcessing()
        }
        
        return START_STICKY
    }

    /** Attaches our effects to a REAL session id (from the broadcast) and applies current settings. */
    private fun attachSession(sessionId: Int) {
        if (!isInitialized) {
            // Broadcast can arrive before the user has pressed Start in the app.
            // We still need startForeground() to have been called before doing
            // any real work, so bring the service up properly first.
            startAudioProcessing()
        }
        if (audioEngine.initializeSession(sessionId)) {
            audioEngine.applyEqualizerBands(sessionId, currentBands)
            currentProfile?.let { audioEngine.applySoundProfile(sessionId, it) }
            updateState { it.copy(activeSessionCount = audioEngine.activeSessions.value.size) }
            Log.d(TAG, "Attached EQ to session $sessionId")
        }
    }

    private fun detachSession(sessionId: Int) {
        audioEngine.releaseSession(sessionId)
        updateState { it.copy(activeSessionCount = audioEngine.activeSessions.value.size) }
        Log.d(TAG, "Detached EQ from session $sessionId")
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")

        try {
            unregisterReceiver(sessionReceiver)
        } catch (e: Exception) {
            // Wasn't registered - fine.
        }
        
        cleanup()
        serviceScope.cancel()
    }
    
    private fun startAudioProcessing() {
        if (isInitialized) {
            Log.d(TAG, "Already initialized")
            return
        }
        
        // Start foreground
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        // Initialize audio engine
        serviceScope.launch {
            initializeAudioEngine()
        }
        
        isInitialized = true
        updateState { it.copy(isRunning = true, isEnabled = true) }
        
        Log.d(TAG, "Audio processing started")
    }
    
    private suspend fun initializeAudioEngine() = withContext(Dispatchers.IO) {
        // Check device capabilities
        val capabilities = audioEngine.checkDeviceCapabilities()
        Log.d(TAG, "Device capabilities: $capabilities")
        
        // Try to initialize global session (session 0)
        val globalSuccess = audioEngine.initializeSession(0)
        Log.d(TAG, "Global session init: $globalSuccess")
        
        // Start session monitoring
        sessionManager.startMonitoring { sessions ->
            serviceScope.launch {
                handleSessionsChanged(sessions)
            }
        }
        
        // Monitor output device changes
        serviceScope.launch {
            sessionManager.outputDevice.collect { device ->
                updateState { it.copy(outputDevice = device.name) }
            }
        }
        
        // Monitor engine state
        serviceScope.launch {
            audioEngine.engineState.collect { engineState ->
                updateState { it.copy(activeSessionCount = engineState.activeSessionCount) }
            }
        }
    }
    
    private suspend fun handleSessionsChanged(sessions: List<AudioSessionManager.AudioSessionInfo>) {
        // NOTE: AudioSessionManager's session IDs come from AudioManager's
        // AudioPlaybackCallback, which does NOT expose real session IDs to
        // third-party apps (AudioPlaybackConfiguration#getSessionId() is a
        // hidden @SystemApi we can't call) - only a count/type of what's
        // playing. We use this purely to show "N apps playing" in the UI,
        // NOT to attach effects. Real effect attachment happens in
        // attachSession()/detachSession(), driven by the broadcast that
        // carries an actual usable session id.
        Log.d(TAG, "Playback activity changed: ${sessions.size} apps playing")
    }
    
    private fun stopAudioProcessing() {
        cleanup()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        
        isInitialized = false
        updateState { ServiceState() }
        
        Log.d(TAG, "Audio processing stopped")
    }
    
    private fun toggleAudioProcessing() {
        val currentEnabled = _serviceState.value.isEnabled
        setEnabled(!currentEnabled)
    }
    
    private fun cleanup() {
        sessionManager.stopMonitoring()
        audioEngine.releaseAll()
    }
    
    // Public API for controlling the service
    
    fun setEnabled(enabled: Boolean) {
        audioEngine.setMasterEnabled(enabled)
        updateState { it.copy(isEnabled = enabled) }
        updateNotification()
        
        Log.d(TAG, "Audio processing enabled: $enabled")
    }
    
    fun applyProfile(profile: SoundProfile) {
        currentProfile = profile
        currentBands = profile.equalizerBands
        audioEngine.applySoundProfileToAll(profile)
        updateState { it.copy(currentProfileName = profile.name) }
        updateNotification()
        
        Log.d(TAG, "Applied profile: ${profile.name}")
    }
    
    fun applyEqualizerBands(bands: List<Float>) {
        currentBands = bands
        audioEngine.applyEqualizerToAll(bands)
    }
    
    fun applyAdvancedSettings(settings: AdvancedAudioSettings) {
        audioEngine.activeSessions.value.forEach { sessionId ->
            audioEngine.applyAdvancedSettings(sessionId, settings)
        }
    }
    
    fun getAudioEngine(): AudioEngineController = audioEngine
    fun getSessionManager(): AudioSessionManager = sessionManager
    
    // Notification handling
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Tunex audio processing notification"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val toggleIntent = Intent(this, AudioProcessingService::class.java).apply {
            action = ACTION_TOGGLE
        }
        
        val togglePendingIntent = PendingIntent.getService(
            this,
            1,
            toggleIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val state = _serviceState.value
        val statusText = when {
            !state.isEnabled -> "Disabled"
            state.currentProfileName != null -> state.currentProfileName
            else -> "Active"
        }
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Tunex Audio")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                if (state.isEnabled) R.drawable.ic_pause else R.drawable.ic_play,
                if (state.isEnabled) "Disable" else "Enable",
                togglePendingIntent
            )
            .build()
    }
    
    private fun updateNotification() {
        val notification = createNotification()
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    private inline fun updateState(update: (ServiceState) -> ServiceState) {
        _serviceState.value = update(_serviceState.value)
    }
}
