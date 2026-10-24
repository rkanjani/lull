package com.rohankanjani.lull

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rohankanjani.lull.bedjetkit.Generation
import com.rohankanjani.lull.bedjetkit.Mode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two running BedJets share one Live Update, as the system shows it. The twin of LiveActivitiesTests.swift.
 * On a phone, since how Android bundles notifications can't be seen from a unit test.
 */
@RunWith(AndroidJUnit4::class)
class LiveNotificationsTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val live by lazy { LiveNotifications(context) }

    @Before
    fun allowNotifications() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun takeItDown() {
        live.show(emptyList(), useFahrenheit = false)
        manager.cancelAll()
    }

    @Test
    fun bothSidesShareOne() {
        val now = System.currentTimeMillis()
        val night = ActiveRun(Schedule.templates[0], startedAt = now - 200 * 60_000L)
        live.show(
            listOf(
                "my-side" to LiveNotifications.Wanted.Show(
                    LiveSide("my-side", LiveStatus.Air(Mode.HEAT, 60, 40, now + 3_600_000L), "My side", Generation.V3),
                ),
                "other-side" to LiveNotifications.Wanted.Show(
                    LiveSide("other-side", LiveStatus.Night(night), "Other side", Generation.V3),
                ),
            ),
            useFahrenheit = false,
            now = now,
        )
        // Android bundles an app's notifications behind its own generic icon, some a few seconds after they're posted.
        Thread.sleep(10_000)

        // One Live Update, under one icon made of both sides' modes, with a line for each: Heat on one side, Cooling through
        // the night on the other.
        val posted = manager.activeNotifications.filter { it.packageName == context.packageName }
        assertEquals(1, posted.size)
        val update = posted.single()
        assertNull("it was bundled", update.overrideGroupKey)
        assertEquals(0, update.notification.flags and Notification.FLAG_GROUP_SUMMARY)
        val text = update.notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
        val lines = text.lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0], lines[0].startsWith("My side: Heating"))
        assertTrue(lines[1], lines[1].startsWith("Other side: Cool Drift · Cooling"))
    }
}
