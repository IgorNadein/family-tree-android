package app.familygem.relationships

import app.familygem.constant.Relation
import app.familygem.util.FamilyUtil
import org.folg.gedcom.model.Family
import org.folg.gedcom.model.Gedcom
import org.folg.gedcom.model.Name
import org.folg.gedcom.model.Person
import org.folg.gedcom.model.SpouseFamilyRef
import org.folg.gedcom.model.SpouseRef
import org.folg.gedcom.parser.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultipleMarriagesTest {
    /**
     * A mother and her two husbands form two distinct GEDCOM FAM records.
     * A child must refer only to the family where its actual father belongs.
     */
    @Test
    fun eachChildHasItsOwnFatherAfterTwoMarriagesAndJsonRoundTrip() {
        val mother = person("I1", "Мария /Тестова/")
        val firstHusband = person("I2", "Первый /Муж/")
        val secondHusband = person("I3", "Второй /Муж/")
        val firstChild = person("I4", "Ребёнок /Один/")
        val secondChild = person("I5", "Ребёнок /Два/")
        val gedcom = Gedcom()
        listOf(mother, firstHusband, secondHusband, firstChild, secondChild)
            .forEach(gedcom::addPerson)

        val firstMarriage = marriage("F1", mother, firstHusband)
        val secondMarriage = marriage("F2", mother, secondHusband)
        gedcom.addFamily(firstMarriage)
        gedcom.addFamily(secondMarriage)

        FamilyUtil.linkPerson(firstChild, firstMarriage, Relation.CHILD)
        FamilyUtil.linkPerson(secondChild, secondMarriage, Relation.CHILD)
        gedcom.createIndexes()

        assertEquals(listOf("F1", "F2"), mother.spouseFamilyRefs.map { it.ref })
        assertEquals(listOf("F1"), firstChild.parentFamilyRefs.map { it.ref })
        assertEquals(listOf("F2"), secondChild.parentFamilyRefs.map { it.ref })
        assertEquals(listOf("I4"), firstMarriage.childRefs.map { it.ref })
        assertEquals(listOf("I5"), secondMarriage.childRefs.map { it.ref })

        val restored = JsonParser().fromJson(JsonParser().toJson(gedcom))
        restored.createIndexes()
        val secondRestored = restored.getPerson("I5")
        val secondFamily = restored.getFamily(secondRestored.parentFamilyRefs.single().ref)

        assertEquals("F2", secondFamily.id)
        assertEquals(listOf("I3"), secondFamily.husbandRefs.map { it.ref })
        assertEquals(listOf("I1"), secondFamily.wifeRefs.map { it.ref })
        assertFalse(secondFamily.husbandRefs.any { it.ref == "I2" })
        assertTrue(restored.getFamily("F1").childRefs.none { it.ref == "I5" })
    }

    private fun person(idValue: String, fullName: String) = Person().apply {
        id = idValue
        addName(Name().apply { value = fullName })
    }

    private fun marriage(idValue: String, mother: Person, husband: Person) = Family().apply {
        id = idValue
        addWife(SpouseRef().apply { ref = mother.id })
        addHusband(SpouseRef().apply { ref = husband.id })
        mother.addSpouseFamilyRef(SpouseFamilyRef().apply { ref = idValue })
        husband.addSpouseFamilyRef(SpouseFamilyRef().apply { ref = idValue })
    }
}
