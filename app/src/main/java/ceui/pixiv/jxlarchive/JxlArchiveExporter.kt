package ceui.pixiv.jxlarchive

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import ceui.pixiv.imagecodec.NativeImageCodec
import ceui.pixiv.imagecodec.ImageFormatSniffer
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class JxlExportMode { ORIGINAL, PNG }

object JxlArchiveExporter {
    fun suggestedName(summary: JxlArchiveSummary, mode: JxlExportMode): String {
        val title = summary.manifest.title
            ?.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            ?.takeIf { it.isNotBlank() }
            ?: "archive"
        val extension = when {
            summary.manifest.pageCount > 1 -> "zip"
            mode == JxlExportMode.PNG -> "png"
            summary.manifest.sourceFormats.firstOrNull() == "JPEG" -> "jpg"
            summary.manifest.sourceFormats.firstOrNull() == "WEBP" -> "webp"
            else -> "png"
        }
        return "${title}_${summary.manifest.illustId}.$extension"
    }

    fun export(context: Context, summary: JxlArchiveSummary, uri: Uri, mode: JxlExportMode) {
        val pages = summary.manifest.pages.mapIndexed { index, relative ->
            val sourceFormat = summary.manifest.sourceFormats.getOrNull(index) ?: "OTHER"
            exportPage(File(summary.directory, relative), sourceFormat, mode)
        }
        context.contentResolver.openOutputStream(uri)?.use { output ->
            if (pages.size == 1) {
                output.write(pages.single().bytes)
            } else {
                ZipOutputStream(output).use { zip ->
                    pages.forEachIndexed { index, page ->
                        zip.putNextEntry(ZipEntry("p$index.${page.extension}"))
                        zip.write(page.bytes)
                        zip.closeEntry()
                    }
                }
            }
        } ?: throw JxlArchiveException("无法打开导出文件")
    }

    private data class ExportedPage(val bytes: ByteArray, val extension: String)

    private fun exportPage(file: File, sourceFormat: String, mode: JxlExportMode): ExportedPage {
        val bytes = file.readBytes()
        if (mode == JxlExportMode.ORIGINAL && ImageFormatSniffer.formatOf(bytes) == 0) {
            val extension = when (sourceFormat) {
                "JPEG" -> "jpg"
                "PNG" -> "png"
                "WEBP" -> "webp"
                else -> "img"
            }
            return ExportedPage(bytes, extension)
        }
        if (mode == JxlExportMode.ORIGINAL && sourceFormat == "JPEG") {
            return ExportedPage(NativeImageCodec.reconstructJpeg(file), "jpg")
        }
        val bitmap = NativeImageCodec.decode(bytes)
        return try {
            val output = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw JxlArchiveException("PNG 导出失败")
            }
            ExportedPage(output.toByteArray(), "png")
        } finally {
            bitmap.recycle()
        }
    }
}
