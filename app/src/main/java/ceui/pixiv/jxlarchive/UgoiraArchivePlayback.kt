package ceui.pixiv.jxlarchive

import android.content.Context
import ceui.pixiv.ui.bulk.UgoiraFrames
import java.io.File

object UgoiraArchivePlayback {
    /** IO only. Returns null when this artwork has no animated unified archive. */
    fun load(context: Context, illustId: Long): UgoiraFrames? {
        val manifest = JxlArchiveRepository.read(context, illustId)?.takeIf { it.isAnimation } ?: return null
        val directory = JxlArchiveRepository.directory(context, illustId)
        val delays = manifest.animationDelaysMs.takeIf { it.isNotEmpty() } ?: return null
        if (manifest.sourceFormats.firstOrNull() == "UGOIRA_FRAMES") {
            return UgoiraFrames(directory, manifest.pages.map { File(directory, it) }, delays, false, archived = true)
        }
        val animation = File(directory, manifest.pages.single())
        return when (animation.extension.lowercase()) {
            "mp4" -> UgoiraFrames(directory, emptyList(), delays, false, animation, archived = true)
            "jxl" -> {
                val playback = File(directory, "playback")
                val marker = File(playback, "delays.txt")
                val cached = delays.indices.map { File(playback, "frame-%08d.png".format(it)) }
                val ready = marker.isFile && marker.readLines().mapNotNull { it.toIntOrNull() } == delays &&
                    cached.all { it.isFile && it.length() > 0 }
                if (!ready) {
                    val decoded = NativeImageCodec.decodeJxlAnimation(animation, playback)
                    if (decoded.delaysMs != delays || decoded.files.size != delays.size) return null
                    marker.writeText(delays.joinToString("\n"))
                }
                UgoiraFrames(playback, cached, delays, false, archived = true)
            }
            else -> null
        }
    }
}
