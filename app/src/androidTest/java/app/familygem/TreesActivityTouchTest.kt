package app.familygem

import android.app.Activity
import android.app.Instrumentation
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
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
    private var originalIds: Set<Int> = emptySet()
    private var previousGedcom: Gedcom? = null
    private var previousReferrer: String? = null

    @Before
    fun prepareTestTree() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = Global.settings
        originalIds = settings.trees.map { it.id }.toSet()
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
        // Delete only trees created by this test. Existing app data remains intact.
        val testTreeIds = Global.settings.trees.map { it.id }.filter { it !in originalIds }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Global.settings.trees.removeAll { it.id in testTreeIds }
        Global.settings.openTree = previousTreeId
        Global.settings.referrer = previousReferrer
        Global.settings.save()
        Global.gc = previousGedcom
        testTreeIds.forEach { File(context.filesDir, "$it.json").delete() }
    }

    @Test
    fun overflowMenuPreservesTreeActions() {
        ActivityScenario.launch(TreesActivity::class.java).use {
            onView(withId(R.id.tree_menu)).perform(click())
            onView(withText(R.string.info)).check(matches(isDisplayed()))
            onView(withText(R.string.rename)).check(matches(isDisplayed()))
            onView(withText(R.string.delete)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun creatingANewTreeReturnsToTheList() {
        ActivityScenario.launch(TreesActivity::class.java).use {
            onView(withId(R.id.fab)).perform(click())
            onView(withId(R.id.new_empty_tree)).perform(click())
            onView(withId(R.id.treeTitle_edit)).perform(replaceText("Created on emulator"))
            onView(withText(R.string.create)).perform(click())
            onView(withText("Created on emulator")).check(matches(isDisplayed()))
        }
    }

    @Test
    fun coldTreeIsLoadedFromJsonBeforeNavigating() {
        Global.gc = null
        Global.settings.openTree = 0
        Global.settings.save()
        Intents.init()
        try {
            intending(hasComponent(app.familygem.main.MainActivity::class.java.name))
                .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
            ActivityScenario.launch(TreesActivity::class.java).use {
                onView(withText(title)).check(matches(isDisplayed())).perform(click())
                // Disk I/O uses Dispatchers.IO, which Espresso does not track.
                // Poll the observed intent on the instrumentation thread.
                val deadline = android.os.SystemClock.uptimeMillis() + 10_000
                while (true) {
                    try {
                        intended(hasComponent(app.familygem.main.MainActivity::class.java.name))
                        break
                    } catch (failure: AssertionError) {
                        if (android.os.SystemClock.uptimeMillis() >= deadline) throw failure
                        Thread.sleep(150)
                    }
                }
            }
        } finally {
            Intents.release()
        }
    }

    @Test
    fun versionedArchiveImportIsReachableFromCreateTreeScreen() {
        ActivityScenario.launch(NewTreeActivity::class.java).use {
            onView(withId(R.id.new_import_ftree)).perform(scrollTo())
                .check(matches(isDisplayed()))
        }
    }

    @Test
    fun versionedArchiveMenuIsVisibleForAnOrdinaryTree() {
        ActivityScenario.launch(TreesActivity::class.java).use {
            onView(withId(R.id.tree_menu)).perform(click())
            onView(withText(R.string.ftree_export)).check(matches(isDisplayed()))
            onView(withText(R.string.ftree_import_merge)).check(matches(isDisplayed()))
            onView(withText(R.string.ftree_history_title)).check(matches(isDisplayed()))
        }
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
