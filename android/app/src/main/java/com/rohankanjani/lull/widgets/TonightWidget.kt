package com.rohankanjani.lull.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.currentState
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import com.rohankanjani.lull.ActiveRun
import com.rohankanjani.lull.AppCommands
import com.rohankanjani.lull.Format
import com.rohankanjani.lull.LiveStatus
import com.rohankanjani.lull.LullTab
import com.rohankanjani.lull.R
import com.rohankanjani.lull.Schedule
import com.rohankanjani.lull.WidgetSnapshot
import com.rohankanjani.lull.activity
import com.rohankanjani.lull.bedjetkit.Mode
import com.rohankanjani.lull.spokenSummary
import com.rohankanjani.lull.summary
import com.rohankanjani.lull.ui.Theme

/** Tonight's schedule, and where the night is up to once it's running. */
class TonightWidget : GlanceAppWidget() {
    // The widget's real size, so the curve spans it; layouts switch at the sizes below.
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = WidgetSnapshot.load(context) ?: WidgetSnapshot()
        WidgetUpdates.scheduleNext(context, snapshot)
        // The BedJet the widget is set to, drawn as the one on screen is.
        provideContent { TonightContent(currentSnapshot().showing(currentState(PickedBedJet)), System.currentTimeMillis()) }
    }

    companion object {
        val SMALL = DpSize(120.dp, 120.dp)
        val MEDIUM = DpSize(250.dp, 120.dp)
        val LARGE = DpSize(250.dp, 260.dp)
    }
}

class TonightWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = TonightWidget()
}

/** The app's near-black, lifted slightly at the top so the widget reads as a card on the Home Screen. */
fun GlanceModifier.widgetBackground() = background(ImageProvider(R.drawable.widget_background)).cornerRadius(24.dp)

@Composable
fun TonightContent(snapshot: WidgetSnapshot, now: Long) {
    val context = LocalContext.current
    // The BedJet the widget is set to, and what it's doing.
    val bedJet = snapshot.bedJet(null)
    val live = bedJet?.live(now)
    Box(
        GlanceModifier
            .fillMaxSize()
            .widgetBackground()
            // Opens Lull on the BedJet the widget shows, which may not be the one on screen.
            .clickable(actionStartActivity(AppCommands.openIntent(context, live?.tab ?: LullTab.SCHEDULE, showing = snapshot.onScreen)))
            .padding(WidgetPadding),
    ) {
        val run = snapshot.run(now)
        val tonight = snapshot.tonight
        when {
            run != null -> Running(snapshot, run, now)
            // Air from the Remote, with its timer.
            bedJet != null && live is LiveStatus.Air ->
                BedJetSummary(bedJet, snapshot, now, width = LocalSize.current.width - WidgetPadding * 2, isMedium = !isSmall)
            tonight != null -> Planned(snapshot, tonight, now)
            else -> Empty()
        }
    }
}

private val isSmall: Boolean @Composable get() = LocalSize.current.width < TonightWidget.MEDIUM.width
private val isLarge: Boolean @Composable get() = LocalSize.current.height >= TonightWidget.LARGE.height

/** The width the curve gets: the widget's, less its margins, reaching a little into them as in the app. */
private val curveWidth @Composable get() = LocalSize.current.width - WidgetPadding * 2 + 16.dp

@Composable
private fun Running(snapshot: WidgetSnapshot, run: ActiveRun, now: Long) {
    val current = run.phase(now)
    val mode = current?.phase?.mode ?: Mode.STANDBY
    val minutesLeft = current?.minutesLeft ?: 0
    val progress = run.progress(now)
    val wake = clock(run.endsAt)
    val spoken = snapshot.spoken(
        listOfNotNull(
            run.schedule.name,
            current?.let { "phase ${it.index + 1} of ${run.schedule.phases.size}" },
            current?.let { "${it.phase.mode.activity}, ${Format.spokenDuration(it.minutesLeft)} left" },
            "ends at $wake",
        ).joinToString(", "),
    )
    val described = GlanceModifier.fillMaxSize().semantics { contentDescription = spoken }

    if (isSmall) {
        Column(described) {
            snapshot.headingName?.let {
                WidgetEyebrow(it)
                Gap(3.dp)
            }
            ModeLabel(mode)
            Gap(4.dp)
            Text(run.schedule.name, style = widgetText(17.sp, weight = FontWeight.Medium), maxLines = 2)
            Spacer(GlanceModifier.defaultWeight())
            WidgetCurve(run.schedule.phases, curveWidth, 40.dp, progress, lineWidth = 2.5f)
            Spacer(GlanceModifier.defaultWeight())
            Text("${Format.duration(minutesLeft)} left", style = widgetText(13.sp, weight = FontWeight.Medium), maxLines = 1)
            Text("Ends $wake", style = widgetText(11.sp, Theme.secondary, FontWeight.Medium), maxLines = 1)
        }
        return
    }
    Column(described) {
        Header(snapshot.headed(current?.let { "Phase ${it.index + 1} of ${run.schedule.phases.size}" } ?: "Finishing"), run.schedule.name) {
            Column(horizontalAlignment = Alignment.End) {
                ModeLabel(mode)
                Gap(3.dp)
                Text("${Format.duration(minutesLeft)} left", style = widgetText(12.sp, Theme.secondary, FontWeight.Medium), maxLines = 1)
            }
        }
        Gap(10.dp)
        WidgetCurve(run.schedule.phases, curveWidth, if (isLarge) 90.dp else 44.dp, progress)
        Gap(10.dp)
        TimeRow(run.startedAt, run.endsAt)
        if (isLarge) {
            Gap(10.dp)
            PhaseList(snapshot, run.schedule, run.startedAt, current?.index)
        }
    }
}

