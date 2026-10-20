package com.virtualrun.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.provider.ProviderProperties
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.virtualrun.app.algorithm.GpsQuality
import com.virtualrun.app.algorithm.InjectScenario
import com.virtualrun.app.algorithm.LinearRouteSource
import com.virtualrun.app.algorithm.PositionResult
import com.virtualrun.app.algorithm.StaticPositionSource
import com.virtualrun.app.algorithm.TrajectoryInterpolator
import com.virtualrun.app.model.Route
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.cos
import kotlin.random.Random

/**
 * Single owner of trajectory state and the GPS/Network delivery clock.
 * UI work is a one-way callback; it never drives this clock.
 */
internal class MockLocationPump(context: Context, private val intervalMs: Long = 200L) {
    init { require(intervalMs in 100L..1000L) }
    private val thread = HandlerThread("mock-location-pump", Process.THREAD_PRIORITY_FOREGROUND).apply { start() }
    private val handler = Handler(thread.looper)
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val gps = PlatformLocationAdapter(manager, LocationManager.GPS_PROVIDER)
    private val network = PlatformLocationAdapter(manager, LocationManager.NETWORK_PROVIDER)
    private val platformFused = PlatformFusedAdapter(manager)
    private val flp = GoogleFlpAdapter(context)
    private val generation = AtomicLong()
    private var trajectory: TrajectoryInterpolator? = null
    private var staticSource: StaticPositionSource? = null
    private var linearSource: LinearRouteSource? = null
    private var scenario: InjectScenario = InjectScenario.NATURAL
    private var quality: GpsQuality = GpsQuality.HIGH
    private var modeLabel: String = "route"
    private var tickIndex = 0L
    private var listener: ((PositionResult) -> Unit)? = null
    private var samples = LocationSampleFactory()
    private var nextDeadline = 0L
    private var lastTickNanos = 0L
    private var count = 0L
    private var activeGeneration = 0L

    fun start(
        route: Route,
        pace: Float,
        loop: Boolean,
        scenario: InjectScenario = InjectScenario.NATURAL,
        quality: GpsQuality = GpsQuality.HIGH,
        onPosition: (PositionResult) -> Unit
    ) {
        val id = generation.incrementAndGet()
        handler.post {
            handler.removeCallbacks(tick)
            if (id != generation.get()) return@post
            activeGeneration = id
            this.scenario = scenario
            this.quality = quality
            modeLabel = "route"
            tickIndex = 0
            // 干净匀速 / 超速走 LinearRouteSource：无噪声、无圆角，作为「过于完美」的对照组
            when (scenario) {
                InjectScenario.CLEAN -> {
                    trajectory = null
                    linearSource = LinearRouteSource(route, paceSpeedMps(pace), loop)
                }
                InjectScenario.OVERSPEED -> {
                    trajectory = null
                    linearSource = LinearRouteSource(route, 8f, loop)
                }
                else -> {
                    trajectory = TrajectoryInterpolator(route, pace, loop)
                    linearSource = null
                }
            }
            staticSource = null
            listener = onPosition
            samples = LocationSampleFactory(quality)
            count = 0
            lastTickNanos = 0
            platformFused.start()
            flp.start()
            nextDeadline = SystemClock.uptimeMillis()
            Log.i(TAG, "start session=$id intervalMs=$intervalMs priority=FOREGROUND")
            tick.run()
        }
    }

    /** 固定定位：只投递同一个坐标，速度恒为 0。 */
    fun startStatic(
        latitude: Double,
        longitude: Double,
        quality: GpsQuality = GpsQuality.HIGH,
        onPosition: (PositionResult) -> Unit
    ) {
        val id = generation.incrementAndGet()
        handler.post {
            handler.removeCallbacks(tick)
            if (id != generation.get()) return@post
            activeGeneration = id
            trajectory = null
            linearSource = null
            scenario = InjectScenario.NATURAL
            this.quality = quality
            modeLabel = "fixed"
            tickIndex = 0
            staticSource = StaticPositionSource(latitude, longitude)
            listener = onPosition
            samples = LocationSampleFactory(quality)
            count = 0
            lastTickNanos = 0
            platformFused.start()
            flp.start()
            nextDeadline = SystemClock.uptimeMillis()
            Log.i(TAG, "start static session=$id intervalMs=$intervalMs lat=$latitude lon=$longitude")
            tick.run()
        }
    }

