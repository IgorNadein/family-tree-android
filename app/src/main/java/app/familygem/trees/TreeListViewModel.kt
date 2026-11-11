package app.familygem.trees

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** State can be rendered by both the existing ListView and a future Compose screen. */
class TreeListViewModel : ViewModel() {
    private val _trees = MutableStateFlow<List<TreeListItem>>(emptyList())
    val trees: StateFlow<List<TreeListItem>> = _trees.asStateFlow()

    private val _openingTreeId = MutableStateFlow<Int?>(null)
    val openingTreeId: StateFlow<Int?> = _openingTreeId.asStateFlow()

    /** Guard against duplicate navigation while loading a large family. */
    fun beginOpening(treeId: Int): Boolean {
        if (_openingTreeId.value != null || actionFor(treeId) != TreeSelectionAction.OPEN) {
            return false
        }
        _openingTreeId.value = treeId
        return true
    }

    fun finishOpening(treeId: Int) {
        if (_openingTreeId.value == treeId) _openingTreeId.value = null
    }

    fun present(items: List<TreeListItem>) {
        // Copy the collection to prevent mutable Settings lists leaking into UI state.
        _trees.value = items.toList()
    }

    fun actionFor(treeId: Int): TreeSelectionAction? =
        _trees.value.firstOrNull { it.id == treeId }?.let(TreeSelectionPolicy::action)
}
