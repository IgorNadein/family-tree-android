package app.familygem.people

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.familygem.Global
import app.familygem.Memory
import app.familygem.R
import app.familygem.Settings
import app.familygem.profile.ProfileActivity
import org.folg.gedcom.model.Gedcom
import org.folg.gedcom.model.Name
import org.folg.gedcom.model.Person
import org.folg.gedcom.parser.JsonParser
import org.hamcrest.Matchers.containsString
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Disposable emulator test, never accesses real family files. */
@RunWith(AndroidJUnit4::class)
class PersonMergeUiTest {
    private var testId = 0
    private var originalId = 0
    private var originalGedcom: Gedcom? = null
    private var originalUnsaved = false
    private var originalReferrer: String? = null

    @Before fun prepare() {
        val settings = Global.settings
        originalId = settings.openTree
        originalGedcom = Global.gc
        originalUnsaved = Global.shouldSave
        originalReferrer = settings.referrer
        testId = settings.max() + 1
        val gedcom = Gedcom()
        for ((id, text) in listOf("I1" to "Иван /Надеин/", "I2" to "Иван /Надеин/")) {
            val person = Person().apply {
                this.id = id
                addName(Name().apply { value = text })
            }
            gedcom.addPerson(person)
        }
        gedcom.createIndexes()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, testId.toString() + ".json").writeText(JsonParser().toJson(gedcom))
        settings.addTree(Settings.Tree(testId, "Merge UI test", 2, 1, "I1", null, null, 0))
        settings.openTree = testId
        settings.referrer = null
        settings.save()
        Global.gc = gedcom
        Global.shouldSave = false
        Memory.clear()
        Memory.setLeader(gedcom.getPerson("I1"))
    }

    @After fun cleanup() {
        if (testId == 0) return
        Global.settings.trees.removeAll { it.id == testId }
        Global.settings.openTree = originalId
        Global.settings.referrer = originalReferrer
        Global.settings.save()
        Global.gc = originalGedcom
        Global.shouldSave = originalUnsaved
        Memory.clear()
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            testId.toString() + ".json").delete()
    }

    @Test fun pickerDisplaysTwoCardsAndPreviewWithoutModifyingTree() {
        ActivityScenario.launch<ProfileActivity>(Intent(
            InstrumentationRegistry.getInstrumentation().targetContext, ProfileActivity::class.java
        )).use { scenario ->
            scenario.onActivity { PersonMergeUi.choose(it, "I1") }
            onView(withText(R.string.person_merge_select_title))
                .inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withText(containsString("I2")))
                .inRoot(isDialog()).perform(click())
            val deadline = android.os.SystemClock.uptimeMillis() + 10_000
            while (true) {
                try {
                    onView(withText(R.string.person_merge_confirm_title))
                        .inRoot(isDialog()).check(matches(isDisplayed()))
                    break
                } catch (failure: Exception) {
                    if (android.os.SystemClock.uptimeMillis() >= deadline) throw failure
                    Thread.sleep(150)
                } catch (failure: AssertionError) {
                    if (android.os.SystemClock.uptimeMillis() >= deadline) throw failure
                    Thread.sleep(150)
                }
            }
            onView(withText(R.string.cancel)).inRoot(isDialog()).perform(click())
            assert(Global.gc.people.size == 2)
        }
    }
}
