package com.virtualrun.app.service

import android.content.Context
import android.location.Location
import android.os.Build
import com.virtualrun.app.algorithm.GpsQuality
import com.virtualrun.app.algorithm.InjectScenario
import com.virtualrun.app.algorithm.PositionResult
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 注入真值日志。
 *
 * 每一条交出去的定位都写一行 CSV，带上「场景标签 / 本端自认是否 mock / 是否真的推送了」，
 * 检测端拿它当 ground truth 才能算漏报率和误报率；没有标注的对冲测试只能靠肉眼。
 *
 * 文件落在 App 外部私有目录：Android/data/com.virtualrun.app/files/inject-logs/
 */
internal object InjectLog {
    private const val HEADER =
        "seq,scenario,gps_quality,mode,wall_ms,elapsed_ns,lat,lng,speed_mps,bearing_deg,accuracy_m,altitude_m,satellites,mock_flag,is_completed,pushed\n"

    private val lock = Any()
    private var writer: BufferedWriter? = null
    private var seq = 0L
    private var wrote = 0

    fun dir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val d = File(base, "inject-logs")
        if (!d.exists()) d.mkdirs()
        return d
    }

    /** 开一次新会话，返回日志文件；失败返回 null（不影响定位注入本身）。 */
    fun beginSession(context: Context, scenario: InjectScenario, quality: GpsQuality, mode: String): File? = synchronized(lock) {
        closeLocked()
        return try {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(
                dir(context),
                "inject-$stamp-${scenario.name.lowercase()}-${quality.name.lowercase()}-$mode.csv"
            )
            val w = file.bufferedWriter()
            w.write(HEADER)
            writer = w
            seq = 0
            wrote = 0
            file
        } catch (e: Exception) {
            null
        }
    }

    fun record(
        scenario: InjectScenario,
        quality: GpsQuality,
        mode: String,
        location: Location,
        result: PositionResult,
        pushed: Boolean
    ) = synchronized(lock) {
        val w = writer ?: return
        seq++
        try {
            val mock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) location.isMock else @Suppress("DEPRECATION") location.isFromMockProvider
            w.write(
                listOf(
                    seq,
                    scenario.name,
                    quality.name,
                    mode,
                    location.time,
                    location.elapsedRealtimeNanos,
                    String.format(Locale.US, "%.7f", result.latitude),
                    String.format(Locale.US, "%.7f", result.longitude),
                    String.format(Locale.US, "%.3f", location.speed),
                    String.format(Locale.US, "%.1f", location.bearing),
                    String.format(Locale.US, "%.2f", location.accuracy),
                    String.format(Locale.US, "%.1f", location.altitude),
                    location.extras?.getInt("satellites") ?: -1,
                    mock,
                    result.isCompleted,
                    pushed
                ).joinToString(",")
            )
            w.newLine()
            wrote++
            if (wrote % 25 == 0) w.flush()
        } catch (e: Exception) {
            // 日志写失败不能影响注入
        }
    }

    fun endSession() = synchronized(lock) { closeLocked() }

    /** 已有日志，按时间倒序（最新在前）。 */
    fun listSessions(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(".csv") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    private fun closeLocked() {
        try {
            writer?.flush()
            writer?.close()
        } catch (_: Exception) {
        }
        writer = null
    }
}
