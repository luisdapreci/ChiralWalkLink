package com.example.ds2stepcounter

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var ipAddressInput: EditText
    private lateinit var toggleButton: Button
    private lateinit var statusText: TextView
    private lateinit var stepCountText: TextView
    private lateinit var sensitivitySlider: SeekBar
    private lateinit var sensitivityLabel: TextView
    private lateinit var walkThresholdSlider: SeekBar
    private lateinit var walkThresholdLabel: TextView
    private lateinit var sprintThresholdSlider: SeekBar
    private lateinit var sprintThresholdLabel: TextView

    private var isTracking = false
    private var stepThreshold = 3.0f
    private var walkThreshold = 1.4f    // steps/sec — Walk → Jog boundary
    private var sprintThreshold = 2.2f  // steps/sec — Jog → Sprint boundary

    private val PERMISSION_REQUEST_CODE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val crashPrefs = getSharedPreferences("crash_prefs", Context.MODE_PRIVATE)
        val crash = crashPrefs.getString("crash", null)
        if (crash != null) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Crash Log")
                .setMessage(crash)
                .setPositiveButton("Clear") { _, _ -> crashPrefs.edit().remove("crash").apply() }
                .setNeutralButton("Copy") { _, _ -> 
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("Crash Log", crash)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    crashPrefs.edit().remove("crash").apply()
                }
                .show()
        }

        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            val trace = android.util.Log.getStackTraceString(e)
            getSharedPreferences("crash_prefs", Context.MODE_PRIVATE).edit().putString("crash", trace).commit()
            System.exit(2)
        }

        setContentView(R.layout.activity_main)

        ipAddressInput = findViewById(R.id.ipAddressInput)
        toggleButton = findViewById(R.id.toggleButton)
        statusText = findViewById(R.id.statusText)
        stepCountText = findViewById(R.id.stepCountText)
        sensitivitySlider = findViewById(R.id.sensitivitySlider)
        sensitivityLabel = findViewById(R.id.sensitivityLabel)
        walkThresholdSlider = findViewById(R.id.walkThresholdSlider)
        walkThresholdLabel = findViewById(R.id.walkThresholdLabel)
        sprintThresholdSlider = findViewById(R.id.sprintThresholdSlider)
        sprintThresholdLabel = findViewById(R.id.sprintThresholdLabel)

        val ds2Prefs = getSharedPreferences("ds2_prefs", Context.MODE_PRIVATE)
        val savedIp = ds2Prefs.getString("last_ip", "")
        if (!savedIp.isNullOrEmpty()) {
            ipAddressInput.setText(savedIp)
        }

        // Restore saved threshold values
        stepThreshold   = ds2Prefs.getFloat("step_threshold",   3.0f)
        sensitivitySlider.progress = ((stepThreshold - 0.5f) * 10).toInt()
        sensitivityLabel.text = "Sensitivity Threshold: %.1f".format(stepThreshold)

        walkThreshold   = ds2Prefs.getFloat("walk_threshold",   1.4f)
        sprintThreshold = ds2Prefs.getFloat("sprint_threshold", 2.2f)
        walkThresholdSlider.progress   = (walkThreshold   * 10).toInt()
        sprintThresholdSlider.progress = (sprintThreshold * 10).toInt()
        walkThresholdLabel.text   = "Walk → Jog: %.1f steps/sec".format(walkThreshold)
        sprintThresholdLabel.text = "Jog → Sprint: %.1f steps/sec".format(sprintThreshold)

        toggleButton.setOnClickListener {
            if (isTracking) stopTracking() else startTracking()
        }

        sensitivitySlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                stepThreshold = 0.5f + (progress / 10.0f)
                sensitivityLabel.text = "Sensitivity Threshold: %.1f".format(stepThreshold)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                ds2Prefs.edit().putFloat("step_threshold", stepThreshold).apply()
            }
        })

        // Walk → Jog threshold slider (0.5 – 4.5 steps/sec, progress 5–45 → /10)
        walkThresholdSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = (progress.coerceAtLeast(5)) / 10.0f   // min 0.5
                // Clamp: walk must stay below sprint
                walkThreshold = value.coerceAtMost(sprintThreshold - 0.1f)
                walkThresholdLabel.text = "Walk → Jog: %.1f steps/sec".format(walkThreshold)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                ds2Prefs.edit().putFloat("walk_threshold", walkThreshold).apply()
            }
        })

        // Jog → Sprint threshold slider (0.5 – 4.5 steps/sec)
        sprintThresholdSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = (progress.coerceAtLeast(5)) / 10.0f
                // Clamp: sprint must stay above walk
                sprintThreshold = value.coerceAtLeast(walkThreshold + 0.1f)
                sprintThresholdLabel.text = "Jog → Sprint: %.1f steps/sec".format(sprintThreshold)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                ds2Prefs.edit().putFloat("sprint_threshold", sprintThreshold).apply()
            }
        })

        requestPermissions()
    }

    override fun onResume() {
        super.onResume()
        
        stepCountText.text = StepTrackerService.currentStepCount.toString()
        
        StepTrackerService.stepUpdateListener = { count ->
            runOnUiThread {
                stepCountText.text = count.toString()
            }
        }

        StepTrackerService.errorListener = { errorMsg ->
            runOnUiThread {
                Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show()
                // Stop tracking UI state gracefully if IP is totally invalid
                if (isTracking) stopTracking()
            }
        }

        StepTrackerService.connectionListener = { success, msg ->
            runOnUiThread {
                if (success) {
                    isTracking = true
                    toggleButton.text = "DEACTIVATE LINK"
                    statusText.text = "CHIRAL NETWORK: LINK ESTABLISHED"
                } else {
                    isTracking = false
                    toggleButton.text = "ACTIVATE LINK"
                    statusText.text = "CHIRAL NETWORK: OFFLINE"
                    Toast.makeText(this@MainActivity, msg ?: "Chiral Network Link Failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        
        StepTrackerService.stepUpdateListener = null
        StepTrackerService.errorListener = null
        StepTrackerService.connectionListener = null
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(Manifest.permission.ACTIVITY_RECOGNITION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun startTracking() {
        val ipStr = ipAddressInput.text.toString().trim()
        if (ipStr.isEmpty()) {
            Toast.makeText(this, "Please enter PC IP address", Toast.LENGTH_SHORT).show()
            return
        }

        val ipPattern = Regex("^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$")
        if (!ipPattern.matches(ipStr)) {
            Toast.makeText(this, "Please enter a valid IPv4 address (e.g. 192.168.1.5)", Toast.LENGTH_LONG).show()
            return
        }

        getSharedPreferences("ds2_prefs", Context.MODE_PRIVATE).edit().putString("last_ip", ipStr).apply()

        val serviceIntent = Intent(this, StepTrackerService::class.java).apply {
            action = StepTrackerService.ACTION_START
            putExtra(StepTrackerService.EXTRA_IP, ipStr)
            putExtra(StepTrackerService.EXTRA_THRESHOLD, stepThreshold)
            putExtra(StepTrackerService.EXTRA_WALK_THRESHOLD,   walkThreshold)
            putExtra(StepTrackerService.EXTRA_SPRINT_THRESHOLD, sprintThreshold)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        toggleButton.text = "ESTABLISHING LINK..."
        statusText.text = "CHIRAL NETWORK: CONNECTING..."
    }

    private fun stopTracking() {
        val serviceIntent = Intent(this, StepTrackerService::class.java).apply {
            action = StepTrackerService.ACTION_STOP
        }
        startService(serviceIntent)
        
        isTracking = false
        toggleButton.text = "ACTIVATE LINK"
        statusText.text = "CHIRAL NETWORK: OFFLINE"
    }
}