    fun updatePace(pace: Float) {
        val id = generation.get()
        handler.post { if (id == activeGeneration) trajectory?.updatePace(pace) }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (activeGeneration != generation.get()) return
            if (trajectory == null && staticSource == null && linearSource == null) return
            try {
                val nanos = SystemClock.elapsedRealtimeNanos()
                val nowMs = nanos / 1_000_000L
                val raw = staticSource?.next()
                    ?: linearSource?.next(nowMs)
                    ?: trajectory?.calculateNextPosition(nowMs)
                    ?: return
                tickIndex++
                val (result, pushed) = applyScenario(raw, nanos)
                val location = samples.create(result, System.currentTimeMillis(), nanos)
                val gap = if (lastTickNanos == 0L) 0.0 else (nanos - lastTickNanos) / 1e6
                lastTickNanos = nanos
                count++
                // 真值日志：每条都记，包含「本次是否真的推送了」
                InjectLog.record(scenario, quality, modeLabel, location, result, pushed)
                if (activeGeneration != generation.get()) return
                if (pushed) {
                    gps.push(location)
                    if (activeGeneration != generation.get()) return
                    network.push(location)
                    platformFused.offer(location)
                    flp.offer(location)
                }
                if (count % 25L == 0L || gap > intervalMs * 2) {
                    Log.i(TAG, "tick=$count intervalMs=$gap gps=${gps.pushCount} network=${network.pushCount}")
                }
                listener?.invoke(result)
                if (result.isCompleted) return
            } catch (error: Exception) {
                // An output failure must not terminate the run or its clock.
                Log.e(TAG, "tick failure; continuing", error)
            }
            nextDeadline += intervalMs
            val now = SystemClock.uptimeMillis()
            if (nextDeadline <= now) nextDeadline += ((now - nextDeadline) / intervalMs + 1) * intervalMs
            if (activeGeneration == generation.get()) handler.postAtTime(this, nextDeadline)
        }
    }

    /** 场景变换：把基础轨迹改造成检测端要的各种「可疑形态」。 */
    private fun applyScenario(raw: PositionResult, nanos: Long): Pair<PositionResult, Boolean> {
        var result = raw
        var pushed = true
        when (scenario) {
            InjectScenario.TELEPORT -> {
                // 每 20 秒（100 个采样）里挑一次插入约 800 米的大跳点
                if (tickIndex % 100L == 50L) {
                    result = raw.copy(
                        latitude = raw.latitude + 0.0072,
                        longitude = raw.longitude + 0.0072,
                        speed = 12f
                    )
                }
            }
            InjectScenario.JITTER -> {
                val meters = 15.0
                val dLat = (Random.nextDouble() - 0.5) * 2 * (meters / 111_320.0)
                val dLng = (Random.nextDouble() - 0.5) * 2 *
                    (meters / (111_320.0 * cos(Math.toRadians(raw.latitude)).coerceAtLeast(0.2)))
                result = raw.copy(latitude = raw.latitude + dLat, longitude = raw.longitude + dLng)
            }
            InjectScenario.DROPOUT -> {
                // 每 8 秒里的前 2 秒不上报，制造时间戳空隙
                val phase = (nanos / 1_000_000_000.0) % 8.0
                if (phase < 2.0) pushed = false
            }
            else -> Unit
        }
        return result to pushed
    }

    private fun paceSpeedMps(paceMinPerKm: Float): Float {
        val pace = paceMinPerKm.coerceIn(3f, 15f)
        return 1000f / (pace * 60f)
    }

    fun stop() {
        generation.incrementAndGet() // Prevent the next send immediately, without waiting for the UI/looper.
        platformFused.stop()
        flp.stop()
        handler.post {
            handler.removeCallbacks(tick)
            trajectory = null
            staticSource = null
            listener = null
            gps.close()
            network.close()
            Log.i(TAG, "stopped gps=${gps.pushCount} network=${network.pushCount}")
        }
    }

    fun close() {
        stop()
        handler.post { thread.quitSafely() }
        platformFused.close()
        flp.close()
    }

    private companion object { const val TAG = "MockLocationPump" }
}

