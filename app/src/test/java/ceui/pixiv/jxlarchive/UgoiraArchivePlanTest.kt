package ceui.pixiv.jxlarchive

import ceui.pixiv.ui.bulk.UgoiraFrames
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class UgoiraArchivePlanTest {
    private fun frames(video: Boolean = false, interpolated: Boolean = false) = UgoiraFrames(
        File("/tmp/frames"), listOf(File("/tmp/frames/0.jpg")), listOf(100), interpolated,
        if (video) File.createTempFile("ugoira-plan", ".mp4").apply { writeBytes(byteArrayOf(1)) } else null,
    )

    @Test
    fun lossyReusesCachedVideoOnlyWhenMp4ModeIsEnabled() {
        assertEquals(UgoiraArchiveSource.CACHED_VIDEO, selectUgoiraArchiveSource(true, true, frames(video = true)))
        assertEquals(UgoiraArchiveSource.CACHED_FRAMES, selectUgoiraArchiveSource(true, false, frames(video = true)))
    }

    @Test
    fun losslessDoesNotReuseVideoOrInterpolatedFrames() {
        assertEquals(UgoiraArchiveSource.ORIGINAL, selectUgoiraArchiveSource(false, true, frames(video = true)))
        assertEquals(UgoiraArchiveSource.ORIGINAL, selectUgoiraArchiveSource(false, false, frames(interpolated = true)))
        assertEquals(UgoiraArchiveSource.CACHED_FRAMES, selectUgoiraArchiveSource(false, false, frames()))
    }

    @Test
    fun largerJxlFallsBackToOriginalFramesOnlyWhenEnabled() {
        assertEquals(true, shouldUseOriginalFrames(true, 110, 100))
        assertEquals(false, shouldUseOriginalFrames(false, 110, 100))
        assertEquals(false, shouldUseOriginalFrames(true, 90, 100))
    }

    @Test
    fun noCacheAlwaysUsesOriginalFrames() {
        assertEquals(UgoiraArchiveSource.ORIGINAL, selectUgoiraArchiveSource(true, true, null))
        assertEquals(UgoiraArchiveSource.ORIGINAL, selectUgoiraArchiveSource(false, false, null))
    }
}
