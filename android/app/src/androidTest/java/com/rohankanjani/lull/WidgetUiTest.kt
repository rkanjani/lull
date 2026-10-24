package com.rohankanjani.lull

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.glance.testing.unit.hasContentDescription as glanceDescription
import androidx.glance.testing.unit.hasText as glanceText
import androidx.glance.testing.unit.hasTextEqualTo as glanceTextEqualTo
import androidx.glance.testing.unit.hasContentDescriptionEqualTo as glanceDescriptionEqualTo
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.testing.unit.GlanceAppWidgetUnitTest
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rohankanjani.lull.widgets.BothSidesContent
import com.rohankanjani.lull.widgets.LastNightContent
import com.rohankanjani.lull.widgets.QuickRemoteContent
import com.rohankanjani.lull.widgets.LastNightWidget
import com.rohankanjani.lull.widgets.TonightContent
import com.rohankanjani.lull.widgets.TonightWidget
import com.rohankanjani.lull.bedjetkit.Mode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What each widget says at each size, and that what widgets, tiles and shortcuts open lands in the right place.
 * The Android side of HomeScreenWidgetTests.swift, which places the iPhone widgets through the system's gallery.
 */
@RunWith(AndroidJUnit4::class)
class WidgetUiTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val now = System.currentTimeMillis()
    private val running = WidgetSnapshot.preview(now)
    private val planned = running.copy(run = null)

    /** Glance's test runner draws one widget at one size per run. */
    private fun draw(size: DpSize, content: @Composable () -> Unit, check: GlanceAppWidgetUnitTest.() -> Unit) =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(size)
            provideComposable(content)
            check()
        }

    @Test
    fun bothSidesShowsEachSide() {
        draw(TonightWidget.MEDIUM, { BothSidesContent(running, null, null, now) }) {
            onNode(glanceDescription("My side, Cool Drift")).assertExists()
            onNode(glanceDescription("Other side, Cooling")).assertExists()
        }
        val one = running.copy(bedJets = running.bedJets.take(1))
        draw(TonightWidget.MEDIUM, { BothSidesContent(one, null, null, now) }) {
            onNode(glanceDescriptionEqualTo("Add a second BedJet in Lull.")).assertExists()
        }
        // A side set to a BedJet forgotten since.
        draw(TonightWidget.MEDIUM, { BothSidesContent(running, "forgotten", null, now) }) {
            onNode(glanceDescriptionEqualTo("Edit the widget to choose a BedJet.")).assertExists()
        }
    }

    @Test
    fun tonightShowsTheBedJetItIsSetTo() {
        val other = running.bedJets[1].id
        for (size in listOf(TonightWidget.SMALL, TonightWidget.MEDIUM)) {
            draw(size, { TonightContent(running.showing(running.bedJets[0].id), now) }) {
                onNode(glanceDescription("Cool Drift, phase 2 of 4, Cooling")).assertExists()
            }
            // The other side is cooled from the Remote, so it shows that and its timer, under its name.
            draw(size, { TonightContent(running.showing(other), now) }) {
                onNode(glanceDescription("Other side, Cooling, 22° · Fan 40%")).assertExists()
            }
        }
        // Once nothing runs there, it offers to start that side's own night, and says which side it is.
        val idle = running.copy(bedJets = running.bedJets.map { it.copy(live = null) })
        draw(TonightWidget.MEDIUM, { TonightContent(idle.showing(other), now) }) {
            onNode(glanceDescriptionEqualTo("Start Night")).assertExists()
            onNode(glanceTextEqualTo("OTHER SIDE · TONIGHT")).assertExists()
        }
        draw(TonightWidget.SMALL, { TonightContent(WidgetSnapshot(), now) }) {
            onNode(glanceDescriptionEqualTo("No schedule yet. Open Lull to plan how your night feels.")).assertExists()
        }
    }

    @Test
    fun quickRemoteOffersItsPresets() {
        for (size in listOf(TonightWidget.SMALL, TonightWidget.MEDIUM)) {
            draw(size, { QuickRemoteContent(running, RemotePreset.defaults, null, now) }) {
                onNode(glanceDescriptionEqualTo("Cool · 30 min, 22° · Fan 40%")).assertExists()
                onNode(glanceDescriptionEqualTo("Heat · 15 min, 33° · Fan 40%")).assertExists()
                onNode(glanceDescriptionEqualTo("Turn off")).assertExists()
            }
        }
        // Edited: each button as chosen, with no Off at all.
        val edited = listOf(
            PresetChoice(Mode.TURBO, 60, fanPercent = 100),
            PresetChoice(Mode.DRY, 45, fanPercent = 60),
            PresetChoice(Mode.EXTENDED_HEAT, 90, degrees = 30, fanPercent = 20),
        ).map { it.preset(fahrenheit = false) }
        draw(TonightWidget.MEDIUM, { QuickRemoteContent(running, edited, null, now) }) {
            onNode(glanceDescriptionEqualTo("Turbo · 10 min, Fan 100%")).assertExists()
            onNode(glanceDescriptionEqualTo("Dry · 45 min, 30° · Fan 60%")).assertExists()
            onNode(glanceDescriptionEqualTo("Gentle heat · 1 hr 30 min, 30° · Fan 20%")).assertExists()
            onNode(glanceDescriptionEqualTo("Turn off")).assertDoesNotExist()
        }
        // Set to one side of the bed: that side, by name, as there's more than one to tell apart.
        val other = running.bedJets[1].id
        draw(TonightWidget.MEDIUM, { QuickRemoteContent(running, RemotePreset.defaults, other, now) }) {
            onNode(glanceTextEqualTo("OTHER SIDE")).assertExists()
            onNode(glanceTextEqualTo("MY SIDE")).assertDoesNotExist()
        }
        // Set to one that has since been forgotten: it says so.
        draw(TonightWidget.MEDIUM, { QuickRemoteContent(running, RemotePreset.defaults, "forgotten", now) }) {
            onNode(glanceTextEqualTo("FORGOTTEN BEDJET")).assertExists()
        }
    }

    @Test
    fun quickRemoteButtonsCarryTheirPreset() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        for (preset in RemotePreset.defaults + RemotePreset(Mode.DRY, 45, 60, 60)) {
            assertEquals(AppCommands.Command.Remote(preset), AppCommands.from(AppCommands.remoteIntent(context, preset)))
            assertEquals(AppCommands.Command.Remote(preset, "right"), AppCommands.from(AppCommands.remoteIntent(context, preset, "right")))
        }
        // Buttons set differently open Lull at different addresses, so Android keeps their taps apart.
        val links = RemotePreset.defaults.map { AppCommands.remoteIntent(context, it).dataString } +
            AppCommands.remoteIntent(context, RemotePreset.defaults[0], "right").dataString
        assertEquals(links.size, links.toSet().size)
    }

    @Test
    fun tonightShowsWhereTheNightIsUpTo() {
        for (size in listOf(TonightWidget.SMALL, TonightWidget.MEDIUM, TonightWidget.LARGE)) {
            draw(size, { TonightContent(running, now) }) {
                onNode(glanceDescription("Cool Drift, phase 2 of 4, Cooling")).assertExists()
            }
        }
    }

    @Test
    fun tonightOffersToStartTheNight() {
        draw(TonightWidget.MEDIUM, { TonightContent(planned, now) }) {
            onNode(glanceDescriptionEqualTo("Start Night")).assertExists()
            onNode(glanceTextEqualTo("Start")).assertExists()
        }
        // The small size has no room for the button; tapping it opens the schedule instead.
        draw(TonightWidget.SMALL, { TonightContent(planned, now) }) {
            onNode(glanceDescription("Tonight, Cool Drift")).assertExists()
        }
    }

    @Test
    fun tonightWithoutASchedule() {
        draw(TonightWidget.MEDIUM, { TonightContent(WidgetSnapshot(), now) }) {
            onNode(glanceText("No schedule yet")).assertExists()
        }
    }

    @Test
    fun lastNightSumsUpTheNightBefore() {
        for (size in listOf(LastNightWidget.SMALL, LastNightWidget.MEDIUM)) {
            draw(size, { LastNightContent(planned, now) }) {
                onNode(glanceDescription("Last night, ")).assertExists()
            }
        }
        draw(LastNightWidget.MEDIUM, { LastNightContent(WidgetSnapshot(), now) }) {
            onNode(glanceText("No nights yet")).assertExists()
        }
    }

    @Test
    fun widgetLinksOpenTheirTab() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(LullTab.HISTORY.link), ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra("ephemeral", true)
        ActivityScenario.launch<MainActivity>(intent).use {
            compose.waitFor(hasText("No nights yet"), timeoutMillis = 10_000)
        }
    }

    @Test
    fun startNightFromATileOrShortcut() {
        val intent = AppCommands.startNightIntent(ApplicationProvider.getApplicationContext()).putExtra("ephemeral", true)
        ActivityScenario.launch<MainActivity>(intent).use {
            // Lull opens on the Schedule and starts tonight's schedule once the BedJet connects.
            compose.waitFor(hasText("End Night"), timeoutMillis = 15_000)
            compose.onNodeWithText("End Night").assertExists()
            compose.waitFor(hasContentDescription("Night timeline", substring = true))
        }
    }
}
