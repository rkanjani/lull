package com.rohankanjani.lull.wear

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rohankanjani.lull.LullTab
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Start Night from Lull's own tile or shortcut starts the night, and the same request from anywhere else only opens the
 * watch app. Android only: on the Apple Watch, starting a night takes an App Intent, which other apps can't run.
 */
@RunWith(AndroidJUnit4::class)
class StartNightTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<WearActivity>
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** The sample BedJet with no night running, so starting one shows. */
    private val otherSide = "6B0D4C2E-1A55-4D8E-9C71-0F2A7E3B5D11"

    @After
    fun close() {
        scenario.close()
    }

    /** Start Night for the other side of the bed, with a pretend phone and nothing kept, as a tile or shortcut asks. */
    private fun launch(token: String?) {
        val intent = Intent(context, WearActivity::class.java)
            .setAction(WearActivity.ACTION_START_NIGHT)
            .putExtra(WearActivity.EXTRA_LINK, LullTab.SCHEDULE.link(otherSide))
            .putExtra("demo", true)
            .putExtra("ephemeral", true)
        if (token != null) intent.putExtra(WearActivity.EXTRA_TOKEN, token)
        scenario = ActivityScenario.launch(intent)
    }

    private fun shows(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun lullsOwnTileOrShortcutStartsTheNight() {
        launch(StartNightToken.get(context))
        compose.waitUntil(10_000) { shows("End Night") }
    }

    @Test
    fun anotherAppOnlyOpensTheWatchApp() {
        launch(token = "not Lull's")
        compose.waitUntil(10_000) { shows("Start Night") }
        // Longer than the pretend phone takes to start a night it was asked for.
        Thread.sleep(2_000)
        compose.waitForIdle()
        assertTrue("Start Night should still be offered", shows("Start Night"))
        assertFalse("No night should have started", shows("End Night"))
    }
}
