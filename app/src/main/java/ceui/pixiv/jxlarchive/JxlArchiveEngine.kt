package ceui.pixiv.jxlarchive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import ceui.lisa.activities.Shaft
import ceui.lisa.utils.GlideUrlChild
import ceui.pixiv.api.model.Illust
import ceui.pixiv.imagecodec.NativeImageCodec
import ceui.pixiv.services.appServices
import ceui.pixiv.snapshot.snapshotPageUrl
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

/** Independent original-image archive path. No LRU and no unbookmark deletion. */
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
                archive(app, illust)
                Timber.tag(TAG).i("archive generated, illustId=%d", illust.id)
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Exception) {
                Timber.tag(TAG).w(error, "archive failed, illustId=%d", illust.id)
            }
        }
    }

    suspend fun archive(
        context: Context,
        illust: Illust,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): JxlArchiveManifest = withContext(Dispatchers.IO) {
        if (illust.isGif()) throw JxlArchiveException("JXL 存档暂不支持动图")
        if (!running.add(illust.id)) throw JxlArchiveException("该作品正在生成 JXL 存档")
        try {
            permit.withPermit {
                JxlArchiveRepository.read(context, illust.id)?.let { return@withPermit it }
                val app = context.applicationContext
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
                val root = JxlArchiveRepository.root(app).apply { mkdirs() }
                val temp = File(root, ".tmp-${bean.id}-${UUID.randomUUID()}")
                val pagesDir = File(temp, "pages")
                check(pagesDir.mkdirs()) { "could not create JXL staging directory" }
                try {
                    val pages = mutableListOf<String>()
                    val effort = Shaft.sSettings.jxlArchiveEffort.coerceIn(1, 9)
                    urls.forEachIndexed { index, url ->
                        val relative = "pages/p$index.jxl"
                        convertOriginal(app, url, File(temp, relative), effort)
                        pages += relative
                        onProgress(index + 1, pageCount)
                    }
                    val totalSize = temp.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    val manifest = JxlArchiveManifest(
                        illustId = bean.id,
                        title = bean.title,
                        authorName = bean.user?.name,
                        authorId = bean.user?.id,
                        type = bean.type ?: "illust",
                        pageCount = pageCount,
                        effort = effort,
                        pages = pages,
                        totalSize = totalSize,
                    )
                    JxlArchiveRepository.writeManifest(temp, manifest)
                    val target = JxlArchiveRepository.directory(app, bean.id)
                    JxlArchiveRepository.read(app, bean.id)?.let {
                        temp.deleteRecursively()
                        return@withPermit it
                    }
                    if (target.exists()) target.deleteRecursively() // Replace an incomplete/corrupt older archive.
                    if (!temp.renameTo(target)) throw JxlArchiveException("无法发布 JXL 存档")
                    manifest
                } catch (error: Throwable) {
                    temp.deleteRecursively()
                    throw error
                }
            }
        } finally {
            running.remove(illust.id)
        }
    }

    private fun convertOriginal(context: Context, url: String, output: File, effort: Int) {
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
        } finally {
            bitmap?.recycle()
            source.delete()
        }
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
