package com.example.parametriceq

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.parametriceq.databinding.ActivityMainBinding

/**
 * Stage 1 UI: request the permissions we need, then request the MediaProjection
 * (system audio capture) token, then hand both off to CaptureService.
 *
 * The EQ bands here map 1:1 onto CaptureService.CENTER_FREQS. If you change one,
 * change the other too.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var projectionManager: MediaProjectionManager

    private val bandFrequencies = CaptureService.CENTER_FREQS
    private val bandGains = FloatArray(bandFrequencies.size)

    // Step 2: once permissions are granted, ask the system for permission to
    // capture audio output. On a device where you've run the ADB "appops set
    // ... PROJECT_MEDIA allow" command, this dialog is skipped automatically.
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            val serviceIntent = Intent(this, CaptureService::class.java).apply {
                putExtra(CaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(CaptureService.EXTRA_RESULT_DATA, data)
                putExtra(CaptureService.EXTRA_BAND_GAINS, bandGains)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            binding.statusText.text = getString(R.string.status_running)
        } else {
            binding.statusText.text = getString(R.string.status_permission_denied)
        }
    }

    // Step 1: normal runtime permissions (RECORD_AUDIO is required by AudioRecord
    // even though we're not touching the microphone; POST_NOTIFICATIONS is needed
    // on Android 13+ to show the required foreground-service notification).
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        } else {
            binding.statusText.text = getString(R.string.status_permission_denied)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        projectionManager = getSystemService(MediaProjectionManager::class.java)

        buildBandSliders()

        binding.startButton.setOnClickListener { requestPermissionsAndStart() }
        binding.stopButton.setOnClickListener {
            stopService(Intent(this, CaptureService::class.java))
            binding.statusText.text = getString(R.string.status_stopped)
        }
    }

    private fun requestPermissionsAndStart() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    /** Builds one label + one SeekBar per band. Values map from 0..30 to -15..+15 dB. */
    private fun buildBandSliders() {
        val container = binding.bandContainer
        bandFrequencies.forEachIndexed { index, freq ->
            val label = TextView(this).apply {
                text = if (freq >= 1000f) "${(freq / 1000).toInt()} kHz" else "${freq.toInt()} Hz"
            }
            val seekBar = SeekBar(this).apply {
                max = 30
                progress = 15 // 0 dB
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                        val gainDb = (value - 15).toFloat()
                        bandGains[index] = gainDb
                        CaptureService.updateBandGain(index, gainDb)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }
            container.addView(label)
            container.addView(seekBar)
        }
    }
}
