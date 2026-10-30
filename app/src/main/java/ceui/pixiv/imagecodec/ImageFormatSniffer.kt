package ceui.pixiv.imagecodec

/** Lightweight signature checks that do not load native code (safe in local JVM tests). */
object ImageFormatSniffer {
    fun formatOf(input: ByteArray, size: Int = input.size): Int {
        if (size >= 2 && input[0] == 0xff.toByte() && input[1] == 0x0a.toByte()) return NativeImageCodec.FORMAT_JXL
        if (size >= 12 && input[0] == 0.toByte() && input[1] == 0.toByte() &&
            input[2] == 0.toByte() && input[3] == 12.toByte() &&
            input.copyOfRange(4, 8).decodeToString() == "JXL "
        ) return NativeImageCodec.FORMAT_JXL
        if (size >= 12 && input.copyOfRange(4, 8).decodeToString() == "ftyp") {
            when (input.copyOfRange(8, 12).decodeToString()) {
                "avif", "avis" -> return NativeImageCodec.FORMAT_AVIF
            }
        }
        return 0
    }
}
