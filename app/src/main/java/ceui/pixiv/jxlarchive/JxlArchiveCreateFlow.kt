package ceui.pixiv.jxlarchive

import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.utils.Common
import ceui.pixiv.api.model.Illust
import ceui.pixiv.witstudio.dialog.WitDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun Fragment.showJxlArchiveDialog(illust: Illust) {
    val appContext = requireContext().applicationContext
    val dialog = WitDialog.CustomDialogBuilder(requireContext())
        .setLayout(R.layout.dialog_snapshot_loading)
        .setCancelable(false)
        .show()
    dialog.findViewById<TextView>(R.id.loading_message)?.text =
        appContext.getString(R.string.jxl_archive_preparing)
    lifecycleScope.launch {
        try {
            JxlArchiveEngine.archive(appContext, illust) { done, total ->
                withContext(Dispatchers.Main) {
                    dialog.findViewById<TextView>(R.id.loading_message)?.text =
                        appContext.getString(R.string.jxl_archive_progress, done, total)
                }
            }
            dialog.dismiss()
            JxlArchiveDiagnostics.success(illust.id)
            Common.showToast(appContext.getString(R.string.jxl_archive_success))
        } catch (ce: CancellationException) {
            dialog.dismiss()
            throw ce
        } catch (error: Exception) {
            dialog.dismiss()
            JxlArchiveDiagnostics.failure(illust.id, error)
            Common.showToast(appContext.getString(R.string.jxl_archive_failed, error.message ?: ""))
        }
    }
}
