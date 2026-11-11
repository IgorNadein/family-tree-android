package app.familygem

import android.app.Activity
import android.app.Instrumentation
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.folg.gedcom.model.Gedcom
import org.folg.gedcom.parser.JsonParser
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Run on a disposable emulator, never against personal family data.
 * A real tap on a visible tree row must reach the navigation action.
 * This catches the 0.3.0 regression where a ComposeView swallowed touches.
 */
@RunWith(AndroidJUnit4::class)
class TreesActivityTouchTest {
    private val title = "Architecture touch test"
    private var treeId = 0
    private lateinit var treeFile: File
    private var previousTreeId = 0
    private var previousGedcom: Gedcom? = null
    private var previousReferrer: String? = null

    @Before
    fun prepareTestTree() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = Global.settings
        previousTreeId = settings.openTree
        previousGedcom = Global.gc
        previousReferrer = settings.referrer
        treeId = settings.max() + 1

        val gedcom = Gedcom().apply { createIndexes() }
        treeFile = File(context.filesDir, "$treeId.json")
        treeFile.writeText(JsonParser().toJson(gedcom), Charsets.UTF_8)
        settings.addTree(Settings.Tree(treeId, title, 0, 0, null, null, null, 0))
        settings.referrer = null
        settings.openTree = treeId
        settings.save()
        Global.gc = gedcom
    }

    @After
    fun removeTestTree() {
        if (treeId == 0) return
        Global.settings.trees.removeAll { it.id == treeId }
        Global.settings.openTree = previousTreeId
        Global.settings.referrer = previousReferrer
        Global.settings.save()
        Global.gc = previousGedcom
        treeFile.delete()
    }

    @Test
    fun tappingTreeTitleOpensTheTreeScreen() {
        Intents.init()
        try {
            // Observe navigation without opening the diagram or changing data.
            intending(hasComponent(app.familygem.main.MainActivity::class.java.name))
                .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
            ActivityScenario.launch(TreesActivity::class.java).use {
                onView(withText(title)).check(matches(isDisplayed())).perform(click())
                intended(hasComponent(app.familygem.main.MainActivity::class.java.name))
            }
        } finally {
            Intents.release()
        }
    }
}
