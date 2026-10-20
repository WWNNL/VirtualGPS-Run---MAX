package com.virtualrun.app.algorithm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsQualityTest {

    @Test
    fun higherQualityHasTighterAccuracyAndMoreSatellites() {
        val order = listOf(GpsQuality.LOW, GpsQuality.MEDIUM, GpsQuality.HIGH, GpsQuality.PRIME)
        order.zipWithNext().forEach { (worse, better) ->
            assertTrue(
                "${better.label} 的精度应优于 ${worse.label}",
                better.accuracyTarget < worse.accuracyTarget
            )
            assertTrue(
                "${better.label} 的卫星数应不少于 ${worse.label}",
                better.satellitesMin >= worse.satellitesMin
            )
        }
    }

    @Test
    fun onlyPrimeProfileIsFlaggedTooPerfect() {
        GpsQuality.entries.filter { it != GpsQuality.PRIME }.forEach {
            assertFalse("${it.label} 不应被标记为过于完美", it.looksTooPerfect)
            assertTrue(it.accuracyMin < it.accuracyMax)
            assertTrue(it.satellitesMin < it.satellitesMax)
        }
        assertTrue(GpsQuality.PRIME.looksTooPerfect)
        // 极致档指标恒定：精度区间退化成一个点
        assertTrue(GpsQuality.PRIME.accuracyMin == GpsQuality.PRIME.accuracyMax)
    }
}
