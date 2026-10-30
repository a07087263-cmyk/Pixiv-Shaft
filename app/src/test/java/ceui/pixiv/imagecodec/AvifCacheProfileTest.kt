package ceui.pixiv.imagecodec

import org.junit.Assert.assertEquals
import org.junit.Test

class AvifCacheProfileTest {
    @Test
    fun measuredDefaultsStayStable() {
        assertEquals(AvifCacheProfile.BALANCED, AvifCacheProfile.DEFAULT)
        assertEquals(18, AvifCacheProfile.DEFAULT.crf)
        assertEquals(8, AvifCacheProfile.DEFAULT.preset)
        assertEquals(AvifCacheProfile.DEFAULT, AvifCacheProfile.fromId(-1))
        assertEquals(AvifCacheProfile.DEFAULT, AvifCacheProfile.fromId(99))
        assertEquals(AvifCacheProfile.EXTREME, AvifCacheProfile.fromId(3))
    }
}
