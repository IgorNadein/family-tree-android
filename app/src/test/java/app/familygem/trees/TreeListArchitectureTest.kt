package app.familygem.trees

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeListArchitectureTest {
    private fun tree(id: Int, grade: Int = 0) =
        TreeListItem(id, "Family $id", "3 people", grade)

    @Test fun ordinaryAndSharedTreesOpenButDerivedAndExhaustedCompare() {
        val model = TreeListViewModel()
        model.present(listOf(tree(1), tree(2, 10), tree(3, 20), tree(4, 30)))
        assertEquals(TreeSelectionAction.OPEN, model.actionFor(1))
        assertEquals(TreeSelectionAction.OPEN, model.actionFor(2))
        assertEquals(TreeSelectionAction.COMPARE, model.actionFor(3))
        assertEquals(TreeSelectionAction.COMPARE, model.actionFor(4))
        assertNull(model.actionFor(5))
    }

    @Test fun listStateRetainsOrderAndUsesSnapshots() {
        val model = TreeListViewModel()
        val original = mutableListOf(tree(2), tree(1))
        model.present(original)
        original.clear()
        assertEquals(listOf(2, 1), model.trees.value.map { it.id })
        model.present(listOf(tree(1), tree(2)))
        assertEquals(listOf(1, 2), model.trees.value.map { it.id })
    }

    @Test fun duplicateTapsAreRejectedAndFailedLoadCanBeRetried() {
        val model = TreeListViewModel()
        model.present(listOf(tree(4), tree(5, 20)))
        assertTrue(model.beginOpening(4))
        assertEquals(4, model.openingTreeId.value)
        assertFalse(model.beginOpening(4))
        assertFalse(model.beginOpening(5))
        model.finishOpening(4)
        assertEquals(null, model.openingTreeId.value)
        assertFalse(model.beginOpening(5))
        assertTrue(model.beginOpening(4))
    }

    @Test fun cachedTreeOpensWithoutReloadingFromDisk() = runBlocking {
        val gateway = FakeGateway(alreadyOpen = true)
        assertTrue(OpenTreeUseCase(gateway).execute(4))
        assertEquals(emptyList<Int>(), gateway.loadCalls)
    }

    @Test fun uncachedTreeMustLoadSuccessfully() = runBlocking {
        val gateway = FakeGateway(alreadyOpen = false, loadSucceeds = true)
        assertTrue(OpenTreeUseCase(gateway).execute(5))
        assertEquals(listOf(5), gateway.loadCalls)
    }

    @Test fun failedTreeLoadDoesNotReportSuccessfulNavigation() = runBlocking {
        val gateway = FakeGateway(alreadyOpen = false, loadSucceeds = false)
        assertFalse(OpenTreeUseCase(gateway).execute(6))
        assertEquals(listOf(6), gateway.loadCalls)
    }

    private class FakeGateway(
        private val alreadyOpen: Boolean,
        private val loadSucceeds: Boolean = true
    ) : TreeListGateway {
        val loadCalls = mutableListOf<Int>()
        override fun snapshot(): List<TreeListItem> = emptyList()
        override fun isOpenInMemory(treeId: Int) = alreadyOpen
        override suspend fun loadTree(treeId: Int): Boolean {
            loadCalls += treeId
            return loadSucceeds
        }
    }
}
