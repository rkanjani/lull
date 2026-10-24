package com.rohankanjani.lull

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.preferences.core.Preferences
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rohankanjani.lull.bedjetkit.Mode
import com.rohankanjani.lull.ui.LullTheme
import com.rohankanjani.lull.widgets.QuickRemoteConfigureActivity
import com.rohankanjani.lull.widgets.QuickRemoteEditor
import com.rohankanjani.lull.widgets.QuickRemoteWidget
import com.rohankanjani.lull.widgets.PickedBedJet
import com.rohankanjani.lull.widgets.QuickRemoteWidgetReceiver
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The screen a launcher opens to choose what a Quick Remote widget's buttons set. Android only: on the iPhone the same
 * choices are the system's widget editor, drawn from `QuickRemoteConfiguration`, whose choices WidgetTests.swift covers.
 */
@RunWith(AndroidJUnit4::class)
class QuickRemotePickerTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val host by lazy { AppWidgetHost(context, 4208) }
    private val durations = hasContentDescription("Duration") and SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)
    private val bedJets = listOf(WidgetBedJet("left", "Left side"), WidgetBedJet("right", "Right side"))

    @After
    fun removeWidgets() {
        host.deleteHost()
    }

    @Test
    fun editsEachButton() {
        val saved = mutableListOf<Pair<String?, List<PresetChoice>>>()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    LullTheme { QuickRemoteEditor(bedJets, "left", PresetChoice.defaults, fahrenheit = false) { id, choices -> saved += id to choices } }
                }
            }

            compose.onNode(hasText("Buttons") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertExists()
            compose.onNodeWithText("What each button sets your BedJet to.").assertExists()
            // Each BedJet by name, starting on the one it was given.
            compose.onNode(hasText("Left side") and isSelected()).assertExists()
            compose.onNodeWithText("The one on screen in Lull").assertDoesNotExist()
            compose.onNodeWithText("Right side").performClick()
            compose.onNode(hasText("Right side") and isSelected()).assertExists()
            // Off sets nothing else, so only the first two buttons have a time, temperature and fan.
            compose.onAllNodes(durations).assertCountEquals(2)

            // The third becomes Turbo, which runs 10 minutes at most, and the first becomes Off.
            compose.onAllNodesWithText("Turbo")[2].performScrollTo().performClick()
            compose.onAllNodesWithText("Off")[0].performScrollTo().performClick()
            compose.onAllNodes(durations).assertCountEquals(2)
            compose.onNodeWithText("Save").performScrollTo().performClick()
        }
        assertEquals(
            listOf("right" to listOf(PresetChoice(Mode.STANDBY, 30), PresetChoice.defaults[1], PresetChoice(Mode.TURBO, 10))),
            saved,
        )
    }

    @Test
    fun savingSetsTheWidgetsButtons() {
        WidgetSnapshot(bedJets = bedJets, onScreen = "left").save(context)
        val widgetId = quickRemoteWidget()
        ActivityScenario.launchActivityForResult<QuickRemoteConfigureActivity>(configure(widgetId)).use {
            compose.waitFor(hasText("Buttons"))
            // A new widget starts on the BedJet on screen in Lull.
            compose.onNode(hasText("Left side") and isSelected()).assertExists()
            compose.onNodeWithText("Right side").performClick()
            compose.onAllNodesWithText("Dry")[0].performScrollTo().performClick()
            // Turning the phone recreates the screen; what was chosen but not yet saved stays.
            it.recreate()
            compose.waitFor(hasText("Buttons"))
            compose.onNodeWithText("Save").performScrollTo().performClick()
            assertEquals(Activity.RESULT_OK, it.result.resultCode)
        }
        assertEquals(listOf(PresetChoice(Mode.DRY, 30)) + PresetChoice.defaults.drop(1), chosen(widgetId))
        assertEquals("right", chosenBedJet(widgetId))

    }

    @Test
    fun onlyLullsOwnQuickRemoteWidgetsAreSetUp() {
        ActivityScenario.launchActivityForResult<QuickRemoteConfigureActivity>(configure(AppWidgetManager.INVALID_APPWIDGET_ID)).use {
            assertEquals(Activity.RESULT_CANCELED, it.result.resultCode)
        }
    }

    /** Places a Quick Remote widget in a host of the test's own, as a launcher would. */
    private fun quickRemoteWidget(): Int {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        // `--user current` fails on Android 16, so the user is named by its id (each user's uids are 100000 apart).
        val user = android.os.Process.myUid() / 100_000
        ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("appwidget grantbind --package ${context.packageName} --user $user"),
        ).use { it.readBytes() }
        val widgetId = host.allocateAppWidgetId()
        val bound = AppWidgetManager.getInstance(context)
            .bindAppWidgetIdIfAllowed(widgetId, ComponentName(context, QuickRemoteWidgetReceiver::class.java))
        assertTrue("The test couldn't place a Quick Remote widget", bound)
        return widgetId
    }

    private fun configure(widgetId: Int) = Intent(context, QuickRemoteConfigureActivity::class.java)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)

    private fun state(widgetId: Int): Preferences = runBlocking {
        val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(widgetId)
        getAppWidgetState<Preferences>(context, PreferencesGlanceStateDefinition, glanceId)
    }

    private fun chosen(widgetId: Int): List<PresetChoice> = QuickRemoteWidget.choices(state(widgetId)[QuickRemoteWidget.PRESETS])

    private fun chosenBedJet(widgetId: Int): String? = state(widgetId)[PickedBedJet]
}
