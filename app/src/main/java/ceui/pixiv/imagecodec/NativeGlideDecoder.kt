package ceui.pixiv.imagecodec

import android.graphics.Bitmap
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.ResourceDecoder
import com.bumptech.glide.load.engine.Resource
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapResource
import com.bumptech.glide.util.ByteBufferUtil
import java.io.InputStream
import java.nio.ByteBuffer

/** Glide decoder for the opt-in AVIF cache and the JXL archive. */
class NativeGlideInputStreamDecoder(private val bitmapPool: BitmapPool) : ResourceDecoder<InputStream, Bitmap> {
    override fun handles(source: InputStream, options: Options): Boolean {
        if (!source.markSupported()) return false
        return try {
            source.mark(16)
            val header = ByteArray(16)
            val read = source.read(header)
            source.reset()
            ImageFormatSniffer.formatOf(header, read) != 0
        } catch (_: Exception) {
            runCatching { source.reset() }
            false
        }
    }

    override fun decode(
        source: InputStream,
        width: Int,
        height: Int,
        options: Options,
    ): Resource<Bitmap>? {
        val bytes = source.readBytes()
        val probe = NativeImageCodec.probe(bytes)
        return BitmapResource.obtain(NativeImageCodec.decode(bytes, sampleSize(probe, width, height)), bitmapPool)
    }
}

class NativeGlideByteBufferDecoder(private val bitmapPool: BitmapPool) : ResourceDecoder<ByteBuffer, Bitmap> {
    override fun handles(source: ByteBuffer, options: Options): Boolean {
        val copy = source.duplicate()
        val header = ByteArray(minOf(16, copy.remaining()))
        copy.get(header)
        return ImageFormatSniffer.formatOf(header, header.size) != 0
    }

    override fun decode(source: ByteBuffer, width: Int, height: Int, options: Options): Resource<Bitmap>? {
        val bytes = ByteBufferUtil.toBytes(source)
        val probe = NativeImageCodec.probe(bytes)
        return BitmapResource.obtain(NativeImageCodec.decode(bytes, sampleSize(probe, width, height)), bitmapPool)
    }
}

private fun sampleSize(probe: NativeImageCodec.Probe, width: Int, height: Int): Int {
    if (width <= 0 || height <= 0) return 1
    var sample = 1
    while (probe.width / (sample * 2) >= width && probe.height / (sample * 2) >= height) sample *= 2
    return sample
}
