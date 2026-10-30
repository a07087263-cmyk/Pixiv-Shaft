package ceui.pixiv.jxlarchive

import android.content.Context
import ceui.lisa.activities.Shaft
import ceui.pixiv.snapshot.SnapshotRepository
import java.io.File

const val JXL_ARCHIVE_SCHEMA_VERSION = 1
private const val MANIFEST = "jxl_archive.json"

data class JxlArchiveManifest(
    val schemaVersion: Int = JXL_ARCHIVE_SCHEMA_VERSION,
    val illustId: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val title: String? = null,
    val authorName: String? = null,
    val authorId: Long? = null,
    val type: String = "illust",
    val pageCount: Int,
    val effort: Int,
    val pages: List<String>,
    val totalSize: Long,
    val originalTotalSize: Long = 0L,
    val sourceFormats: List<String> = emptyList(),
)

data class JxlArchiveSummary(
    val manifest: JxlArchiveManifest,
    val directory: File,
) {
    val cover: File get() = File(directory, manifest.pages.first())
    val totalSize: Long get() = manifest.totalSize
}

/** JXL is a codec inside the unified snapshot library, not a third copy of the artwork. */
object JxlArchiveRepository {
    fun snapshotId(illustId: Long): String = "jxl-$illustId"
    fun directory(context: Context, illustId: Long): File = SnapshotRepository.dir(context, snapshotId(illustId))

    fun read(context: Context, illustId: Long): JxlArchiveManifest? {
        val directory = directory(context, illustId)
        val file = File(directory, MANIFEST)
        if (!file.isFile) return null
        return runCatching {
            val manifest = Shaft.sGson.fromJson(file.readText(), JxlArchiveManifest::class.java)
            manifest.takeIf {
                it.schemaVersion == JXL_ARCHIVE_SCHEMA_VERSION && it.illustId == illustId &&
                    it.pageCount == it.pages.size && it.pages.isNotEmpty() &&
                    it.pages.all { page -> File(directory, page).isFile }
            }
        }.getOrNull()
    }

    fun list(context: Context): List<JxlArchiveSummary> = SnapshotRepository.root(context).listFiles()
        ?.filter { it.isDirectory && it.name.startsWith("jxl-") }
        ?.mapNotNull { dir ->
            val id = dir.name.removePrefix("jxl-").toLongOrNull() ?: return@mapNotNull null
            read(context, id)?.let { JxlArchiveSummary(it, dir) }
        }
        ?.sortedByDescending { it.manifest.createdAt }
        ?: emptyList()

    fun delete(context: Context, illustId: Long): Boolean = directory(context, illustId).deleteRecursively()

    internal fun writeManifest(directory: File, manifest: JxlArchiveManifest) {
        File(directory, MANIFEST).writeText(Shaft.sGson.toJson(manifest))
    }
}
