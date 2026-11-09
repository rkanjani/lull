package com.rohankanjani.lull

import com.rohankanjani.lull.bedjetkit.DeviceStatus
import com.rohankanjani.lull.bedjetkit.Generation
import com.rohankanjani.lull.bedjetkit.Mode
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

/** What an automation asks of a BedJet. The iPhone app's Shortcuts actions carry the same requests. */
sealed interface AutomationRequest {
    /** Without a schedule, tonight's. */
    data class StartNight(val schedule: String?) : AutomationRequest
    /** Turn On sets a mode; Set Temperature, Set Fan and Set Run Time change the air that is already on. */
    data class Air(val settings: AirSettings) : AutomationRequest
    data object TurnOff : AutomationRequest
    data class LightRing(val on: Boolean) : AutomationRequest
    data class MuteBeeps(val muted: Boolean) : AutomationRequest
}

/** Air for an action, as the Remote sets it. The temperature is in the unit Lull shows. */
data class AirSettings(val mode: Mode? = null, val degrees: Int? = null, val fanPercent: Int? = null, val minutes: Int? = null)

/** What was sent to the BedJet, once kept to what it allows. The temperature is in half-degrees Celsius. */
data class AppliedAir(val mode: Mode, val temperature: Int? = null, val fanPercent: Int? = null, val minutes: Int? = null)

sealed interface Started {
    data object Now : Started
    /** Every slot was taken, and Lull is asking which to reuse. */
    data object ChoosingSlot : Started
    /** The schedule's name, or null for one started from another phone. */
    data class AlreadyRunning(val schedule: String?) : Started
}

sealed interface TurnedOff {
    /** The schedule's name, or null for one started from another phone. */
    data class EndedNight(val schedule: String?) : TurnedOff
    data object StoppedAir : TurnedOff
    data object AlreadyOff : TurnedOff
}

/** What the BedJet was doing when Lull last heard from it. */
data class AirReading(
    val mode: Mode,
    /** Half-degrees Celsius. */
    val temperature: Int,
    val fanPercent: Int,
    val programStep: Int,
    val remainingSeconds: Int,
    val at: Long,
)

sealed class AutomationError(message: String) : Exception(message) {
    data object NoBedJet : AutomationError("Choose a BedJet in Lull first.")
    /** The action names a BedJet that was forgotten since. */
    data object ForgottenBedJet : AutomationError("Lull no longer knows the BedJet this action is set to. Pick another for it.")
    data object NoSchedule : AutomationError("Make a schedule in Lull first.")
    /** The action names a schedule that was deleted since. */
    data object ForgottenSchedule : AutomationError("Lull no longer has the schedule this action is set to. Pick another for it.")
    /** The BedJet couldn't be reached without Lull on screen: another phone has it, Bluetooth is off, or it is away. */
    data object NeedsLull : AutomationError("Lull couldn't reach your BedJet in the background.")
    data object SlotsFull : AutomationError("Your BedJet's three slots are full. Start a night in Lull once and pick one to reuse.")
    data class UnsupportedMode(val why: String) : AutomationError(why)
    data object AirOff : AutomationError("Your BedJet is off. Turn it on first.")
    data class FixedTemperature(val mode: Mode) : AutomationError("${mode.title} runs at its own temperature.")
    data object HeatOnlyTemperature : AutomationError("A BedJet V2 only takes a temperature for Heat.")
    data object ScheduleSetsTiming : AutomationError("A schedule is running and sets when the air turns off. End it or choose a mode first.")
    /** The BedJet is using its Wi-Fi, to join a network or to check for or install firmware. */
    data object Updating : AutomationError("Your BedJet is busy with its Wi-Fi or a firmware update. Try again once it's done.")
}

/** An action that didn't work, in the words Lull's alert would use. */
class AutomationFailure(message: String) : Exception(message)

