package ceui.pixiv.jxlarchive

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.utils.Common
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class JxlArchiveActivity : AppCompatActivity() {
    private val adapter = ArchiveAdapter()
    private var items: List<JxlArchiveSummary> = emptyList()
    private var pendingSummary: JxlArchiveSummary? = null
    private var pendingMode = JxlExportMode.ORIGINAL
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val summary = pendingSummary
        if (uri == null || summary == null) return@registerForActivityResult
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { JxlArchiveExporter.export(applicationContext, summary, uri, pendingMode) }
                Common.showToast(getString(R.string.jxl_archive_export_success))
            } catch (error: Exception) {
                Common.showToast(getString(R.string.jxl_archive_export_failed, error.message ?: ""))
            }
        }
    }

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
        adapter.onExport = { summary -> showExportDialog(summary) }
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

    private fun showExportDialog(summary: JxlArchiveSummary) {
        val modes = if (summary.manifest.isAnimation) {
            if (summary.manifest.sourceFormats.firstOrNull() == "UGOIRA_FRAMES" || summary.manifest.pages.single().endsWith(".jxl")) {
                listOf(JxlExportMode.ORIGINAL, JxlExportMode.AV1, JxlExportMode.GIF)
            }
            else listOf(JxlExportMode.ORIGINAL, JxlExportMode.AV1)
        } else listOf(JxlExportMode.ORIGINAL, JxlExportMode.PNG)
        val labels = modes.map { getString(when (it) {
            JxlExportMode.ORIGINAL -> R.string.jxl_archive_export_original
            JxlExportMode.PNG -> R.string.jxl_archive_export_png
            JxlExportMode.AV1 -> R.string.jxl_archive_export_av1
            JxlExportMode.GIF -> R.string.jxl_archive_export_gif
        }) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.jxl_archive_export)
            .setItems(labels) { _, which ->
                pendingSummary = summary
                pendingMode = modes[which]
                exportLauncher.launch(JxlArchiveExporter.suggestedName(summary, pendingMode))
            }
            .show()
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
        var onExport: (JxlArchiveSummary) -> Unit = {}

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
            holder.subtitle.text = if (item.manifest.originalTotalSize > 0) {
                holder.itemView.context.getString(
                    R.string.jxl_archive_list_sizes,
                    item.manifest.pageCount,
                    formatSize(item.manifest.originalTotalSize),
                    formatSize(item.totalSize),
                    formatSaving(item.manifest.originalTotalSize, item.totalSize),
                )
            } else {
                holder.itemView.context.getString(R.string.jxl_archive_list_subtitle, item.manifest.pageCount, formatSize(item.totalSize))
            }
            holder.export.setOnClickListener { onExport(item) }
            holder.itemView.setOnClickListener { onOpen(item) }
            holder.itemView.setOnLongClickListener { onDelete(item); true }
        }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val cover: ImageView = view.findViewById(R.id.cover)
            val title: TextView = view.findViewById(R.id.title)
            val subtitle: TextView = view.findViewById(R.id.subtitle)
            val export: View = view.findViewById(R.id.export)
        }
    }
}

internal fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatSaving(original: Long, compressed: Long): String = when {
    compressed < original -> String.format(Locale.US, "节约 %.1f%%", (1.0 - compressed.toDouble() / original) * 100)
    compressed > original -> String.format(Locale.US, "增加 %.1f%%", (compressed.toDouble() / original - 1.0) * 100)
    else -> "大小不变"
}
