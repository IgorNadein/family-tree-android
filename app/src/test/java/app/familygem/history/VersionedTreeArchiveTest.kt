package app.familygem.history

import kotlinx.coroutines.runBlocking
import org.folg.gedcom.model.Gedcom
import org.folg.gedcom.model.Name
import org.folg.gedcom.model.Person
import org.folg.gedcom.model.ParentFamilyRef
import org.folg.gedcom.parser.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class VersionedTreeArchiveTest {
    @get:Rule val temp = TemporaryFolder()

    private fun tree(vararg people: Pair<String, String>): String {
        val gedcom = Gedcom()
        people.forEach { (id, name) ->
            gedcom.addPerson(Person().apply {
                this.id = id
                addName(Name().apply { value = name })
            })
        }
        gedcom.createIndexes()
        return JsonParser().toJson(gedcom)
    }

    private fun names(json: String): Map<String, String> {
        val tree = JsonParser().fromJson(json)
        return tree.people.associate { it.id to it.names.first().value }
    }

    private suspend fun export(store: VersionedTreeStore, treeId: Int, snapshot: String): ByteArray {
        val out = ByteArrayOutputStream()
        store.export(treeId, "Семья Тестовых", snapshot, out)
        return out.toByteArray()
    }

    @Test fun archiveRoundTripKeepsIdentityAndRevisionHistory() = runBlocking {
        val original = VersionedTreeStore(temp.newFolder("a"))
        val initial = tree("I1" to "Мария /Тестова/")
        val second = tree("I1" to "Мария /Тестова/", "I2" to "Иван /Тестов/")
        original.record(1, initial)
        original.record(1, second)
        val bytes = export(original, 1, second)
        val archive = original.inspect(ByteArrayInputStream(bytes))
        assertEquals(2, archive.commits.size)
        assertEquals("Семья Тестовых", archive.title)
        assertEquals(2, names(archive.headCommit().snapshotJson).size)

        val importedDir = temp.newFolder("b")
        val imported = VersionedTreeStore(importedDir)
        imported.importNew(4, archive)
        assertEquals(archive.manifest.treeUuid, imported.localIdentity(4))
        assertEquals(archive.manifest.head, imported.history(4).first().id)
        assertEquals(second, File(importedDir, "4.json").readText())
        assertEquals(archive.manifest.head, imported.inspect(ByteArrayInputStream(
            export(imported, 4, second))).manifest.head)
    }

    @Test fun divergingEditsAreMergedWithTwoParentsAndNoConflicts() = runBlocking {
        val leftDir = temp.newFolder("left")
        val left = VersionedTreeStore(leftDir)
        val rightDir = temp.newFolder("right")
        val right = VersionedTreeStore(rightDir)
        val baseline = tree("I1" to "Мария", "I2" to "Иван")
        val original = left.inspect(ByteArrayInputStream(export(left, 1, baseline)))
        right.importNew(5, original)

        val ours = tree("I1" to "Мария (исправлено)", "I2" to "Иван")
        val theirs = tree("I1" to "Мария", "I2" to "Иван (уточнено)")
        File(leftDir, "1.json").writeText(ours)
        left.record(1, ours)
        File(rightDir, "5.json").writeText(theirs)
        right.record(5, theirs)
        val incoming = left.inspect(ByteArrayInputStream(export(right, 5, theirs)))
        val preview = left.planMerge(1, ours, incoming)
        assertTrue(preview.conflicts.isEmpty())
        assertTrue(preview.changes > 0)
        assertTrue(left.applyMerge(preview, emptyMap()))

        val merged = left.history(1).first()
        assertEquals(2, merged.parents.size)
        assertEquals(mapOf("I1" to "Мария (исправлено)", "I2" to "Иван (уточнено)"), names(merged.snapshotJson))
        val updated = left.inspect(ByteArrayInputStream(export(left, 1, merged.snapshotJson)))
        assertEquals(4, updated.commits.size)
        // The other collaborator can merge again: their common history survives.
        val secondPreview = right.planMerge(5, theirs, updated)
        assertTrue(secondPreview.conflicts.isEmpty())
        assertTrue(right.applyMerge(secondPreview, emptyMap()))
        assertEquals(names(merged.snapshotJson), names(right.history(5).first().snapshotJson))
    }

    @Test fun concurrentChangesToOnePersonRequireExplicitChoice() = runBlocking {
        val originalDir = temp.newFolder("original")
        val branchDir = temp.newFolder("branch")
        val local = VersionedTreeStore(originalDir)
        val remote = VersionedTreeStore(branchDir)
        val base = tree("I1" to "Маша")
        val source = local.inspect(ByteArrayInputStream(export(local, 1, base)))
        remote.importNew(2, source)
        val ours = tree("I1" to "Мария")
        val theirs = tree("I1" to "Марина")
        File(originalDir, "1.json").writeText(ours)
        local.record(1, ours)
        File(branchDir, "2.json").writeText(theirs)
        remote.record(2, theirs)
        val incoming = local.inspect(ByteArrayInputStream(export(remote, 2, theirs)))
        val plan = local.planMerge(1, ours, incoming)
        assertEquals(1, plan.conflicts.size)
        assertEquals("people/I1", plan.conflicts.single().key)
        try {
            local.applyMerge(plan, emptyMap())
            fail("Conflict cannot be silently accepted")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("conflicts"))
        }
        assertEquals(ours, local.history(1).first().snapshotJson)
        assertTrue(local.applyMerge(plan, mapOf("people/I1" to MergeChoice.INCOMING)))
        assertEquals("Марина", names(local.history(1).first().snapshotJson)["I1"])
    }

    @Test fun parallelSaveTimestampsInHeaderDoNotCreateFalseConflicts() {
        val base = """{"header":{"dateTime":"2020","file":"a.ged"},"people":[]}"""
        val ours = """{"header":{"dateTime":"2021","file":"a.ged"},"people":[]}"""
        val theirs = """{"header":{"dateTime":"2022","file":"a.ged"},"people":[]}"""
        val merged = GedcomThreeWayMerge.merge(base, ours, theirs)
        assertTrue(merged.conflicts.isEmpty())
        assertEquals("2021", merged.merged.getAsJsonObject("header").get("dateTime").asString)
    }

    @Test fun simultaneousNewPersonIdCollisionIsReportedAsConflict() {
        val base = tree("I1" to "Маша")
        val mine = tree("I1" to "Маша", "I2" to "Пётр")
        val theirs = tree("I1" to "Маша", "I2" to "Сергей")
        val result = GedcomThreeWayMerge.merge(base, mine, theirs)
        assertEquals(listOf("people/I2"), result.conflicts.map { it.key })
    }

    @Test fun refusesUnrelatedTreesAndStaleMergePreview() = runBlocking {
        val a = VersionedTreeStore(temp.newFolder("a"))
        val b = VersionedTreeStore(temp.newFolder("b"))
        val original = tree("I1" to "Маша")
        val another = tree("I1" to "Другая семья")
        a.record(1, original)
        val unrelated = b.inspect(ByteArrayInputStream(export(b, 1, another)))
        try {
            a.planMerge(1, original, unrelated)
            fail("Unrelated trees must not be merged")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("Different"))
        }
        val cloned = a.inspect(ByteArrayInputStream(export(a, 1, original)))
        b.importNew(3, cloned)
        val theirs = tree("I1" to "Маша", "I2" to "Тётя")
        b.record(3, theirs)
        val plan = a.planMerge(1, original, a.inspect(ByteArrayInputStream(export(b, 3, theirs))))
        a.record(1, tree("I1" to "Маша", "I3" to "Брат"))
        try {
            a.applyMerge(plan, emptyMap())
            fail("A stale plan must not overwrite newer changes")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("changed after preview"))
        }
    }

    @Test fun archiveRejectsPathTraversalAndTamperedCommitHash() = runBlocking {
        val store = VersionedTreeStore(temp.newFolder("a"))
        val json = tree("I1" to "Маша")
        val clean = store.inspect(ByteArrayInputStream(export(store, 1, json)))
        val tampered = clean.copy(commits = clean.commits.map {
            it.copy(snapshotJson = tree("I1" to "Подмена"))
        })
        try {
            store.importNew(2, tampered)
            fail("Tampering must not be imported")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("hash"))
        }
        val pathTraversal = ByteArrayOutputStream()
        ZipOutputStream(pathTraversal).use {
            it.putNextEntry(ZipEntry("../settings.json"))
            it.write("unsafe".toByteArray())
            it.closeEntry()
        }
        try {
            store.inspect(ByteArrayInputStream(pathTraversal.toByteArray()))
            fail("Unexpected ZIP entries must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("Unsupported"))
        }
    }

    @Test fun danglingFamilyReferencesAreRejectedRatherThanPublished() = runBlocking {
        val bad = Gedcom().apply {
            addPerson(Person().apply {
                id = "I1"
                addName(Name().apply { value = "Тест" })
                addParentFamilyRef(ParentFamilyRef().apply { ref = "F404" })
            })
        }
        val store = VersionedTreeStore(temp.newFolder("bad"))
        try {
            store.record(1, JsonParser().toJson(bad))
            fail("Dangling references must not enter a history archive")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("Dangling"))
        }
    }

    @Test fun deletingAndEditingTheSamePersonIsAConflict() {
        val ancestor = tree("I1" to "Маша", "I2" to "Пётр")
        val mine = tree("I1" to "Маша")
        val incoming = tree("I1" to "Маша", "I2" to "Пётр Иванович")
        val result = GedcomThreeWayMerge.merge(ancestor, mine, incoming)
        assertEquals(listOf("people/I2"), result.conflicts.map { it.key })
        val resolved = GedcomThreeWayMerge.merge(
            ancestor, mine, incoming, mapOf("people/I2" to MergeChoice.INCOMING)
        )
        assertEquals("Пётр Иванович", names(resolved.merged.toString())["I2"])
    }

    @Test fun repeatedSaveWithSameSnapshotDoesNotCreateExtraCommits() = runBlocking {
        val repo = VersionedTreeStore(temp.newFolder("a"))
        val json = tree("I1" to "Мария")
        val a = repo.record(1, json)
        val b = repo.record(1, json)
        assertEquals(a.head, b.head)
        assertEquals(1, repo.history(1).size)
    }
}
