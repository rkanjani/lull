package com.rohankanjani.lull

import com.rohankanjani.lull.bedjetkit.BedJetError
import com.rohankanjani.lull.bedjetkit.BedJetLink
import com.rohankanjani.lull.bedjetkit.DemoLink
import com.rohankanjani.lull.bedjetkit.DeviceStatus
import com.rohankanjani.lull.bedjetkit.Generation
import com.rohankanjani.lull.bedjetkit.LinkState
import com.rohankanjani.lull.bedjetkit.takes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A BedJet for tests. It speaks like the simulated one, but connects only when allowed, records every command,
 * can hold a command in flight, and keeps its state between connections like a real unit. It can be held by another
 * phone, and then only connects when asked for.
 */
class FakeLink(name: String, private val scope: CoroutineScope) : BedJetLink {
    var device: DemoLink = DemoLink(name, scope = scope)
        set(value) {
            field = value
            attach()
        }
    override var generation: Generation = Generation.V3
    var answers = true
    /** Another phone has the BedJet: it connects only once asked for, as a hand-over request does. */
    var heldElsewhere = false
    var holdsSends = false
    /** Off, the unit takes commands but its reports never arrive, as when Lull lets go of it first. */
    var reports = true
    /**
     * On, letting go takes a moment, as over Bluetooth: the link still says it's ready until the connection drops, but
     * nothing sent on it arrives, and asking for the unit meanwhile connects once it has dropped.
     */
    var dropsSlowly = false
    private var isDropping = false
    private var connectsAfterDrop = false
    val sent = mutableListOf<List<Byte>>()
    var stops = 0
        private set
    /** How often Lull asked for the unit, which over Bluetooth asks another phone to hand it over too. */
    var starts = 0
        private set
    /** How many times another phone was asked to hand the BedJet over. */
    var requests = 0
        private set
    /** How often Lull connected only if the unit was free. */
    var listens = 0
        private set
    private val held = mutableListOf<CompletableDeferred<Unit>>()
    val isHolding: Boolean get() = held.isNotEmpty()
    val heldCount: Int get() = held.size

    override var onStateChange: ((LinkState) -> Unit)? = null
    override var onStatus: ((DeviceStatus) -> Unit)? = null
    override val state: LinkState get() = if (heldElsewhere) LinkState.YIELDED else device.state
    override val lastStatus: DeviceStatus? get() = device.lastStatus

    init {
        attach()
    }

    private fun attach() {
        device.onStateChange = { onStateChange?.invoke(it) }
        device.onStatus = { if (reports) onStatus?.invoke(it) }
    }

    override fun start() {
        starts += 1
        connect()
    }

    override fun listen() {
        listens += 1
        connect()
    }

    private fun connect() {
        if (heldElsewhere) {
            onStateChange?.invoke(LinkState.YIELDED)
            return
        }
        if (isDropping) {
            connectsAfterDrop = true
            return
        }
        device.answers = answers
        device.start()
    }

    override fun stop() {
        stops += 1
        connectsAfterDrop = false
        if (!dropsSlowly || state != LinkState.READY) return device.stop()
        isDropping = true
        scope.launch {
            delay(300)
            isDropping = false
            device.stop()
            if (connectsAfterDrop) connect()
        }
    }

    override suspend fun writeWiFi(network: ByteArray, password: ByteArray) = device.writeWiFi(network, password)

    override fun requestControl() {
        requests += 1
        heldElsewhere = false
        connect()
    }

    override suspend fun send(command: ByteArray) {
        if (holdsSends) CompletableDeferred<Unit>().also { held += it }.await()
        if (state != LinkState.READY || isDropping) throw BedJetError.NotConnected
        sent += command.toList()
        device.send(command)
    }

    override suspend fun query(request: Int): ByteArray {
        if (state != LinkState.READY || isDropping) throw BedJetError.NotConnected
        return device.query(request)
    }

    fun release() {
        holdsSends = false
        val waiting = held.toList()
        held.clear()
        for (continuation in waiting) continuation.complete(Unit)
    }

    /** Lets only the oldest command in flight through; the rest stay held. */
    fun releaseFirst() {
        if (held.isEmpty()) return
        held.removeAt(0).complete(Unit)
    }

    /** A status report, however late it arrives. */
    fun report(status: DeviceStatus) {
        onStatus?.invoke(status)
    }
}
