package com.rohankanjani.lull

import android.content.Context
import com.rohankanjani.lull.bedjetkit.Generation
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
) {
    /**
     * The schedule running at `now`. Lull lets go of the BedJet overnight, so one that stopped early still
     * shows as running until Lull is opened again and notices.
     */
    fun run(now: Long): ActiveRun? = run?.takeIf { it.endsAt > now }

    /**
     * When the widgets need redrawing: now, every few minutes through a running night to move it along,
     * and the moment each phase begins and the night ends.
     */
    fun redraws(from: Long, interval: Long = 300_000L): List<Long> {
        val run = run(from) ?: return listOf(from)
        val ticks = generateSequence(from) { it + interval }.takeWhile { it < run.endsAt }.toList()
        return (ticks + run.phaseEnds.filter { it > from }).toSortedSet().toList()
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

        /** History worth keeping for the widgets: the last two days, which always covers last night. */
        fun recent(history: List<HistoryEntry>, before: Long = System.currentTimeMillis()): List<HistoryEntry> {
            val cutoff = before - 48 * 3_600_000L
            return history.filter { it.date >= cutoff }
        }

        /** A night a few hours into Cool Drift, shown in the widget picker and while a widget loads. */
        fun preview(now: Long = System.currentTimeMillis()): WidgetSnapshot {
            val schedule = Schedule.templates[0]
            return WidgetSnapshot(
                tonight = schedule,
                run = ActiveRun(schedule, startedAt = now - 200 * 60_000L),
                recentHistory = recent(HistoryEntry.samples(now), before = now),
            )
        }
    }
}

/** "7 hr 45 min · 4 phases" */
val Schedule.summary: String
    get() = "${Format.duration(totalMinutes)} · ${phases.size} ${if (phases.size == 1) "phase" else "phases"}"

val Schedule.spokenSummary: String
    get() = "${Format.spokenDuration(totalMinutes)}, ${phases.size} ${if (phases.size == 1) "phase" else "phases"}"
