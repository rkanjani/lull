package com.rohankanjani.lull

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Walks the main flows against the simulated BedJet and keeps a screenshot of each screen. Mirrors LullUITests.swift. */
@RunWith(AndroidJUnit4::class)
class LullUiTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun launch() {
        scenario = launchLull(ephemeral = true)
    }

    @After
    fun close() {
        scenario.close()
    }

    @Test
    fun opensOnRemote() {
        compose.waitFor(hasText("Remote"), timeoutMillis = 10_000)
        compose.onNode(hasText("Remote") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).assertIsSelected()
    }

    @Test
    fun startAndEndNight() {
        compose.onNodeWithText("Schedule").performClick()
        // The button enables once the simulated BedJet connects.
        compose.waitFor(hasContentDescription("Start Night") and isEnabled(), timeoutMillis = 10_000)
        compose.onNodeWithContentDescription("Start Night").performClick()

        compose.waitFor(hasText("End Night"), timeoutMillis = 10_000)
        compose.waitFor(hasText("PHASE 1 OF", substring = true))
        Thread.sleep(2000)
        compose.capture("schedule-running")

        compose.onNodeWithText("End Night").performClick()
        compose.waitFor(hasContentDescription("Start Night"), timeoutMillis = 10_000)
    }

    @Test
    fun editScheduleAndUseItTonight() {
        compose.onNodeWithText("Schedule").performClick()
        compose.onNode(hasContentDescription("Warm Welcome,", substring = true)).performScrollTo().performClick()
        compose.waitFor(hasText("Add Phase"))
        compose.capture("editor")

        compose.onNode(hasContentDescription("Turbo,", substring = true) and hasContentDescription("10 minutes", substring = true)).performClick()
        compose.waitFor(hasText("Remove Phase"))
        compose.onNodeWithText("Gentle heat").performClick()
        Thread.sleep(1000)
        compose.capture("phase-editor")
        compose.onNodeWithText("Done").performClick()

        compose.onNodeWithText("Add Phase").performScrollTo().performClick()
        compose.waitFor(hasText("Remove Phase"))
        compose.onNodeWithText("Done").performClick()
        compose.waitUntilGone(hasText("Remove Phase"))
        compose.onNodeWithText("Use Tonight").performClick()

        // Warm Welcome is now the featured schedule, so Cool Drift moves down and offers the swap back.
        compose.waitFor(hasContentDescription("Use Cool Drift tonight"))
        compose.onNodeWithContentDescription("Use Warm Welcome tonight").assertDoesNotExist()
        compose.capture("schedule-planned")

        compose.onNodeWithContentDescription("Use Cool Drift tonight").performScrollTo().performClick()
        compose.waitFor(hasContentDescription("Use Warm Welcome tonight"))
    }

    @Test
    fun historyKeepsTheNight() {
        compose.onNodeWithText("History").performClick()
        compose.waitFor(hasText("No nights yet"), timeoutMillis = 10_000)

        compose.onNodeWithText("Remote").performClick()
        compose.waitFor(hasText("Heat"), timeoutMillis = 10_000)
        // History starts from the BedJet's first settled report; a change before that is where it starts.
        Thread.sleep(5000)
        compose.onNodeWithText("Heat").performClick()
        compose.waitFor(hasContentDescription("Turns off in"))
        // A setting is recorded once the BedJet has held it for a few seconds.
        Thread.sleep(4000)
        compose.onNodeWithText("Off").performClick()
        Thread.sleep(4000)

        // A schedule's phases are its own; only its start and end are recorded.
        compose.onNodeWithText("Schedule").performClick()
        compose.waitFor(hasContentDescription("Start Night") and isEnabled(), timeoutMillis = 10_000)
        compose.onNodeWithContentDescription("Start Night").performClick()
        compose.waitFor(hasText("End Night"), timeoutMillis = 10_000)
        Thread.sleep(4000)
        compose.onNodeWithText("End Night").performClick()
        compose.waitFor(hasContentDescription("Start Night"), timeoutMillis = 10_000)
        Thread.sleep(4000)

        compose.onNodeWithText("History").performClick()
        // The night is one row named after the schedule that ran, and opens up to the whole night.
        compose.waitFor(hasTestTag("Night"))
        val night = compose.onNodeWithTag("Night")
        assertTrue(night.label().endsWith("Cool Drift"))
        compose.onNode(hasContentDescription("Turned off", substring = true)).assertDoesNotExist()
        compose.capture("history-collapsed")
        night.performClick()
        compose.waitFor(hasContentDescription(", Heat,", substring = true))
        compose.onNode(hasContentDescription("Turned off", substring = true)).assertExists()
        compose.onNode(hasContentDescription("Started Cool Drift", substring = true)).assertExists()
        compose.onNode(hasContentDescription("Ended Cool Drift", substring = true)).assertExists()
        // Nothing else: the schedule's first phase and the BedJet turning off with it are not separate entries.
        assertEquals(0, compose.onAllNodes(hasContentDescription(", Cool,", substring = true)).fetchSemanticsNodes().size)
        Thread.sleep(1000)
        compose.capture("history-expanded")

        night.performClick()
        compose.waitUntilGone(hasContentDescription("Turned off", substring = true))
    }

    @Test
    fun historyListsNightsAsRows() {
        scenario.close()
        scenario = launchLull(ephemeral = true, demoHistory = true)
        compose.onNodeWithText("History").performClick()

        compose.waitFor(hasTestTag("Night"))
        val nights = compose.onAllNodesWithTag("Night")
        assertEquals(3, nights.fetchSemanticsNodes().size)
        // Newest first; a night without a schedule is summed up by its times instead.
        assertTrue(nights[0].label().endsWith("Cool Drift"))
        assertTrue(nights[1].label().contains(" to "))
        compose.capture("history-rows")

        nights[1].performClick()
        compose.waitFor(hasContentDescription("Turned off", substring = true))
        compose.onNode(hasContentDescription("Timer ran out", substring = true)).assertExists()
        Thread.sleep(1000)
        compose.capture("history-row-expanded")
    }

    private fun pill(title: String) = labelStartsWith("BedJet, $title,")

    /** A row in a list of BedJets, by its name and any code after it. */
    private fun row(title: String) = labelStartsWith("$title,")

    private fun relaunch(demoChoose: Boolean = false, demoFirstUnitAway: Boolean = false) {
        scenario.close()
        scenario = launchLull(ephemeral = true, demoChoose = demoChoose, demoFirstUnitAway = demoFirstUnitAway)
    }

    /** Opens the BedJet sheet's list of BedJets nearby and picks the second simulated one, which shares a name. */
    private fun addSecondBedJet(from: SemanticsMatcher) {
        compose.onNode(from).performClick()
        compose.waitFor(hasText("Done"))
        compose.onNodeWithText("Add Another BedJet").performScrollTo().performClick()
        val other = row("BEDJET-DEMO · 5F2C")
        compose.waitFor(other)
        compose.capture("nearby-bedjets")
        compose.onNode(other).performClick()
        compose.waitFor(hasText("Done"))
        compose.onNodeWithText("Done").performClick()
    }

    @Test
    fun addAndSwitchBedJets() {
        compose.waitFor(hasText("Heat"), timeoutMillis = 10_000)

        // With one BedJet the pill names it and says how it's connected; its settings offer to add another.
        addSecondBedJet(from = pill("BEDJET-DEMO"))

        // Two share a name, so each carries a code from its Bluetooth identity.
        val second = pill("BEDJET-DEMO · 5F2C")
        val first = pill("BEDJET-DEMO · D3A0")
        compose.waitFor(second)
        Thread.sleep(2000)
        compose.onNodeWithText("Heat").performClick()
        compose.waitFor(hasContentDescription("Turns off in"))

        // The pill switches BedJets, and each keeps its own state.
        compose.onNode(second).performClick()
        compose.waitFor(hasText("BEDJET-DEMO · D3A0"))
        compose.capture("bedjet-switcher")
        compose.onNodeWithText("BEDJET-DEMO · D3A0").performClick()
        compose.waitFor(first)
        compose.waitUntilGone(hasContentDescription("Turns off in"))

        compose.onNode(first).performClick()
        compose.waitFor(hasText("BEDJET-DEMO · 5F2C"))
        compose.onNodeWithText("BEDJET-DEMO · 5F2C").performClick()
        compose.waitFor(second)
        compose.waitFor(hasContentDescription("Turns off in"))
        compose.onNodeWithText("Off").performClick()
    }

    @Test
    fun choosesABedJetBeforeAnything() {
        relaunch(demoChoose = true)
        // Nothing is connected or controllable until a BedJet is picked.
        compose.waitFor(hasText("Choose Your BedJet"), timeoutMillis = 10_000)
        compose.onNodeWithText("Remote").assertDoesNotExist()
        val partners = row("BEDJET-DEMO · 5F2C")
        compose.waitFor(partners)
        compose.onNode(row("BEDJET-DEMO · D3A0")).assertExists()
        compose.capture("choose-first")
        compose.onNode(partners).performScrollTo().performClick()

        compose.waitFor(pill("BEDJET-DEMO"))
        compose.waitFor(hasText("Remote") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
    }

    @Test
    fun forgettingTheBedJetOnScreenAsksWhichToShow() {
        compose.waitFor(hasText("Heat"), timeoutMillis = 10_000)
        addSecondBedJet(from = pill("BEDJET-DEMO"))

        compose.waitFor(pill("BEDJET-DEMO · 5F2C"))
        compose.onNode(pill("BEDJET-DEMO · 5F2C")).performClick()
        compose.waitFor(hasText("BedJet Settings"))
        compose.onNodeWithText("BedJet Settings").performClick()
        compose.waitFor(hasText("Done"))
        compose.onNodeWithText("Forget This BedJet").performScrollTo().performClick()

        // The other BedJet is offered rather than shown in its place.
        compose.waitFor(hasText("Choose Your BedJet"))
        val remaining = row("BEDJET-DEMO")
        compose.waitFor(remaining)
        compose.waitFor(row("BEDJET-DEMO · 5F2C"))
        compose.capture("after-forget")
        compose.onNode(remaining).performScrollTo().performClick()
        compose.waitFor(pill("BEDJET-DEMO"))
    }

    @Test
    fun anAbsentBedJetIsNotSwappedForAnother() {
        relaunch(demoFirstUnitAway = true)
        // Lull keeps looking for the chosen BedJet, then says it can't find it, with others right there.
        val notFound = hasContentDescription("BedJet, BEDJET-DEMO, Not found")
        compose.waitFor(notFound, timeoutMillis = 25_000)
        compose.onNode(notFound).performClick()
        compose.waitFor(hasText("Done"))
        compose.onNodeWithText("Choose Another BedJet").performScrollTo()
        compose.capture("not-found")
        compose.onNodeWithText("Choose Another BedJet").performClick()
        val other = row("BEDJET-DEMO · 5F2C")
        compose.waitFor(other)
        compose.onNode(other).performClick()
        compose.waitFor(hasText("Done"))
        compose.onNodeWithText("Done").performClick()

        // Only because it was picked; the missing one is still there to go back to.
        val picked = pill("BEDJET-DEMO · 5F2C")
        compose.waitFor(picked)
        compose.onNode(picked).performClick()
        compose.waitFor(hasText("BEDJET-DEMO · D3A0"))
    }

    @Test
    fun remoteAndDevice() {
        compose.waitFor(hasText("Heat"), timeoutMillis = 10_000)
        Thread.sleep(2000)
        compose.onNodeWithText("Heat").performClick()
        // Turning a mode on asks for the longest run the simulated BedJet allows: 4 hours of heat.
        compose.waitFor(hasContentDescription("Turns off in"))
        compose.waitForSlider("Turns off in", "4 hours")

        // A shorter time picked by hand sticks, and survives a fan change.
        compose.slider("Turns off in").setTo(0f)
        Thread.sleep(3000)
        assertEquals("5 minutes", compose.slider("Turns off in").value())
        compose.slider("Fan").setTo(0.8f)
        Thread.sleep(3000)
        assertEquals("5 minutes", compose.slider("Turns off in").value())

        // Switching mode starts over at that mode's longest run.
        compose.onNodeWithText("Cool").performClick()
        compose.waitForSlider("Turns off in", "12 hours")
        compose.onNodeWithText("Heat").performClick()
        compose.waitForSlider("Turns off in", "4 hours")
        compose.capture("remote-heat")
        compose.onNodeWithText("Off").performClick()

        compose.onNode(hasContentDescription("BedJet,", substring = true)).performClick()
        compose.waitFor(hasText("SAVED ON THE BEDJET"), timeoutMillis = 10_000)
        compose.capture("device")
    }

    @Test
    fun wifiAndFirmwareCheck() {
        compose.waitFor(hasText("Heat"), timeoutMillis = 10_000)
        Thread.sleep(2000)
        compose.onNode(hasContentDescription("BedJet,", substring = true)).performClick()
        compose.waitFor(hasText("Network"), timeoutMillis = 10_000)
        compose.onNodeWithText("Network").performScrollTo().performClick()

        // A demo BedJet's phone is on a pretend network, offered first, and the simulated BedJet joins any network,
        // testing it one step a second.
        compose.waitFor(hasContentDescription("Home, This phone's network"))
        compose.onNode(hasContentDescription("Home, This phone's network")).assertIsSelected()
        compose.onNode(hasContentDescription("Password")).performTextInput("12345678")
        compose.onNodeWithText("Join Network").performClick()
        // What the BedJet is doing takes the button's title, and how it went goes below once the title is back.
        compose.waitUntilGone(hasText("Join Network"))
        compose.waitFor(hasText("Your BedJet joined “Home” and can now check for firmware updates."), timeoutMillis = 15_000)
        compose.onNodeWithText("Join Network").assertExists()
        compose.capture("wifi")

        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitFor(hasText("Home"))
        compose.onNodeWithText("Check for Update").performScrollTo().performClick()
        compose.waitFor(hasText("Check for a firmware update?"))
        compose.capture("firmware-confirm")
        compose.onNodeWithText("Check").performClick()
        // Like a real BedJet 3, the simulated one restarts to check, and the sheet says so rather than losing it.
        compose.waitFor(hasText("Restarting your BedJet to check…"), timeoutMillis = 10_000)
        compose.onNodeWithText("Check for Update").assertDoesNotExist()
        compose.capture("firmware-restarting")
        compose.waitFor(hasText("Your BedJet's firmware is up to date."), timeoutMillis = 15_000)
        compose.onNodeWithText("Check for Update").assertExists()
        // Going back shows the sheet from the top, so bring the result, and the Wi-Fi network below it, into view.
        compose.onNodeWithText("Network").performScrollTo()
        compose.capture("firmware")
    }
}

