package ceui.pixiv.jxlarchive

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import ceui.lisa.R
import com.github.panpf.sketch.loadImage
import com.github.panpf.zoomimage.SketchZoomImageView
import java.io.File

class JxlArchiveViewerActivity : AppCompatActivity() {
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
