package ceui.pixiv.jxlarchive

/** Last archive outcome, including background bookmark failures that have no toast. */
object JxlArchiveDiagnostics {
    @Volatile var last: String = "本次启动还没有 JXL 存档结果"
        private set

    fun success(illustId: Long) {
        last = "作品 $illustId：JXL 存档已完成并发布"
    }

    fun failure(illustId: Long, error: Throwable) {
        last = "作品 $illustId：${error.message ?: error.javaClass.simpleName}"
    }
}
