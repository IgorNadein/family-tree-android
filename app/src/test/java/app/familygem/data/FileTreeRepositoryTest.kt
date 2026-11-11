package app.familygem.data

import app.familygem.history.VersionedTreeStore
import kotlinx.coroutines.runBlocking
import org.folg.gedcom.model.Gedcom
import org.folg.gedcom.model.Name
import org.folg.gedcom.model.Person
import org.folg.gedcom.parser.ModelParser
import org.folg.gedcom.parser.JsonParser
import org.folg.gedcom.visitors.GedcomWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

class FileTreeRepositoryTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun savingAndReopeningPreservesPeopleAndCyrillicNames() = runBlocking {
        val repository = FileTreeRepository(folder.root)
        val original = treeWithPerson("I1", "Иван /Иванов/")
        repository.write(1, original)

        val reopened = repository.read(1)!!
        assertEquals(1, reopened.people.size)
        assertEquals("I1", reopened.people.first().id)
        assertEquals("Иван /Иванов/", reopened.people.first().names.first().value)
        assertTrue(File(folder.root, "1.json").exists())
    }

    @Test
    fun savingNormalGedcomCreatesVersionHistoryWithoutChangingItsJson() = runBlocking {
        val repository = FileTreeRepository(folder.root)
        val first = treeWithPerson("I1", "Мария /Иванова/")
        val edited = treeWithPerson("I1", "Мария /Петрова/")
        repository.write(3, first)
        repository.write(3, edited)
        val history = VersionedTreeStore(folder.root).history(3)
        assertEquals(2, history.size)
        assertEquals(JsonParser().toJson(edited), File(folder.root, "3.json").readText())
        assertEquals(JsonParser().toJson(edited), history.first().snapshotJson)
    }

    @Test
    fun treesStaySeparateAndSavedFilesAreReplacedWithoutStaleTempFiles() = runBlocking {
        val repository = FileTreeRepository(folder.root)
        repository.write(1, treeWithPerson("I1", "Первый /Человек/"))
        repository.write(2, treeWithPerson("I2", "Другой /Человек/"))
        repository.write(1, treeWithPerson("I3", "Новый /Человек/"))

        assertEquals("I3", repository.read(1)!!.people.single().id)
        assertEquals("I2", repository.read(2)!!.people.single().id)
        assertFalse(folder.root.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
    }

    @Test
    fun legacyJsonKeysAreMigratedWithoutChangingNames() {
        val oldJson = """{"zona":7,"famili":2,"passato":true,"name":"zona famili passato"}"""
        val expected = """{"zone":7,"kin":2,"passed":true,"name":"zona famili passato"}"""
        assertEquals(expected, LegacyTreeJson.normalize(oldJson))
    }

    @Test
    fun invalidJsonDoesNotRewriteOriginalFile() = runBlocking {
        val invalid = File(folder.root, "4.json")
        invalid.writeText("{not valid JSON")
        val repository = FileTreeRepository(folder.root)

        try {
            repository.read(4)
            throw AssertionError("Expected a parse failure")
        } catch (_: Exception) {
            assertEquals("{not valid JSON", invalid.readText())
        }
    }

    @Test
    fun storedJsonIsCompatibleWithOriginalSerializer() = runBlocking {
        val tree = treeWithPerson("I7", "Test /Name/")
        FileTreeRepository(folder.root).write(7, tree)

        assertEquals(JsonParser().toJson(tree), File(folder.root, "7.json").readText())
    }

    @Test
    fun failedReplacementLeavesExistingPathIntactAndCleansUpTempFile() = runBlocking {
        val existingDirectory = folder.newFolder("8.json")
        val repository = FileTreeRepository(folder.root)

        try {
            repository.write(8, treeWithPerson("I8", "Test /Name/"))
            throw AssertionError("Expected replacement to fail")
        } catch (_: IOException) {
            assertTrue(existingDirectory.isDirectory)
            assertFalse(folder.root.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
        }
    }

    @Test
    fun gedcomFileCanBeImportedExportedAndImportedAgain() {
        val gedcomText = """
            0 HEAD
            1 SOUR FamilyTree
            1 GEDC
            2 VERS 5.5.1
            2 FORM LINEAGE-LINKED
            1 CHAR UTF-8
            0 @I1@ INDI
            1 NAME Ivan /Testov/
            0 TRLR
        """.trimIndent() + "\n"
        val imported = ModelParser().parseGedcom(ByteArrayInputStream(gedcomText.toByteArray()))
        assertEquals(1, imported.people.size)

        val exported = folder.newFile("roundtrip.ged")
        GedcomWriter().write(imported, exported)

        val reopened = exported.inputStream().use { ModelParser().parseGedcom(it) }
        assertEquals(1, reopened.people.size)
        assertEquals(imported.people.first().id, reopened.people.first().id)
    }

    private fun treeWithPerson(id: String, fullName: String) = Gedcom().apply {
        val person = Person().apply {
            this.id = id
            addName(Name().apply { value = fullName })
        }
        addPerson(person)
    }
}
