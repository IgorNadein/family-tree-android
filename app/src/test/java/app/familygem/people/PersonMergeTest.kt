package app.familygem.people

import app.familygem.history.VersionedTreeStore
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.folg.gedcom.parser.JsonParser as GedcomParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PersonMergeTest {
    @get:Rule val folder = TemporaryFolder()

    private val base = """
    {
      "people":[
        {"id":"I1","names":[{"value":"Иван /Надеин/"}],
         "fams":[{"ref":"F1"}],"media":[{"file":"portrait1.jpg"}],
         "eventsFacts":[{"tag":"BIRT","date":"1 JAN 1920"}]},
        {"id":"I2","names":[{"value":"Иван /Надеин/"}],
         "fams":[{"ref":"F2"}],"media":[{"file":"portrait2.jpg"}],
         "notes":[{"value":"Семейный архив"}],
         "sourceCitations":[{"ref":"S1"}],
         "eventsFacts":[{"tag":"OCCU","value":"Строитель"}]},
        {"id":"I3","names":[{"value":"Тамара"}],"fams":[{"ref":"F1"}]},
        {"id":"I4","names":[{"value":"Ольга"}],"fams":[{"ref":"F2"}]},
        {"id":"I5","names":[{"value":"Первый ребёнок"}],"famc":[{"ref":"F1"}]},
        {"id":"I6","names":[{"value":"Второй ребёнок"}],"famc":[{"ref":"F2"}]}
      ],
      "families":[
        {"id":"F1","husbandRefs":[{"ref":"I1"}],"wifeRefs":[{"ref":"I3"}],
         "childRefs":[{"ref":"I5"}]},
        {"id":"F2","husbandRefs":[{"ref":"I2"}],"wifeRefs":[{"ref":"I4"}],
         "childRefs":[{"ref":"I6"}]}
      ],
      "sources":[{"id":"S1","title":"Семейная запись"}]
    }""".trimIndent()

    @Test fun mergesTwoPeopleAndKeepsEachSpouseAndTheirOwnChildren() {
        val original = JsonParser.parseString(base).asJsonObject
        val preview = PersonMerge.plan(base, "I1", "I2")
        val gedcom = GedcomParser().fromJson(preview.mergedJson).apply { createIndexes() }
        assertEquals(5, gedcom.people.size)
        assertEquals(2, gedcom.families.size)
        assertEquals("I1", gedcom.getFamily("F1").husbandRefs.single().ref)
        assertEquals("I1", gedcom.getFamily("F2").husbandRefs.single().ref)
        assertEquals("I5", gedcom.getFamily("F1").childRefs.single().ref)
        assertEquals("I6", gedcom.getFamily("F2").childRefs.single().ref)
        assertEquals(setOf("F1", "F2"),
            gedcom.getPerson("I1").spouseFamilyRefs.map { it.ref }.toSet())
        assertEquals(2, gedcom.getPerson("I1").media.size)
        assertEquals("Семейный архив", gedcom.getPerson("I1").notes.single().value)
        assertEquals("S1", gedcom.getPerson("I1").sourceCitations.single().ref)
        assertEquals(1, preview.affectedFamilies)
        assertNull(gedcom.getPerson("I2"))
        assertEquals(original, JsonParser.parseString(base).asJsonObject)
    }

    @Test fun mergesSiblingsWithSameParentsWithoutDuplicateChildRefs() {
        val json = """{"people":[{"id":"I1","famc":[{"ref":"F1"}]},
           {"id":"I2","famc":[{"ref":"F1"}]},{"id":"I3"},{"id":"I4"}],
           "families":[{"id":"F1","husbandRefs":[{"ref":"I3"}],
           "wifeRefs":[{"ref":"I4"}],"childRefs":[{"ref":"I1"},{"ref":"I2"}]}]}"""
        val merged = GedcomParser().fromJson(PersonMerge.plan(json, "I1", "I2").mergedJson)
        assertEquals(listOf("I1"), merged.getFamily("F1").childRefs.map { it.ref })
        assertEquals(listOf("F1"), merged.getPerson("I1").parentFamilyRefs.map { it.ref })
    }

    @Test fun rewritesPersonAssociationsWithoutChangingResearchText() {
        val json = """{"people":[
          {"id":"I1","notes":[{"value":"Historical ID I2 is discussed here"}]},
          {"id":"I2"},
          {"id":"I3","associations":[{"ref":"I2","type":"Witness"}]}],
          "families":[]}"""
        val merged = JsonParser.parseString(PersonMerge.plan(json, "I1", "I2").mergedJson)
            .asJsonObject
        val remaining = merged.getAsJsonArray("people").map { it.asJsonObject }
        assertEquals(2, remaining.size)
        val association = remaining.single { it.get("id").asString == "I3" }
            .getAsJsonArray("associations")[0].asJsonObject
        assertEquals("I1", association.get("ref").asString)
        assertEquals("Historical ID I2 is discussed here",
            remaining.single { it.get("id").asString == "I1" }
                .getAsJsonArray("notes")[0].asJsonObject.get("value").asString)
    }

    @Test fun refusesConflictingSexInsideIndividualCard() {
        val json = """{"people":[
          {"id":"I1","eventsFacts":[{"tag":"SEX","value":"F"},{"tag":"SEX","value":"M"}]},
          {"id":"I2"}],"families":[]}"""
        assertThrows(IllegalArgumentException::class.java) {
            PersonMerge.plan(json, "I1", "I2")
        }
    }

    @Test fun doesNotAllowMergingParentIntoChildOrChildIntoParent() {
        val json = """{"people":[{"id":"I1"},{"id":"I2"}],
          "families":[{"id":"F1","husbandRefs":[{"ref":"I1"}],
          "childRefs":[{"ref":"I2"}]}]}"""
        assertThrows(IllegalArgumentException::class.java) { PersonMerge.plan(json, "I1", "I2") }
        assertThrows(IllegalArgumentException::class.java) { PersonMerge.plan(json, "I2", "I1") }
    }

    @Test fun refusesDifferentParentFamiliesRatherThanInventingBiologicalParentage() {
        val json = """{"people":[
         {"id":"I1","famc":[{"ref":"F1"}]},
         {"id":"I2","famc":[{"ref":"F2"}]},
         {"id":"I3"},{"id":"I4"}],
         "families":[
          {"id":"F1","husbandRefs":[{"ref":"I3"}],"childRefs":[{"ref":"I1"}]},
          {"id":"F2","husbandRefs":[{"ref":"I4"}],"childRefs":[{"ref":"I2"}]}
         ]}"""
        val error = assertThrows(IllegalArgumentException::class.java) {
            PersonMerge.plan(json, "I1", "I2")
        }
        assertTrue(error.message.orEmpty().contains("parent", true))
    }

    @Test fun blocksMergingSpousesOrIncompatibleSexRecords() {
        val spouses = """{"people":[{"id":"I1"},{"id":"I2"}],
          "families":[{"id":"F1","husbandRefs":[{"ref":"I1"}],
          "wifeRefs":[{"ref":"I2"}]}]}"""
        assertThrows(IllegalArgumentException::class.java) { PersonMerge.plan(spouses, "I1", "I2") }
        val sexes = """{"people":[{"id":"I1","eventsFacts":[{"tag":"SEX","value":"M"}]},
          {"id":"I2","eventsFacts":[{"tag":"SEX","value":"F"}]}],"families":[]}"""
        assertThrows(IllegalArgumentException::class.java) { PersonMerge.plan(sexes, "I1", "I2") }
    }

    @Test fun preservesConflictingBirthFactsAndShowsWarning() {
        val json = """{"people":[{"id":"I1","eventsFacts":[{"tag":"BIRT","date":"1935"}]},
          {"id":"I2","eventsFacts":[{"tag":"BIRT","date":"1936"}]}],"families":[]}"""
        val preview = PersonMerge.plan(json, "I1", "I2")
        assertEquals(1, preview.addedFacts)
        assertTrue(preview.warnings.any { it.contains("birth") })
        val merged = GedcomParser().fromJson(preview.mergedJson)
        assertEquals(2, merged.people.single().eventsFacts.size)
    }

    @Test fun doesNotDiscardUnknownContradictoryAttributes() {
        val json = """{"people":[{"id":"I1","rin":"A"},{"id":"I2","rin":"B"}],"families":[]}"""
        assertThrows(IllegalArgumentException::class.java) { PersonMerge.plan(json, "I1", "I2") }
    }

    @Test fun mergedResultCreatesReversibleHistoryCommit() = runBlocking {
        val preview = PersonMerge.plan(base, "I1", "I2")
        val store = VersionedTreeStore(folder.root)
        val before = store.record(2, base)
        val after = store.record(2, preview.mergedJson)
        assertNotEquals(before.head, after.head)
        val history = store.history(2)
        assertEquals(preview.mergedJson, history.first().snapshotJson)
        assertEquals(before.head, history.first().parents.single())
        assertEquals(base, history.single { it.id == before.head }.snapshotJson)
    }

    @Test fun restorePreviousVersionCreatesRevertCommitWithoutDeletingHistory() = runBlocking {
        val store = VersionedTreeStore(folder.root)
        val original = store.record(7, base)
        val merged = PersonMerge.plan(base, "I1", "I2").mergedJson
        val updated = store.record(7, merged)
        java.io.File(folder.root, "7.json").writeText(merged)
        assertTrue(store.restoreRevision(7, original.head))
        assertEquals(base, java.io.File(folder.root, "7.json").readText())
        val versions = store.history(7)
        assertEquals(3, versions.size)
        assertEquals(updated.head, versions.first().parents.single())
        assertEquals(base, versions.first().snapshotJson)
        assertTrue(versions.any { it.id == original.head })
        assertTrue(versions.any { it.id == updated.head })
    }

    @Test fun restoreRefusesToOverwriteUnsavedChanges() = runBlocking {
        val store = VersionedTreeStore(folder.root)
        val original = store.record(8, base)
        val merged = PersonMerge.plan(base, "I1", "I2").mergedJson
        store.record(8, merged)
        java.io.File(folder.root, "8.json").writeText("unsaved work")
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { store.restoreRevision(8, original.head) }
        }
        assertEquals("unsaved work", java.io.File(folder.root, "8.json").readText())
    }

    @Test fun invalidIdAndSamePersonNeverModifyInput() {
        assertThrows(IllegalArgumentException::class.java) { PersonMerge.plan(base, "I1", "I1") }
        assertThrows(IllegalArgumentException::class.java) { PersonMerge.plan(base, "I1", "I999") }
    }
}
