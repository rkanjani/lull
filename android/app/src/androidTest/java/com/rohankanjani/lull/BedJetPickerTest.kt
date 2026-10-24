package com.rohankanjani.lull

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.preferences.core.Preferences
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rohankanjani.lull.ui.LullTheme
import com.rohankanjani.lull.widgets.BedJetConfigureActivity
import com.rohankanjani.lull.widgets.BedJetPicker
import com.rohankanjani.lull.widgets.BothSidesWidgetReceiver
import com.rohankanjani.lull.widgets.LastNightWidgetReceiver
import com.rohankanjani.lull.widgets.PickedBedJet
import com.rohankanjani.lull.widgets.PickedLeft
import com.rohankanjani.lull.widgets.PickedRight
import com.rohankanjani.lull.widgets.TonightWidgetReceiver
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.reflect.KClass

/**
 * The screen a launcher opens to pick which BedJet a Tonight or Last Night widget shows, or which two a Both
 * Sides widget shows. Android only: on the iPhone the same choice is the system's widget editor, drawn from
 * `BedJetConfiguration` and `BothSidesConfiguration`, whose choices WidgetTests.swift covers.
 */
@RunWith(AndroidJUnit4::class)
class BedJetPickerTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val host by lazy { AppWidgetHost(context, 4207) }
    private val bedJets = listOf(WidgetBedJet("left", "Left side"), WidgetBedJet("right", "Right side"))

    @After
    fun removeWidgets() {
        host.deleteHost()
    }

    @Test
    fun listsEachBedJetByName() {
        val chosen = mutableListOf<String>()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { LullTheme { BedJetPicker(bedJets, "left") { id -> chosen += id } } } }

            compose.onNode(hasText("BedJet") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertExists()
            compose.onNodeWithText("Which BedJet this widget shows.").assertExists()
            compose.onNodeWithText("The one on screen in Lull").assertDoesNotExist()
            compose.onNode(hasText("Left side") and isSelected()).assertExists()
            compose.onNodeWithText("Right side").performClick()
        }
        assertEquals(listOf("right"), chosen)
    }

    @Test
    fun choosingABedJetSetsTheWidgetToIt() {
        WidgetSnapshot(bedJets = bedJets, onScreen = "left").save(context)
        for (receiver in listOf(TonightWidgetReceiver::class, LastNightWidgetReceiver::class)) {
            val widgetId = place(receiver)
            assertEquals(Activity.RESULT_OK, configure(widgetId) {
                compose.waitFor(hasText("Which BedJet this widget shows."))
                // A new widget starts on the BedJet on screen in Lull.
                compose.onNode(hasText("Left side") and isSelected()).assertExists()
                compose.onNodeWithText("Right side").performClick()
            })
            assertEquals("right", state(widgetId)[PickedBedJet])
        }
    }

    @Test
    fun withOneBedJetThereIsNothingToPick() {
        WidgetSnapshot(bedJets = bedJets.take(1), onScreen = "left").save(context)
        val widgetId = place(TonightWidgetReceiver::class)
        assertEquals(Activity.RESULT_OK, configure(widgetId) {})
        assertEquals("left", state(widgetId)[PickedBedJet])
    }

    @Test
    fun bothSidesPicksEachSide() {
        val guest = WidgetBedJet("guest", "Guest bed")
        WidgetSnapshot(bedJets = bedJets + guest, onScreen = "right").save(context)
        val widgetId = place(BothSidesWidgetReceiver::class)
        assertEquals(Activity.RESULT_OK, configure(widgetId) {
            compose.waitFor(hasText("BedJets") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            // A new widget starts with the BedJet on screen on the left, as an iPhone's does, and another on the right.
            compose.onAllNodesWithText("Right side")[0].assertIsSelected()
            compose.onAllNodesWithText("Left side")[1].assertIsSelected()
            // The right side becomes the guest bed: each side lists every BedJet.
            compose.onAllNodesWithText("Guest bed")[1].performScrollTo().performClick()
            compose.onNodeWithText("Save").performScrollTo().performClick()
        })
        assertEquals("right", state(widgetId)[PickedLeft])
        assertEquals("guest", state(widgetId)[PickedRight])
    }

    @Test
    fun onlyLullsOwnWidgetsAreSetUp() {
        val intent = Intent(context, BedJetConfigureActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        ActivityScenario.launchActivityForResult<BedJetConfigureActivity>(intent).use {
            assertEquals(Activity.RESULT_CANCELED, it.result.resultCode)
        }
    }

    /** Places a widget in a host of the test's own, as a launcher would. */
    private fun place(receiver: KClass<out GlanceAppWidgetReceiver>): Int {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        // `--user current` fails on Android 16, so the user is named by its id (each user's uids are 100000 apart).
        val user = android.os.Process.myUid() / 100_000
        ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("appwidget grantbind --package ${context.packageName} --user $user"),
        ).use { it.readBytes() }
        val widgetId = host.allocateAppWidgetId()
        val bound = AppWidgetManager.getInstance(context).bindAppWidgetIdIfAllowed(widgetId, ComponentName(context, receiver.java))
        assertTrue("The test couldn't place a ${receiver.simpleName}", bound)
        return widgetId
    }

    /** Opens the picker for a widget, as a launcher does, and says how it closed. */
    private fun configure(widgetId: Int, pick: () -> Unit): Int {
        val intent = Intent(context, BedJetConfigureActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        return ActivityScenario.launchActivityForResult<BedJetConfigureActivity>(intent).use {
            pick()
            it.result.resultCode
        }
    }

    private fun state(widgetId: Int): Preferences = runBlocking {
        val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(widgetId)
        getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)
    }
}
