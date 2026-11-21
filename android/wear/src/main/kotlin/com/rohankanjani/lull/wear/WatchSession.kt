package com.rohankanjani.lull.wear

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.rohankanjani.lull.ActiveRun
import com.rohankanjani.lull.LiveStatus
import com.rohankanjani.lull.Phase
import com.rohankanjani.lull.RemoteSetting
import com.rohankanjani.lull.WatchCommand
import com.rohankanjani.lull.WatchMessages
import com.rohankanjani.lull.WatchReply
import com.rohankanjani.lull.WidgetSnapshot
import com.rohankanjani.lull.bedjetkit.Mode
import com.rohankanjani.lull.lullJson
import com.rohankanjani.lull.maxMinutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * How the watch reaches Lull on the phone, which reaches the BedJet. The watch has no Bluetooth link of its own to
 * the BedJet, so everything it does is an errand the phone runs.
 */
interface Phone {
    /** Whether Lull on the phone can be asked now. It's started in the background if it isn't running. */
    val isReachable: Boolean
    var onReachabilityChange: (() -> Unit)?
    /** What the widgets show, handed over whenever it changes and with each reply. */
    var onSnapshot: ((WidgetSnapshot) -> Unit)?
    /** How an errand went, once the phone has done it. */
    var onReply: ((WatchReply) -> Unit)?
    fun activate()
    /** Hands the phone an errand. Returns its answer when it refused at once, or null once it's on it. */
    suspend fun send(command: WatchCommand): WatchReply?
}

/** Lull on the paired phone, over the Wearable Data Layer's messages, which go straight to the phone. */
class PhoneLink(context: Context) : Phone {
    private val context = context.applicationContext
    override var isReachable = false
        private set
    override var onReachabilityChange: (() -> Unit)? = null
    override var onSnapshot: ((WidgetSnapshot) -> Unit)? = null
    override var onReply: ((WatchReply) -> Unit)? = null
    private var phone: String? = null
    private val capabilities = CapabilityClient.OnCapabilityChangedListener { found(it) }

    override fun activate() {
        current = this
        val client = Wearable.getCapabilityClient(context)
        client.addListener(capabilities, WatchMessages.PHONE_CAPABILITY)
        client.getCapability(WatchMessages.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE).addOnSuccessListener(::found)
    }

    /** Stops taking what arrives, once the watch app is closed, so nothing keeps its model around. */
    fun deactivate() {
        Wearable.getCapabilityClient(context).removeListener(capabilities, WatchMessages.PHONE_CAPABILITY)
        if (current === this) current = null
    }

    override suspend fun send(command: WatchCommand): WatchReply? {
        val phone = phone ?: throw IOException("No phone with Lull is nearby")
        Wearable.getMessageClient(context).sendMessage(phone, WatchMessages.COMMAND, command.data).awaited()
        return null
    }

    private fun found(info: CapabilityInfo) {
        // The phone on the wrist's own Bluetooth link first, if there's more than one.
        phone = (info.nodes.firstOrNull { it.isNearby } ?: info.nodes.firstOrNull())?.id
        isReachable = phone != null
        onReachabilityChange?.invoke()
    }

    companion object {
        /** The link the open app is using, which [WatchSessionService] hands what arrives. */
        @Volatile var current: PhoneLink? = null
    }
}

/**
 * Takes what Lull on the phone sends, even while the watch app is closed: what the widgets show, kept for the tile and
 * the complication, and how an errand went.
 */
class WatchSessionService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        val main = Handler(Looper.getMainLooper())
        when (event.path) {
            WatchMessages.SNAPSHOT -> {
                val snapshot = runCatching { lullJson.decodeFromString(WidgetSnapshot.serializer(), event.data.decodeToString()) }
                    .getOrNull() ?: return
                // The open app's model keeps it, as it keeps what it's handed itself; closed, it's kept here for the
                // tile and the complication.
                val open = PhoneLink.current
                if (open == null) DeviceStore(this).save(snapshot) else main.post { open.onSnapshot?.invoke(snapshot) }
            }
            WatchMessages.REPLY -> {
                val reply = WatchReply.from(event.data) ?: return
                main.post { PhoneLink.current?.onReply?.invoke(reply) }
            }
        }
    }
}

/**
 * A pretend phone, for the emulator on its own, tests and screenshots (`demo`). It does what it's asked a moment
 * later, as Lull on the phone does with a simulated BedJet.
 */
