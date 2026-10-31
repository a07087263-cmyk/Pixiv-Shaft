package ceui.pixiv.imagecodec

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Native AVIF/JXL entry points. Files are always identified by their signature, never by suffix. */
data class DecodedJxlAnimation(val files: List<File>, val delaysMs: List<Int>)

object NativeImageCodec {
    const val FORMAT_AVIF = 1
    const val FORMAT_JXL = 2

    init {
        System.loadLibrary("shaft_image_codecs")
    }

    @JvmStatic
    private external fun nativeEncodeAvif(bitmap: Bitmap, output: String, crf: Int, preset: Int): Boolean

    @JvmStatic
    private external fun nativeEncodeJxlBitmap(bitmap: Bitmap, output: String, effort: Int): Boolean

    @JvmStatic
    private external fun nativeEncodeJxlJpeg(input: ByteArray, output: String, effort: Int): Boolean

    @JvmStatic
    private external fun nativeProbe(input: ByteArray): IntArray

    @JvmStatic
    private external fun nativeDecode(input: ByteArray, sampleSize: Int): Bitmap

    @JvmStatic
    private external fun nativeCanDecodeFile(path: String): Boolean

    @JvmStatic
    private external fun nativeReconstructJpeg(path: String): ByteArray

    @JvmStatic
    private external fun nativeCreateJxlAnimation(width: Int, height: Int, effort: Int): Long

    @JvmStatic
    private external fun nativeAddJxlAnimationFrame(handle: Long, bitmap: Bitmap, delayMs: Int, last: Boolean): Boolean

    @JvmStatic
    private external fun nativeFinishJxlAnimation(handle: Long, path: String)

    @JvmStatic
    private external fun nativeAbortJxlAnimation(handle: Long)

    @JvmStatic
    private external fun nativeDecodeJxlAnimation(path: String, outputDir: String): IntArray

    @JvmStatic
    private fun writeBitmapAsPng(bitmap: Bitmap, path: String): Boolean = runCatching {
        FileOutputStream(path).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }.getOrDefault(false)

    fun encodeAvif(bitmap: Bitmap, output: File, crf: Int, preset: Int) {
        check(nativeEncodeAvif(bitmap, output.absolutePath, crf, preset)) { "AVIF encoder returned false" }
    }

    fun encodeJxl(bitmap: Bitmap, output: File, effort: Int) {
        check(nativeEncodeJxlBitmap(bitmap, output.absolutePath, effort)) { "JXL encoder returned false" }
    }

    fun encodeJpegAsJxl(input: ByteArray, output: File, effort: Int) {
        check(nativeEncodeJxlJpeg(input, output.absolutePath, effort)) { "JXL encoder returned false" }
    }

    fun probe(input: ByteArray): Probe {
        val values = nativeProbe(input)
        return Probe(values[0], values[1], values[2])
    }

    fun decode(input: ByteArray, sampleSize: Int = 1): Bitmap = nativeDecode(input, sampleSize)

    fun canDecode(file: File): Boolean = file.isFile && file.length() > 0 && nativeCanDecodeFile(file.absolutePath)

    fun reconstructJpeg(file: File): ByteArray = nativeReconstructJpeg(file.absolutePath)

    fun encodeJxlAnimation(files: List<File>, delaysMs: List<Int>, output: File, effort: Int) {
        require(files.isNotEmpty() && files.size == delaysMs.size) { "animation frame/delay count mismatch" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(files.first().absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "could not read animation dimensions" }
        val handle = nativeCreateJxlAnimation(bounds.outWidth, bounds.outHeight, effort.coerceIn(1, 9))
        check(handle != 0L) { "could not create animated JXL encoder" }
        var finished = false
        try {
            files.forEachIndexed { index, file ->
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    ?: throw IOException("could not decode animation frame $index")
                try {
                    if (bitmap.width != bounds.outWidth || bitmap.height != bounds.outHeight) {
                        throw IOException("animation frame dimensions differ at $index")
                    }
                    if (!nativeAddJxlAnimationFrame(handle, bitmap, delaysMs[index].coerceAtLeast(1), index == files.lastIndex)) {
                        throw IOException("could not add animation frame $index")
                    }
                } finally {
                    bitmap.recycle()
                }
            }
            nativeFinishJxlAnimation(handle, output.absolutePath)
            finished = true
        } finally {
            if (!finished) nativeAbortJxlAnimation(handle)
        }
    }

    fun decodeJxlAnimation(input: File, outputDir: File): DecodedJxlAnimation {
        check(outputDir.isDirectory || outputDir.mkdirs()) { "could not create animation decode directory" }
        outputDir.listFiles()?.forEach { it.delete() }
        val delays = nativeDecodeJxlAnimation(input.absolutePath, outputDir.absolutePath)
        return DecodedJxlAnimation(delays.indices.map { File(outputDir, "frame-%08d.png".format(it)) }, delays.toList())
    }

    fun formatOf(input: ByteArray, size: Int = input.size): Int = ImageFormatSniffer.formatOf(input, size)

    data class Probe(val width: Int, val height: Int, val format: Int) {
        val mimeType: String
            get() = when (format) {
                FORMAT_AVIF -> "image/avif"
                FORMAT_JXL -> "image/jxl"
                else -> "application/octet-stream"
            }
    }
}
