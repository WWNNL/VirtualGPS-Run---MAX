package com.virtualrun.app.service

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.maps.model.LatLng
import com.virtualrun.app.MainActivity
import com.virtualrun.app.R
import com.virtualrun.app.algorithm.GpsQuality
import com.virtualrun.app.algorithm.InjectScenario
import com.virtualrun.app.model.Route
import java.util.Locale

/**
 * 虚拟定位 Mock Location Service
 *
 * 核心功能：
 * 1. 同时模拟 GPS、NETWORK、系统 FUSED provider 和 Google Play FLP
 * 2. 模拟真实动作 (TrajectoryInterpolator 提供)
 * 3. 支持后台运行和实时参数更新
 */
@SuppressLint("InlinedApi")
class MockLocationService : Service() {

    companion object {
        private const val TAG = "MockLocationService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "mock_location_service_channel"
        private const val WAKELOCK_TIMEOUT_MS = 10 * 60 * 60 * 1000L

        const val ACTION_START_MOCK = "com.virtualrun.action.START_MOCK"
        const val ACTION_START_FIXED = "com.virtualrun.action.START_FIXED"
        const val ACTION_STOP_MOCK = "com.virtualrun.action.STOP_MOCK"
        const val ACTION_UPDATE_PARAMS = "com.virtualrun.action.UPDATE_PARAMS"

        const val EXTRA_PACE = "extra_pace"
        const val EXTRA_ROUTE_POINTS = "extra_route_points"
        const val EXTRA_IS_LOOP = "extra_is_loop"
        const val EXTRA_IS_FIXED = "extra_is_fixed"
        const val EXTRA_FIXED_LAT = "extra_fixed_lat"
        const val EXTRA_FIXED_LNG = "extra_fixed_lng"
        const val EXTRA_SCENARIO = "extra_scenario"
        const val EXTRA_QUALITY = "extra_quality"

        const val BROADCAST_ACTION_STATE_UPDATE = "com.virtualrun.broadcast.STATE_UPDATE"
        const val BROADCAST_EXTRA_LAT = "extra_lat"
        const val BROADCAST_EXTRA_LNG = "extra_lng"
        const val BROADCAST_EXTRA_PROGRESS = "extra_progress"
        const val BROADCAST_EXTRA_LAP = "extra_lap"
        const val BROADCAST_EXTRA_SPEED = "extra_speed"
        const val BROADCAST_EXTRA_COMPLETED = "extra_completed"
        const val BROADCAST_EXTRA_RUNNING = "extra_running"
        const val BROADCAST_EXTRA_HAS_LOCATION = "extra_has_location"

        fun isMockEnabled(context: Context): Boolean {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            return try {
                lm.addTestProvider(
                    "test_check",
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    ProviderProperties.POWER_USAGE_LOW,
                    ProviderProperties.ACCURACY_FINE
                )
                lm.removeTestProvider("test_check")
                true
            } catch (e: SecurityException) {
                false
            } catch (e: Exception) {
                true
            }
        }
    }

    private lateinit var pump: MockLocationPump
    private val mainHandler = Handler(android.os.Looper.getMainLooper())
    private var session = 0L
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var currentPace = 6.0f
    private var currentRoutePoints: ArrayList<LatLng>? = null
    private var currentIsLoop = false
    private var currentIsFixed = false
    private var currentFixedLat = 0.0
    private var currentFixedLng = 0.0
    private var currentScenario: InjectScenario = InjectScenario.NATURAL
    private var currentQuality: GpsQuality = GpsQuality.HIGH
    private var currentLogFile: java.io.File? = null
    private var lastResult: com.virtualrun.app.algorithm.PositionResult? = null
    private var terminalStateBroadcast = false
    private var lastNotificationAt = 0L

