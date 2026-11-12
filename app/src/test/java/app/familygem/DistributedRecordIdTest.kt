package app.familygem

import org.folg.gedcom.model.*
import org.folg.gedcom.parser.JsonParser
import org.folg.gedcom.parser.ModelParser
import org.folg.gedcom.visitors.GedcomWriter
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/** Record identities must be stable, globally collision-resistant, and GEDCOM-serializable. */
class DistributedRecordIdTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    @Test fun separateDevicesNeverAllocateTheSameSequentialId() {
        val left = Gedcom()
        val right = Gedcom()
        val ids = mutableSetOf<String>()
        repeat(200) {
            val personId = U.newID(left, Person::class.java)
            val otherId = U.newID(right, Person::class.java)
            val familyId = U.newID(left, Family::class.java)
            assertTrue(personId.matches(Regex("I_[0-9a-f]{32}")))
            assertTrue(familyId.matches(Regex("F_[0-9a-f]{32}")))
            assertTrue(ids.add(personId))
            assertTrue(ids.add(otherId))
            assertTrue(ids.add(familyId))
            left.addPerson(Person().apply { id = personId })
            right.addPerson(Person().apply { id = otherId })
            left.addFamily(Family().apply { id = familyId })
        }
        assertEquals(600, ids.size)
    }

    @Test fun uuidIdsAndFamilyReferencesSurviveJsonRoundTrip() {
        val gedcom = Gedcom()
        val motherId = U.newID(gedcom, Person::class.java)
        val childId = U.newID(gedcom, Person::class.java)
        val familyId = U.newID(gedcom, Family::class.java)
        gedcom.addPerson(Person().apply {
            id = motherId
            addName(Name().apply { value = "Тамара /Торосян/" })
            addSpouseFamilyRef(SpouseFamilyRef().apply { ref = familyId })
        })
        gedcom.addPerson(Person().apply {
            id = childId
            addName(Name().apply { value = "Гарник /Торосян/" })
            addParentFamilyRef(ParentFamilyRef().apply { ref = familyId })
        })
        gedcom.addFamily(Family().apply {
            id = familyId
            addWife(SpouseRef().apply { ref = motherId })
            addChild(ChildRef().apply { ref = childId })
        })
        gedcom.createIndexes()
        val restored = JsonParser().fromJson(JsonParser().toJson(gedcom))
        restored.createIndexes()
        assertEquals(childId, restored.getFamily(familyId).childRefs.single().ref)
        assertEquals(motherId, restored.getFamily(familyId).wifeRefs.single().ref)
        assertEquals(familyId, restored.getPerson(childId).parentFamilyRefs.single().ref)
    }

    @Test fun uuidPersonIdsSurviveGedcomTextExport() {
        val gedcom = Gedcom()
        val id = U.newID(gedcom, Person::class.java)
        gedcom.addPerson(Person().apply {
            this.id = id
            addName(Name().apply { value = "Мария /Надеина/" })
        })
        val file = temporaryFolder.newFile("uuid-test.ged")
        GedcomWriter().write(gedcom, file)
        val parsed = file.inputStream().use { ModelParser().parseGedcom(it) }
        assertEquals(id, parsed.people.single().id)
    }

    @Test fun oldIdIsPreservedAndNewIdDoesNotDependOnLegacyCounter() {
        val gedcom = Gedcom()
        gedcom.addPerson(Person().apply { id = "I163" })
        val generated = U.newID(gedcom, Person::class.java)
        assertTrue(generated.startsWith("I_"))
        assertEquals("I163", gedcom.people.first().id)
        assertEquals(Int.MAX_VALUE, U.extractNum(generated))
        assertEquals(163, U.extractNum("I163"))
    }
}