@Composable
private fun Planned(snapshot: WidgetSnapshot, schedule: Schedule, now: Long) {
    val context = LocalContext.current
    val spoken = snapshot.spoken("Tonight, ${schedule.name}, ${schedule.spokenSummary}")
    if (isSmall) {
        Column(GlanceModifier.fillMaxSize().semantics { contentDescription = spoken }) {
            // Stacked: a name and "Tonight" don't fit across a small widget together.
            snapshot.headingName?.let {
                WidgetEyebrow(it)
                Gap(3.dp)
            }
            WidgetEyebrow("Tonight")
            Gap(4.dp)
            Text(schedule.name, style = widgetText(17.sp, weight = FontWeight.Medium), maxLines = 2)
            Spacer(GlanceModifier.defaultWeight())
            WidgetCurve(schedule.phases, curveWidth, 40.dp, lineWidth = 2.5f)
            Spacer(GlanceModifier.defaultWeight())
            Text(Format.duration(schedule.totalMinutes), style = widgetText(13.sp, weight = FontWeight.Medium), maxLines = 1)
            Text("Tap to start", style = widgetText(11.sp, Theme.secondary, FontWeight.Medium), maxLines = 1)
        }
        return
    }
    Column(GlanceModifier.fillMaxSize()) {
        Header(snapshot.headed("Tonight"), schedule.name) {
            Box(
                GlanceModifier
                    .background(Color.White)
                    .cornerRadius(16.dp)
                    // Started on the BedJet the widget shows, which may not be the one on screen in Lull.
                    .clickable(actionStartActivity(AppCommands.startNightIntent(context, snapshot.bedJet(null)?.id)))
                    .semantics { contentDescription = "Start Night" }
                    .padding(horizontal = 16.dp, vertical = 7.dp),
            ) {
                Text("Start", style = widgetText(13.sp, Color.Black, FontWeight.Medium))
            }
        }
        Gap(10.dp)
        Box(GlanceModifier.semantics { contentDescription = spoken }) {
            WidgetCurve(schedule.phases, curveWidth, if (isLarge) 90.dp else 44.dp)
        }
        Gap(10.dp)
        Text(schedule.summary, style = widgetText(11.sp, Theme.secondary, FontWeight.Medium), maxLines = 1)
        if (isLarge) {
            Gap(10.dp)
            PhaseList(snapshot, schedule, now, current = null)
        }
    }
}

@Composable
private fun Empty() {
    Column(GlanceModifier.fillMaxSize().semantics { contentDescription = "No schedule yet. Open Lull to plan how your night feels." }) {
        WidgetIcon(Icons.Filled.Bedtime, Theme.secondary, 22.dp)
        Spacer(GlanceModifier.defaultWeight())
        Text("No schedule yet", style = widgetText(17.sp, weight = FontWeight.Medium))
        Gap(6.dp)
        Text("Open Lull to plan how your night feels.", style = widgetText(12.sp, Theme.secondary))
    }
}

@Composable
private fun Header(eyebrow: String, title: String, trailing: @Composable () -> Unit) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(GlanceModifier.defaultWeight()) {
            WidgetEyebrow(eyebrow)
            Gap(3.dp)
            Text(title, style = widgetText(19.sp, weight = FontWeight.Medium), maxLines = 1)
        }
        trailing()
    }
}

/** Up to four phases, keeping the one running and the next in view. */
@Composable
private fun PhaseList(snapshot: WidgetSnapshot, schedule: Schedule, startedAt: Long, current: Int?) {
    val shown = 4
    val first = maxOf(0, minOf((current ?: 0) - 1, schedule.phases.size - shown))
    var start = startedAt
    val starts = schedule.phases.map { phase -> start.also { start += phase.minutes * 60_000L } }
    Column(GlanceModifier.fillMaxWidth()) {
        schedule.phases.withIndex().drop(first).take(shown).forEach { (index, phase) ->
            PhaseLine(
                phase, snapshot.detail(phase), starts[index],
                isCurrent = index == current,
                isDone = current?.let { index < it } ?: false,
            )
        }
    }
}
