package ceui.pixiv.jxlarchive

import android.content.Context
import ceui.lisa.activities.Shaft
import java.io.File

const val JXL_ARCHIVE_SCHEMA_VERSION = 1
private const val ROOT_DIR = "ShaftJxlArchives"
private const val MANIFEST = "archive.json"

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
)

data class JxlArchiveSummary(
    val manifest: JxlArchiveManifest,
    val directory: File,
) {
    val cover: File get() = File(directory, manifest.pages.first())
    val totalSize: Long get() = manifest.totalSize
}

object JxlArchiveRepository {
    fun root(context: Context): File = File(context.filesDir, ROOT_DIR)
    fun directory(context: Context, illustId: Long): File = File(root(context), illustId.toString())

    fun read(context: Context, illustId: Long): JxlArchiveManifest? {
        val file = File(directory(context, illustId), MANIFEST)
        if (!file.isFile) return null
        return runCatching {
            val manifest = Shaft.sGson.fromJson(file.readText(), JxlArchiveManifest::class.java)
            manifest.takeIf {
                it.schemaVersion == JXL_ARCHIVE_SCHEMA_VERSION && it.illustId == illustId &&
                    it.pageCount == it.pages.size && it.pages.isNotEmpty() &&
                    it.pages.all { page -> File(directory(context, illustId), page).isFile }
            }
        }.getOrNull()
    }

    fun list(context: Context): List<JxlArchiveSummary> = root(context).listFiles()
        ?.filter { it.isDirectory && !it.name.startsWith(".tmp-") }
        ?.mapNotNull { dir ->
            val id = dir.name.toLongOrNull() ?: return@mapNotNull null
            read(context, id)?.let { JxlArchiveSummary(it, dir) }
        }
        ?.sortedByDescending { it.manifest.createdAt }
        ?: emptyList()

    fun delete(context: Context, illustId: Long): Boolean = directory(context, illustId).deleteRecursively()

    internal fun writeManifest(directory: File, manifest: JxlArchiveManifest) {
        File(directory, MANIFEST).writeText(Shaft.sGson.toJson(manifest))
    }
}
