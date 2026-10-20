package com.virtualrun.app.service

import android.location.Location
import android.os.Build
import android.os.Bundle
import com.virtualrun.app.algorithm.GpsQuality
import com.virtualrun.app.algorithm.PositionResult
import kotlin.math.*
import kotlin.random.Random

/**
 * One immutable, time-stamped observation shared by all output adapters.
 *
 * 采样里的「传感器指标」由 [GpsQuality] 决定：低档会给出十几米的精度、3~8 颗星；
 * 极致档给出 1.2 米、22 颗星，而且所有指标恒定不游走（人为的「过于完美」样本）。
 */
internal class LocationSampleFactory(private val quality: GpsQuality = GpsQuality.HIGH) {
    private var horizontalAccuracyMeters = quality.accuracyTarget
    private var altitudeMeters = 45.0
    private var verticalAccuracyMetersState = quality.accuracyTarget * 1.3f
    private var speedAccuracyMetersPerSecondState = quality.speedAccuracyTarget
    private var bearingAccuracyDegreesState = quality.bearingAccuracyTarget
    private var satelliteCount = (quality.satellitesMin + quality.satellitesMax) / 2

    private data class SensorMetadata(
        val horizontalAccuracyMeters: Float,
        val altitudeMeters: Double,
        val verticalAccuracyMeters: Float,
        val speedAccuracyMetersPerSecond: Float,
        val bearingAccuracyDegrees: Float,
        val satellites: Int
    )

    private var sampleTimeMs = 0L
    private var lastSatelliteChangeMs = 0L
    private var deltaSeconds = 0f

    fun create(result: PositionResult, wallMs: Long, elapsedNanos: Long): Location {
        val nextMs = elapsedNanos / 1_000_000L
        deltaSeconds = if (sampleTimeMs == 0L) 0f else ((nextMs - sampleTimeMs) / 1000f).coerceIn(0f, 5f)
        sampleTimeMs = nextMs
        val metadata = evolveSensorMetadata()
        return buildMockLocation("gps", result, metadata, metadata.horizontalAccuracyMeters, wallMs, elapsedNanos)
    }

    private fun buildMockLocation(
        provider: String,
        result: com.virtualrun.app.algorithm.PositionResult,
        metadata: SensorMetadata,
        providerAccuracy: Float,
        wallClockMillis: Long,
        elapsedRealtimeNanosValue: Long
    ): Location = Location(provider).apply {
        latitude = result.latitude
        longitude = result.longitude
        altitude = metadata.altitudeMeters
        speed = result.speed
        bearing = result.bearing
        accuracy = providerAccuracy
        time = wallClockMillis
        elapsedRealtimeNanos = elapsedRealtimeNanosValue

        extras = Bundle().apply {
            putInt("satellites", metadata.satellites)
            putString("gps_quality", quality.name)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            verticalAccuracyMeters = metadata.verticalAccuracyMeters
            bearingAccuracyDegrees = metadata.bearingAccuracyDegrees
            speedAccuracyMetersPerSecond = metadata.speedAccuracyMetersPerSecond
        }
    }

    /**
     * 真实 GNSS 精度和卫星可见数会缓慢变化，而不是每秒完全相同。
     * 各状态使用均值回归，避免逐点独立随机数造成不自然的闪烁。
     * 极致档把噪声清零、区间压成一个点，指标因此恒定不变。
     */
    private fun evolveSensorMetadata(): SensorMetadata {
        val noiseScale = if (quality.perfect) 0f else 1f
        horizontalAccuracyMeters = evolveValue(
            horizontalAccuracyMeters, quality.accuracyTarget,
            0.10f, 0.14f * noiseScale, quality.accuracyMin, quality.accuracyMax
        )
        altitudeMeters = evolveValue(
            altitudeMeters.toFloat(), 45.0f,
            0.025f, quality.altitudeJitter * noiseScale, 40.0f, 50.0f
        ).toDouble()
        verticalAccuracyMetersState = evolveValue(
            verticalAccuracyMetersState, quality.accuracyTarget * 1.3f,
            0.08f, 0.20f * noiseScale, quality.accuracyTarget, quality.accuracyTarget * 2.6f
        )
        speedAccuracyMetersPerSecondState = evolveValue(
            speedAccuracyMetersPerSecondState,
            quality.speedAccuracyTarget,
            0.12f,
            0.018f * noiseScale,
            quality.speedAccuracyTarget * 0.6f,
            quality.speedAccuracyTarget * 2.0f
        )
        bearingAccuracyDegreesState = evolveValue(
            bearingAccuracyDegreesState,
            quality.bearingAccuracyTarget,
            0.08f,
            0.22f * noiseScale,
            quality.bearingAccuracyTarget * 0.6f,
            quality.bearingAccuracyTarget * 2.5f
        )

        if (!quality.perfect && sampleTimeMs - lastSatelliteChangeMs >= 5000L) {
            lastSatelliteChangeMs = sampleTimeMs
            satelliteCount = (satelliteCount + Random.nextInt(-1, 2))
                .coerceIn(quality.satellitesMin, quality.satellitesMax)
        }

        return SensorMetadata(
            horizontalAccuracyMeters = horizontalAccuracyMeters,
            altitudeMeters = altitudeMeters,
            verticalAccuracyMeters = verticalAccuracyMetersState,
            speedAccuracyMetersPerSecond = speedAccuracyMetersPerSecondState,
            bearingAccuracyDegrees = bearingAccuracyDegreesState,
            satellites = satelliteCount
        )
    }

    private fun evolveValue(
        current: Float,
        target: Float,
        meanReversion: Float,
        noiseScale: Float,
        minimum: Float,
        maximum: Float
    ): Float {
        val retention = (1.0 - meanReversion.toDouble()).pow(deltaSeconds.toDouble())
        val noiseFactor = sqrt((1.0 - retention * retention) / (1.0 - (1.0 - meanReversion) * (1.0 - meanReversion)))
        val next = current + (target - current) * (1.0 - retention).toFloat() + randomGaussian().toFloat() * noiseScale * noiseFactor.toFloat()
        return next.coerceIn(minimum, maximum)
    }

    private fun randomGaussian(): Double {
        val first = Random.nextDouble().coerceAtLeast(1e-12)
        val second = Random.nextDouble()
        return sqrt(-2.0 * ln(first)) * cos(2.0 * Math.PI * second)
    }
}
