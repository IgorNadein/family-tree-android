package app.familygem.trees

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeMenuPolicyTest {
    private fun menu(
        grade: Int = 0,
        expert: Boolean = false,
        exists: Boolean = true,
        multiple: Boolean = false,
        shares: Boolean = false,
        unsaved: Boolean = false
    ) = TreeMenuPolicy.available(
        TreeMenuContext(
            grade, expert, exists, multiple, shares, unsaved
        )
    )

    @Test fun ordinaryTreeKeepsBasicActions() {
        assertEquals(
            listOf(
                TreeMenuAction.INFO,
                TreeMenuAction.RENAME,
                TreeMenuAction.FIND_ERRORS,
                TreeMenuAction.SHARE,
                TreeMenuAction.DELETE
            ),
            menu()
        )
    }

    @Test fun unsavedCurrentTreeShowsSaveFirst() {
        val actions = menu(unsaved = true)
        assertEquals(TreeMenuAction.SAVE, actions.first())
        assertEquals(TreeMenuAction.DELETE, actions.last())
    }

    @Test fun expertCanExportBackupAndMerge() {
        val actions = menu(expert = true, multiple = true)
        assertTrue(actions.contains(TreeMenuAction.MERGE))
        assertTrue(actions.contains(TreeMenuAction.BACKUP))
        assertTrue(actions.contains(TreeMenuAction.EXPORT_GEDCOM))
        assertFalse(actions.contains(TreeMenuAction.COMPARE))
    }

    @Test fun onlySharedHistoryInNonzeroGradeAllowsCompare() {
        assertTrue(menu(grade = 10, expert = true, multiple = true, shares = true)
            .contains(TreeMenuAction.COMPARE))
        assertFalse(menu(grade = 0, expert = true, multiple = true, shares = true)
            .contains(TreeMenuAction.COMPARE))
    }

    @Test fun derivedTreeKeepsCompareOnTapButLimitsMenu() {
        assertEquals(
            listOf(TreeMenuAction.INFO, TreeMenuAction.FIND_ERRORS, TreeMenuAction.DELETE),
            menu(grade = 20)
        )
        val expertActions = menu(grade = 20, expert = true)
        assertTrue(expertActions.contains(TreeMenuAction.OPEN_DERIVED))
        assertFalse(expertActions.contains(TreeMenuAction.SHARE))
    }

    @Test fun exhaustedTreeHasOnlyDeleteUnlessExpert() {
        assertEquals(listOf(TreeMenuAction.DELETE), menu(grade = 30))
        val expertActions = menu(grade = 30, expert = true)
        assertTrue(expertActions.contains(TreeMenuAction.OPEN_DERIVED))
        assertFalse(expertActions.contains(TreeMenuAction.FIND_ERRORS))
        assertFalse(expertActions.contains(TreeMenuAction.EXPORT_GEDCOM))
    }

    @Test fun missingTreeFileDisablesTransferActions() {
        val actions = menu(expert = true, exists = false, multiple = true, shares = true)
        assertFalse(actions.contains(TreeMenuAction.BACKUP))
        assertFalse(actions.contains(TreeMenuAction.EXPORT_GEDCOM))
        assertFalse(actions.contains(TreeMenuAction.MERGE))
        assertFalse(actions.contains(TreeMenuAction.SHARE))
    }
}
