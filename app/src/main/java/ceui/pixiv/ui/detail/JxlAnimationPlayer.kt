package ceui.pixiv.ui.detail

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import ceui.pixiv.imagecodec.NativeImageCodec
import ceui.pixiv.ui.bulk.UGOIRA_LOG_TAG
import timber.log.Timber
import java.io.File

/** Sequential native JXL decoder with a three-Bitmap ceiling: decoding, pending and displayed. */
class JxlAnimationPlayer(
    private val file: File,
    private val delaysMs: List<Int>,
    private val onFrame: (Bitmap) -> Unit,
    private val onError: () -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val starts = LongArray(delaysMs.size)
    private val totalMs: Long
    private var pending: Bitmap? = null
    private var displayed: Bitmap? = null
    private var decodeThread: Thread? = null
    @Volatile private var running = false
    private var startNanos = 0L

    init {
        var elapsed = 0L
        for (i in delaysMs.indices) {
            starts[i] = elapsed
            elapsed += delaysMs[i].coerceAtLeast(1)
        }
        totalMs = elapsed
    }

    fun start() {
        if (running || delaysMs.isEmpty()) return
        running = true
        startNanos = System.nanoTime()
        decodeThread = Thread({ decodeLoop() }, "jxl-animation").apply {
            priority = Thread.NORM_PRIORITY - 1
            start()
        }
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun stop() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        decodeThread?.interrupt()
        decodeThread = null
        synchronized(lock) {
            pending?.recycle()
            pending = null
        }
        displayed = null
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val next = synchronized(lock) {
                val value = pending
                pending = null
                value
            }
            if (next != null) {
                val old = displayed
                displayed = next
                onFrame(next)
                old?.recycle()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun decodeLoop() {
        try {
            NativeImageCodec.AnimationDecoder(file).use { decoder ->
                var firstLoop = true
                while (running) {
                    if (!firstLoop) decoder.rewind()
                    firstLoop = false
                    for (index in delaysMs.indices) {
                        if (!running) return
                        val target = starts[index] - LOOKAHEAD_MS
                        while (running) {
                            val elapsed = (System.nanoTime() - startNanos) / 1_000_000L
                            val position = elapsed % totalMs
                            if (position >= target || target < 0) break
                            Thread.sleep(2)
                        }
                        val bitmap = decoder.nextFrame()
                            ?: throw IllegalStateException("JXL ended before frame $index")
                        if (!running) {
                            bitmap.recycle()
                            return
                        }
                        synchronized(lock) {
                            pending?.recycle()
                            pending = bitmap
                        }
                    }
                }
            }
        } catch (_: InterruptedException) {
        } catch (error: Throwable) {
            if (running) {
                Timber.tag(UGOIRA_LOG_TAG).w(error, "[jxl] streaming decode failed")
                mainHandler.post { if (running) onError() }
            }
        }
    }

    private companion object {
        const val LOOKAHEAD_MS = 12L
    }
}
