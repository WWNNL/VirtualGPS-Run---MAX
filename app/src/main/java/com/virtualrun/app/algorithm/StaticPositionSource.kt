package com.virtualrun.app.algorithm

/**
 * 固定定位的位置源：坐标永不移动，速度为 0。
 *
 * 与 [TrajectoryInterpolator] 不同，它不生成轨迹、不推进进度，
 * 只把同一个点按采样节拍交出去，用于「人站在这里不动」的场景。
 */
class StaticPositionSource(
    private val latitude: Double,
    private val longitude: Double
) {
    fun next(): PositionResult = PositionResult(
        latitude = latitude,
        longitude = longitude,
        speed = 0f,
        bearing = 0f,
        isCompleted = false,
        progress = 0f,
        lapCount = 0
    )
}
