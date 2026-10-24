package com.rohankanjani.lull

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.format.DateFormat
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.rohankanjani.lull.bedjetkit.Generation
import com.rohankanjani.lull.ui.icon
import com.rohankanjani.lull.ui.tint
import com.rohankanjani.lull.widgets.toBitmap
import kotlinx.serialization.Serializable
import java.io.File
import java.util.Date
import kotlin.math.roundToInt

/**
 * Keeps Lull's Live Update up while a BedJet is running, so the night or the Remote's timer can be seen in the status
 * bar and on the lock screen, as the iPhone app does with its Live Activity. Like that, it holds every running BedJet:
 * with one on each side of the bed, both share one, under one icon made of both their modes. It counts down by itself
 * and is brought up to date whenever Lull hears from a BedJet. While Lull is closed, an alarm moves a night's progress
 * along every few minutes and on to each new phase, and the system takes it down when the last night or timer ends.
 */
class LiveNotifications(private val context: Context) {
    sealed interface Wanted {
        data class Show(val side: LiveSide) : Wanted
        /** Lull hasn't heard from the BedJet since launching, so whatever is up for it stays until it does or runs out. */
        data object Keep : Wanted
    }

    /**
     * What is up: when it next needs redrawing (never, once it's been swiped away), and when it was posted, to tell a
     * notification that was swiped away from one a restart took down.
     */
    @Serializable
    internal data class Shown(val content: LiveContent, val staleAt: Long, val postedAt: Long = 0)

    private val manager = NotificationManagerCompat.from(context)

    /**
     * Brings the Live Update in line with the BedJets, in the order Lull lists them. A BedJet left out, being off or no
     * longer known, is taken out of it, and it goes once none is left. Says whether it would be shown but notifications
     * aren't allowed.
     */
    fun show(wanted: List<Pair<String, Wanted>>, useFahrenheit: Boolean, now: Long = System.currentTimeMillis()): Boolean {
        val before = current()
        val target = content(wanted, useFahrenheit, before?.content, now)
        var blocked = false
        val next = target?.let {
            decide(before, it, ::isShowing, bootedAt(now), now)
                ?: if (manager.areNotificationsEnabled()) post(it, now) else null.also { blocked = true }
        }
        commit(before, next, now)
        return blocked
    }

    /** Moves the Live Update on to where each night is now, and lets go of each BedJet whose time has run out. */
    fun tick(now: Long = System.currentTimeMillis()) {
        val before = current() ?: return
        val sides = before.content.sides.filter { it.status.endsAt > now }
        val next = if (sides.isEmpty()) null else before.content.copy(sides = sides).let { content ->
            decide(before, content, ::isShowing, bootedAt(now), now) ?: post(content, now)
        }
        commit(before, next, now)
    }

    private fun commit(before: Shown?, next: Shown?, now: Long) {
        cache = next
        loaded = true
        if (next == before) return
        if (next == null) manager.cancel(TAG, NOTIFICATION_ID)
        save(next)
        scheduleNext(next, now)
    }

    /** Whether the Live Update is up, and not swiped away. */
    private fun isShowing(): Boolean = manager.activeNotifications.any { it.id == NOTIFICATION_ID && it.tag == TAG }

    private fun post(content: LiveContent, now: Long): Shown {
        ensureChannel()
        // Checked by `show` and `tick`, which only redraw what is allowed and already up.
        @Suppress("MissingPermission")
        manager.notify(TAG, NOTIFICATION_ID, build(content, now))
        // A night's progress bar only moves when redrawn, so it is redrawn every few minutes, as the widgets are;
        // the iPhone's moves by itself.
        val redraw = if (content.sides.any { it.status is LiveStatus.Night }) now + REDRAW_INTERVAL else Long.MAX_VALUE
        return Shown(content, minOf(content.staleAt(now), redraw), postedAt = now)
    }

