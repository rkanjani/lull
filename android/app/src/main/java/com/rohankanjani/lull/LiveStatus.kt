package com.rohankanjani.lull

import com.rohankanjani.lull.bedjetkit.DeviceStatus
import com.rohankanjani.lull.bedjetkit.Generation
import com.rohankanjani.lull.bedjetkit.Mode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * What a running BedJet is doing, as shown outside Lull. Lull lets go of the BedJet whenever it leaves the screen,
 * so this has to stay true by the clock alone: a night plays out from when it started, and air from the Remote turns
 * off when its timer runs out.
 */
@Serializable
sealed interface LiveStatus {
    val endsAt: Long

    /** A schedule started from this phone. */
    @Serializable
    @SerialName("night")
    data class Night(val run: ActiveRun) : LiveStatus {
        override val endsAt: Long get() = run.endsAt
    }

    /** Air started from the Remote. The temperature is null for modes this BedJet doesn't let you set it for. */
    @Serializable
    @SerialName("air")
    data class Air(val mode: Mode, val temperature: Int?, val fanPercent: Int, override val endsAt: Long) : LiveStatus

    /**
     * When what's shown may stop being true unless Lull updates it: for a night, the end of the phase it is in,
     * after which the phase is hidden and only the night's own progress, which is always right, stays.
     */
    fun staleAt(now: Long): Long = when (this) {
        is Night -> run.phaseEnds.firstOrNull { it > now } ?: run.endsAt
        is Air -> endsAt
    }

    /**
     * Whether a new report is worth redrawing for. The BedJet reports every second and its timer drifts against
     * the phone's clock, so a shut-off time within a minute of the one shown is left alone.
     */
    fun differsEnough(shown: LiveStatus): Boolean = when {
        this is Night && shown is Night -> run != shown.run
        this is Air && shown is Air ->
            mode != shown.mode || temperature != shown.temperature || fanPercent != shown.fanPercent ||
                abs(endsAt - shown.endsAt) > 60_000
        else -> true
    }

    companion object {
        /**
         * What to show for a BedJet, or null when it is off. A schedule another phone started isn't shown: its phases
         * aren't known here, and its timer only counts down the step it is on.
         * @param reportedAt When the BedJet sent [status]. The last report is kept while Lull is away from the BedJet,
         *   so its timer counts from then, not from now.
         */
        fun of(
            run: ActiveRun?,
            status: DeviceStatus?,
            reportedAt: Long? = null,
            generation: Generation,
            now: Long = System.currentTimeMillis(),
        ): LiveStatus? {
            if (run != null && run.endsAt > now) return Night(run)
            // Whether a schedule is running arrives in the second half of the report; until it does, it can't be told apart.
            if (status == null || !status.isRunning || status.remainingSeconds <= 0) return null
            if (status.extra?.programStep != 0) return null
            val endsAt = (reportedAt ?: now) + status.remainingSeconds * 1000L
            if (endsAt <= now) return null
            return Air(
                mode = status.mode,
                temperature = status.targetTemperature.takeIf { generation.adjustsTemperature(status.mode) },
                fanPercent = status.fanPercent,
                endsAt = endsAt,
            )
        }
    }
}
