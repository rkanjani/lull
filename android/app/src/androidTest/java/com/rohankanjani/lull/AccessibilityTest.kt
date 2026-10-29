package com.rohankanjani.lull

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Walks every screen at the default and the largest text size: Google's Accessibility Test Framework on each
 * (what TalkBack reads and reaches, touch target sizes, contrast, text that can't grow), plus what TalkBack says
 * for the key controls. Mirrors AccessibilityTests.swift.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @After
    fun restoreTextSize() {
        shell("settings put system font_scale 1.0")
    }

    @Test
    fun everyScreen() = walk(textSize = 1f)

    /** Android's largest text size. Layouts that can't hold it stack instead, as on iOS. */
    @Test
    fun everyScreenAtLargestTextSize() = walk(textSize = 2f)

    private fun walk(textSize: Float) {
        shell("settings put system font_scale $textSize")
        compose.enableAccessibilityChecks()
        launchLull(ephemeral = true, demoHistory = true).use {
            compose.waitFor(hasText("Heat"), timeoutMillis = 10_000)
            compose.waitFor(hasContentDescription("Off, room", substring = true), timeoutMillis = 10_000)
            audit()

            compose.onNodeWithText("Heat").performScrollTo().performClick()
            compose.showTimer()
            compose.waitForSlider("Turns off in", "4 hours")
            compose.onNode(hasContentDescription("Heating, set to", substring = true)).assertExists()
            audit()
            // Shown as a time: the option reads as selected, the time with its name, then how long is left.
            compose.onNodeWithText("Time").performScrollTo().performClick()
            compose.waitFor(hasContentDescription("Turns off at, ", substring = true))
            compose.onNodeWithText("Time").assertIsSelected()
            compose.onNode(hasText("In ", substring = true)).assertExists()
            audit()
            // Back to the default for the rest of the walk and later launches.
            compose.onNodeWithText("Timer").performScrollTo().performClick()
            compose.waitFor(hasContentDescription("Turns off in"))
            compose.onNodeWithText("Off").performScrollTo().performClick()

            // At the largest text sizes the Remote runs past the screen, so its top may have scrolled away.
            compose.onNode(hasContentDescription("BedJet,", substring = true)).performScrollTo().performClick()
            compose.waitFor(hasText("Done"))
            audit()
            // BedJets nearby, each row read with its kind and signal.
            compose.onNodeWithText("Add Another BedJet").performScrollTo().performClick()
            compose.waitFor(hasContentDescription("BEDJET-DEMO · 5F2C, BedJet 3, fair signal"))
            audit()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitFor(hasText("Done"))
            compose.onNodeWithText("Done").performClick()

            compose.onNodeWithText("Schedule").performClick()
            compose.waitFor(hasContentDescription("Start Night") and isEnabled(), timeoutMillis = 10_000)
            compose.onNode(hasContentDescription("Night timeline", substring = true)).assertExists()
            compose.onNodeWithContentDescription("Edit Cool Drift").assertExists()
            audit()
            compose.onNodeWithContentDescription("Start Night").performScrollTo().performClick()
            compose.waitFor(hasText("End Night"), timeoutMillis = 10_000)
            compose.onNode(hasContentDescription("Cooling,", substring = true) and hasContentDescription("left", substring = true)).assertExists()
            audit()
            compose.onNodeWithText("End Night").performScrollTo().performClick()
            compose.waitFor(hasContentDescription("Start Night"), timeoutMillis = 10_000)

            compose.onNode(hasContentDescription("Warm Welcome,", substring = true)).performScrollTo().performClick()
            compose.waitFor(hasText("Add Phase"))
            compose.onNode(hasContentDescription("Schedule name", substring = true)).assertExists()
            audit()
            val second = hasContentDescription("Phase 2, Heat,", substring = true) and hasContentDescription("30 minutes, starts", substring = true)
            compose.onNode(second).performScrollTo().performClick()
            compose.waitFor(hasText("Remove Phase"))
            assertEquals("30 minutes", compose.slider("Duration").value())
            audit()
            compose.onNodeWithText("Done").performClick()
            compose.waitUntilGone(hasText("Remove Phase"))
            // The night ends at a wake time or runs from the usual bedtime, chosen as the Remote chooses Timer or Time. With a
            // wake time the editor shows it under the usual bedtime, and the phases still read when they start on a usual night.
            compose.onNodeWithText("Bedtime").assertIsSelected()
            compose.onNode(hasContentDescription("Wake time, ", substring = true)).assertDoesNotExist()
            compose.onNodeWithText("Wake time").performScrollTo().performClick()
            compose.waitFor(hasContentDescription("Wake time, ", substring = true))
            compose.onNode(hasContentDescription("Usual bedtime, ", substring = true)).assertExists()
            compose.onAllNodes(hasContentDescription("Phase ", substring = true) and hasContentDescription(", starts ", substring = true))
                .assertCountEquals(4)
            val last = hasContentDescription("Phase 4,", substring = true)
            // The starter's closing warm-up keeps its length, read in its label.
            compose.onNode(last and hasContentDescription(", fixed length", substring = true)).assertExists()
            audit()
            // Its sheet has a switch to keep its length, read as one control.
            compose.onNode(last).performScrollTo().performClick()
            compose.waitFor(hasText("Fixed length") and isToggleable())
            // At the largest text sizes the sheet scrolls to reach it.
            compose.onNode(hasText("Fixed length") and isToggleable()).performScrollTo()
            audit()
            compose.onNode(hasText("Fixed length") and isToggleable()).performClick()
            compose.onNodeWithText("Done").performScrollTo().performClick()
            compose.waitUntilGone(hasText("Remove Phase"))
            compose.waitFor(hasContentDescription("Phase 4,", substring = true) and !hasContentDescription(", fixed length", substring = true))
            // Back on Bedtime, only the usual bedtime.
            compose.onNodeWithText("Bedtime").performScrollTo().performClick()
            compose.waitUntilGone(hasContentDescription("Wake time, ", substring = true))
            compose.onNodeWithText("Cancel").performClick()

            compose.onNodeWithText("History").performClick()
            // A night without a schedule is summed up by its times, read as a range rather than a dash.
            val night = hasTestTag("Night") and hasContentDescription(" to ", substring = true)
            compose.waitFor(night)
            assertEquals("Collapsed", compose.onNode(night).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
            compose.onNode(night).performScrollTo().performClick()
            compose.waitFor(hasContentDescription("Turned off", substring = true))
            assertEquals("Expanded", compose.onNode(night).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
            audit()
        }
    }

    /** Runs the checks on every window showing, so a sheet and the screen beneath it are both covered. */
    private fun audit() {
        compose.waitForIdle()
        val roots = compose.onAllNodes(isRoot())
        roots.fetchSemanticsNodes().indices.forEach { roots[it].tryPerformAccessibilityChecks() }
    }

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).close()
        Thread.sleep(500)
    }
}

