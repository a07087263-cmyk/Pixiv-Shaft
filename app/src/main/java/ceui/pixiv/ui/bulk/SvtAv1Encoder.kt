package ceui.pixiv.ui.bulk

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import androidx.annotation.Keep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/** One display-order AV1 access unit, already stripped of OBUs that MP4 stores elsewhere. */
@Keep
internal data class Av1Packet(
    val data: ByteArray,
    val presentationTimeUs: Long,
    val isKeyFrame: Boolean,
)

/**
 * JPEG/PNG frame sequence -> SVT-AV1 -> MP4.
 *
 * Unlike the H.264 Surface path this deliberately defines every colour conversion step:
 * sRGB bitmap -> BT.709 limited-range YUV420 -> matching AV1 CICP metadata. LOW_DELAY keeps
 * output in display order, so variable ugoira frame delays survive as exact MP4 sample PTS.
 */
internal object SvtAv1Encoder {
    private const val MIME = "video/av01"
    private const val CRF = 28
    private const val PRESET = 11
    private const val MIN_DIMENSION = 64 // SVT-AV1's public API minimum

    @Volatile
    private var nativeUnavailable = false

    /** AV1 encoding is useful only when this same device can play the cached result. */
    fun isSupported(width: Int = 64, height: Int = 64): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || nativeUnavailable) return false
        val format = MediaFormat.createVideoFormat(MIME, width, height)
        return runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format) != null
        }.getOrDefault(false)
    }

    suspend fun encode(
        target: File,
        files: List<File>,
        delaysMs: List<Int>,
        onProgress: (Int) -> Unit = {},
    ): File? = withContext(Dispatchers.IO) {
        if (nativeUnavailable || files.size < 2 || files.size != delaysMs.size) {
            return@withContext null
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(files[0].absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width < MIN_DIMENSION || height < MIN_DIMENSION || !isSupported(width, height)) {
            return@withContext null
        }

        val totalMs = delaysMs.sum().coerceAtLeast(1)
        // Keep the average rate rational; actual timing comes from each input PTS below.
        val fpsNumerator = files.size * 1000
        val fpsDenominator = totalMs
        val fps = (files.size * 1000.0 / totalMs).roundToInt().coerceIn(1, 120)
        val tmp = File(target.parentFile, target.name + ".tmp")
        runCatching { tmp.delete() }
        target.parentFile?.mkdirs()

        var handle = 0L
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        val t0 = System.currentTimeMillis()
        try {
            ensureNativeLoaded()
            handle = nativeCreate(width, height, fpsNumerator, fpsDenominator, CRF, PRESET)
            check(handle != 0L) { "nativeCreate returned null handle" }
            var track = -1
            var sampleCount = 0
            fun write(packets: Array<Av1Packet>) {
                check(muxerStarted)
                for (packet in packets) {
                    val info = MediaCodec.BufferInfo()
                    info.set(
                        0,
                        packet.data.size,
                        packet.presentationTimeUs,
                        if (packet.isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0,
                    )
                    muxer!!.writeSampleData(track, ByteBuffer.wrap(packet.data), info)
                    sampleCount++
                }
            }

            var reuse: Bitmap? = null
            val opts = BitmapFactory.Options().apply {
                inMutable = true
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            var ptsMs = 0L
            try {
                for ((i, file) in files.withIndex()) {
                    coroutineContext.ensureActive()
                    opts.inBitmap = reuse
                    val bitmap = try {
                        BitmapFactory.decodeFile(file.absolutePath, opts)
                    } catch (_: IllegalArgumentException) {
                        opts.inBitmap = null
                        reuse?.recycle()
                        reuse = null
                        BitmapFactory.decodeFile(file.absolutePath, opts)
                    } ?: error("decode frame failed: $file")
                    reuse = bitmap
                    check(bitmap.width == width && bitmap.height == height) {
                        "frame size mismatch: $file is ${bitmap.width}x${bitmap.height}, expected ${width}x$height"
                    }
                    val packets = nativeEncode(handle, bitmap, ptsMs * 1000L)
                    if (!muxerStarted) {
                        // Use the sequence header from the first actual packet. The optional
                        // initialization-time SVT header can differ in order-hint/timing fields.
                        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
                            setByteBuffer("csd-0", ByteBuffer.wrap(nativeCodecConfig(handle)))
                            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                            setInteger("color-standard", 1) // COLOR_STANDARD_BT709
                            setInteger("color-transfer", 3) // COLOR_TRANSFER_SDR_VIDEO
                            setInteger("color-range", 2) // COLOR_RANGE_LIMITED
                        }
                        val created = MediaMuxer(
                            tmp.absolutePath,
                            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
                        )
                        muxer = created
                        track = created.addTrack(format)
                        created.start()
                        muxerStarted = true
                    }
                    write(packets)
                    ptsMs += delaysMs[i].coerceAtLeast(1)
                    onProgress((i + 1) * 100 / files.size)
                }
            } finally {
                reuse?.recycle()
            }
            write(nativeFinish(handle))
            check(muxerStarted) { "SVT-AV1 produced no MP4 track" }
            check(sampleCount == files.size) {
                "SVT-AV1 emitted $sampleCount frames, expected ${files.size}"
            }

            checkNotNull(muxer).stop()
            checkNotNull(muxer).release()
            muxer = null
            muxerStarted = false
            runCatching { target.delete() }
            check(tmp.renameTo(target)) { "rename AV1 MP4 tmp failed" }
            Timber.tag(UGOIRA_LOG_TAG).i(
                "[mp4][av1] SVT-AV1 CRF%d preset%d %dx%d %d帧 %dKB 耗时%dms",
                CRF, PRESET, width, height, files.size, target.length() / 1024,
                System.currentTimeMillis() - t0,
            )
            target
        } catch (c: CancellationException) {
            runCatching { tmp.delete() }
            throw c
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            Timber.tag(UGOIRA_LOG_TAG).w(t, "[mp4][av1] 压制失败,回退 H.264")
            null
        } finally {
            if (handle != 0L) runCatching { nativeClose(handle) }
            muxer?.let { m ->
                if (muxerStarted) runCatching { m.stop() }
                runCatching { m.release() }
            }
        }
    }

    private fun ensureNativeLoaded() {
        if (nativeUnavailable) throw UnsatisfiedLinkError("SVT-AV1 disabled")
        try {
            System.loadLibrary("shaft_ugoira_av1")
        } catch (t: UnsatisfiedLinkError) {
            nativeUnavailable = true
            throw t
        }
    }

    private external fun nativeCreate(
        width: Int,
        height: Int,
        fpsNumerator: Int,
        fpsDenominator: Int,
        crf: Int,
        preset: Int,
    ): Long

    private external fun nativeCodecConfig(handle: Long): ByteArray
    private external fun nativeEncode(handle: Long, bitmap: Bitmap, presentationTimeUs: Long): Array<Av1Packet>
    private external fun nativeFinish(handle: Long): Array<Av1Packet>
    private external fun nativeClose(handle: Long)
}
