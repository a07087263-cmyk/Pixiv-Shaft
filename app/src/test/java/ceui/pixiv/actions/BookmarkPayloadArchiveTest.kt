package ceui.pixiv.actions

import com.google.gson.Gson
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkPayloadArchiveTest {
    @Test
    fun archiveFlagIsIndependentAndBackwardCompatible() {
        val gson = Gson()
        val legacy = gson.fromJson("""{"id":1,"bookmark":true,"restrict":"public","autoSnapshot":false}""", BookmarkPayload::class.java)
        assertFalse(legacy.jxlArchive)
        val archiveOnly = BookmarkPayload(1, true, "public", autoSnapshot = false, jxlArchive = true)
        assertFalse(archiveOnly.autoSnapshot)
        assertTrue(archiveOnly.jxlArchive)
    }
}
