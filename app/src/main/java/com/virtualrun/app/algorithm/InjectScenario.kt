package com.virtualrun.app.algorithm

import com.virtualrun.app.model.Route
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 注入场景预设。每一种都对应检测端的一条规则，用来量化「漏报 / 误报」。
 */
enum class InjectScenario(val label: String, val hint: String) {
    /** 默认：现有的自然轨迹（速度扰动、圆角、每圈内外偏移）。 */
    NATURAL("自然轨迹", "现有算法，最接近真人"),

    /** 沿折线匀速推进，无噪声、无圆角：对照组，专测「过于完美」。 */
    CLEAN("干净匀速", "匀速无扰动，测「过于完美」"),

    /** 匀速但速度拉到 8 m/s（28.8 km/h）：测超速规则。 */
    OVERSPEED("超速", "8 m/s ≈ 28.8 km/h"),

    /** 稳定轨迹里插入一次性大跳点：测速度突变 / 瞬移规则。 */
    TELEPORT("瞬移跳点", "每隔一段时间瞬移约 800 m"),

    /** 每个采样点叠加 ±15 m 随机偏移：测噪声合理性。 */
    JITTER("坐标抖动", "每次采样 ±15 m 抖动"),

    /** 周期性不上报（2s / 8s）：测掉点与时间戳间隙。 */
    DROPOUT("高频掉点", "每 8 秒里断 2 秒"),
}

/**
 * 干净匀速源：沿折线以恒定速度推进。
 *
 * 不做圆角、不加速度扰动、不做 GPS 噪声、不生成圈间偏移，
 * 因此轨迹在几何上「完美」——正是检测器最容易命中的形态，用作对照组。
 */
class LinearRouteSource(
    private val route: Route,
    private val speedMps: Float,
    private val loop: Boolean
) {
    private val points = route.points
    private val total = route.totalDistance.coerceAtLeast(1f)
    private var distance = 0f
    private var lastMs = 0L
    private var started = false
    private var lap = 0

    fun next(nowMs: Long): PositionResult {
        if (started) {
            val dt = ((nowMs - lastMs) / 1000f).coerceIn(0f, 5f)
            distance += speedMps * dt
        } else {
            started = true
        }
        lastMs = nowMs

        var completed = false
        if (loop) {
            while (distance > total) {
                distance -= total
                lap++
            }
        } else if (distance >= total) {
            distance = total
            completed = true
        }

        val (lat, lng, bearing) = interpolate(distance)
        return PositionResult(
            latitude = lat,
            longitude = lng,
            speed = if (completed) 0f else speedMps,
            bearing = bearing,
            isCompleted = completed,
            progress = (distance / total).coerceIn(0f, 1f),
            lapCount = lap
        )
    }

    private fun interpolate(target: Float): Triple<Double, Double, Float> {
        if (points.size < 2) {
            val only = points.firstOrNull()
            return Triple(only?.lat ?: 0.0, only?.lng ?: 0.0, 0f)
        }
        var index = 1
        while (index < points.size - 1 && points[index].distanceFromStart < target) index++
        val start = points[index - 1]
        val end = points[index]
        val span = (end.distanceFromStart - start.distanceFromStart).coerceAtLeast(0.01f)
        val ratio = ((target - start.distanceFromStart) / span).coerceIn(0f, 1f)

        val lat = start.lat + (end.lat - start.lat) * ratio
        val lng = start.lng + (end.lng - start.lng) * ratio
        return Triple(lat, lng, bearingBetween(start.lat, start.lng, end.lat, end.lng))
    }

    private fun bearingBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLng = Math.toRadians(lng2 - lng1)
        val y = sin(dLng) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLng)
        val deg = Math.toDegrees(atan2(y, x)).toFloat()
        return (deg + 360f) % 360f
    }
}
