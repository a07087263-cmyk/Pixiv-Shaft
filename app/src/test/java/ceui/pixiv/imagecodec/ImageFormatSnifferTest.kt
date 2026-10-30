package ceui.pixiv.imagecodec

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageFormatSnifferTest {
    @Test
    fun detectsBothJxlSignaturesAndAvifWithoutLoadingNativeCode() {
        assertEquals(NativeImageCodec.FORMAT_JXL, ImageFormatSniffer.formatOf(byteArrayOf(0xff.toByte(), 0x0a)))
        val jxlContainer = byteArrayOf(0, 0, 0, 12, 74, 88, 76, 32, 13, 10, 0x87.toByte(), 10)
        assertEquals(NativeImageCodec.FORMAT_JXL, ImageFormatSniffer.formatOf(jxlContainer))
        val avif = byteArrayOf(0, 0, 0, 24, 102, 116, 121, 112, 97, 118, 105, 102)
        assertEquals(NativeImageCodec.FORMAT_AVIF, ImageFormatSniffer.formatOf(avif))
        assertEquals(0, ImageFormatSniffer.formatOf(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0)))
    }
}