class DemoPhone(snapshot: WidgetSnapshot, private val scope: CoroutineScope) : Phone {
    override var isReachable = true
        set(value) {
            field = value
            onReachabilityChange?.invoke()
        }
    override var onReachabilityChange: (() -> Unit)? = null
    override var onSnapshot: ((WidgetSnapshot) -> Unit)? = null
    override var onReply: ((WatchReply) -> Unit)? = null
    var snapshot = snapshot
        private set
    /** Every errand it was handed, oldest first. */
    val received = mutableListOf<WatchCommand>()
    /** How long it takes to do an errand, in milliseconds. Null holds every errand until [finish]. */
    var delayMillis: Long? = 600
    /** What the next errand says went wrong, if anything. */
    var failure: String? = null
    /** How far into the night a night it starts appears, as `demoElapsedMinutes` makes it. */
    var elapsedMinutes = 0
    /** Off, errands don't get through though it's reachable, as when the phone drops out between asking and sending. */
    var delivers = true
    private val held = mutableListOf<WatchCommand>()

    override fun activate() {
        onSnapshot?.invoke(snapshot)
    }

    override suspend fun send(command: WatchCommand): WatchReply? {
        if (!isReachable || !delivers) throw IOException("No phone with Lull is nearby")
        received += command
        val wait = delayMillis
        if (wait != null) {
            scope.launch {
                delay(wait)
                answer(command)
            }
        } else {
            held += command
        }
        return null
    }

    /** Does the errands it was holding, oldest first: all of them, or only so many. */
    fun finish(count: Int = Int.MAX_VALUE) {
        val waiting = held.take(count)
        repeat(waiting.size) { held.removeAt(0) }
        waiting.forEach(::answer)
    }

    private fun answer(command: WatchCommand) {
        failure?.takeIf { command.isAnswered }?.let {
            failure = null
            onReply?.invoke(WatchReply.failed(it))
            return
        }
        val now = System.currentTimeMillis()
        val index = snapshot.bedJets.indexOfFirst { it.id == (command.bedJet ?: snapshot.onScreen) }
        if (index >= 0) {
            var bedJet = snapshot.bedJets[index]
            when (command) {
                is WatchCommand.StartNight -> {
                    // A night already under way is left running, as on the phone.
                    val tonight = bedJet.tonight
                    if (bedJet.live(now) !is LiveStatus.Night && tonight != null) {
                        bedJet = bedJet.copy(live = LiveStatus.Night(ActiveRun(tonight, now - elapsedMinutes * 60_000L)))
                    }
                }
                is WatchCommand.EndNight -> bedJet = bedJet.copy(live = null)
                is WatchCommand.Remote -> {
                    val preset = command.preset.clamped
                    val changes = command.changed ?: RemoteSetting.entries.toSet()
                    val air = bedJet.live(now) as? LiveStatus.Air
                    if (RemoteSetting.MODE in changes) {
                        // A new mode runs as long as it can, unless a time was set with it.
                        val minutes = if (RemoteSetting.TIME in changes) {
                            preset.minutes
                        } else {
                            Phase(preset.mode, preset.temperature, preset.fanPercent, 1).maxMinutes
                        }
                        bedJet = bedJet.copy(
                            live = if (preset.mode == Mode.STANDBY) null else LiveStatus.Air(
                                preset.mode,
                                preset.temperature.takeIf { bedJet.generation.adjustsTemperature(preset.mode) },
                                preset.fanPercent,
                                now + minutes * 60_000L,
                            ),
                        )
                    } else if (air != null) {
                        // Only what was changed, as the phone does: the rest stays as the BedJet has it.
                        bedJet = bedJet.copy(
                            live = LiveStatus.Air(
                                air.mode,
                                if (RemoteSetting.TEMPERATURE in changes && bedJet.generation.adjustsTemperature(air.mode)) preset.temperature else air.temperature,
                                if (RemoteSetting.FAN in changes) preset.fanPercent else air.fanPercent,
                                if (RemoteSetting.TIME in changes) now + preset.minutes * 60_000L else air.endsAt,
                            ),
                        )
                    }
                }
                WatchCommand.Refresh -> Unit
            }
            val bedJets = snapshot.bedJets.toMutableList().also { it[index] = bedJet }
            snapshot = snapshot.copy(
                bedJets = bedJets,
                run = if (bedJet.id == snapshot.onScreen) (bedJet.live as? LiveStatus.Night)?.run else snapshot.run,
            )
        }
        onSnapshot?.invoke(snapshot)
        if (command.isAnswered) onReply?.invoke(WatchReply.done)
    }
}

/** Waits for a Play services task without a library for it. */
suspend fun <T> Task<T>.awaited(): T = suspendCancellableCoroutine { waiting ->
    addOnSuccessListener { waiting.resume(it) }
    addOnFailureListener { waiting.resumeWithException(it) }
    addOnCanceledListener { waiting.cancel() }
}
