package com.virtualrun.app.algorithm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StaticPositionSourceTest {

    @Test
    fun fixedSourceKeepsSameCoordinateAndZeroSpeed() {
        val source = StaticPositionSource(33.003_600, 112.528_300)

        repeat(5) {
            val result = source.next()
            assertEquals(33.003_600, result.latitude, 0.0)
            assertEquals(112.528_300, result.longitude, 0.0)
            assertEquals(0f, result.speed, 0f)
            assertEquals(0f, result.progress, 0f)
            assertEquals(0, result.lapCount)
            // 固定定位永不完赛，否则服务会自己停掉
            assertFalse(result.isCompleted)
        }
    }
}