fun launchLull(
    ephemeral: Boolean = true,
    demoElapsedMinutes: Int = 0,
    demoLabelConnected: Boolean = false,
    demoHistory: Boolean = false,
    demoChoose: Boolean = false,
    demoFirstUnitAway: Boolean = false,
): ActivityScenario<MainActivity> {
    val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
        .putExtra("ephemeral", ephemeral)
        .putExtra("demoHistory", demoHistory)
        .putExtra("demoElapsedMinutes", demoElapsedMinutes)
        .putExtra("demoLabelConnected", demoLabelConnected)
        .putExtra("demoChoose", demoChoose)
        .putExtra("demoFirstUnitAway", demoFirstUnitAway)
    return ActivityScenario.launch(intent)
}

/** A node whose spoken label begins with this, so "BEDJET-DEMO," doesn't also find "BEDJET-DEMO · 5F2C,". */
fun labelStartsWith(prefix: String) = SemanticsMatcher("label starts with \"$prefix\"") { node ->
    node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.startsWith(prefix) }
}

fun ComposeTestRule.waitFor(matcher: SemanticsMatcher, timeoutMillis: Long = 5_000) {
    waitUntil(timeoutMillis) { onAllNodes(matcher, useUnmergedTree = false).fetchSemanticsNodes().isNotEmpty() }
}

