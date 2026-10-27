package com.rohankanjani.lull.wear

import com.rohankanjani.lull.LiveStatus
import com.rohankanjani.lull.RemotePreset
import com.rohankanjani.lull.RemoteSetting
import com.rohankanjani.lull.WatchCommand
import com.rohankanjani.lull.WidgetSnapshot
import com.rohankanjani.lull.bedjetkit.BedJetError
import com.rohankanjani.lull.bedjetkit.Mode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * The watch app, against a pretend phone. The watch only ever asks Lull on the phone, which reaches the BedJet, so
 * this is everything the watch decides for itself. Mirrors WatchModelTests.swift.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WatchModelTest {
    private class MemoryStore : WatchStore {
        override var picked: String? = null
        override var askedAt: Long? = null
        override fun load(): WidgetSnapshot? = null
        override fun save(snapshot: WidgetSnapshot) {}
    }

    private val sample = WidgetSnapshot.preview()
    private val mine get() = sample.bedJets[0]
    private val theirs get() = sample.bedJets[1]
    private lateinit var store: MemoryStore

    @Before
    fun setUp() {
        store = MemoryStore()
        WatchModel.patience = 30_000
    }

    private fun TestScope.phone() = DemoPhone(sample, this).apply { delayMillis = null }

    private fun TestScope.launch(phone: DemoPhone) = WatchModel(phone, store, this)

    @Test
    fun showsWhatThePhoneHandsOver() = runTest {
        val model = launch(phone())
        assertEquals(sample, model.snapshot)
        // Nothing picked here: the BedJet on screen on the phone.
        assertEquals(mine.id, model.bedJet?.id)
        assertEquals("Cool Drift", model.shown.run(System.currentTimeMillis())?.schedule?.name)
    }

    @Test
    fun anErrandWaitsForThePhoneThenShowsWhatChanged() = runTest {
        val phone = phone()
        val model = launch(phone)
        model.send(WatchCommand.EndNight(mine.id))
        assertEquals(WatchCommand.EndNight(mine.id), model.pending)
        assertEquals(listOf<WatchCommand>(WatchCommand.EndNight(mine.id)), phone.received)
        // One at a time.
        model.send(WatchCommand.StartNight(mine.id))
        assertEquals(listOf<WatchCommand>(WatchCommand.EndNight(mine.id)), phone.received)

        phone.finish()
        assertNull(model.pending)
        assertNull(model.shown.run(System.currentTimeMillis()))
        assertNull(model.errorMessage)
    }

    @Test
    fun anErrandStaysBusyWhileItsSettingsChange() = runTest {
        val phone = phone()
        val model = launch(phone)
        val cool = RemotePreset(Mode.COOL, minutes = 30, temperature = 44, fanPercent = 40)
        model.send(WatchCommand.Remote(cool, mine.id))
        // The Remote's Apply, rebuilt as its settings are turned.
        assertTrue(model.isSending(WatchCommand.Remote(RemotePreset(Mode.HEAT, minutes = 60, temperature = 70, fanPercent = 50), mine.id)))
        assertFalse(model.isSending(WatchCommand.Remote(cool, theirs.id)))
        assertFalse(model.isSending(WatchCommand.StartNight(mine.id)))
        phone.finish()
        assertFalse(model.isSending(WatchCommand.Remote(cool, mine.id)))
    }

    @Test
    fun thePretendPhoneSetsOnlyWhatWasChanged() = runTest {
        val phone = phone()
        val model = launch(phone)
        model.send(WatchCommand.Remote(RemotePreset(Mode.COOL, minutes = 30, temperature = 44, fanPercent = 40), mine.id))
        phone.finish()
        val endsAt = liveAir(mine.id, model)!!.endsAt

        model.send(WatchCommand.Remote(RemotePreset(Mode.COOL, minutes = 90, temperature = 50, fanPercent = 60), mine.id, setOf(RemoteSetting.FAN)))
        phone.finish()
        val air = liveAir(mine.id, model)!!
        assertEquals(60, air.fanPercent)
        assertEquals(44, air.temperature)
        assertEquals(endsAt, air.endsAt)
    }

    private fun liveAir(id: String, model: WatchModel): LiveStatus.Air? =
        model.snapshot.bedJets.firstOrNull { it.id == id }?.live(System.currentTimeMillis()) as? LiveStatus.Air

    @Test
    fun whatWentWrongOnThePhoneIsSaid() = runTest {
        val phone = phone()
        val model = launch(phone)
        phone.failure = BedJetError.NotConnected.message
        model.send(WatchCommand.StartNight(mine.id))
        phone.finish()
        assertNull(model.pending)
        assertEquals(BedJetError.NotConnected.message, model.errorMessage)
    }

    @Test
    fun withThePhoneAwayNothingIsSentAndItSaysWhy() = runTest {
        val phone = phone().apply { isReachable = false }
        val model = launch(phone)
        assertEquals("Your phone isn't nearby. Lull reaches your BedJet through it.", model.unavailable)
        model.send(WatchCommand.StartNight(mine.id))
        assertTrue(phone.received.isEmpty())
        assertNull(model.pending)
        // What was last handed over still plays out by the clock.
        assertNotNull(model.shown.run(System.currentTimeMillis()))
    }

    @Test
    fun anErrandThePhoneNeverAnswersGivesUp() = runTest {
        WatchModel.patience = 200
        val model = launch(phone())
        model.send(WatchCommand.StartNight(mine.id))
        advanceTimeBy(201)
        assertNull(model.pending)
        assertEquals("Lull on your phone didn't answer in time. Open it there to check on your BedJet.", model.errorMessage)
    }

    @Test
    fun asksForAnUpdateAtMostEveryQuarterHour() = runTest {
        val phone = phone()
        val model = launch(phone)
        val now = System.currentTimeMillis()
        model.refreshIfStale(now)
        runCurrent()
        assertEquals(listOf<WatchCommand>(WatchCommand.Refresh), phone.received)
        // Asked quietly: nothing waits for it.
        assertNull(model.pending)
        // What the phone hands over on its own doesn't count as an answer, since it may be old.
        model.receive(sample)
        model.refreshIfStale(now + 10 * 60_000L)
        runCurrent()
        assertEquals(listOf<WatchCommand>(WatchCommand.Refresh), phone.received)

        model.refreshIfStale(now + 20 * 60_000L)
        runCurrent()
        assertEquals(listOf<WatchCommand>(WatchCommand.Refresh, WatchCommand.Refresh), phone.received)
    }

    @Test
    fun aRefreshDoesntAnswerAnErrandSentWhileItRan() = runTest {
        val phone = phone()
        val model = launch(phone)
        model.refreshIfStale()
        runCurrent()
        model.send(WatchCommand.StartNight(mine.id))
        phone.finish(1)
        assertEquals(WatchCommand.StartNight(mine.id), model.pending)
        phone.finish()
        assertNull(model.pending)
    }

    @Test
    fun aRefreshThatDidntGoIsTriedAgain() = runTest {
        val phone = phone()
        val model = launch(phone)
        phone.delivers = false
        model.refreshIfStale()
        runCurrent()
        assertTrue(phone.received.isEmpty())

        phone.delivers = true
        model.refreshIfStale()
        runCurrent()
        assertEquals(listOf<WatchCommand>(WatchCommand.Refresh), phone.received)
    }

    @Test
    fun asksForAnUpdateOnceThePhoneIsReachable() = runTest {
        val phone = phone().apply { isReachable = false }
        val model = launch(phone)
        model.refreshIfStale()
        runCurrent()
        assertTrue(phone.received.isEmpty())

        phone.isReachable = true
        runCurrent()
        assertEquals(listOf<WatchCommand>(WatchCommand.Refresh), phone.received)
        assertTrue(model.isReachable)
    }

    @Test
    fun aWaitThatRunsOutOnlyGivesUpOnItsOwnErrand() = runTest {
        WatchModel.patience = 1_000
        val phone = phone()
        val model = launch(phone)
        model.send(WatchCommand.StartNight(mine.id))
        phone.finish()
        // The same errand again, before the first one's wait runs out, and checked once it has.
        advanceTimeBy(500)
        model.send(WatchCommand.StartNight(mine.id))
        advanceTimeBy(750)
        assertEquals(WatchCommand.StartNight(mine.id), model.pending)
        assertNull(model.errorMessage)
    }

    @Test
    fun aLateAnswerToAnErrandGivenUpOnIsIgnored() = runTest {
        WatchModel.patience = 100
        val phone = phone()
        val model = launch(phone)
        phone.failure = BedJetError.NotConnected.message
        model.send(WatchCommand.StartNight(mine.id))
        advanceTimeBy(101)
        assertNull(model.pending)
        model.errorMessage = null
        phone.finish()
        assertNull(model.errorMessage)
    }

    @Test
    fun thePickedBedJetSurvivesARelaunch() = runTest {
        val model = launch(phone())
        model.pickedID = theirs.id
        assertEquals(theirs.id, model.bedJet?.id)
        assertEquals(theirs.tonight?.name, model.shown.tonight?.name)
        assertNull(model.shown.run(System.currentTimeMillis()))

        val relaunched = launch(phone())
        assertEquals(theirs.id, relaunched.bedJet?.id)
    }

    @Test
    fun aPickedBedJetForgottenSinceGoesBackToTheOneOnScreen() = runTest {
        val model = launch(phone())
        model.pickedID = UUID.randomUUID().toString()
        // As the tile and complication do, rather than a blank watch with nothing to pick when only one BedJet is left.
        assertEquals(mine.id, model.bedJet?.id)
        assertEquals(mine.tonight?.name, model.shown.tonight?.name)
    }

    @Test
    fun theTileAndComplicationShowTheBedJetPickedHere() {
        // Followed by the phone: the one on screen there, as the watch shows it.
        assertEquals(sample.showing(mine.id), DeviceStore.forTileAndComplication(sample, null))
        // Picked here: that BedJet's own tonight and night, not the one on screen on the phone.
        val saved = DeviceStore.forTileAndComplication(sample, theirs.id)
        assertEquals(theirs.id, saved.onScreen)
        assertNull(saved.run(System.currentTimeMillis()))
        assertEquals(theirs.tonight, saved.tonight)
        assertEquals("Other side · Tonight", saved.headed("Tonight"))
        // Forgotten since: the one on screen on the phone, as the watch goes back to.
        assertEquals(sample.showing(mine.id), DeviceStore.forTileAndComplication(sample, UUID.randomUUID().toString()))
        // None on screen on the phone, as once it forgets the one that was: the first it knows, as the watch shows.
        val noneOnScreen = sample.copy(onScreen = null, tonight = null, run = null)
        val first = DeviceStore.forTileAndComplication(noneOnScreen, null)
        assertEquals(mine.id, first.onScreen)
        assertEquals(mine.tonight, first.tonight)
        assertEquals("Cool Drift", first.run(System.currentTimeMillis())?.schedule?.name)
        assertEquals("My side · Tonight", first.headed("Tonight"))
    }

    @Test
    fun startNightFromATileWaitsForThePhoneToBeFound() = runTest {
        // Opened by the tile: the phone is found a moment after the app starts.
        val phone = phone().apply { isReachable = false }
        val model = launch(phone)
        model.startNight()
        runCurrent()
        advanceTimeBy(300)
        phone.isReachable = true
        advanceTimeBy(150)
        runCurrent()
        assertTrue(WatchCommand.StartNight(mine.id) in phone.received)
        assertNull(model.errorMessage)
    }

    @Test
    fun startNightFromAShortcutGoesToTheBedJetShownHere() = runTest {
        val phone = phone()
        val model = launch(phone)
        model.pickedID = theirs.id
        model.startNight()
        runCurrent()
        assertEquals(listOf<WatchCommand>(WatchCommand.StartNight(theirs.id)), phone.received)
    }
}
