package com.rohankanjani.lull

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A running BedJet shows in a Live Update notification, and goes when it stops. The twin of LiveActivityTests.swift.
 * Live Updates are left out of ephemeral launches, so the flows elsewhere don't leave them behind; these launch as
 * Lull normally does, and turn the BedJet off again before they finish.
 */
@RunWith(AndroidJUnit4::class)
class LiveUpdateTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun launch() {
        // Lull asks the first time a BedJet runs; granted here so the system's prompt doesn't cover the screen.
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        scenario = launchLull(ephemeral = false)
    }

    @After
    fun close() {
        scenario.close()
        // A failed run would otherwise leave its notification up for the next test.
        context.getSystemService(NotificationManager::class.java).cancelAll()
    }

    /** Lull's Live Update, if one is up, rather than the summary of its group. */
    private fun liveUpdate(): Notification? =
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .firstOrNull { it.packageName == context.packageName && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            ?.notification

    private val Notification.title get() = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private val Notification.text get() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

    @Test
    fun airFromTheRemoteShowsUntilTurnedOff() {
        compose.waitFor(hasText("Heat"), timeoutMillis = 10_000)
        Thread.sleep(2000)
        compose.onNodeWithText("Heat").performClick()

        compose.waitUntil(10_000) { liveUpdate()?.title == "Heating" }
        assertTrue(liveUpdate()!!.text.contains("Turns off at"))

        compose.onNodeWithText("Off").performClick()
        compose.waitUntil(10_000) { liveUpdate() == null }
    }

    @Test
    fun aNightShowsUntilItEnds() {
        compose.onNodeWithText("Schedule").performClick()
        compose.waitFor(hasContentDescription("Start Night") and isEnabled(), timeoutMillis = 10_000)
        compose.onNodeWithContentDescription("Start Night").performClick()
        compose.waitFor(hasText("End Night"), timeoutMillis = 10_000)

        // Titled with tonight's schedule, with its phases along a progress bar.
        compose.waitUntil(10_000) { liveUpdate()?.text?.contains("Ends") == true }
        val night = liveUpdate()!!
        assertEquals(Notification.ProgressStyle::class.java.name, night.extras.getString(Notification.EXTRA_TEMPLATE))

        compose.onNodeWithText("End Night").performClick()
        compose.waitUntil(10_000) { liveUpdate() == null }
    }
}