    override fun onCreate() {
        super.onCreate()
        pump = MockLocationPump(applicationContext)
        createNotificationChannel()
        acquireWakeLock()
        Log.d(TAG, "Mock Service Created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MOCK -> {
                Log.d(TAG, "Action: START_MOCK")
                currentPace = intent.getFloatExtra(EXTRA_PACE, 6.0f)
                currentIsLoop = intent.getBooleanExtra(EXTRA_IS_LOOP, false)
                currentIsFixed = false
                currentRoutePoints = intent.getParcelableArrayListExtra(EXTRA_ROUTE_POINTS)
                val scenario = parseScenario(intent.getStringExtra(EXTRA_SCENARIO))
                val quality = parseQuality(intent.getStringExtra(EXTRA_QUALITY))
                currentScenario = scenario
                currentQuality = quality
                currentLogFile = InjectLog.beginSession(this, scenario, quality, "route")

                val points = currentRoutePoints
                if (points != null && points.size >= 2) {
                    val route = Route.fromLatLngPoints(points)
                    val activeSession = beginSession()
                    pump.start(route, currentPace, currentIsLoop, scenario, quality) { result ->
                        onPumpPosition(activeSession, result)
                    }
                } else {
                    broadcastTerminalState()
                    stopSelf()
                }
            }
            ACTION_START_FIXED -> {
                val lat = intent.getDoubleExtra(EXTRA_FIXED_LAT, Double.NaN)
                val lng = intent.getDoubleExtra(EXTRA_FIXED_LNG, Double.NaN)
                Log.d(TAG, "Action: START_FIXED lat=$lat lng=$lng")
                if (lat.isNaN() || lng.isNaN()) {
                    broadcastTerminalState()
                    stopSelf()
                } else {
                    currentPace = 0f
                    currentIsLoop = false
                    currentIsFixed = true
                    currentFixedLat = lat
                    currentFixedLng = lng
                    currentRoutePoints = null
                    currentScenario = InjectScenario.NATURAL
                    val quality = parseQuality(intent.getStringExtra(EXTRA_QUALITY))
                    currentQuality = quality
                    currentLogFile = InjectLog.beginSession(this, InjectScenario.NATURAL, quality, "fixed")
                    val activeSession = beginSession()
                    pump.startStatic(lat, lng, quality) { result ->
                        onPumpPosition(activeSession, result)
                    }
                }
            }
            ACTION_UPDATE_PARAMS -> {
                val pace = intent.getFloatExtra(EXTRA_PACE, -1.0f)
                Log.d(TAG, "收到 ACTION_UPDATE_PARAMS, pace=$pace")
                if (pace > 0) {
                    currentPace = pace
                    pump.updatePace(pace)
                    updateNotification(lastResult)
                } else {
                    Log.w(TAG, "收到无效的配速值: $pace")
                }
            }
            ACTION_STOP_MOCK -> {
                Log.d(TAG, "Action: STOP_MOCK")
                stopMockImmediately()
            }
        }
        // 未持久化运动状态时不自动重放起跑 Intent，避免进程恢复后瞬移回路线起点。
        return START_NOT_STICKY
    }

    private fun parseScenario(name: String?): InjectScenario =
        InjectScenario.entries.firstOrNull { it.name == name } ?: InjectScenario.NATURAL

    private fun parseQuality(name: String?): GpsQuality =
        GpsQuality.entries.firstOrNull { it.name == name } ?: GpsQuality.HIGH

    /** 统一开启一次新会话：切换 session 号、重置状态并拉起前台通知。 */
    private fun beginSession(): Long {
        val activeSession = ++session
        terminalStateBroadcast = false
        startForeground(NOTIFICATION_ID, buildNotification())
        return activeSession
    }

    /** 轨迹/固定定位共用的位置回调，过期会话的结果直接丢弃。 */
    private fun onPumpPosition(activeSession: Long, result: com.virtualrun.app.algorithm.PositionResult) {
        mainHandler.post {
            if (session == activeSession && !terminalStateBroadcast) {
                lastResult = result
                broadcastUpdate(result)
                if (SystemClock.elapsedRealtime() - lastNotificationAt >= 1000L) {
                    lastNotificationAt = SystemClock.elapsedRealtime()
                    updateNotification(result)
                }
                if (result.isCompleted) stopMockImmediately()
            }
        }
    }

    private fun stopMockImmediately() {
        ++session
        pump.stop()
        InjectLog.endSession()
        currentLogFile = null
        lastResult = null
        broadcastTerminalState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun broadcastUpdate(result: com.virtualrun.app.algorithm.PositionResult) {
        val running = !result.isCompleted
        val intent = Intent(BROADCAST_ACTION_STATE_UPDATE).apply {
            setPackage(packageName)
            putExtra(BROADCAST_EXTRA_HAS_LOCATION, true)
            putExtra(BROADCAST_EXTRA_LAT, result.latitude)
            putExtra(BROADCAST_EXTRA_LNG, result.longitude)
            putExtra(BROADCAST_EXTRA_PROGRESS, result.progress)
            putExtra(BROADCAST_EXTRA_LAP, result.lapCount)
            putExtra(BROADCAST_EXTRA_SPEED, result.speed)
            putExtra(BROADCAST_EXTRA_COMPLETED, result.isCompleted)
            putExtra(BROADCAST_EXTRA_RUNNING, running)
            putExtra(EXTRA_PACE, currentPace)
            putExtra(EXTRA_IS_LOOP, currentIsLoop)
            putExtra(EXTRA_IS_FIXED, currentIsFixed)
            putExtra(EXTRA_SCENARIO, currentScenario.name)
            putExtra(EXTRA_QUALITY, currentQuality.name)
            putExtra(EXTRA_FIXED_LAT, currentFixedLat)
            putExtra(EXTRA_FIXED_LNG, currentFixedLng)
            putParcelableArrayListExtra(EXTRA_ROUTE_POINTS, currentRoutePoints)
        }
        if (result.isCompleted) terminalStateBroadcast = true
        sendBroadcast(intent)
    }

    private fun broadcastTerminalState() {
        if (terminalStateBroadcast) return
        terminalStateBroadcast = true
        sendBroadcast(Intent(BROADCAST_ACTION_STATE_UPDATE).apply {
            setPackage(packageName)
            putExtra(BROADCAST_EXTRA_HAS_LOCATION, false)
            putExtra(BROADCAST_EXTRA_COMPLETED, true)
            putExtra(BROADCAST_EXTRA_RUNNING, false)
            putExtra(BROADCAST_EXTRA_SPEED, 0f)
        })
    }

    override fun onDestroy() {
        ++session
        InjectLog.endSession()
        if (!terminalStateBroadcast) broadcastTerminalState()
        pump.close()
        mainHandler.removeCallbacksAndMessages(null)
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "$packageName:mock-location").apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "虚拟跑步服务",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val result = lastResult
        val statusText = if (currentIsFixed) {
            String.format(Locale.getDefault(), "固定位置 · %.5f, %.5f", currentFixedLat, currentFixedLng)
        } else if (result == null) {
            "正在准备虚拟定位..."
        } else {
            val progressPercent = (result.progress * 100).coerceIn(0f, 100f)
            val lapText = if (currentIsLoop) " · 第${result.lapCount + 1}圈" else ""
            val sceneText = if (currentScenario == InjectScenario.NATURAL) "" else " · ${currentScenario.label}"
            String.format(Locale.getDefault(), "%.1f 分/km · %.0f%%%s%s", currentPace, progressPercent, lapText, sceneText)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (currentIsFixed) "固定定位进行中" else "虚拟跑步进行中")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(result: com.virtualrun.app.algorithm.PositionResult?) {
        lastResult = result
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }
}
