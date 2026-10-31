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
    fun animatedJxlPreservesFramesAndDelays() {
        val inputDir = File(context.cacheDir, "jxl-animation-input").apply { mkdirs() }
        val outputDir = File(context.cacheDir, "jxl-animation-output")
        val sources = (0 until 3).map { index ->
            Bitmap.createBitmap(65, 67, Bitmap.Config.ARGB_8888).apply {
                for (y in 0 until height) for (x in 0 until width) {
                    setPixel(x, y, Color.rgb(x * 3 % 256, y * 3 % 256, index * 70 + x + y))
                }
            }
        }
        val files = sources.mapIndexed { index, bitmap ->
            File(inputDir, "frame-$index.png").also { file ->
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        val delays = listOf(40, 70, 110)
        val jxl = File(context.cacheDir, "codec-animation.jxl")
        NativeImageCodec.encodeJxlAnimation(files, delays, jxl, 5)
        val decoded = NativeImageCodec.decodeJxlAnimation(jxl, outputDir)
        assertEquals(delays, decoded.delaysMs)
        assertEquals(sources.size, decoded.files.size)
        sources.forEachIndexed { index, source ->
            val frame = BitmapFactory.decodeFile(decoded.files[index].absolutePath)
            for (y in 0 until source.height) for (x in 0 until source.width) {
                assertEquals("frame $index pixel $x,$y", source.getPixel(x, y), frame.getPixel(x, y))
            }
            frame.recycle(); source.recycle()
        }
        inputDir.deleteRecursively(); outputDir.deleteRecursively(); jxl.delete()
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
