package app.familygem.history

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.familygem.Global
import app.familygem.R
import app.familygem.Settings
import app.familygem.util.TreeUtil
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Reviewed conflict resolution for legacy Android screens. No data is modified
 * until the user confirms the preview and explicitly resolves every conflict.
 */
object VersionedTreeUi {
    fun reviewMerge(activity: AppCompatActivity, treeId: Int, archive: VersionedTreeArchive, onComplete: () -> Unit) {
        if (Global.settings.openTree == treeId && Global.shouldSave) {
            Toast.makeText(activity, R.string.ftree_save_first, Toast.LENGTH_LONG).show()
            archive.release()
            return
        }
        val store = VersionedTreeStore(activity.filesDir, AndroidTreeMediaAccess(activity))
        activity.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val snapshot = File(activity.filesDir, "$treeId.json").readText(Charsets.UTF_8)
                    store.planMerge(treeId, snapshot, archive)
                }
            }
            result.onSuccess { plan ->
                val summary = activity.getString(R.string.ftree_merge_summary, archive.title, plan.changes, plan.conflicts.size)
                AlertDialog.Builder(activity)
                    .setTitle(R.string.ftree_merge_title)
                    .setMessage(summary)
                    .setPositiveButton(R.string.ftree_review_merge) { _, _ ->
                        resolveNext(activity, store, plan, mutableMapOf(), 0, onComplete)
                    }
                    .setNegativeButton(R.string.cancel) { _, _ -> archive.release() }
                    .setOnCancelListener { archive.release() }
                    .show()
            }.onFailure {
                archive.release()
                showError(activity, it)
            }
        }
    }

    private fun resolveNext(
        activity: AppCompatActivity, store: VersionedTreeStore, plan: MergePreview,
        decisions: MutableMap<String, MergeChoice>, index: Int, onComplete: () -> Unit
    ) {
        if (index >= plan.conflicts.size) {
            activity.lifecycleScope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) { store.applyMerge(plan, decisions) }
                }
                result.onSuccess { merged ->
                    if (merged) {
                        val updatedTree = withContext(Dispatchers.IO) {
                            val data = File(activity.filesDir, plan.treeId.toString() + ".json").readText()
                            org.folg.gedcom.parser.JsonParser().fromJson(data).apply { createIndexes() }
                        }
                        TreeUtil.refreshData(updatedTree, Global.settings.getTree(plan.treeId))
                        if (Global.settings.openTree == plan.treeId) {
                            // Other screens may retain the previously opened GEDCOM.
                            withContext(Dispatchers.IO) { TreeUtil.openGedcom(plan.treeId, false) }
                        }
                    }
                    Toast.makeText(activity,
                        if (merged) R.string.ftree_merge_completed else R.string.ftree_no_changes,
                        Toast.LENGTH_LONG).show()
                    plan.incoming.release()
                    onComplete()
                }.onFailure {
                    plan.incoming.release()
                    showError(activity, it)
                }
            }
            return
        }
        val conflict = plan.conflicts[index]
        val preview = activity.getString(
            R.string.ftree_conflict_header, index + 1, plan.conflicts.size,
            conflict.collection, conflict.recordId
        ) + "\n\n" +
            activity.getString(R.string.ftree_local_version) + ":\n" + describe(conflict.local) +
            "\n\n" + activity.getString(R.string.ftree_incoming_version) + ":\n" + describe(conflict.incoming)

        AlertDialog.Builder(activity)
            .setTitle(R.string.ftree_conflict_title)
            .setMessage(preview)
            .setPositiveButton(R.string.ftree_take_incoming) { _, _ ->
                decisions[conflict.key] = MergeChoice.INCOMING
                resolveNext(activity, store, plan, decisions, index + 1, onComplete)
            }
            .setNegativeButton(R.string.ftree_keep_local) { _, _ ->
                decisions[conflict.key] = MergeChoice.LOCAL
                resolveNext(activity, store, plan, decisions, index + 1, onComplete)
            }
            .setNeutralButton(R.string.cancel) { _, _ -> plan.incoming.release() }
            .setOnCancelListener { plan.incoming.release() }
            .show()
    }

    fun showHistory(activity: AppCompatActivity, treeId: Int) {
        activity.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    VersionedTreeStore(activity.filesDir, AndroidTreeMediaAccess(activity)).history(treeId)
                }
            }
            result.onSuccess { commits ->
                val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                val message = if (commits.isEmpty()) activity.getString(R.string.ftree_history_empty)
                    else commits.take(50).mapIndexed { index, commit ->
                        "${index + 1}. ${date.format(Date(commit.createdAtMillis))}\n${commit.id.take(10)} · ${commit.parents.size} parent(s)"
                    }.joinToString("\n\n")
                AlertDialog.Builder(activity)
                    .setTitle(R.string.ftree_history_title)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }.onFailure { showError(activity, it) }
        }
    }

    fun importAsNew(activity: AppCompatActivity, archive: VersionedTreeArchive, onComplete: () -> Unit) {
        activity.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val id = Global.settings.max() + 1
                    val store = VersionedTreeStore(activity.filesDir, AndroidTreeMediaAccess(activity))
                    store.importNew(id, archive)
                    id
                }
            }
            result.onSuccess { id ->
                val json = archive.headCommit().snapshotJson
                val gedcom = org.folg.gedcom.parser.JsonParser().fromJson(json)
                gedcom.createIndexes()
                val title = archive.title.ifBlank { activity.getString(R.string.app_name) }
                val tree = Settings.Tree(id, title, gedcom.people.size, 0, null, null, null, 0)
                Global.settings.addTree(tree)
                TreeUtil.refreshData(gedcom, tree)
                Global.settings.openTree = id
                Global.settings.save()
                Global.gc = gedcom
                Global.shouldSave = false
                Toast.makeText(activity, R.string.ftree_import_completed, Toast.LENGTH_LONG).show()
                archive.release()
                onComplete()
            }.onFailure {
                archive.release()
                showError(activity, it)
            }
        }
    }

    fun showError(context: Context, error: Throwable) {
        Toast.makeText(context,
            context.getString(R.string.ftree_error, error.localizedMessage ?: error.javaClass.simpleName),
            Toast.LENGTH_LONG).show()
    }

    private fun describe(value: com.google.gson.JsonElement?): String {
        if (value == null) return "—"
        val pretty = GsonBuilder().setPrettyPrinting().create().toJson(value)
        return if (pretty.length > 900) pretty.take(900) + "…" else pretty
    }
}
