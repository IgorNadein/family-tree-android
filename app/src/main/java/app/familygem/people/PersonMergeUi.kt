package app.familygem.people

import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.familygem.Global
import app.familygem.Memory
import app.familygem.R
import app.familygem.U
import app.familygem.data.FileTreeRepository
import app.familygem.history.AndroidTreeMediaAccess
import app.familygem.history.VersionedTreeStore
import app.familygem.main.MainActivity
import app.familygem.util.TreeUtil
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.folg.gedcom.parser.JsonParser as GedcomJsonParser
import java.io.File
import java.io.IOException

object PersonMergeUi {
    /** The primary person is the one whose profile was opened. */
    fun choose(activity: AppCompatActivity, keepId: String) {
        val gedcom = Global.gc ?: return
        if (Global.shouldSave) {
            Toast.makeText(activity, R.string.ftree_save_first, Toast.LENGTH_LONG).show()
            return
        }
        val people = gedcom.people.filter { it.id != keepId }
            .sortedBy { U.properName(it).lowercase() }
        if (people.isEmpty()) return

        val visible = people.toMutableList()
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, 0)
        }
        val search = EditText(activity).apply {
            hint = activity.getString(R.string.person_merge_search)
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val listView = ListView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (370 * resources.displayMetrics.density).toInt())
        }
        fun label(person: org.folg.gedcom.model.Person): String {
            val birth = person.eventsFacts.firstOrNull { it.tag == "BIRT" }?.date
            return U.properName(person) + (birth?.takeIf { it.isNotBlank() }?.let { " · " + it } ?: "") +
                " · " + person.id
        }
        val adapter = ArrayAdapter(activity,
            android.R.layout.simple_list_item_1, visible.map(::label).toMutableList())
        listView.adapter = adapter
        layout.addView(search)
        layout.addView(listView)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.person_merge_select_title)
            .setView(layout)
            .setNegativeButton(R.string.cancel, null)
            .create()
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                visible.clear()
                val query = s?.toString()?.trim().orEmpty()
                visible += people.filter {
                    label(it).contains(query, ignoreCase = true)
                }
                adapter.clear()
                adapter.addAll(visible.map(::label))
                adapter.notifyDataSetChanged()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        listView.setOnItemClickListener { _, _, index, _ ->
            val duplicate = visible.getOrNull(index) ?: return@setOnItemClickListener
            dialog.dismiss()
            preview(activity, keepId, duplicate.id)
        }
        dialog.show()
    }

    private fun preview(activity: AppCompatActivity, keepId: String, removeId: String) {
        val treeId = Global.settings.openTree
        val primary = Global.gc?.getPerson(keepId) ?: return
        val secondary = Global.gc?.getPerson(removeId) ?: return
        if (Global.settings.currentTree.root == removeId ||
            Global.settings.currentTree.shareRoot == removeId) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.person_merge_blocked)
                .setMessage(R.string.person_merge_root_blocked)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        activity.lifecycleScope.launch {
            val plan = runCatching {
                withContext(Dispatchers.Default) {
                    val json = GedcomJsonParser().toJson(Global.gc)
                    val merged = PersonMerge.plan(json, keepId, removeId)
                    Pair(json, merged)
                }
            }
            plan.onFailure { showError(activity, it) }
            plan.onSuccess { (original, changes) ->
                fun details(person: org.folg.gedcom.model.Person): String {
                    val birth = person.eventsFacts.firstOrNull { it.tag == "BIRT" }
                    val death = person.eventsFacts.firstOrNull { it.tag == "DEAT" }
                    return activity.getString(R.string.person_merge_profile_details,
                        birth?.date?.takeIf { it.isNotBlank() } ?: "—",
                        birth?.place?.takeIf { it.isNotBlank() } ?: "—",
                        death?.date?.takeIf { it.isNotBlank() } ?: "—",
                        person.parentFamilyRefs.size, person.spouseFamilyRefs.size)
                }
                val summary = activity.getString(R.string.person_merge_preview,
                    U.properName(primary), U.properName(secondary),
                    changes.addedNames, changes.addedFacts,
                    changes.addedPhotos, changes.addedNotes,
                    changes.addedSources, changes.affectedFamilies) +
                    "\n\n" + U.properName(primary) + ":\n" + details(primary) +
                    "\n\n" + U.properName(secondary) + ":\n" + details(secondary)
                val extra = changes.warnings.map { warning ->
                    when {
                        warning.contains("birth") -> activity.getString(R.string.person_merge_birth_warning)
                        warning.contains("death") -> activity.getString(R.string.person_merge_death_warning)
                        else -> warning
                    }
                }.joinToString("\n")
                AlertDialog.Builder(activity)
                    .setTitle(R.string.person_merge_confirm_title)
                    .setMessage(summary + if (extra.isEmpty()) "" else "\n\n" + extra)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.person_merge_do) { _, _ ->
                        apply(activity, treeId, original, changes)
                    }
                    .show()
            }
        }
    }

    private fun apply(
        activity: AppCompatActivity,
        treeId: Int,
        previousJson: String,
        preview: PersonMergePreview
    ) {
        activity.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    check(Global.settings.openTree == treeId && !Global.shouldSave) {
                        "Tree was changed; save and retry"
                    }
                    check(JsonParser.parseString(GedcomJsonParser().toJson(Global.gc)) ==
                        JsonParser.parseString(previousJson)) {
                        "Person data changed while reviewing; retry the merge"
                    }
                    val file = File(activity.filesDir, treeId.toString() + ".json")
                    val onDisk = file.readText(Charsets.UTF_8)
                    // No lost updates from another activity or an in-flight auto-save.
                    check(JsonParser.parseString(onDisk) == JsonParser.parseString(previousJson)) {
                        "Tree changed while reviewing; open the two people again"
                    }
                    val refreshed = PersonMerge.plan(onDisk, preview.keepId, preview.removeId)
                    val mediaAccess = AndroidTreeMediaAccess(activity)
                    val history = VersionedTreeStore(activity.filesDir, mediaAccess)
                    // First ensure the pre-merge state is reachable for a future revert.
                    history.record(treeId, onDisk)
                    val updated = GedcomJsonParser().fromJson(refreshed.mergedJson)
                        .apply { createIndexes() }
                    FileTreeRepository(activity.filesDir, mediaAccess).write(treeId, updated)
                    // FileTreeRepository deliberately tolerates journal failures for old
                    // saves. Here the operation explicitly requires a history commit.
                    val saved = file.readText(Charsets.UTF_8)
                    history.record(treeId, saved)
                    check(history.history(treeId).firstOrNull()?.snapshotJson == saved) {
                        "Merge saved, but history needs repair before any further edits"
                    }
                    updated
                }
            }
            result.onSuccess { updated ->
                Global.gc = updated
                Global.indi = preview.keepId
                Global.edited = true
                Global.shouldSave = false
                TreeUtil.refreshData(updated, Global.settings.currentTree)
                Memory.clear()
                Toast.makeText(activity, R.string.person_merge_completed, Toast.LENGTH_LONG).show()
                activity.startActivity(Intent(activity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                activity.finish()
            }.onFailure { showError(activity, it) }
        }
    }

    private fun showError(activity: AppCompatActivity, error: Throwable) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.person_merge_blocked)
            .setMessage(error.localizedMessage ?: error.javaClass.simpleName)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
