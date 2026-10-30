package ceui.pixiv.imagecodec

import android.graphics.Bitmap
import java.io.File

/** Native AVIF/JXL entry points. Files are always identified by their signature, never by suffix. */
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
