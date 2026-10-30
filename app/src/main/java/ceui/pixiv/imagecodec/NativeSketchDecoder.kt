package ceui.pixiv.imagecodec

import com.github.panpf.sketch.asImage
import com.github.panpf.sketch.decode.DecodeResult
import com.github.panpf.sketch.decode.Decoder
import com.github.panpf.sketch.decode.ImageInfo
import com.github.panpf.sketch.decode.internal.DecodeHelper
import com.github.panpf.sketch.decode.internal.HelperDecoder
import com.github.panpf.sketch.fetch.FetchResult
import com.github.panpf.sketch.request.RequestContext
import com.github.panpf.sketch.source.DataSource
import com.github.panpf.sketch.util.Rect
import okio.buffer

/** Sketch decoder. HelperDecoder keeps the viewer's normal resize/subsampling pipeline. */
data object NativeSketchDecoderFactory : Decoder.Factory {
    override val key: String = "ShaftNativeAvifJxlDecoder"

    override fun create(requestContext: RequestContext, fetchResult: FetchResult): Decoder? {
        val header = fetchResult.dataSource.openSource().use { source ->
            source.buffer().readByteArray(16L)
        }
        if (ImageFormatSniffer.formatOf(header, header.size) == 0) return null
        return HelperDecoder(requestContext, fetchResult.dataSource) {
            NativeDecodeHelper(fetchResult.dataSource)
        }
    }
}

private class NativeDecodeHelper(private val source: DataSource) : DecodeHelper {
    private val bytes: ByteArray by lazy { source.openSource().use { it.buffer().readByteArray() } }
    private val probe: NativeImageCodec.Probe by lazy { NativeImageCodec.probe(bytes) }

    override val imageInfo: ImageInfo
        get() = ImageInfo(probe.width, probe.height, probe.mimeType)

    override val supportRegion: Boolean = false

    override fun decode(sampleSize: Int) = NativeImageCodec.decode(bytes, sampleSize).asImage()

    override fun decodeRegion(region: Rect, sampleSize: Int) =
        throw UnsupportedOperationException("AVIF/JXL region decode is not supported")

    override fun close() = Unit
}
