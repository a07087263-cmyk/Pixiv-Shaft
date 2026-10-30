package ceui.pixiv.snapshot

import java.util.concurrent.ConcurrentHashMap

/** Cross-codec creation reservation. Existing-archive checks alone cannot close a start-up race. */
object ArchiveReservation {
    private val ids = ConcurrentHashMap.newKeySet<Long>()
    fun tryReserve(illustId: Long): Boolean = ids.add(illustId)
    fun release(illustId: Long) { ids.remove(illustId) }
}
