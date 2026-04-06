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
    private val STEP_COOLDOWN_MS = 300L

    // Speed mode detection via rolling step cadence
    // Stores timestamps (ms) of the last N steps
    private val stepTimestamps = ArrayDeque<Long>()
    private val CADENCE_WINDOW_MS = 3000L   // look at steps within last 3 seconds
    private val CADENCE_WINDOW_SIZE = 6     // keep at most 6 entries

    // steps/sec thresholds  (tune to feel — overridden by intent from MainActivity)
    // < WALK_MAX  -> walk  (W + LCtrl)
    // < JOG_MAX   -> jog   (W only)
    // >= JOG_MAX  -> sprint (W + LShift)
    private var WALK_MAX_SPS = 1.4f   // up to ~1.4 steps/sec = walking
    private var JOG_MAX_SPS  = 2.2f   // up to ~2.2 steps/sec = jogging

    private var currentMode = "jog"

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
        const val EXTRA_WALK_THRESHOLD   = "EXTRA_WALK_THRESHOLD"
        const val EXTRA_SPRINT_THRESHOLD = "EXTRA_SPRINT_THRESHOLD"
        
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
                WALK_MAX_SPS  = intent.getFloatExtra(EXTRA_WALK_THRESHOLD,   1.4f)
                JOG_MAX_SPS   = intent.getFloatExtra(EXTRA_SPRINT_THRESHOLD, 2.2f)
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
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            
            // Subtract gravity for a hardware-accelerated linear acceleration estimate
            val rawMagnitude = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()
            val magnitude = Math.abs(rawMagnitude - SensorManager.GRAVITY_EARTH)
            
            val currentTime = System.currentTimeMillis()
            
            if (magnitude > stepThreshold && (currentTime - lastStepTime) > STEP_COOLDOWN_MS) {
                lastStepTime = currentTime
                stepCount++
                currentStepCount = stepCount
                
                stepUpdateListener?.invoke(stepCount)

                // Update cadence window and classify speed mode
                val mode = classifyMode(currentTime)
                currentMode = mode

                stopHandler.removeCallbacks(stopRunnable)
                sendStepWithMode(mode)
                
                stopHandler.postDelayed(stopRunnable, 400L)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** Classify current walking speed based on recent step cadence. */
    private fun classifyMode(nowMs: Long): String {
        // Add current timestamp
        stepTimestamps.addLast(nowMs)

        // Prune entries outside the rolling window
        while (stepTimestamps.isNotEmpty() && nowMs - stepTimestamps.first() > CADENCE_WINDOW_MS) {
            stepTimestamps.removeFirst()
        }
        // Keep list bounded
        while (stepTimestamps.size > CADENCE_WINDOW_SIZE) {
            stepTimestamps.removeFirst()
        }

        // Need at least 2 events to compute cadence
        if (stepTimestamps.size < 2) return currentMode

        val spanMs = stepTimestamps.last() - stepTimestamps.first()
        if (spanMs <= 0) return currentMode

        val stepsPerSec = (stepTimestamps.size - 1) * 1000f / spanMs

        return when {
            stepsPerSec < WALK_MAX_SPS -> "walk"
            stepsPerSec < JOG_MAX_SPS  -> "jog"
            else                        -> "sprint"
        }
    }

    private fun sendStepWithMode(mode: String) {
        if (ipAddress.isEmpty()) return
        thread {
            try {
                val udpSocket = DatagramSocket()
                val serverAddr = InetAddress.getByName(ipAddress)
                val buf = "{\"event\":\"step\",\"mode\":\"$mode\"}".toByteArray()
                val packet = DatagramPacket(buf, buf.size, serverAddr, 5005)
                udpSocket.send(packet)
                udpSocket.close()
            } catch (e: Exception) {
                Log.e("StepTrackerService", "Error sending step", e)
                val errorMessage = e.message ?: "Unknown Network Error"
                Handler(Looper.getMainLooper()).post {
                    errorListener?.invoke("Failed to send: $errorMessage")
                }
            }
        }
    }

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
