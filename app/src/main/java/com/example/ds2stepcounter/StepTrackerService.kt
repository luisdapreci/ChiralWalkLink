package com.example.ds2stepcounter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

class StepTrackerService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometerSensor: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var ipAddress: String = ""
    private var stepThreshold = 3.0f
    
    private var stepCount = 0
    private var lastStepTime = 0L

    // ── Step detection pipeline constants ─────────────────────────────────────
    // Low-pass filter smoothing factor: 0 < alpha < 1
    //   Lower  → smoother signal, better for slow walks, slightly more lag
    //   Higher → noisier signal, faster response
    private val FILTER_ALPHA = 0.15f

    // Adaptive cooldown bounds (ms)
    private val MIN_STEP_INTERVAL_MS = 250L   // ~4 steps/sec max (sprinting)
    private val MAX_STEP_INTERVAL_MS = 1400L  // ~0.7 steps/sec min (very slow)

    // Sliding window for computing dynamic min/max of the filtered signal (ms)
    private val DYNAMIC_WINDOW_MS = 2000L

    // Peak sensitivity: what fraction of the (max-min) range the signal must
    // exceed before being considered a candidate peak.
    // Mapped from the user's sensitivity slider: MIN_PEAK_FACTOR = most sensitive.
    private val MIN_PEAK_FACTOR = 0.22f
    private val MAX_PEAK_FACTOR = 0.72f

    // ── Step detection pipeline state ─────────────────────────────────────────
    private var filteredMagnitude = 0f        // current EMA output
    private var isArmed           = false     // true after signal rises above threshold
    private var peakValue         = 0f        // highest value seen while armed

    // Circular buffer of (timestamp, filteredMagnitude) used for dynamic range
    private val signalHistory = ArrayDeque<Pair<Long, Float>>(128)

    private val stopHandler = Handler(Looper.getMainLooper())
    private val stopRunnable = Runnable { sendEventToServer("stop") }
    
    companion object {
        var stepUpdateListener: ((Int) -> Unit)? = null
        var errorListener: ((String) -> Unit)? = null
        var connectionListener: ((Boolean, String?) -> Unit)? = null
        var currentStepCount = 0
        
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_IP = "EXTRA_IP"
        const val EXTRA_THRESHOLD = "EXTRA_THRESHOLD"
        
        const val NOTIFICATION_ID = 101
        const val CHANNEL_ID = "StepTrackerChannel"
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                ipAddress = intent.getStringExtra(EXTRA_IP) ?: ""
                stepThreshold = intent.getFloatExtra(EXTRA_THRESHOLD, 3.0f)
                startTracking()
            }
            ACTION_STOP -> stopTracking()
        }
        return START_NOT_STICKY
    }

    private fun startTracking() {
        // OS Requirement: Service MUST call startForeground within 5 seconds of startForegroundService
        val connectingNotification = createNotification("Connecting to Chiral Network...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, connectingNotification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, connectingNotification)
        }

        thread {
            try {
                val udpSocket = DatagramSocket()
                udpSocket.soTimeout = 2000 // 2 seconds timeout
                val serverAddr = InetAddress.getByName(ipAddress)
                
                // Send Ping
                val pingBuf = "{\"event\":\"ping\"}".toByteArray()
                val pingPacket = DatagramPacket(pingBuf, pingBuf.size, serverAddr, 5005)
                udpSocket.send(pingPacket)
                
                // Wait for Pong
                val recvBuf = ByteArray(1024)
                val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
                udpSocket.receive(recvPacket)
                val response = String(recvPacket.data, 0, recvPacket.length)
                udpSocket.close()
                
                if (response.contains("pong")) {
                    Handler(Looper.getMainLooper()).post {
                        connectionListener?.invoke(true, null)
                        startSensors()
                    }
                } else {
                    Handler(Looper.getMainLooper()).post {
                        connectionListener?.invoke(false, "Invalid response: $response")
                        stopSelf()
                    }
                }
            } catch (e: Exception) {
                 Handler(Looper.getMainLooper()).post {
                    connectionListener?.invoke(false, "Chiral Network Link offline. Check IP.")
                    stopSelf()
                 }
            }
        }
    }

    private fun startSensors() {
        val notification = createNotification("Tracking steps in background...")
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)

        // Acquire WakeLock
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DS2StepCounter::WakeLock")
        wakeLock?.acquire()

        stepCount = 0
        currentStepCount = 0
        stepUpdateListener?.invoke(stepCount)

        // Reset detection pipeline state
        filteredMagnitude = 0f
        isArmed           = false
        peakValue         = 0f
        signalHistory.clear()
        lastStepTime = 0L

        accelerometerSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    private fun stopTracking() {
        sensorManager.unregisterListener(this)
        stopHandler.removeCallbacks(stopRunnable)
        sendEventToServer("stop")
        
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        // ─── Stage 1: Gravity-subtracted magnitude ────────────────────────────
        val rawMagnitude = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()
        val rawLinear    = Math.abs(rawMagnitude - SensorManager.GRAVITY_EARTH)

        // ─── Stage 2: Exponential low-pass filter (EMA) ───────────────────────
        // Initialise filter on first sample
        if (filteredMagnitude == 0f) filteredMagnitude = rawLinear
        filteredMagnitude = FILTER_ALPHA * rawLinear + (1f - FILTER_ALPHA) * filteredMagnitude

        val now = System.currentTimeMillis()

        // ─── Stage 3: Sliding-window dynamic range ────────────────────────────
        signalHistory.addLast(Pair(now, filteredMagnitude))
        // Prune samples older than DYNAMIC_WINDOW_MS
        while (signalHistory.isNotEmpty() && now - signalHistory.first().first > DYNAMIC_WINDOW_MS) {
            signalHistory.removeFirst()
        }

        val dynMin  = signalHistory.minOf { it.second }
        val dynMax  = signalHistory.maxOf { it.second }
        val dynRange = dynMax - dynMin

        // Map stepThreshold (user slider, e.g. 0.5–4.0) to a peak factor.
        // stepThreshold is in range [0.5, 4.5]; map linearly to [MIN, MAX] peak factor.
        val sliderNorm   = ((stepThreshold - 0.5f) / 4.0f).coerceIn(0f, 1f)
        val peakFactor   = MIN_PEAK_FACTOR + sliderNorm * (MAX_PEAK_FACTOR - MIN_PEAK_FACTOR)
        val armThreshold = dynMin + dynRange * peakFactor
        // Reset line: signal must fall below this after a peak to re-enable detection
        val resetLine    = dynMin + dynRange * (peakFactor * 0.5f)

        // ─── Stage 4: Hysteresis ARM → TRIGGER state machine ─────────────────
        val timeSinceLastStep = now - lastStepTime

        // Only process if past the minimum cooldown
        if (timeSinceLastStep >= MIN_STEP_INTERVAL_MS) {

            if (!isArmed) {
                // Arm when signal rises above the dynamic threshold
                if (filteredMagnitude > armThreshold && dynRange > 0.10f) {
                    isArmed   = true
                    peakValue = filteredMagnitude
                }
            } else {
                // Track peak while armed
                if (filteredMagnitude > peakValue) peakValue = filteredMagnitude

                // Trigger (step!) when signal drops back below the reset line
                if (filteredMagnitude < resetLine) {
                    isArmed = false

                    // Adaptive cooldown: estimate next expected interval from recent cadence,
                    // clamped to [MIN, MAX]. This prevents double-triggers at high cadence
                    // while staying responsive at low cadence.
                    val adaptiveCooldown = timeSinceLastStep
                        .coerceIn(MIN_STEP_INTERVAL_MS, MAX_STEP_INTERVAL_MS)

                    // Guard: must still be within the adaptive window
                    if (timeSinceLastStep >= adaptiveCooldown || lastStepTime == 0L) {
                        lastStepTime   = now
                        stepCount++
                        currentStepCount = stepCount

                        stepUpdateListener?.invoke(stepCount)

                        stopHandler.removeCallbacks(stopRunnable)
                        sendEventToServer("step")
                        stopHandler.postDelayed(stopRunnable, 500L)
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun sendEventToServer(eventType: String) {
        if (ipAddress.isEmpty()) return
        thread {
            try {
                val udpSocket = DatagramSocket()
                val serverAddr = InetAddress.getByName(ipAddress)
                val buf = "{\"event\":\"$eventType\"}".toByteArray()
                val packet = DatagramPacket(buf, buf.size, serverAddr, 5005)
                udpSocket.send(packet)
                udpSocket.close()
            } catch (e: Exception) {
                Log.e("StepTrackerService", "Error sending event", e)
                val errorMessage = e.message ?: "Unknown Network Error"
                Handler(Looper.getMainLooper()).post {
                    errorListener?.invoke("Failed to send: $errorMessage")
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Step Tracker Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CHIRAL WALK LINK")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_walk)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopTracking()
    }
}
