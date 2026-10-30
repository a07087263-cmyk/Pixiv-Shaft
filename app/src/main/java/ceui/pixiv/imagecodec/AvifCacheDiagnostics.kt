package ceui.pixiv.imagecodec

import java.util.concurrent.atomic.AtomicLong

/** Process-local, user-visible proof of what the AVIF writer actually produced. */
object AvifCacheDiagnostics {
    private val successes = AtomicLong()
    private val fallbacks = AtomicLong()
    private val avifDecodes = AtomicLong()
    private val networkAttempts = AtomicLong()
    private val skipped = AtomicLong()

    @Volatile var last: String = "本次启动还没有 AVIF 转换记录"
        private set

    fun success(width: Int, height: Int, sourceBytes: Long, avifBytes: Long, profile: AvifCacheProfile) {
        successes.incrementAndGet()
        last = "已验证 image/avif · ${width}×${height} · ${sourceBytes} B → ${avifBytes} B · " +
            "CRF${profile.crf}/preset${profile.preset}"
    }

    fun fallback(reason: String) {
        fallbacks.incrementAndGet()
        last = "已保留原图 · $reason"
    }

    fun decodedAvif() {
        avifDecodes.incrementAndGet()
    }

    fun networkAttempt() {
        networkAttempts.incrementAndGet()
    }

    fun skipped(reason: String) {
        skipped.incrementAndGet()
        last = "未进入编码 · $reason"
    }

    fun summary(): String =
        "网络进入 $networkAttempts 次，AVIF 成功 $successes 次，保留原图 $fallbacks 次，跳过 $skipped 次；" +
            "AVIF 解码 $avifDecodes 次\n$last"
}
