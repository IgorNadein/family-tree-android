package app.familygem.relationships

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.familygem.Global
import app.familygem.NewRelativeDialog
import app.familygem.PersonEditorActivity
import app.familygem.R
import app.familygem.Settings
import app.familygem.TreesActivity
import app.familygem.U
import app.familygem.constant.Extra
import app.familygem.constant.Relation
import org.folg.gedcom.model.ChildRef
import org.folg.gedcom.model.EventFact
import org.folg.gedcom.model.Family
import org.folg.gedcom.model.Gedcom
import org.folg.gedcom.model.Name
import org.folg.gedcom.model.ParentFamilyRef
import org.folg.gedcom.model.Person
import org.folg.gedcom.model.SpouseFamilyRef
import org.folg.gedcom.model.SpouseRef
import org.folg.gedcom.parser.JsonParser
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.anything
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Isolated emulator-only regression tests for remarriage / child parentage. */
@RunWith(AndroidJUnit4::class)
class MultipleMarriagesUiTest {
    private var previousTree = 0
    private var previousReferrer: String? = null
    private var previousGedcom: Gedcom? = null
    private var previousIds: Set<Int> = emptySet()
    private var testTreeId = 0
    private lateinit var treeFile: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = Global.settings
        previousTree = settings.openTree
        previousReferrer = settings.referrer
        previousGedcom = Global.gc
        previousIds = settings.trees.map { it.id }.toSet()
        testTreeId = settings.max() + 1

