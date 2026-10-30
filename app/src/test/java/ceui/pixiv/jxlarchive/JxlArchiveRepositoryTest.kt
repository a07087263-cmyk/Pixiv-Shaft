package ceui.pixiv.jxlarchive

import android.app.Application
import org.robolectric.RuntimeEnvironment
import ceui.lisa.activities.Shaft
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class JxlArchiveRepositoryTest {
    @Test
    fun unifiedSnapshotDirectoryIsReturnedByJxlListing() {
        val context = RuntimeEnvironment.getApplication()
        Shaft.sGson = Gson()
        val id = 112233L
        val directory = JxlArchiveRepository.directory(context, id)
        directory.deleteRecursively()
        File(directory, "pages").mkdirs()
        File(directory, "pages/p0.jxl").writeBytes(byteArrayOf(0xff.toByte(), 0x0a))
        JxlArchiveRepository.writeManifest(directory, JxlArchiveManifest(
            illustId = id,
            pageCount = 1,
            effort = 5,
            pages = listOf("pages/p0.jxl"),
            totalSize = 2,
        ))
        assertNotNull(JxlArchiveRepository.read(context, id))
        assertEquals(id, JxlArchiveRepository.list(context).single().manifest.illustId)
        directory.deleteRecursively()
    }
}
