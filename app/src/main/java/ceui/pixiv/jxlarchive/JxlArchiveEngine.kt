package ceui.pixiv.jxlarchive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import ceui.lisa.activities.Shaft
import ceui.lisa.utils.GlideUrlChild
import ceui.pixiv.api.model.Illust
import ceui.pixiv.imagecodec.NativeImageCodec
import ceui.pixiv.services.appServices
import ceui.pixiv.snapshot.AUTO_SNAPSHOT_MANIFEST
import ceui.pixiv.snapshot.AutoSnapshotManifest
import ceui.pixiv.snapshot.AutoSnapshotRepository
import ceui.pixiv.snapshot.SNAPSHOT_ASSETS_JSON
import ceui.pixiv.snapshot.SNAPSHOT_ILLUST_JSON
import ceui.pixiv.snapshot.SNAPSHOT_MANIFEST
import ceui.pixiv.snapshot.SNAPSHOT_STORAGE_JXL
import ceui.pixiv.snapshot.SnapshotAssets
import ceui.pixiv.snapshot.SnapshotManifest
import ceui.pixiv.snapshot.SnapshotRepository
import ceui.pixiv.snapshot.snapshotCoverVariantUrls
import ceui.pixiv.snapshot.snapshotPageUrl
import ceui.pixiv.snapshot.snapshotPageVariantUrls
import ceui.pixiv.utils.fetchFullIllustDetail
import ceui.pixiv.utils.hasTrustedCaption
import ceui.pixiv.utils.isFullDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class JxlArchiveException(message: String) : IOException(message)

