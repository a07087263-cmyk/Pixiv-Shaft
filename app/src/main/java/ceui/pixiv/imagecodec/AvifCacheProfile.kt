package ceui.pixiv.imagecodec

/** User-selectable still-image profiles measured with SVT-AV1 4.2.0 on real Pixiv images. */
enum class AvifCacheProfile(val id: Int, val crf: Int, val preset: Int) {
    QUALITY(0, 18, 6),
    BALANCED(1, 18, 8),
    SMALL(2, 24, 10),
    EXTREME(3, 30, 10);

    companion object {
        val DEFAULT = BALANCED
        fun fromId(id: Int): AvifCacheProfile = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
