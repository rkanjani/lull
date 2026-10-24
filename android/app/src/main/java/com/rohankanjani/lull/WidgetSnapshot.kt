package com.rohankanjani.lull

import android.content.Context
import com.rohankanjani.lull.bedjetkit.DeviceStatus
import com.rohankanjani.lull.bedjetkit.Generation
import com.rohankanjani.lull.bedjetkit.Mode
import kotlinx.serialization.Serializable
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * What the widgets show, written by the app whenever any of it changes. Widgets can't reach the BedJet and don't need
 * to: a schedule plays out on the BedJet by the clock, so where it is at any moment follows from when it started.
 */
@Serializable
data class WidgetSnapshot(
    val tonight: Schedule? = null,
    val run: ActiveRun? = null,
    /** The last couple of days of history: enough to find last night, even while tonight is running. */
    val recentHistory: List<HistoryEntry> = emptyList(),
    val useFahrenheit: Boolean = false,
    val generation: Generation = Generation.V3,
    /** Every BedJet Lull knows and what each is doing, for the Tonight, Both Sides and Quick Remote widgets. */
    val bedJets: List<WidgetBedJet> = emptyList(),
    /** The BedJet on screen in Lull, which those widgets show unless set to another. */
    val onScreen: String? = null,
) {
    /**
     * The schedule running at `now`. Lull lets go of the BedJet overnight, so one that stopped early still
     * shows as running until Lull is opened again and notices.
     */
    fun run(now: Long): ActiveRun? = run?.takeIf { it.endsAt > now }

    /**
     * When the widgets need redrawing: now, every few minutes while any BedJet runs to move it along, and the
     * moment each phase begins, each night ends and each Remote timer runs out.
     */
    fun redraws(from: Long, interval: Long = 300_000L): List<Long> {
        val running = bedJets.mapNotNull { it.live(from) } + listOfNotNull(run(from)?.let(LiveStatus::Night))
        val last = running.maxOfOrNull { it.endsAt } ?: return listOf(from)
        val ticks = generateSequence(from) { it + interval }.takeWhile { it < last }.toList()
        val changes = running.flatMap { live ->
            when (live) {
                is LiveStatus.Night -> live.run.phaseEnds
                is LiveStatus.Air -> listOf(live.endsAt)
            }
        }
        return (ticks + changes.filter { it > from }).toSortedSet().toList()
    }

    /** The BedJet a widget shows: the one picked for it, or the one on screen. */
    fun bedJet(id: String?): WidgetBedJet? = bedJets.firstOrNull { it.id == (id ?: onScreen) }

    /** Whether widgets name the BedJet they show: once Lull knows more than one, so each side of the bed's can be told apart. */
    val hasSeveralBedJets: Boolean get() = bedJets.size > 1

    /** The name a widget heads itself with once Lull knows more than one BedJet, as the Tonight widget does. Null with one. */
    val headingName: String? get() = if (hasSeveralBedJets) bedJet(null)?.name else null

    /** A widget's heading, after the BedJet's name once there's more than one: "My side · Tonight". */
    fun headed(heading: String): String = headingName?.let { "$it · $heading" } ?: heading

    /** What TalkBack reads for a widget, after the BedJet's name once there's more than one. */
    fun spoken(text: String): String = headingName?.let { "$it, $text" } ?: text

    /** The BedJet a widget not yet set to one shows: the one on screen in Lull, or the first Lull knows. */
    val usualBedJet: WidgetBedJet? get() = bedJet(null) ?: bedJets.firstOrNull()

    /**
     * What a widget set to one BedJet shows: that BedJet's tonight, running night and history in place of those of the
     * one on screen, so the Tonight and Last Night widgets draw any side of the bed as they do the one on screen. A
     * BedJet forgotten since leaves nothing to show. Null is the one on screen.
     */
    fun showing(id: String?): WidgetSnapshot {
        if (id == null) return this
        val bedJet = bedJets.firstOrNull { it.id == id }
        return copy(
            tonight = bedJet?.tonight,
            run = (bedJet?.live as? LiveStatus.Night)?.run,
            recentHistory = bedJet?.recentHistory.orEmpty(),
            generation = bedJet?.generation ?: generation,
            onScreen = id,
        )
    }

    /**
     * The two BedJets a Both Sides widget shows: as picked, or else the one on screen on the left and another on the
     * right. Set to the same one twice, the right shows the other. A side set to a BedJet forgotten since is empty.
     */
    fun sides(left: String?, right: String?): Pair<WidgetBedJet?, WidgetBedJet?> {
        val leftSide = if (left != null) bedJets.firstOrNull { it.id == left } else usualBedJet
        val rightSide = if (right != null && right != leftSide?.id) {
            bedJets.firstOrNull { it.id == right }
        } else {
            bedJets.firstOrNull { it.id != leftSide?.id }
        }
        return leftSide to rightSide
    }

    /** The latest night that's over: not tonight's, which begins at noon, and not one still running past midnight. */
    fun lastNight(now: Long, zone: ZoneId = ZoneId.systemDefault()): Night? {
        val tonight = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()
        val running = run(now)?.let { Night.evening(it.startedAt, zone).toString() }
        return Night.group(recentHistory, zone).firstOrNull { it.id != tonight && it.id != running }
    }

    fun degrees(halfCelsius: Int): String = "${Format.degrees(halfCelsius, useFahrenheit)}°"

    /** What a phase is set to, such as "75° · Fan 40%", worded like the schedule editor. */
    fun detail(phase: Phase): String = phase.setting(on = generation, fahrenheit = useFahrenheit)

    /** Saves the snapshot and says whether it differs from what was there, so widgets are only redrawn when it does. */
    fun save(context: Context): Boolean {
        val data = AppModel.json.encodeToString(serializer(), this)
        val file = file(context)
        if (file.exists() && runCatching { file.readText() }.getOrNull() == data) return false
        return runCatching {
            val temporary = File(file.parentFile, "widgets.json.tmp")
            temporary.writeText(data)
            temporary.renameTo(file)
        }.getOrDefault(false)
    }

    companion object {
        private fun file(context: Context) = File(context.filesDir, "widgets.json")

        fun load(context: Context): WidgetSnapshot? =
            runCatching { AppModel.json.decodeFromString(serializer(), file(context).readText()) }.getOrNull()

        /**
         * What to save for a BedJet that just reported. The BedJet reports every second and its timer drifts against
         * the phone's clock, so what was saved stays unless it is really different; otherwise the widgets would be
         * redrawn every second.
         */
        fun steadied(new: LiveStatus?, old: LiveStatus?): LiveStatus? =
            if (new == null || old == null || new.differsEnough(old)) new else old

        /**
         * What a BedJet's widgets show after it reports. What was saved stays while Lull is still waiting to hear what
         * it is doing (see [isWaiting]), until it runs out.
         */
        fun live(current: LiveStatus?, old: LiveStatus?, isWaiting: Boolean, now: Long): LiveStatus? =
            if (current == null && isWaiting) old?.takeIf { it.endsAt > now } else steadied(current, old)

        /**
         * Whether Lull is still waiting to hear what a BedJet is doing: since launching, while a night is being saved to
         * it, or while it runs before the second half of its report, which tells air from a schedule, has arrived.
         */
        fun isWaiting(status: DeviceStatus?, isSavingSchedule: Boolean): Boolean =
            status == null || isSavingSchedule || (status.isRunning && status.extra == null)

        /** History worth keeping for the widgets: the last two days, which always covers last night. */
        fun recent(history: List<HistoryEntry>, before: Long = System.currentTimeMillis()): List<HistoryEntry> {
            val cutoff = before - 48 * 3_600_000L
            return history.filter { it.date >= cutoff }
        }

        /**
         * A night a few hours into Cool Drift, shown in the widget picker and while a widget loads. One side of the bed
         * has it running too, cooled from the Remote, for the Tonight and Both Sides widgets.
         */
        fun preview(now: Long = System.currentTimeMillis()): WidgetSnapshot {
            val schedule = Schedule.templates[0]
            val run = ActiveRun(schedule, startedAt = now - 200 * 60_000L)
            val mine = WidgetBedJet(
                "6B0D4C2E-1A55-4D8E-9C71-0F2A7E3B5D10", "My side", LiveStatus.Night(run), schedule,
                recentHistory = recent(HistoryEntry.samples(now), before = now),
            )
            val theirs = WidgetBedJet(
                "6B0D4C2E-1A55-4D8E-9C71-0F2A7E3B5D11", "Other side",
                LiveStatus.Air(Mode.COOL, temperature = 44, fanPercent = 40, endsAt = now + 45 * 60_000L), schedule,
            )
            return WidgetSnapshot(
                tonight = schedule,
                run = run,
                recentHistory = recent(HistoryEntry.samples(now), before = now),
                bedJets = listOf(mine, theirs),
                onScreen = mine.id,
            )
        }
    }
}

/** One BedJet as the widgets see it. */
@Serializable
data class WidgetBedJet(
    val id: String,
    /** What the BedJet is called on screen, with its code when another known BedJet has the same name. */
    val name: String,
    /** What it was last known to be doing: a night started from this phone, or air from the Remote. */
    val live: LiveStatus? = null,
    val tonight: Schedule? = null,
    val generation: Generation = Generation.V3,
    /** The last two days of its history, for a Last Night widget set to it. */
    val recentHistory: List<HistoryEntry> = emptyList(),
) {
    /** What it is doing at `now`. Lull lets go of the BedJet when it leaves the screen, so this plays out by the clock. */
    fun live(now: Long): LiveStatus? = live?.takeIf { it.endsAt > now }
}

/** "7 hr 45 min · 4 phases" */
val Schedule.summary: String
    get() = "${Format.duration(totalMinutes)} · ${phases.size} ${if (phases.size == 1) "phase" else "phases"}"

val Schedule.spokenSummary: String
    get() = "${Format.spokenDuration(totalMinutes)}, ${phases.size} ${if (phases.size == 1) "phase" else "phases"}"
