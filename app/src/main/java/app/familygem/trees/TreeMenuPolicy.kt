package app.familygem.trees

/** Stable IDs match the legacy PopupMenu's MenuItemClickListener. */
enum class TreeMenuAction(val menuId: Int) {
    SAVE(-1),
    OPEN_DERIVED(0),
    INFO(1),
    RENAME(2),
    FIND_ERRORS(4),
    SHARE(5),
    MERGE(6),
    COMPARE(7),
    BACKUP(8),
    EXPORT_GEDCOM(9),
    DELETE(10)
}

data class TreeMenuContext(
    val grade: Int,
    val expert: Boolean,
    val treeExists: Boolean,
    val otherTreesExist: Boolean,
    val hasShareHistory: Boolean,
    val unsavedCurrentTree: Boolean
)

/** Exactly one policy for legacy Views now and Compose later. */
object TreeMenuPolicy {
    fun available(context: TreeMenuContext): List<TreeMenuAction> = buildList {
        val derived = context.grade == 20
        val exhausted = context.grade == 30
        val ordinary = !derived && !exhausted

        if (context.unsavedCurrentTree) add(TreeMenuAction.SAVE)
        if (context.expert && (derived || exhausted)) add(TreeMenuAction.OPEN_DERIVED)
        if (!exhausted || context.expert) add(TreeMenuAction.INFO)
        if (ordinary || context.expert) add(TreeMenuAction.RENAME)
        if (!exhausted) add(TreeMenuAction.FIND_ERRORS)
        if (context.treeExists && ordinary) {
            add(TreeMenuAction.SHARE)
            if (context.expert && context.otherTreesExist) {
                add(TreeMenuAction.MERGE)
                if (context.hasShareHistory && context.grade != 0) {
                    add(TreeMenuAction.COMPARE)
                }
            }
        }
        if (context.treeExists && context.expert) {
            add(TreeMenuAction.BACKUP)
            if (!exhausted) add(TreeMenuAction.EXPORT_GEDCOM)
        }
        add(TreeMenuAction.DELETE)
    }
}