    private fun build(content: LiveContent, now: Long): Notification {
        val clock: (Date) -> String = DateFormat.getTimeFormat(context)::format
        val lives = content.sides.map { Live(it, content.useFahrenheit, now, clock) }
        val first = lives.first()
        val tab = if (first.run != null) LullTab.SCHEDULE else LullTab.REMOTE
        val tap = PendingIntent.getActivity(
            context, 0, AppCommands.openIntent(context, tab, showing = first.side.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL)
            // What the status bar chip shows: the mode, or both sides' modes in one icon, as the iPhone's Dynamic Island does.
            .setSmallIcon(icon(lives))
            .setColor(first.mode?.tint?.toArgb() ?: android.graphics.Color.WHITE)
            .setContentIntent(tap)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setTimeoutAfter(maxOf(content.endsAt - now, 1L))
            // Counted down by the system, so it stays right while Lull is closed: to the end of the one BedJet's night or
            // timer, or with two, to whichever ends first.
            .setWhen(content.sides.minOf { it.status.endsAt })
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setRequestPromotedOngoing(true)
        if (lives.size > 1) {
            // A line for each side of the bed, as the iPhone's Live Activity has a row for each.
            val lines = lives.map { "${it.side.name}: ${it.title} · ${it.text}" }
            builder.setContentTitle(lives.joinToString(" · ") { it.title })
                .setContentText(lines.joinToString("\n"))
                .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
        } else {
            builder.setContentTitle(first.title).setContentText(first.text).setSubText(first.side.name)
            val run = first.run
            if (run != null) {
                builder.setStyle(
                    NotificationCompat.ProgressStyle()
                        .setProgressSegments(run.schedule.phases.map { NotificationCompat.ProgressStyle.Segment(it.minutes).setColor(it.mode.tint.toArgb()) })
                        .setProgress(((now - run.startedAt) / 60_000L).toInt().coerceIn(0, run.schedule.totalMinutes))
                        // A white dot where the night is now, as the widgets and the iPhone's Live Activity have.
                        .setProgressTrackerIcon(nowDot),
                )
            }
        }
        return builder.build()
    }

    /** The mode's icon, or with two BedJets, both modes side by side, left and right as on the bed, split by a line. */
    private fun icon(lives: List<Live>): IconCompat {
        fun glyph(live: Live, size: Int) = (live.mode?.icon ?: Icons.Filled.NightsStay).toBitmap(Color.White, size)
        if (lives.size < 2) return IconCompat.createWithBitmap(glyph(lives.first(), iconSize))
        val size = iconSize
        val icon = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(icon)
        // Material icons keep 2 of their 24 units clear on each side; trimmed, each symbol fills its half.
        val half = size * 0.47f
        val top = (size - half) / 2
        for ((index, live) in lives.take(2).withIndex()) {
            val drawn = glyph(live, size)
            val inset = drawn.width / 12
            val source = android.graphics.Rect(inset, inset, drawn.width - inset, drawn.height - inset)
            val left = if (index == 0) 0f else size - half
            canvas.drawBitmap(drawn, source, RectF(left, top, left + half, top + half), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
        val width = maxOf(size / 20f, 1f)
        canvas.drawRoundRect(RectF(size / 2f - width / 2, top, size / 2f + width / 2, top + half), width, width, line)
        return IconCompat.createWithBitmap(icon)
    }

    private val iconSize get() = (24 * context.resources.displayMetrics.density).roundToInt()

    private val nowDot: IconCompat by lazy {
        val size = iconSize
        val dot = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(dot).drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
        IconCompat.createWithBitmap(dot)
    }

    private fun ensureChannel() {
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        if (system.getNotificationChannel(CHANNEL) != null) return
        system.createNotificationChannel(
            NotificationChannel(CHANNEL, "Running", NotificationManager.IMPORTANCE_LOW).apply {
                description = "A BedJet that's running, and the time left"
                setShowBadge(false)
            },
        )
    }

    /** Wakes Lull at the next phase change, or not at all once nothing is up. */
    private fun scheduleNext(shown: Shown?, now: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val tick = PendingIntent.getBroadcast(
            context, 0, Intent(context, LiveNotificationReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val next = shown?.staleAt?.takeIf { it != Long.MAX_VALUE }
        // Inexact is close enough: a phase shown a minute late still counts down to the right end.
        if (next == null) alarms.cancel(tick) else alarms.set(AlarmManager.RTC, maxOf(next, now), tick)
    }

    private val file get() = File(context.filesDir, "live.json")

    /** What is up, read from `live.json` once per process and shared with the alarm's receiver. */
    private fun current(): Shown? {
        if (!loaded) {
            cache = load()
            loaded = true
        }
        return cache
    }

    private fun load(): Shown? =
        runCatching { AppModel.json.decodeFromString<Shown>(file.readText()) }.getOrNull()

    private fun save(shown: Shown?) {
        runCatching {
            if (shown == null) return@runCatching file.delete()
            val temporary = File(file.parentFile, "live.json.tmp")
            temporary.writeText(AppModel.json.encodeToString(shown))
            temporary.renameTo(file)
        }
    }

    companion object {
        private const val CHANNEL = "running"
        private const val TAG = "live"
        private const val NOTIFICATION_ID = 1
        private const val REDRAW_INTERVAL = 300_000L

        /**
         * What is up, kept for the life of the process. The BedJet reports every second, so `live.json` is only
         * written, and the next redraw only booked, when something changed.
         */
        private var cache: Shown? = null
        private var loaded = false

        private fun bootedAt(now: Long) = now - android.os.SystemClock.elapsedRealtime()

        /**
         * What the Live Update should show: each BedJet wanted, up to as many as fit, or null when none is running.
         * A BedJet Lull hasn't heard from yet keeps what was shown for it, until it runs out.
         */
        internal fun content(wanted: List<Pair<String, Wanted>>, useFahrenheit: Boolean, shown: LiveContent?, now: Long): LiveContent? {
            val sides = wanted.mapNotNull { (id, want) ->
                when (want) {
                    is Wanted.Show -> want.side
                    Wanted.Keep -> shown?.sides?.firstOrNull { it.id == id && it.status.endsAt > now }
                }
            }
            return if (sides.isEmpty()) null else LiveContent(sides.take(LiveContent.MAX_SIDES), useFahrenheit)
        }

        /**
         * What to do with the Live Update: the entry to keep as it is, or null to post `content` again.
         * - Something new to show, or nothing shown yet: posted.
         * - Taken down by a restart rather than swiped away: posted again.
         * - Still current: kept.
         * - Swiped away: kept down, and never redrawn, until there is something new to show.
         * - Due for a redraw: posted.
         */
        internal fun decide(last: Shown?, content: LiveContent, isShowing: () -> Boolean, bootedAt: Long, now: Long): Shown? = when {
            last == null || content.differsEnough(last.content) -> null
            last.staleAt != Long.MAX_VALUE && last.postedAt < bootedAt && !isShowing() -> null
            now < last.staleAt -> last
            !isShowing() -> last.copy(staleAt = Long.MAX_VALUE)
            else -> null
        }
    }
}

/** What one BedJet in the Live Update says, worded as the iPhone app's Live Activity. */
class Live(val side: LiveSide, val useFahrenheit: Boolean, val now: Long, private val clock: (Date) -> String) {
    val run: ActiveRun? = (side.status as? LiveStatus.Night)?.run
    /** The phase the night is in, null for air from the Remote. */
    val current: ActiveRun.Current? = run?.phase(now)
    /** The mode the BedJet is in. */
    val mode = when (val status = side.status) {
        is LiveStatus.Night -> current?.phase?.mode
        is LiveStatus.Air -> status.mode
    }

    /** The schedule's name through a night, and what the air is doing from the Remote: "Cooling". */
    val title: String = run?.schedule?.name ?: mode?.activity.orEmpty()

    /** What the BedJet is set to right now: "Fan 30%", "68° · Fan 40%". */
    val detail: String? = when (val status = side.status) {
        is LiveStatus.Night -> current?.phase?.setting(on = side.generation, fahrenheit = useFahrenheit)
        is LiveStatus.Air -> Format.setting(status.mode, status.temperature, status.fanPercent, useFahrenheit)
    }

    val ending: String
        get() {
            val time = clock(Date(side.status.endsAt))
            return if (run == null) "Turns off at $time" else "Ends $time"
        }

    /** The line under the title: the phase and its setting through a night, the setting from the Remote, then the end. */
    val text: String
        get() = listOfNotNull(if (run != null) current?.phase?.mode?.activity else null, detail, ending).joinToString(" · ")
}

/** Wakes at each phase change to move the Live Update on while Lull is closed. */
class LiveNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        LiveNotifications(context).tick()
    }
}

/** One BedJet in the Live Update. */
@Serializable
data class LiveSide(
    /** The BedJet's own ID, so a tap opens Lull on it. */
    val id: String,
    val status: LiveStatus,
    /** What the BedJet is called on screen, which can change while it runs. */
    val name: String,
    val generation: Generation,
) {
    fun differsEnough(shown: LiveSide): Boolean =
        status.differsEnough(shown.status) || name != shown.name || generation != shown.generation
}

/** What the Live Update shows: each BedJet running from this phone, in the order Lull lists them. */
@Serializable
data class LiveContent(val sides: List<LiveSide>, val useFahrenheit: Boolean) {
    fun differsEnough(shown: LiveContent): Boolean =
        sides.map { it.id } != shown.sides.map { it.id } || useFahrenheit != shown.useFahrenheit ||
            sides.zip(shown.sides).any { (side, shownSide) -> side.differsEnough(shownSide) }

    /** When what's shown may stop being true unless Lull updates it: the soonest of its BedJets'. */
    fun staleAt(now: Long): Long = sides.minOf { it.status.staleAt(now) }

    /** When the last of its BedJets' nights or timers ends. */
    val endsAt: Long get() = sides.maxOf { it.status.endsAt }

    companion object {
        /** The most BedJets one Live Update shows, as on the iPhone: one for each side of the bed. */
        const val MAX_SIDES = 2
    }
}
