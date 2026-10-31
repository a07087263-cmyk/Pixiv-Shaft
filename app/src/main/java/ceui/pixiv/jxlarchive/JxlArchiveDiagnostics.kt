package ceui.pixiv.jxlarchive

import android.os.Process
import android.util.Log
import ceui.lisa.activities.Shaft
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Persistent synchronous checkpoints. The final line before a native abort identifies the site. */
object JxlArchiveDiagnostics {
    @Volatile var last: String = "本次启动还没有 JXL 存档结果"
        private set
    private val lock = Any()
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val logFile: File get() = File(Shaft.getContext().filesDir, "jxl_archive_diagnostics.log")

    init {
        runCatching {
            last = Shaft.getContext().getSharedPreferences("jxl_archive_diagnostics", 0)
                .getString("last", last) ?: last
        }
        checkpoint("diagnostics initialized; previous=$last")
    }

    private fun update(value: String) {
        last = value
        runCatching {
            Shaft.getContext().getSharedPreferences("jxl_archive_diagnostics", 0)
                .edit().putString("last", value).apply()
        }
        checkpoint(value)
    }

    fun checkpoint(message: String) {
        val line = buildString {
            append(time.format(Date()))
            append(" pid=").append(Process.myPid())
            append(" thread=").append(Thread.currentThread().name)
            append(" | ").append(message)
            append('\n')
        }
        synchronized(lock) { runCatching { logFile.appendText(line) } }
        Timber.tag("JxlArchive").i("%s", line.trim())
    }

    fun running(illustId: Long, stage: String) = update("作品 $illustId：$stage")
    fun success(illustId: Long) = update("作品 $illustId：JXL 存档已完成并发布")
    fun failure(illustId: Long, error: Throwable) {
        update("作品 $illustId：${error.message ?: error.javaClass.simpleName}")
        checkpoint("FAILURE stack:\n${Log.getStackTraceString(error)}")
    }

    fun recentLog(maxChars: Int = 48_000): String = synchronized(lock) {
        runCatching {
            val file = logFile
            if (!file.isFile) return@synchronized "暂无诊断日志"
            if (file.length() <= maxChars) file.readText()
            else file.inputStream().use { input ->
                input.skip(file.length() - maxChars)
                input.readBytes().decodeToString()
            }
        }.getOrElse { "读取诊断日志失败：${it.message}" }
    }
}
