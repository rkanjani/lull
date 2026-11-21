package com.rohankanjani.lull.wear

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rohankanjani.lull.WatchCommand
import com.rohankanjani.lull.WatchMessages
import com.rohankanjani.lull.WatchReply
import com.rohankanjani.lull.WidgetBedJet
import com.rohankanjani.lull.WidgetSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What the watch shows and asks for. Everything comes from Lull on the phone: what the widgets show, handed over
 * whenever it changes, and the errands it runs. Between handovers the watch plays the night out by the clock, as the
 * widgets do. Mirrors WatchModel.swift.
 *
 * @param scope Where errands run. It outlives the page that asked, as an unstructured Task does on the Apple Watch, so
 *   leaving a page mid-send doesn't cancel the send.
 */
class WatchModel(val phone: Phone, private val store: WatchStore, val scope: CoroutineScope) {
    /** What Lull on the phone last handed over. */
    var snapshot by mutableStateOf(store.load() ?: WidgetSnapshot())
        private set

    private var picked by mutableStateOf(store.picked)
    private var showsTime by mutableStateOf(store.remoteShowsTime)

    /** Whether the Remote page shows when air turns off as a time of day rather than a duration. */
    var remoteShowsTime: Boolean
        get() = showsTime
        set(value) {
            showsTime = value
            store.remoteShowsTime = value
        }
    /** The BedJet picked on the watch, by each side of the bed. None picked follows the one on screen on the phone. */
    var pickedID: String?
        get() = picked
        set(value) {
            picked = value
            store.picked = value
            store.save(snapshot)
        }

    var isReachable by mutableStateOf(false)
        private set
    /** The errand on its way, until the phone says how it went. */
    var pending by mutableStateOf<WatchCommand?>(null)
        private set
    var errorMessage by mutableStateOf<String?>(null)

    /** Counts errands sent, so a wait that runs out only gives up on the errand it was for. */
    private var sent = 0
    private var isRefreshing = false

    init {
        phone.onSnapshot = ::receive
        phone.onReply = ::receive
        phone.onReachabilityChange = ::reachabilityChanged
        phone.activate()
        isReachable = phone.isReachable
    }

    // What's shown

    /**
     * The BedJet the watch shows: the one picked here, or else the one on screen on the phone, or the first Lull knows
     * when none is. One picked and forgotten in Lull since goes back to the one on screen, as the tile and complication
     * do, rather than leaving the watch blank with nothing to pick.
     */
    val bedJet: WidgetBedJet? get() = WatchModel.bedJet(snapshot, picked)

    /** What the pages show: the BedJet's tonight, night and history. */
    val shown: WidgetSnapshot get() = bedJet?.let { snapshot.showing(it.id) } ?: snapshot

    val hasSeveralBedJets: Boolean get() = snapshot.hasSeveralBedJets

    /** Why the controls can't be used just now, if they can't. */
    val unavailable: String? get() = if (isReachable) null else "Your phone isn't nearby. Lull reaches your BedJet through it."

    // Errands

    /** Hands the phone an errand, and waits to hear how it went. */
    suspend fun send(command: WatchCommand) {
        if (pending != null) return
        unavailable?.let {
            errorMessage = it
            return
        }
        pending = command
        errorMessage = null
        val errand = ++sent
        try {
            phone.send(command)?.let {
                receive(it)
                return
            }
        } catch (error: CancellationException) {
            // The watch app is closing: nothing is left to say it to.
            pending = null
            throw error
        } catch (error: Exception) {
            pending = null
            errorMessage = "Couldn't reach Lull on your phone. Try again."
            return
        }
        scope.launch {
            delay(patience)
            if (sent != errand || pending == null) return@launch
            pending = null
            errorMessage = WatchMessages.TOO_LATE
        }
    }

    /**
     * Whether an errand like this one, for the same BedJet, is on its way, whatever it's set to: the Remote's Apply
     * stays busy while its settings are turned.
     */
    fun isSending(command: WatchCommand): Boolean {
        val pending = pending ?: return false
        return pending::class == command::class && pending.bedJet == command.bedJet
    }

    /**
     * Asks the phone what the BedJets are doing, at most every quarter of an hour. Nothing is shown for it. What the
     * phone hands over on its own can't stand in for this, as on the Apple Watch, where it may be old.
     */
    fun refreshIfStale(now: Long = System.currentTimeMillis()) {
        if (!isReachable || pending != null || isRefreshing) return
        val askedAt = store.askedAt
        if (askedAt != null && now - askedAt < 15 * 60_000L) return
        isRefreshing = true
        scope.launch {
            // Only a refresh that went counts: one that couldn't is tried again the next time the phone is reachable.
            runCatching { phone.send(WatchCommand.Refresh) }.onSuccess { store.askedAt = now }
            isRefreshing = false
        }
    }

    /** The phone is usually found a moment after the watch app opens, which is when a refresh can go. */
    private fun reachabilityChanged() {
        isReachable = phone.isReachable
        refreshIfStale()
    }

    fun receive(snapshot: WidgetSnapshot) {
        this.snapshot = snapshot
        store.save(snapshot)
    }

    fun receive(reply: WatchReply) {
        // A late answer to an errand already given up on: what changed shows in what the phone hands over.
        if (pending == null) return
        pending = null
        if (!reply.ok) errorMessage = reply.message ?: "Something went wrong."
    }

    /**
     * Start Night from a tile or the launcher's shortcut. Nothing named means the BedJet the watch shows. They usually open
     * the app, so the phone may not be found yet: they wait a moment for it rather than saying it isn't nearby.
     */
    fun startNight(bedJet: String? = null) {
        scope.launch {
            waitForPhone()
            send(WatchCommand.StartNight(bedJet ?: this@WatchModel.bedJet?.id))
        }
    }

    private suspend fun waitForPhone() {
        repeat((findingPhone / 100).toInt()) {
            if (isReachable) return
            delay(100)
        }
    }

    companion object {
        /** Shared with the tile and complication, so they show the BedJet the watch does. */
        fun bedJet(snapshot: WidgetSnapshot, picked: String?): WidgetBedJet? =
            picked?.let { id -> snapshot.bedJets.firstOrNull { it.id == id } } ?: snapshot.usualBedJet

        /** How long to wait for an errand before saying the phone didn't answer, in milliseconds. */
        var patience = WatchMessages.PATIENCE_MILLIS
        /** How long a tile or shortcut that opened the app waits for the phone to be found, which takes a moment. */
        var findingPhone = 5_000L
    }
}