/** Primary adapters are independent: recovery failure in one never skips the other. */
@SuppressLint("MissingPermission", "InlinedApi")
internal class PlatformLocationAdapter(private val manager: LocationManager, private val provider: String) {
    private val lease = ProviderLease.slots.computeIfAbsent(provider) { ProviderLease() }
    private var initialized = false
    private var owned = false
    private var retryAt = 0L
    private val backoff = DeliveryBackoff()
    var pushCount = 0L
        private set
    private var lastNanos = 0L

    fun push(sample: Location): Unit = synchronized(lease) {
        val now = SystemClock.elapsedRealtime()
        if (now < retryAt) return
        try {
            if (!initialized || !manager.isProviderEnabled(provider)) recover()
            val location = Location(sample).apply {
                this.provider = this@PlatformLocationAdapter.provider
                accuracy = when (provider) {
                    LocationManager.NETWORK_PROVIDER -> (sample.accuracy + 2.2f).coerceAtMost(8f)
                    "fused" -> (sample.accuracy + 0.35f).coerceAtMost(6f)
                    else -> sample.accuracy
                }
            }
            manager.setTestProviderLocation(provider, location)
            pushCount++
            lastNanos = DeliveryLog.success(provider, pushCount, location, lastNanos)
            backoff.reset()
        } catch (error: Exception) {
            initialized = false
            retryAt = now + backoff.nextDelayMs()
            Log.e("MockLocationPump", "provider=$provider push failed; recoverAt=$retryAt", error)
        }
    }

    @Suppress("DEPRECATION")
    private fun recover() {
        lease.owner = this
        Log.i("MockLocationPump", "provider=$provider initialize/recover")
        try { manager.removeTestProvider(provider) } catch (error: IllegalArgumentException) {
            Log.d("MockLocationPump", "provider=$provider no previous test provider")
        }
        manager.addTestProvider(
            provider, provider == LocationManager.NETWORK_PROVIDER,
            provider == LocationManager.GPS_PROVIDER, provider == LocationManager.NETWORK_PROVIDER,
            false, true, true, true,
            if (provider == LocationManager.GPS_PROVIDER) ProviderProperties.POWER_USAGE_HIGH else ProviderProperties.POWER_USAGE_LOW,
            ProviderProperties.ACCURACY_FINE
        )
        owned = true
        manager.setTestProviderEnabled(provider, true)
        initialized = true
        Log.i("MockLocationPump", "provider=$provider ready")
    }

    fun close(): Unit = synchronized(lease) {
        if (lease.owner !== this) return@synchronized
        if (owned) try {
            manager.removeTestProvider(provider)
        } catch (error: Exception) {
            Log.e("MockLocationPump", "provider=$provider cleanup failed", error)
        }
        owned = false
        initialized = false
        retryAt = 0
        backoff.reset()
        lease.owner = null
    }
}

/** A destroyed service cannot remove providers already claimed by its replacement. */
internal class ProviderLease {
    var owner: PlatformLocationAdapter? = null
    companion object {
        val slots = java.util.concurrent.ConcurrentHashMap<String, ProviderLease>()
    }
}

internal object DeliveryLog {
    fun success(channel: String, count: Long, location: Location, previousNanos: Long): Long {
        val sendNanos = SystemClock.elapsedRealtimeNanos()
        val gap = if (previousNanos == 0L) 0.0 else (sendNanos - previousNanos) / 1e6
        Log.d("MockLocationPush", "channel=$channel count=$count intervalMs=$gap lat=${location.latitude} lon=${location.longitude} speed=${location.speed} bearing=${location.bearing} accuracy=${location.accuracy} altitude=${location.altitude} satellites=${location.extras?.getInt("satellites")} time=${location.time} elapsedRealtimeNanos=${location.elapsedRealtimeNanos} sendElapsedNanos=$sendNanos")
        return sendNanos
    }
}