fun ComposeTestRule.waitUntilGone(matcher: SemanticsMatcher, timeoutMillis: Long = 5_000) {
    waitUntil(timeoutMillis) { onAllNodes(matcher).fetchSemanticsNodes().isEmpty() }
}

fun ComposeTestRule.slider(title: String): SemanticsNodeInteraction =
    onNode(hasContentDescription(title) and SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))

fun SemanticsNodeInteraction.label(): String =
    fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: ""

fun SemanticsNodeInteraction.value(): String? =
    fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

/** Moves a slider to a point along its track, 0 at the start and 1 at the end, like XCUIElement.adjust. */
fun SemanticsNodeInteraction.setTo(fraction: Float) {
    val range = fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].range
    performSemanticsAction(SemanticsActions.SetProgress) { it(range.start + (range.endInclusive - range.start) * fraction) }
}

fun ComposeTestRule.waitForSlider(title: String, value: String, timeoutMillis: Long = 5_000) {
    waitUntil(timeoutMillis) { runCatching { slider(title).value() == value }.getOrDefault(false) }
}

/**
 * Saves the screen to the app's files, where `adb pull` can collect it. Compose only draws new frames
 * when a test syncs with it, so let it catch up first.
 */
fun ComposeTestRule.capture(name: String, folder: String = "screens") {
    waitForIdle()
    Thread.sleep(300)
    waitForIdle()
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
    val directory = File(instrumentation.targetContext.getExternalFilesDir(null), folder).apply { mkdirs() }
    File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
}
