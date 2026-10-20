package com.virtualrun.app.algorithm

/**
 * GPS 信号质量档位。
 *
 * 作用：控制注入样本里那些「看起来像什么级别的接收机」的指标——
 * 水平精度、卫星数、海拔抖动、速度/方位精度，以及指标是否游走。
 *
 * 注意能力边界：Android 的 mock API 只能改 Location 对象里的字段（accuracy、extras 里的卫星数等），
 * 改不了真正的 [android.location.GnssStatus] / GnssMeasurements（那来自 GNSS 芯片与 HAL）。
 * 所以「档位」只是让样本在数值上像高端机，并不会凭空长出卫星观测。
 * 对测试台来说这正好用：用来验证检测端的「指标完美反而不对劲」这类规则。
 */
enum class GpsQuality(
    val label: String,
    /** 水平精度目标值（米），越低越好 */
    val accuracyTarget: Float,
    val accuracyMin: Float,
    val accuracyMax: Float,
    val satellitesMin: Int,
    val satellitesMax: Int,
    /** 海拔随机游走的噪声尺度，0 = 海拔恒定 */
    val altitudeJitter: Float,
    val speedAccuracyTarget: Float,
    val bearingAccuracyTarget: Float,
    /** true = 所有指标恒定不游走，人为制造「过于完美」样本 */
    val perfect: Boolean = false
) {
    LOW("低", 14f, 6f, 30f, 3, 8, 0.45f, 0.9f, 14f),
    MEDIUM("中", 6f, 3f, 12f, 6, 12, 0.20f, 0.40f, 6f),
    HIGH("高", 2.6f, 1.6f, 5.5f, 8, 16, 0.16f, 0.28f, 3.4f),
    PRIME("极致", 1.2f, 1.2f, 1.2f, 22, 22, 0f, 0.08f, 0.8f, perfect = true);

    /** 该档位是否声称「载波相位级」的完美指标（检测端最该怀疑的对象）。 */
    val looksTooPerfect: Boolean get() = perfect
}
