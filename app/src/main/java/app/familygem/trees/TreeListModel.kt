package app.familygem.trees

/** A UI-safe snapshot: the screen cannot mutate GEDCOM or Settings through it. */
data class TreeListItem(
    val id: Int,
    val title: String,
    val details: String,
    val grade: Int
)

enum class TreeSelectionAction {
    OPEN,
    COMPARE
}

/** Rules independent of Activity, Compose, view holders and Android events. */
object TreeSelectionPolicy {
    fun action(item: TreeListItem): TreeSelectionAction =
        if (item.grade == 20 || item.grade == 30) {
            TreeSelectionAction.COMPARE
        } else {
            TreeSelectionAction.OPEN
        }
}

/**
 * Allows the existing Settings/GEDCOM code to be replaced behind an interface.
 * A ViewModel must never hold the mutable Global.gc or Settings.Tree objects.
 */
interface TreeListGateway {
    fun snapshot(): List<TreeListItem>
    fun isOpenInMemory(treeId: Int): Boolean
    suspend fun loadTree(treeId: Int): Boolean
}

/** Opening a tree is a business operation; navigation stays in the Activity. */
class OpenTreeUseCase(private val gateway: TreeListGateway) {
    suspend fun execute(treeId: Int): Boolean =
        gateway.isOpenInMemory(treeId) || gateway.loadTree(treeId)
}
