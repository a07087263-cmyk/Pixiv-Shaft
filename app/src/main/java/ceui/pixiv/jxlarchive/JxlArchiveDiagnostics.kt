package ceui.pixiv.jxlarchive

import ceui.lisa.activities.Shaft
import timber.log.Timber

/** Last archive outcome, persisted so a native/process failure cannot erase the previous stage. */
object JxlArchiveDiagnostics {
    @Volatile var last: String = "本次启动还没有 JXL 存档结果"
        private set

    init {
        runCatching {
            last = Shaft.getContext().getSharedPreferences("jxl_archive_diagnostics", 0)
                .getString("last", last) ?: last
        }
    }

    private fun update(value: String) {
        last = value
        Timber.tag("JxlArchive").i("%s", value)
        runCatching {
            Shaft.getContext().getSharedPreferences("jxl_archive_diagnostics", 0)
                .edit().putString("last", value).apply()
        }
    }

    fun running(illustId: Long, stage: String) = update("作品 $illustId：$stage")
    fun success(illustId: Long) = update("作品 $illustId：JXL 存档已完成并发布")
    fun failure(illustId: Long, error: Throwable) = update("作品 $illustId：${error.message ?: error.javaClass.simpleName}")
}
