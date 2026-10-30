package ceui.pixiv.imagecodec

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import ceui.lisa.activities.Shaft
import ceui.lisa.core.LeakSafeOkHttpStreamFetcher
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import okhttp3.Call
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Opt-in network-to-cache transcoder. Cache hits never pass through this class. */
class AvifCompressingUrlLoader(
    private val context: Context,
    private val client: Call.Factory,
) : ModelLoader<GlideUrl, InputStream> {
    override fun handles(model: GlideUrl): Boolean = true

    override fun buildLoadData(model: GlideUrl, width: Int, height: Int, options: Options): ModelLoader.LoadData<InputStream> {
        val original = LeakSafeOkHttpStreamFetcher(client, model.toStringUrl(), model.headers)
        val fetcher = if (Shaft.sSettings?.isAvifCacheEnabled == true) {
            AvifCompressingFetcher(context.applicationContext, original)
        } else original
        return ModelLoader.LoadData(model, fetcher)
    }

    class Factory(private val context: Context, private val client: Call.Factory) : ModelLoaderFactory<GlideUrl, InputStream> {
        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<GlideUrl, InputStream> =
            AvifCompressingUrlLoader(context, client)
        override fun teardown() = Unit
    }
}

private class AvifCompressingFetcher(
    private val context: Context,
    private val delegate: LeakSafeOkHttpStreamFetcher,
) : DataFetcher<InputStream> {
    @Volatile private var callback: DataFetcher.DataCallback<in InputStream>? = null
    @Volatile private var cancelled = false
    @Volatile private var delivered: InputStream? = null

    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
        this.callback = callback
        delegate.loadData(priority, object : DataFetcher.DataCallback<InputStream> {
            override fun onDataReady(data: InputStream?) {
                ENCODE_EXECUTOR.execute { transcode(data ?: return@execute) }
            }
            override fun onLoadFailed(e: Exception) {
                if (!cancelled) callback.onLoadFailed(e)
            }
        })
    }

    private fun transcode(network: InputStream) {
        var source: File? = null
        var avif: File? = null
        var bitmap: Bitmap? = null
        var sourceComplete = false
        try {
            val sourceFile = File.createTempFile("shaft-avif-source-", ".img", context.cacheDir)
            source = sourceFile
            sourceFile.outputStream().use { output -> network.use { it.copyTo(output) } }
            sourceComplete = true
            if (cancelled) return
            val avifFile = File.createTempFile("shaft-avif-encoded-", ".avif", context.cacheDir)
            avif = avifFile
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            }
            bitmap = BitmapFactory.decodeFile(source.absolutePath, options)
            val image = bitmap
            var useAvif = false
            if (image != null && image.width >= 64 && image.height >= 64 &&
                image.width.toLong() * image.height <= MAX_PIXELS && hasNormalExif(source) &&
                !isAnimatedImage(source)
            ) {
                val profile = AvifCacheProfile.fromId(Shaft.sSettings.avifCacheProfile)
                try {
                    NativeImageCodec.encodeAvif(image, avifFile, profile.crf, profile.preset)
                    useAvif = !cancelled && avifFile.length() in 1 until sourceFile.length() && NativeImageCodec.canDecode(avifFile)
                    if (useAvif) {
                        val probe = NativeImageCodec.probe(avifFile.readBytes())
                        AvifCacheDiagnostics.success(image.width, image.height, sourceFile.length(), avifFile.length(), profile)
                        Timber.tag(TAG).i("AVIF verified %s %dx%d %d -> %d bytes CRF%d preset%d",
                            probe.mimeType, image.width, image.height, sourceFile.length(), avifFile.length(), profile.crf, profile.preset)
                    } else {
                        AvifCacheDiagnostics.fallback("编码结果更大或未通过 AVIF 完整解码")
                    }
                } catch (error: Throwable) {
                    AvifCacheDiagnostics.fallback(error.javaClass.simpleName)
                    Timber.tag(TAG).w(error, "AVIF cache encode failed; keeping source")
                }
            }
            if (cancelled) return
            val chosen = if (useAvif) avifFile else sourceFile
            val unused = if (useAvif) sourceFile else avifFile
            unused.delete()
            val result = BufferedInputStream(CleaningFileInputStream(chosen, sourceFile, avifFile))
            delivered = result
            callback?.onDataReady(result)
        } catch (error: Throwable) {
            // The network stream has already been buffered, so every failure still has a safe fallback.
            if (!cancelled && sourceComplete && source?.isFile == true && source.length() > 0) {
                try {
                    val fallbackSource = requireNotNull(source)
                    val result = BufferedInputStream(CleaningFileInputStream(fallbackSource, fallbackSource, avif))
                    delivered = result
                    callback?.onDataReady(result)
                } catch (fallbackError: Exception) {
                    callback?.onLoadFailed(fallbackError)
                }
            } else if (!cancelled) {
                callback?.onLoadFailed(if (error is Exception) error else IOException(error))
            }
        } finally {
            bitmap?.recycle()
            if (delivered == null) {
                source?.delete()
                avif?.delete()
            }
        }
    }

    private fun isAnimatedImage(file: File): Boolean {
        val header = file.inputStream().use { input ->
            val bytes = ByteArray(4096)
            val count = input.read(bytes).coerceAtLeast(0)
            bytes.copyOf(count)
        }
        if (header.size >= 6 && header.copyOfRange(0, 6).decodeToString().startsWith("GIF8")) return true
        return listOf("ANIM", "acTL").any { marker ->
            val bytes = marker.encodeToByteArray()
            header.indices.any { index -> index + bytes.size <= header.size && bytes.indices.all { header[index + it] == bytes[it] } }
        }
    }

    private fun hasNormalExif(file: File): Boolean = runCatching {
        ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) ==
            ExifInterface.ORIENTATION_NORMAL
    }.getOrDefault(false)

    override fun cleanup() {
        delegate.cleanup()
        runCatching { delivered?.close() }
        delivered = null
        callback = null
    }

    override fun cancel() {
        cancelled = true
        delegate.cancel()
    }

    override fun getDataClass(): Class<InputStream> = InputStream::class.java
    override fun getDataSource(): DataSource = delegate.dataSource

    companion object {
        private const val TAG = "AvifCache"
        private const val MAX_PIXELS = 32_000_000L
        private val ENCODE_EXECUTOR: Executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "shaft-avif-cache").apply { isDaemon = true }
        }
    }
}

private class CleaningFileInputStream(
    file: File,
    private val first: File?,
    private val second: File?,
) : FileInputStream(file) {
    override fun close() {
        runCatching { super.close() }
        first?.delete()
        second?.delete()
    }
}