/** How an action's values are kept to what the BedJet allows, as the Remote's controls keep them. */
object Automation {
    /** A temperature in the unit Lull shows, kept to the range the BedJet reports for the mode, or else the mode's own. */
    fun halfCelsius(degrees: Int, fahrenheit: Boolean, mode: Mode, status: DeviceStatus?): Int {
        // An automation passes any number. One far outside every mode's range is brought near it first, since converting
        // it as it is would overflow.
        val degrees = degrees.coerceIn(-1000, 1000)
        var limits = mode.temperatureRange
        if (status != null && status.mode == mode && status.minTemperature < status.maxTemperature) {
            limits = status.minTemperature..status.maxTemperature
        }
        return Format.halfCelsius(degrees, fahrenheit).coerceIn(limits.first, limits.last)
    }

    /** Why a temperature can't be set for air running in [mode], if it can't. */
    fun temperatureRefusal(mode: Mode, generation: Generation): AutomationError? {
        if (generation.adjustsTemperature(mode)) return null
        return if (generation == Generation.V2 && mode.usesTemperature) AutomationError.HeatOnlyTemperature else AutomationError.FixedTemperature(mode)
    }

    /** 5 to 100%, in the steps of 5 the BedJet's fan runs at. */
    fun fanPercent(percent: Int): Int = ((percent / 5.0).roundToInt() * 5).coerceIn(5, 100)

    /** Minutes until the air turns off, kept to the longest the BedJet allows for the air it is running. */
    fun runTimeMinutes(minutes: Int, mode: Mode, status: DeviceStatus?): Int {
        var longest = mode.maxMinutes
        if (status != null && status.mode == mode && status.maxRuntimeMinutes > 0) longest = status.maxRuntimeMinutes
        return minutes.coerceIn(1, longest)
    }

    /** What an air action did, such as "Turned on Heat on My side: 86°, fan 40%." or "Set the fan on My side to 40%." */
    fun airDone(applied: AppliedAir, asked: AirSettings, on: String, fahrenheit: Boolean): String {
        if (asked.mode != null) {
            // What was set besides the mode, as the BedJet took it, since any of it may have been kept to its limits.
            val set = mutableListOf<String>()
            applied.temperature?.let { set += "${Format.degrees(it, fahrenheit)}°" }
            applied.fanPercent?.let { set += "fan $it%" }
            applied.minutes?.let { set += "turns off in ${Format.duration(it)}" }
            val details = if (set.isEmpty()) "" else ": " + set.joinToString(", ")
            return "Turned on ${applied.mode.title} on $on$details."
        }
        val said = mutableListOf<String>()
        applied.temperature?.let { said += "Set $on to ${Format.degrees(it, fahrenheit)}°." }
        applied.fanPercent?.let { said += "Set the fan on $on to $it%." }
        applied.minutes?.let { said += "$on turns off in ${Format.duration(it)}." }
        return said.joinToString(" ")
    }
}

/** What Get BedJet Status says, from what Lull knows without connecting. */
data class BedJetReport(
    val nightRunning: Boolean = false,
    /** Null for a schedule started from another phone. */
    val schedule: String? = null,
    val nightEnds: Long? = null,
    val mode: Mode = Mode.STANDBY,
    /** Half-degrees Celsius, for modes that have one. */
    val temperature: Int? = null,
    val fanPercent: Int? = null,
    val lastSeen: Long? = null,
) {
    /** The report in a sentence, such as "My side is on: Heat, 86°, fan 40%." */
    fun summary(of: String, fahrenheit: Boolean): String {
        if (nightRunning) {
            return schedule?.let { "“$it” is running on $of." } ?: "A schedule from another phone is running on $of."
        }
        if (lastSeen == null) return "Lull hasn't reached $of yet."
        if (mode == Mode.STANDBY) return "$of is off."
        val parts = mutableListOf(mode.title)
        temperature?.let { parts += "${Format.degrees(it, fahrenheit)}°" }
        fanPercent?.let { parts += "fan $it%" }
        return "$of is on: ${parts.joinToString(", ")}."
    }

    companion object {
        operator fun invoke(run: ActiveRun?, reading: AirReading?, generation: Generation, now: Long = System.currentTimeMillis()): BedJetReport {
            val lastSeen = reading?.at
            // A night started from this phone is known phase by phase, connected or not.
            val phase = run?.phase(now)?.phase
            if (run != null && phase != null) {
                return air(phase.mode, phase.temperature, phase.fanPercent, generation)
                    .copy(nightRunning = true, schedule = run.schedule.name, nightEnds = run.endsAt, lastSeen = lastSeen)
            }
            // Otherwise only until the BedJet's timer, or the phase another phone started, would have run out.
            if (reading == null || reading.at + reading.remainingSeconds * 1000L <= now) return BedJetReport(lastSeen = lastSeen)
            return air(reading.mode, reading.temperature, reading.fanPercent, generation)
                .copy(nightRunning = reading.programStep > 0, lastSeen = lastSeen)
        }

        private fun air(mode: Mode, temperature: Int, fanPercent: Int, generation: Generation) = BedJetReport(
            mode = mode,
            temperature = if (generation.adjustsTemperature(mode)) temperature else null,
            fanPercent = if (mode.usesFan) fanPercent else null,
        )
    }
}

