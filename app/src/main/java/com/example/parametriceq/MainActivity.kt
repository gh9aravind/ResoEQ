package com.example.parametriceq

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
 * Stage 3 UI: no MediaProjection request needed anymore. SessionEqService
 * attaches directly to other apps' audio sessions via broadcast, so the only
 * permission we need here is POST_NOTIFICATIONS (Android 13+, for the
 * required foreground-service notification).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val bandFrequencies = SessionEqService.CENTER_FREQS
    private val bandGains = FloatArray(bandFrequencies.size)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            startSessionService()
        } else {
            binding.statusText.text = getString(R.string.status_permission_denied)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        buildBandSliders()

        binding.startButton.setOnClickListener { requestPermissionsAndStart() }
        binding.stopButton.setOnClickListener {
            stopService(Intent(this, SessionEqService::class.java))
            binding.statusText.text = getString(R.string.status_stopped)
        }
    }

    private fun requestPermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startSessionService()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startSessionService() {
        val serviceIntent = Intent(this, SessionEqService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        binding.statusText.text = getString(R.string.status_running)
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
                        SessionEqService.updateBandGain(index, gainDb)
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
