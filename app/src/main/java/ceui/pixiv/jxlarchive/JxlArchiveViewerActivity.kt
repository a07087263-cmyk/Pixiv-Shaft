package ceui.pixiv.jxlarchive

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.pixiv.api.model.Illust
import ceui.pixiv.ui.detail.OfflineUgoira
import ceui.pixiv.ui.detail.UgoiraPlayerView
import com.github.panpf.sketch.loadImage
import com.github.panpf.zoomimage.SketchZoomImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class JxlArchiveViewerActivity : AppCompatActivity() {
    private var animationPlayer: UgoiraPlayerView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_jxl_archive_viewer)
        val id = intent.getLongExtra(EXTRA_ILLUST_ID, 0L)
        val manifest = JxlArchiveRepository.read(applicationContext, id)
        if (manifest == null) {
            finish()
            return
        }
        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).apply {
            title = manifest.title ?: getString(R.string.jxl_archive_title)
            setNavigationOnClickListener { finish() }
        }
        if (manifest.isAnimation) {
            showAnimation(id)
            return
        }
        val indicator = findViewById<TextView>(R.id.page_indicator)
        val pager = findViewById<ViewPager2>(R.id.view_pager)
        fun update(position: Int) {
            indicator.text = getString(R.string.jxl_archive_page_indicator, position + 1, manifest.pageCount)
        }
        pager.adapter = PageAdapter(manifest.pages.map { File(JxlArchiveRepository.directory(applicationContext, id), it) })
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = update(position)
        })
        update(0)
    }

    private fun showAnimation(id: Long) {
        findViewById<ViewPager2>(R.id.view_pager).visibility = View.GONE
        findViewById<TextView>(R.id.page_indicator).visibility = View.GONE
        val container = findViewById<FrameLayout>(R.id.animation_container)
        container.visibility = View.VISIBLE
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val frames = UgoiraArchivePlayback.load(this@JxlArchiveViewerActivity, id)
                val illust = runCatching {
                    Shaft.sGson.fromJson(
                        File(JxlArchiveRepository.directory(applicationContext, id), "illust.json").readText(),
                        Illust::class.java,
                    )
                }.getOrNull()
                frames?.let { it to illust }
            }
            val frames = loaded?.first
            val illust = loaded?.second
            if (frames == null || illust == null) {
                findViewById<TextView>(R.id.page_indicator).apply {
                    visibility = View.VISIBLE
                    text = getString(R.string.jxl_archive_empty)
                }
                return@launch
            }
            val player = UgoiraPlayerView(this@JxlArchiveViewerActivity)
            animationPlayer = player
            container.addView(player, FrameLayout.LayoutParams(-1, -1))
            player.bind(
                this@JxlArchiveViewerActivity,
                illust,
                resources.displayMetrics.heightPixels,
                fitToViewport = true,
                offline = OfflineUgoira(id.toString(), false, frames),
            )
        }
    }

    override fun onDestroy() {
        animationPlayer?.recycle()
        animationPlayer = null
        super.onDestroy()
    }

    private class PageAdapter(private val pages: List<File>) : androidx.recyclerview.widget.RecyclerView.Adapter<PageAdapter.Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val image = SketchZoomImageView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            return Holder(image)
        }
        override fun getItemCount(): Int = pages.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.image.loadImage(pages[position])
        }
        class Holder(val image: SketchZoomImageView) : androidx.recyclerview.widget.RecyclerView.ViewHolder(image)
    }

    companion object {
        const val EXTRA_ILLUST_ID = "jxl_archive_illust_id"
    }
}