/** The BedJet an action is for: the one it names, or the one Lull shows. Lull never picks one by itself. */
fun AppModel.automationTarget(id: String?): BedJet {
    if (id != null) return bedJets.firstOrNull { it.id == id } ?: throw AutomationError.ForgottenBedJet
    return bedJet ?: throw AutomationError.NoBedJet
}

/**
 * Does what an action asked of a BedJet and says what happened. `mayAsk` is for an action that went on in
 * Lull, which asks another phone for the BedJet as its controls do.
 */
suspend fun AppModel.perform(request: AutomationRequest, on: BedJet, mayAsk: Boolean = false): String {
    val name = title(on)
    try {
        // Restarting into new firmware, it can't be reached until the install is done, by Lull either, so going on in
        // Lull wouldn't help. Lull stops expecting it back ten minutes after it was asked to check.
        if (on.isInstallingUpdate) throw AutomationError.Updating
        return when (request) {
            is AutomationRequest.StartNight -> {
                val schedule = if (request.schedule == null) on.tonight else schedules.firstOrNull { it.id == request.schedule }
                if (schedule == null) throw if (request.schedule == null) AutomationError.NoSchedule else AutomationError.ForgottenSchedule
                // Lull's slot choice starts tonight's schedule on the BedJet on screen, so only that can wait on it.
                val choosingSlot = mayAsk && schedule.id == on.tonight?.id && on.id == bedJet?.id
                when (val started = reaching(on, mayAsk) { on.start(schedule, choosingSlot) }) {
                    Started.Now -> "Started “${schedule.name}” on $name."
                    Started.ChoosingSlot -> "Your BedJet's three slots are full. Pick one to reuse in Lull."
                    is Started.AlreadyRunning -> started.schedule?.let { "“$it” is already running on $name." }
                        ?: "A schedule from another phone is already running on $name."
                }
            }
            is AutomationRequest.Air -> {
                val applied = reaching(on, mayAsk) { on.apply(request.settings, useFahrenheit) }
                Automation.airDone(applied, request.settings, name, useFahrenheit)
            }
            AutomationRequest.TurnOff -> when (val off = reaching(on, mayAsk) { on.turnOff() }) {
                is TurnedOff.EndedNight -> off.schedule?.let { "Ended “$it” on $name." } ?: "Ended the night on $name."
                TurnedOff.StoppedAir -> "Turned off $name."
                TurnedOff.AlreadyOff -> "$name is already off."
            }
            is AutomationRequest.LightRing -> {
                reaching(on, mayAsk) { on.turnLightRing(request.on) }
                if (request.on) "Turned on the light ring on $name." else "Turned off the light ring on $name."
            }
            is AutomationRequest.MuteBeeps -> {
                reaching(on, mayAsk) { on.muteBeeps(request.muted) }
                if (request.muted) "Muted beeps on $name." else "Unmuted beeps on $name."
            }
        }
    } catch (error: AutomationError.NeedsLull) {
        // Left for the action to decide whether to go on in Lull.
        throw error
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        throw AutomationFailure(attributed(error.message.orEmpty(), on))
    }
}

/** What Get BedJet Status reports, from what Lull already knows. It never connects. */
fun AppModel.status(of: BedJet): BedJetReport = BedJetReport(run = of.run, reading = of.reading, generation = of.generation)
