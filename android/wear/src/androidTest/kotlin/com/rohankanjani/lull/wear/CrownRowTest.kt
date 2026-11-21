package com.rohankanjani.lull.wear

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performRotaryScrollInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Remote's settings turn with the rotating side button or bezel once tapped, and stop at the ends of what the BedJet
 * takes, as the Apple Watch's do with the Digital Crown. The twin of `CrownRowUITests.swift`.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class CrownRowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<WearActivity>

    @Before
    fun launch() {
        // A pretend phone with the sample BedJets, and nothing kept.
        val intent = Intent(ApplicationProvider.getApplicationContext(), WearActivity::class.java)
            .putExtra("demo", true)
            .putExtra("ephemeral", true)
        scenario = ActivityScenario.launch(intent)
    }

    @After
    fun close() {
        scenario.close()
    }

    @Test
    fun theBezelTurnsATappedSetting() {
        // The Remote is the page after Tonight, past the end of Tonight's own scrolling. Every page is kept, so this
        // waits for its heading to be on screen rather than for its rows to be there.
        val heading = hasText("Remote") and isHeading()
        val pages = compose.onNode(hasScrollToIndexAction() and hasAnyDescendant(heading))
        repeat(4) { if (!compose.onNode(heading).isDisplayed()) pages.performTouchInput { swipeUp() } }
        val temperature = compose.onNodeWithContentDescription("Temperature").performScrollTo()
        val start = temperature.value()

        temperature.performClick()
        // About a notch of the bezel.
        temperature.performRotaryScrollInput { rotateToScrollVertically(60f) }
        assertNotEquals(start, temperature.value())

        // No lower than the BedJet takes: turning further down changes nothing.
        temperature.performRotaryScrollInput { rotateToScrollVertically(-5000f) }
        val lowest = temperature.value()
        temperature.performRotaryScrollInput { rotateToScrollVertically(-100f) }
        assertEquals(lowest, temperature.value())
        assertNotEquals(start, lowest)
    }

    private fun SemanticsNodeInteraction.value(): String? =
        fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
}
