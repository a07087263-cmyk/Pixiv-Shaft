package ceui.pixiv.imagecodec

import java.util.concurrent.atomic.AtomicLong

/** Process-local, user-visible proof of what the AVIF writer actually produced. */
object AvifCacheDiagnostics {
    private val successes = AtomicLong()
    private val fallbacks = AtomicLong()
    private val avifDecodes = AtomicLong()

    @Volatile var last: String = "本次启动还没有 AVIF 转换记录"
        private set

    val successCount: Long get() = successes.get()
    val fallbackCount: Long get() = fallbacks.get()
    val avifDecodeCount: Long get() = avifDecodes.get()

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

    fun summary(): String = "AVIF 成功 $successCount 次，保留原图 $fallbackCount 次；AVIF 解码 $avifDecodeCount 次\n$last"
}