        val familyTree = buildFamilyTree()
        treeFile = File(context.filesDir, "$testTreeId.json")
        treeFile.writeText(JsonParser().toJson(familyTree))
        settings.addTree(Settings.Tree(testTreeId, "Two marriages fixture", 4, 2, null, null, null, 0))
        settings.referrer = null
        settings.openTree = testTreeId
        settings.save()
        Global.gc = familyTree
    }

    @After
    fun tearDown() {
        if (testTreeId == 0) return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val newTreeIds = Global.settings.trees.map { it.id }.filter { it !in previousIds }
        Global.settings.trees.removeAll { it.id in newTreeIds }
        Global.settings.openTree = previousTree
        Global.settings.referrer = previousReferrer
        Global.settings.save()
        Global.gc = previousGedcom
        newTreeIds.forEach { File(context.filesDir, "$it.json").delete() }
    }

    @Test
    fun expertChooserDoesNotPreselectDeceasedFirstHusband() {
        Intents.init()
        try {
            intending(hasComponent(PersonEditorActivity::class.java.name))
                .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
            ActivityScenario.launch(TreesActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    NewRelativeDialog(Global.gc.getPerson("I1"), null, null, true, null)
                        .show(activity.supportFragmentManager, "parent-choice")
                }
                onView(withId(R.id.newRelative_child)).perform(scrollTo(), click())
                onView(withText(R.string.select_parents_for_child)).check(matches(isDisplayed()))
                onView(withId(android.R.id.button1)).check(matches(not(isEnabled())))

                onView(withId(R.id.newRelative_families)).perform(click())
                // Spinner items live in a platform PopupWindow, not in the
                // Activity root. Match the second husband by name.
                onView(withText(containsString("Второй")))
                    .inRoot(isPlatformPopup())
                    .perform(click())
                onView(withId(android.R.id.button1)).check(matches(isEnabled())).perform(click())
                intended(allOf(
                    hasComponent(PersonEditorActivity::class.java.name),
                    hasExtra(Extra.FAMILY_ID, "F2")
                ))
            }
        } finally {
            Intents.release()
        }
    }

    @Test
    fun simpleModeChoosesSecondFatherInsteadOfFirst() {
        Intents.init()
        try {
            intending(hasComponent(PersonEditorActivity::class.java.name))
                .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
            ActivityScenario.launch(TreesActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val intent = Intent(activity, PersonEditorActivity::class.java)
                        .putExtra(Extra.PERSON_ID, "I1")
                        .putExtra(Extra.RELATION, Relation.CHILD)
                    assertTrue(U.checkMultiMarriages(intent, activity, null))
                }
                onView(withText(R.string.which_family_add_child))
                    .inRoot(isDialog())
                    .check(matches(isDisplayed()))
                // Existing families appear in GEDCOM order. Second = new husband.
                onData(anything()).inRoot(isDialog()).atPosition(1).perform(click())
                intended(allOf(
                    hasComponent(PersonEditorActivity::class.java.name),
                    hasExtra(Extra.FAMILY_ID, "F2")
                ))
            }
        } finally {
            Intents.release()
        }
    }

    @Test
    fun oneDeceasedHusbandDoesNotBecomeDefaultFatherOfNextChild() {
        val gedcom = Global.gc
        val secondMarriage = gedcom.getFamily("F2")
        gedcom.families.remove(secondMarriage)
        gedcom.getPerson("I1").spouseFamilyRefs.removeAll { it.ref == "F2" }
        gedcom.getPerson("I3").spouseFamilyRefs.removeAll { it.ref == "F2" }
        gedcom.createIndexes()

        Intents.init()
        try {
            intending(hasComponent(PersonEditorActivity::class.java.name))
                .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
            ActivityScenario.launch(TreesActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val intent = Intent(activity, PersonEditorActivity::class.java)
                        .putExtra(Extra.PERSON_ID, "I1")
                        .putExtra(Extra.RELATION, Relation.CHILD)
                    // Previously returned false and silently put the child
                    // in the former husband's F1 family.
                    assertTrue(U.checkMultiMarriages(intent, activity, null))
                }
                // The choice lives in an AlertDialog window, not the
                // background Activity's view hierarchy.
                onData(anything()).inRoot(isDialog()).atPosition(1).perform(click())
                intended(allOf(
                    hasComponent(PersonEditorActivity::class.java.name),
                    hasExtra(Extra.DESTINATION, "NEW_FAMILY_OFI1")
                ))
            }
        } finally {
            Intents.release()
        }
    }

    @Test
    fun addingSecondChildLinksOnlyTheSecondHusband() {
        val gedcom = Global.gc
        val secondChild = Person().apply {
            id = "I5"
            addName(Name().apply { value = "Второй /Ребёнок/" })
        }
        gedcom.addPerson(secondChild)

        PersonEditorActivity.addRelative("I1", "I5", "F2", Relation.CHILD, null)
        gedcom.createIndexes()

        assertEquals(listOf("F2"), secondChild.parentFamilyRefs.map { it.ref })
        assertEquals(listOf("I4"), gedcom.getFamily("F1").childRefs.map { it.ref })
        assertEquals(listOf("I5"), gedcom.getFamily("F2").childRefs.map { it.ref })
        assertEquals("I3", gedcom.getFamily("F2").husbandRefs.single().ref)
        assertFalse(gedcom.getFamily("F1").childRefs.any { it.ref == "I5" })
    }

    private fun person(idValue: String, name: String) = Person().apply {
        id = idValue
        addName(Name().apply { value = name })
    }

    private fun buildFamilyTree(): Gedcom {
        val mother = person("I1", "Мария /Тестова/")
        val firstHusband = person("I2", "Первый /Муж/")
        firstHusband.addEventFact(EventFact().apply { tag = "DEAT" })
        val secondHusband = person("I3", "Второй /Муж/")
        val firstChild = person("I4", "Первый /Ребёнок/")

        val firstMarriage = createMarriage("F1", mother, firstHusband)
        firstMarriage.addChild(ChildRef().apply { ref = firstChild.id })
        firstChild.addParentFamilyRef(ParentFamilyRef().apply { ref = "F1" })
        val secondMarriage = createMarriage("F2", mother, secondHusband)

        return Gedcom().apply {
            listOf(mother, firstHusband, secondHusband, firstChild).forEach(::addPerson)
            addFamily(firstMarriage)
            addFamily(secondMarriage)
            createIndexes()
        }
    }

    private fun createMarriage(idValue: String, mother: Person, husband: Person) = Family().apply {
        id = idValue
        addWife(SpouseRef().apply { ref = mother.id })
        addHusband(SpouseRef().apply { ref = husband.id })
        mother.addSpouseFamilyRef(SpouseFamilyRef().apply { ref = idValue })
        husband.addSpouseFamilyRef(SpouseFamilyRef().apply { ref = idValue })
    }
}
