package ceui.pixiv.jxlarchive

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import ceui.pixiv.imagecodec.NativeImageCodec
import ceui.pixiv.imagecodec.ImageFormatSniffer
import ceui.pixiv.imagecodec.NativeImageCodec
import ceui.pixiv.ui.bulk.UgoiraVideoEncoder
import ceui.pixiv.ui.bulk.UgoiraFrames
import ceui.pixiv.ui.bulk.encodeFramesToGif
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class JxlExportMode { ORIGINAL, PNG, AV1, GIF }

object JxlArchiveExporter {
    fun suggestedName(summary: JxlArchiveSummary, mode: JxlExportMode): String {
        val title = summary.manifest.title
            ?.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            ?.takeIf { it.isNotBlank() }
            ?: "archive"
        if (summary.manifest.isAnimation) {
            val extension = when {
                mode == JxlExportMode.AV1 -> "mp4"
                mode == JxlExportMode.GIF -> "gif"
                summary.manifest.pages.size > 1 -> "zip"
                else -> File(summary.directory, summary.manifest.pages.single()).extension
            }
            return "${title}_${summary.manifest.illustId}.$extension"
        }
        val extension = when {
            summary.manifest.pageCount > 1 -> "zip"
            mode == JxlExportMode.PNG -> "png"
            summary.manifest.sourceFormats.firstOrNull() == "JPEG" -> "jpg"
            summary.manifest.sourceFormats.firstOrNull() == "WEBP" -> "webp"
            else -> "png"
        }
        return "${title}_${summary.manifest.illustId}.$extension"
    }

    suspend fun export(context: Context, summary: JxlArchiveSummary, uri: Uri, mode: JxlExportMode) {
        if (summary.manifest.isAnimation) {
            exportAnimation(context, summary, uri, mode)
            return
        }
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

    private suspend fun exportAnimation(context: Context, summary: JxlArchiveSummary, uri: Uri, mode: JxlExportMode) {
        val originalFrames = summary.manifest.sourceFormats.firstOrNull() == "UGOIRA_FRAMES"
        if (mode == JxlExportMode.ORIGINAL && originalFrames) {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                java.util.zip.ZipOutputStream(output).use { zip ->
                    summary.manifest.pages.forEach { relative ->
                        val file = File(summary.directory, relative)
                        zip.putNextEntry(java.util.zip.ZipEntry(file.name))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            } ?: throw JxlArchiveException("无法打开导出文件")
            return
        }
        val animation = File(summary.directory, summary.manifest.pages.single())
        if (mode == JxlExportMode.ORIGINAL || (mode == JxlExportMode.AV1 && animation.extension == "mp4")) {
            context.contentResolver.openOutputStream(uri)?.use { output -> animation.inputStream().use { it.copyTo(output) } }
                ?: throw JxlArchiveException("无法打开导出文件")
            return
        }
        if (mode == JxlExportMode.GIF && !originalFrames && animation.extension != "jxl") {
            throw JxlArchiveException("有损 AV1 存档不能恢复原始 GIF；仅支持导出 MP4")
        }
        val archived = UgoiraArchivePlayback.load(context, summary.manifest.illustId)
            ?: throw JxlArchiveException("无法读取动图存档")
        var temporaryFrames: File? = null
        val frames = if (archived.animationJxl != null) {
            val directory = File(context.cacheDir, "jxl-animation-export-${summary.manifest.illustId}")
            temporaryFrames = directory
            val decoded = NativeImageCodec.decodeJxlAnimation(archived.animationJxl, directory)
            UgoiraFrames(directory, decoded.files, decoded.delaysMs, false, archived = true)
        } else archived
        try {
        context.contentResolver.openOutputStream(uri)?.use { output ->
            when (mode) {
                JxlExportMode.GIF -> encodeFramesToGif(frames.files, frames.delaysMs, output)
                JxlExportMode.AV1 -> {
                    val temp = File(context.cacheDir, "ugoira-archive-export-${summary.manifest.illustId}.mp4")
                    try {
                        UgoiraVideoEncoder.encode(temp, frames.files, frames.delaysMs)
                            ?: throw JxlArchiveException("AV1/MP4 导出压制失败")
                        temp.inputStream().use { it.copyTo(output) }
                    } finally {
                        temp.delete()
                    }
                }
                else -> throw JxlArchiveException("不支持的动图导出格式")
            }
        } ?: throw JxlArchiveException("无法打开导出文件")
        } finally {
            temporaryFrames?.deleteRecursively()
        }
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
