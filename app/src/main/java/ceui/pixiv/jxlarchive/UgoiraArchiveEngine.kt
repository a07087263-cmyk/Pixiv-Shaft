package ceui.pixiv.jxlarchive

import android.content.Context
import android.system.Os
import ceui.lisa.activities.Shaft
import ceui.pixiv.api.model.Illust
import ceui.pixiv.imagecodec.NativeImageCodec
import ceui.pixiv.snapshot.ArchiveReservation
import ceui.pixiv.snapshot.AUTO_SNAPSHOT_MANIFEST
import ceui.pixiv.snapshot.AutoSnapshotManifest
import ceui.pixiv.snapshot.SNAPSHOT_MANIFEST
import ceui.pixiv.snapshot.SNAPSHOT_STORAGE_JXL
import ceui.pixiv.snapshot.SnapshotManifest
import ceui.pixiv.ui.bulk.UgoiraEngine
import ceui.pixiv.ui.bulk.UgoiraFrames
import ceui.pixiv.ui.bulk.UgoiraVideoEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal enum class UgoiraArchiveSource { CACHED_VIDEO, CACHED_FRAMES, ORIGINAL }

internal fun selectUgoiraArchiveSource(lossy: Boolean, mp4Enabled: Boolean, cached: UgoiraFrames?): UgoiraArchiveSource {
    if (cached == null) return UgoiraArchiveSource.ORIGINAL
    if (lossy && mp4Enabled && cached.video?.isFile == true) return UgoiraArchiveSource.CACHED_VIDEO
    if (lossy || (!cached.interpolated && !mp4Enabled)) return UgoiraArchiveSource.CACHED_FRAMES
    return UgoiraArchiveSource.ORIGINAL
}

/** Animated member of the unified archive library. Lossless JXL and lossy AV1 are explicit modes. */
object UgoiraArchiveEngine {
    suspend fun archive(
        context: Context,
        bean: Illust,
        automatic: Boolean = false,
        onProgress: suspend (Int, Int) -> Unit = { _, _ -> },
    ): JxlArchiveManifest =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val id = bean.id
            if (JxlArchiveRepository.read(app, id) != null) {
                throw JxlArchiveException("该作品已有收藏存档；如需更换格式，请先删除旧存档")
            }
            if (!ArchiveReservation.tryReserve(id)) throw JxlArchiveException("该作品正在创建其他存档")
            val final = JxlArchiveRepository.directory(app, id)
            val temp = File(final.parentFile, ".tmp-ugoira-$id-${java.util.UUID.randomUUID()}")
            check(temp.mkdirs()) { "could not create animated archive staging directory" }
            try {
                val cached = UgoiraEngine.peekPlayableFrames(bean)
                val lossy = Shaft.sSettings.isUgoiraArchiveLossy
                val sourceChoice = selectUgoiraArchiveSource(lossy, Shaft.sSettings.isUgoiraSaveAsMp4(), cached)
                val reusableVideo = cached?.video?.takeIf { sourceChoice == UgoiraArchiveSource.CACHED_VIDEO }
                val relative: String
                val delays: List<Int>
                val sourceBytes: Long
                if (reusableVideo != null) {
                    relative = "animation.mp4"
                    delays = cached!!.delaysMs
                    sourceBytes = cached.files.sumOf { it.length() }
                    val output = File(temp, relative)
                    try {
                        Os.link(reusableVideo.absolutePath, output.absolutePath)
                    } catch (_: Throwable) {
                        reusableVideo.copyTo(output, overwrite = true)
                    }
                    onProgress(1, 1)
                } else {
                    val source = cached?.takeIf { sourceChoice == UgoiraArchiveSource.CACHED_FRAMES }
                        ?: UgoiraEngine.loadOriginalFramesForArchive(bean)
                    delays = source.delaysMs
                    sourceBytes = source.files.sumOf { it.length() }
                    if (lossy) {
                        relative = "animation.mp4"
                        val output = File(temp, relative)
                        val encoded = UgoiraVideoEncoder.encode(output, source.files, source.delaysMs) { }
                            ?: throw JxlArchiveException("AV1/H.264 动图存档压制失败")
                        if (!encoded.isFile || encoded.length() == 0L) throw JxlArchiveException("动图视频存档为空")
                    } else {
                        relative = "animation.jxl"
                        val output = File(temp, relative)
                        NativeImageCodec.encodeJxlAnimation(source.files, source.delaysMs, output, Shaft.sSettings.jxlArchiveEffort)
                        val verifyDir = File(temp, ".verify")
                        val decoded = NativeImageCodec.decodeJxlAnimation(output, verifyDir)
                        if (decoded.files.size != source.files.size || decoded.delaysMs != source.delaysMs.map { it.coerceAtLeast(1) }) {
                            throw JxlArchiveException("JXL 动图全帧或延时校验失败")
                        }
                        verifyDir.deleteRecursively()
                        onProgress(1, 1)
                    }
                }
                File(temp, "illust.json").writeText(Shaft.sGson.toJson(bean))
                File(temp, "assets.json").writeText("{}")
                val archiveFile = File(temp, relative)
                val manifest = JxlArchiveManifest(
                    illustId = id,
                    title = bean.title,
                    authorName = bean.user?.name,
                    authorId = bean.user?.id,
                    type = "ugoira",
                    pageCount = 1,
                    effort = if (lossy) 0 else Shaft.sSettings.jxlArchiveEffort,
                    pages = listOf(relative),
                    totalSize = archiveFile.length(),
                    originalTotalSize = sourceBytes,
                    sourceFormats = listOf("UGOIRA"),
                    animationDelaysMs = delays,
                    isAnimation = true,
                )
                JxlArchiveRepository.writeManifest(temp, manifest)
                val snapshotId = JxlArchiveRepository.snapshotId(id)
                val commonSize = archiveFile.length()
                if (automatic) {
                    File(temp, AUTO_SNAPSHOT_MANIFEST).writeText(Shaft.sGson.toJson(AutoSnapshotManifest(
                        snapshotId = snapshotId, createdAt = manifest.createdAt, illustId = id,
                        type = "ugoira", includeOriginal = !lossy, isBookmarked = bean.isBookmarked,
                        isFollowed = bean.user?.is_followed ?: false, xRestrict = bean.x_restrict,
                        pageCount = 1, title = bean.title, authorName = bean.user?.name,
                        authorId = bean.user?.id, coverPath = relative, fileCount = 3,
                        totalSize = commonSize, storageFormat = SNAPSHOT_STORAGE_JXL,
                    )))
                } else {
                    File(temp, SNAPSHOT_MANIFEST).writeText(Shaft.sGson.toJson(SnapshotManifest(
                        snapshotId = snapshotId, createdAt = manifest.createdAt, illustId = id,
                        type = "ugoira", includeOriginal = !lossy, isBookmarked = bean.isBookmarked,
                        isFollowed = bean.user?.is_followed ?: false, xRestrict = bean.x_restrict,
                        pageCount = 1, title = bean.title, authorName = bean.user?.name,
                        authorId = bean.user?.id, coverPath = relative, fileCount = 3,
                        totalSize = commonSize,
                    )))
                }
                final.deleteRecursively()
                if (!temp.renameTo(final)) throw JxlArchiveException("发布动图存档失败")
                JxlArchiveDiagnostics.success(id, "动图存档成功：${if (lossy) "AV1/MP4" else "JXL"}")
                manifest
            } finally {
                temp.deleteRecursively()
                ArchiveReservation.release(id)
            }
        }
}