/** JXL codec inside the unified snapshot library. No second artwork directory and no LRU. */
object JxlArchiveEngine {
    private const val TAG = "JxlArchive"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO +
        CoroutineExceptionHandler { _, error -> Timber.tag(TAG).e(error, "archive scope crashed") })
    private val permit = Semaphore(1)
    private val running = ConcurrentHashMap.newKeySet<Long>()

    fun onBookmarkConfirmed(illust: Illust) {
        if (!Shaft.sSettings.isJxlArchiveOnBookmark || illust.isGif()) return
        scope.launch {
            try {
                val app = Shaft.getContext()
                if (app.appServices().networkStateManager.networkState.value?.isOnline != true) return@launch
                if (hasRegularSnapshot(app, illust.id)) {
                    JxlArchiveDiagnostics.failure(illust.id, JxlArchiveException("已有普通离线快照，未重复创建"))
                    return@launch
                }
                archive(app, illust, automatic = true)
                JxlArchiveDiagnostics.success(illust.id)
                Timber.tag(TAG).i("archive generated, illustId=%d", illust.id)
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Exception) {
                JxlArchiveDiagnostics.failure(illust.id, error)
                Timber.tag(TAG).w(error, "archive failed, illustId=%d", illust.id)
            }
        }
    }

    suspend fun archive(
        context: Context,
        illust: Illust,
        automatic: Boolean = false,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): JxlArchiveManifest = withContext(Dispatchers.IO) {
        if (illust.isGif()) throw JxlArchiveException("JXL 存档暂不支持动图")
        if (!running.add(illust.id)) throw JxlArchiveException("该作品正在生成 JXL 存档")
        try {
            permit.withPermit {
                val app = context.applicationContext
                JxlArchiveRepository.read(app, illust.id)?.let { return@withPermit it }
                if (hasRegularSnapshot(app, illust.id)) {
                    throw JxlArchiveException("该作品已有离线快照，未重复创建 JXL 存档")
                }
                if (!ceui.pixiv.snapshot.ArchiveReservation.tryReserve(illust.id)) {
                    throw JxlArchiveException("该作品正在创建其他存档")
                }
                try {
                val bean = if (illust.isFullDetail() && illust.hasTrustedCaption()) {
                    illust
                } else {
                    fetchFullIllustDetail(illust.id, false) ?: illust
                }
                val pageCount = bean.page_count.coerceAtLeast(1)
                val urls = (0 until pageCount).map { index ->
                    bean.snapshotPageUrl(index, original = true)
                        ?: throw JxlArchiveException("第 ${index + 1} 页缺少原图地址")
                }
                val root = SnapshotRepository.root(app).apply { mkdirs() }
                val temp = File(root, ".tmp-jxl-${bean.id}-${UUID.randomUUID()}")
                val pagesDir = File(temp, "pages")
                check(pagesDir.mkdirs()) { "could not create JXL staging directory" }
                try {
                    val pages = mutableListOf<String>()
                    val assets = linkedMapOf<String, String>()
                    val sourceFormats = mutableListOf<String>()
                    var originalTotalSize = 0L
                    val effort = Shaft.sSettings.jxlArchiveEffort.coerceIn(1, 9)
                    urls.forEachIndexed { index, url ->
                        val relative = "pages/p$index.jxl"
                        val original = convertOriginal(app, url, File(temp, relative), effort)
                        sourceFormats += original.format
                        originalTotalSize += original.bytes
                        pages += relative
                        bean.snapshotPageVariantUrls(index).forEach { variant -> assets[variant] = relative }
                        assets[url] = relative
                        onProgress(index + 1, pageCount)
                    }
                    if (pageCount > 1) {
                        bean.snapshotCoverVariantUrls().forEach { variant -> assets[variant] = pages.first() }
                    }
                    File(temp, SNAPSHOT_ILLUST_JSON).writeText(Shaft.sGson.toJson(bean))
                    File(temp, SNAPSHOT_ASSETS_JSON).writeText(Shaft.sGson.toJson(SnapshotAssets(assets)))

                    val totalSize = temp.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    val fileCount = temp.walkTopDown().count { it.isFile }
                    val createdAt = System.currentTimeMillis()
                    val manifest = JxlArchiveManifest(
                        illustId = bean.id,
                        createdAt = createdAt,
                        title = bean.title,
                        authorName = bean.user?.name,
                        authorId = bean.user?.id,
                        type = bean.type ?: "illust",
                        pageCount = pageCount,
                        effort = effort,
                        pages = pages,
                        totalSize = totalSize,
                        originalTotalSize = originalTotalSize,
                        sourceFormats = sourceFormats,
                    )
                    JxlArchiveRepository.writeManifest(temp, manifest)
                    val snapshotId = JxlArchiveRepository.snapshotId(bean.id)
                    if (automatic) {
                        File(temp, AUTO_SNAPSHOT_MANIFEST).writeText(Shaft.sGson.toJson(
                            AutoSnapshotManifest(
                                snapshotId = snapshotId,
                                createdAt = createdAt,
                                illustId = bean.id,
                                type = bean.type ?: "illust",
                                includeOriginal = true,
                                isBookmarked = bean.isBookmarked,
                                isFollowed = bean.user?.is_followed ?: false,
                                xRestrict = bean.x_restrict,
                                pageCount = pageCount,
                                title = bean.title,
                                authorName = bean.user?.name,
                                authorId = bean.user?.id,
                                coverPath = pages.first(),
                                fileCount = fileCount,
                                totalSize = totalSize,
                                storageFormat = SNAPSHOT_STORAGE_JXL,
                            )
                        ))
                    } else {
                        File(temp, SNAPSHOT_MANIFEST).writeText(Shaft.sGson.toJson(
                            SnapshotManifest(
                                snapshotId = snapshotId,
                                createdAt = createdAt,
                                illustId = bean.id,
                                type = bean.type ?: "illust",
                                includeOriginal = true,
                                isBookmarked = bean.isBookmarked,
                                isFollowed = bean.user?.is_followed ?: false,
                                xRestrict = bean.x_restrict,
                                pageCount = pageCount,
                                title = bean.title,
                                authorName = bean.user?.name,
                                authorId = bean.user?.id,
                                coverPath = pages.first(),
                                fileCount = fileCount,
                                totalSize = totalSize,
                                storageFormat = SNAPSHOT_STORAGE_JXL,
                            )
                        ))
                    }
                    val target = JxlArchiveRepository.directory(app, bean.id)
                    JxlArchiveRepository.read(app, bean.id)?.let {
                        temp.deleteRecursively()
                        return@withPermit it
                    }
                    if (target.exists()) target.deleteRecursively()
                    if (!temp.renameTo(target)) throw JxlArchiveException("无法发布 JXL 存档")
                    manifest
                } catch (error: Throwable) {
                    temp.deleteRecursively()
                    throw error
                }
                } finally {
                    ceui.pixiv.snapshot.ArchiveReservation.release(illust.id)
                }
            }
        } finally {
            running.remove(illust.id)
        }
    }

    private fun hasRegularSnapshot(context: Context, illustId: Long): Boolean =
        SnapshotRepository.list(context).any { it.manifest.illustId == illustId } ||
            AutoSnapshotRepository.listAuto(context).any { it.manifest.illustId == illustId }

    private data class OriginalInfo(val format: String, val bytes: Long)

    private fun convertOriginal(context: Context, url: String, output: File, effort: Int): OriginalInfo {
        val source = File.createTempFile("shaft-jxl-original-", ".img", context.cacheDir)
        var bitmap: Bitmap? = null
        try {
            download(context, url, source)
            val bytes = source.readBytes()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                bounds.outWidth.toLong() * bounds.outHeight > MAX_ARCHIVE_PIXELS
            ) {
                throw JxlArchiveException("图片尺寸超过当前无损存档内存上限")
            }
            if (bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()) {
                NativeImageCodec.encodeJpegAsJxl(bytes, output, effort)
            } else {
                if (isPngWithMoreThan8Bits(bytes)) {
                    throw JxlArchiveException("暂不支持 16 位 PNG；未创建降精度存档")
                }
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inScaled = false
                }
                bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    ?: throw JxlArchiveException("无法解码原始图片")
                NativeImageCodec.encodeJxl(bitmap, output, effort)
            }
            bitmap?.recycle()
            bitmap = null
            if (!NativeImageCodec.canDecode(output)) throw JxlArchiveException("JXL 完整解码校验失败")
            return OriginalInfo(sourceFormat(bytes), bytes.size.toLong())
        } finally {
            bitmap?.recycle()
            source.delete()
        }
    }

    private fun sourceFormat(bytes: ByteArray): String = when {
        bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() -> "JPEG"
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "PNG"
        bytes.size >= 12 && bytes.copyOfRange(0, 4).decodeToString() == "RIFF" &&
            bytes.copyOfRange(8, 12).decodeToString() == "WEBP" -> "WEBP"
        else -> "OTHER"
    }

    private const val MAX_ARCHIVE_PIXELS = 32_000_000L

    private fun isPngWithMoreThan8Bits(bytes: ByteArray): Boolean {
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        return bytes.size > 25 && signature.indices.all { bytes[it] == signature[it] } && bytes[24] > 8
    }

    private fun download(context: Context, url: String, target: File) {
        val glideUrl = GlideUrlChild(url)
        val request = Request.Builder().url(glideUrl.toStringUrl()).apply {
            glideUrl.headers.forEach { (key, value) -> addHeader(key, value) }
        }.build()
        (context as Shaft).okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw JxlArchiveException("原图下载失败：HTTP ${response.code}")
            val body = response.body ?: throw JxlArchiveException("原图下载返回空内容")
            body.byteStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        if (target.length() <= 0) throw JxlArchiveException("原图下载返回空文件")
    }
}
