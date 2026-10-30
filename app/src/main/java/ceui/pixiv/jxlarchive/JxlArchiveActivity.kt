package ceui.pixiv.jxlarchive

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class JxlArchiveActivity : AppCompatActivity() {
    private val adapter = ArchiveAdapter()
    private var items: List<JxlArchiveSummary> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_jxl_archive)
        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        findViewById<RecyclerView>(R.id.archive_list).apply {
            layoutManager = LinearLayoutManager(this@JxlArchiveActivity)
            adapter = this@JxlArchiveActivity.adapter
        }
        adapter.onOpen = { summary ->
            startActivity(Intent(this, JxlArchiveViewerActivity::class.java).apply {
                putExtra(JxlArchiveViewerActivity.EXTRA_ILLUST_ID, summary.manifest.illustId)
            })
        }
        adapter.onDelete = { summary -> confirmDelete(summary) }
    }

    override fun onResume() {
        super.onResume()
        findViewById<TextView>(R.id.diagnostics).text = JxlArchiveDiagnostics.last
        reload()
    }

    private fun reload() {
        lifecycleScope.launch {
            items = withContext(Dispatchers.IO) { JxlArchiveRepository.list(applicationContext) }
            adapter.submit(items)
            findViewById<TextView>(R.id.empty_hint).visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun confirmDelete(summary: JxlArchiveSummary) {
        AlertDialog.Builder(this)
            .setMessage(R.string.jxl_archive_delete_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { JxlArchiveRepository.delete(applicationContext, summary.manifest.illustId) }
                    reload()
                }
            }
            .show()
    }

    private class ArchiveAdapter : RecyclerView.Adapter<ArchiveAdapter.Holder>() {
        private val values = mutableListOf<JxlArchiveSummary>()
        var onOpen: (JxlArchiveSummary) -> Unit = {}
        var onDelete: (JxlArchiveSummary) -> Unit = {}

        fun submit(items: List<JxlArchiveSummary>) {
            values.clear(); values.addAll(items); notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
            LayoutInflater.from(parent.context).inflate(R.layout.recy_jxl_archive, parent, false)
        )

        override fun getItemCount(): Int = values.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = values[position]
            Glide.with(holder.cover).load(item.cover).into(holder.cover)
            holder.title.text = item.manifest.title ?: item.manifest.illustId.toString()
            holder.subtitle.text = holder.itemView.context.getString(
                R.string.jxl_archive_list_subtitle,
                item.manifest.pageCount,
                formatSize(item.totalSize),
            )
            holder.itemView.setOnClickListener { onOpen(item) }
            holder.itemView.setOnLongClickListener { onDelete(item); true }
        }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val cover: ImageView = view.findViewById(R.id.cover)
            val title: TextView = view.findViewById(R.id.title)
            val subtitle: TextView = view.findViewById(R.id.subtitle)
        }
    }
}

internal fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
