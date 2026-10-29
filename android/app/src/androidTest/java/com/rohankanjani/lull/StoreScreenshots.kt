package com.rohankanjani.lull

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Captures the screens used for the Google Play listing, the same five as the App Store's. Runs only when asked:
 * `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.storeShots=true`,
 * then `adb pull /sdcard/Android/data/com.rohankanjani.lull/files/store`. Mirrors StoreScreenshots.swift.
 */
@RunWith(AndroidJUnit4::class)
class StoreScreenshots {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun captureListingScreens() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("storeShots") == "true")
        cleanStatusBar()
        // A night that is already a few hours in shows more than one that has just begun.
        launchLull(ephemeral = true, demoElapsedMinutes = 200, demoLabelConnected = true).use {
            compose.waitFor(hasText("Heat"), timeoutMillis = 10_000)
            Thread.sleep(2000)
            compose.onNodeWithText("Heat").performClick()
            compose.showTimer()
            compose.waitForSlider("Turns off in", "4 hours")
            Thread.sleep(6000)
            compose.capture("01-remote", "store")

            compose.onNodeWithText("Schedule").performClick()
            compose.waitFor(hasContentDescription("Start Night"))
            compose.capture("02-schedule", "store")

            compose.onNode(hasContentDescription("Warm Welcome,", substring = true)).performScrollTo().performClick()
            compose.waitFor(hasText("Add Phase"))
            compose.capture("03-editor", "store")

            compose.onNode(hasContentDescription("Heat,", substring = true) and hasContentDescription("30 minutes", substring = true)).performClick()
            compose.waitFor(hasText("Remove Phase"))
            Thread.sleep(800)
            compose.capture("04-phase", "store")
            compose.onNodeWithText("Done").performClick()
            compose.waitUntilGone(hasText("Remove Phase"))
            compose.onNodeWithText("Cancel").performClick()

            compose.waitFor(hasContentDescription("Start Night"))
            compose.onNodeWithContentDescription("Start Night").performClick()
            compose.waitFor(hasText("End Night"), timeoutMillis = 10_000)
            Thread.sleep(2000)
            compose.capture("05-running", "store")
        }
        shell("am broadcast -a com.android.systemui.demo -e command exit")
    }

    /** A 9:41 clock, full signal and battery, no notifications: the same status bar as the App Store shots. */
    private fun cleanStatusBar() {
        shell("settings put global sysui_demo_allowed 1")
        val demo = "am broadcast -a com.android.systemui.demo -e command"
        shell("$demo enter")
        shell("$demo clock -e hhmm 0941")
        shell("$demo battery -e level 100 -e plugged false")
        shell("$demo network -e wifi show -e level 4 -e mobile show -e datatype none -e level 4")
        shell("$demo notifications -e visible false")
    }

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).close()
        Thread.sleep(200)
    }
}
