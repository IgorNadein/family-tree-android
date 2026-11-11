package app.familygem.trees

import app.familygem.Global
import app.familygem.util.TreeUtil
import app.familygem.util.getBasicData

/**
 * Sole adapter for the legacy mutable application state in the tree-list flow.
 * Nothing in the ViewModel or future screen needs to know about Global.
 */
class LegacyTreeListGateway : TreeListGateway {
    override fun snapshot(): List<TreeListItem> =
        Global.settings.trees.map { tree ->
            if (Global.settings.openTree == tree.id && Global.gc != null) {
                TreeUtil.refreshData(Global.gc, tree)
            }
            TreeListItem(
                id = tree.id,
                title = tree.title,
                details = tree.getBasicData(),
                grade = tree.grade
            )
        }

    override fun isOpenInMemory(treeId: Int): Boolean =
        Global.gc != null && treeId == Global.settings.openTree

    override suspend fun loadTree(treeId: Int): Boolean =
        TreeUtil.openGedcom(treeId, true)
}
