package ceui.pixiv.imagecodec

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativeImageCodecTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun jxlBitmapRoundTripPreservesEveryPixel() {
        val source = sampleBitmap()
        val output = File(context.cacheDir, "codec-test-odd.jxl")
        NativeImageCodec.encodeJxl(source, output, 3)
        assertTrue(NativeImageCodec.canDecode(output))
        val decoded = NativeImageCodec.decode(output.readBytes())
        assertEquals(65, decoded.width)
        assertEquals(67, decoded.height)
        for (y in 0 until source.height) for (x in 0 until source.width) {
            assertEquals("pixel $x,$y", source.getPixel(x, y), decoded.getPixel(x, y))
        }
        source.recycle(); decoded.recycle(); output.delete()
    }

    @Test
    fun decodedTransparentBitmapCanBeDrawnOnCanvas() {
        val source = sampleBitmap(transparentCorner = true)
        val output = File(context.cacheDir, "codec-test-alpha.jxl")
        NativeImageCodec.encodeJxl(source, output, 3)
        val decoded = NativeImageCodec.decode(output.readBytes())
        Canvas(Bitmap.createBitmap(65, 67, Bitmap.Config.ARGB_8888)).drawBitmap(decoded, 0f, 0f, null)
        source.recycle(); decoded.recycle(); output.delete()
    }

    @Test
    fun jpegTranscodeAndAvifProduceReadableFiles() {
        val source = sampleBitmap()
        val jpeg = java.io.ByteArrayOutputStream().also {
            source.compress(Bitmap.CompressFormat.JPEG, 95, it)
        }.toByteArray()
        val jxl = File(context.cacheDir, "codec-test-jpeg.jxl")
        NativeImageCodec.encodeJpegAsJxl(jpeg, jxl, 3)
        assertTrue(NativeImageCodec.canDecode(jxl))
        assertArrayEquals(jpeg, NativeImageCodec.reconstructJpeg(jxl))
        val avif = File(context.cacheDir, "codec-test.avif")
        NativeImageCodec.encodeAvif(source, avif, 18, 8)
        assertTrue(NativeImageCodec.canDecode(avif))
        assertEquals(NativeImageCodec.FORMAT_AVIF, NativeImageCodec.probe(avif.readBytes()).format)
        source.recycle(); jxl.delete(); avif.delete()
    }

    private fun sampleBitmap(transparentCorner: Boolean = false): Bitmap = Bitmap.createBitmap(65, 67, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until 67) for (x in 0 until 65) {
            setPixel(x, y, Color.argb(if (transparentCorner && x == 0 && y == 0) 128 else 255,
                (x * 4) % 256, (y * 4) % 256, (x + y) % 256))
        }
    }
}
