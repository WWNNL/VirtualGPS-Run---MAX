package com.virtualrun.app.algorithm

import com.virtualrun.app.model.Route
import com.virtualrun.app.model.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinearRouteSourceTest {

    // 手工构造带累积距离的 Route，避免单元测试里调用 android.location.Location
    private fun straightRoute(lengthMeters: Float = 1000f) = Route(
        points = listOf(
            RoutePoint(lat = 33.0000, lng = 112.5000, distanceFromStart = 0f),
            RoutePoint(lat = 33.0000, lng = 112.5100, distanceFromStart = lengthMeters)
        ),
        totalDistance = lengthMeters,
        totalDuration = 0L
    )

    /**
     * 按 2 秒一步推进 seconds 秒。
     * 采样间隔必须小于源码里的 5 秒 dt 上限，否则会被当成时钟跳变钳掉。
     */
    private fun run(source: LinearRouteSource, seconds: Int, stepMs: Long = 2_000L): PositionResult {
        var last = source.next(0L)
        var elapsed = 0L
        repeat((seconds * 1000L / stepMs).toInt()) {
            elapsed += stepMs
            last = source.next(elapsed)
        }
        return last
    }

    @Test
    fun startsAtTargetSpeedAndAdvancesLinearly() {
        val source = LinearRouteSource(straightRoute(), speedMps = 2f, loop = false)

        val start = source.next(0L)
        // 匀速源没有加速过程，第一个采样就是目标速度
        assertEquals(2f, start.speed, 0.001f)
        assertEquals(0f, start.progress, 0.001f)
        assertFalse(start.isCompleted)

        val source2 = LinearRouteSource(straightRoute(), speedMps = 2f, loop = false)
        val after50s = run(source2, 50)   // 50 s × 2 m/s = 100 m ≈ 全程 10%
        assertEquals(0.10f, after50s.progress, 0.01f)
        assertEquals(2f, after50s.speed, 0.001f)
        assertEquals(112.501, after50s.longitude, 0.001)
    }

    @Test
    fun overspeedScenarioRunsAtEightMetersPerSecond() {
        val source = LinearRouteSource(straightRoute(), speedMps = 8f, loop = false)
        val result = run(source, 10)   // 10 s × 8 m/s = 80 m
        assertEquals(8f, result.speed, 0.001f)
        assertEquals(0.08f, result.progress, 0.005f)
    }

    @Test
    fun openRouteCompletesAtTheEnd() {
        val source = LinearRouteSource(straightRoute(100f), speedMps = 5f, loop = false)
        val done = run(source, 60)
        assertTrue(done.isCompleted)
        assertEquals(0f, done.speed, 0f)
        assertEquals(1f, done.progress, 0.001f)
    }

    @Test
    fun loopRouteWrapsInsteadOfCompleting() {
        val source = LinearRouteSource(straightRoute(100f), speedMps = 5f, loop = true)
        val lap2 = run(source, 30)   // 150 m → 绕一圈后余 50 m
        assertFalse(lap2.isCompleted)
        assertEquals(1, lap2.lapCount)
        assertEquals(0.5f, lap2.progress, 0.05f)
    }
}
