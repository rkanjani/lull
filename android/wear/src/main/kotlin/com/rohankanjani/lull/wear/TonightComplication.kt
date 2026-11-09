package com.rohankanjani.lull.wear

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.CountDownTimeReference
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.rohankanjani.lull.ActiveRun
import com.rohankanjani.lull.Format
import com.rohankanjani.lull.LullTab
import com.rohankanjani.lull.Schedule
import com.rohankanjani.lull.WidgetSnapshot
import com.rohankanjani.lull.activity
import com.rohankanjani.lull.shortLength
import com.rohankanjani.lull.spokenSummary
import com.rohankanjani.lull.widgets.clock
import java.time.Instant

/**
 * Tonight on the watch face, worded as on the phone's Lock Screen and the Apple Watch: where the night is up to once
 * it's running, otherwise tonight's schedule. It reads what the watch last heard from Lull on the phone.
 */
class TonightComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        Format.followClockSetting(this)
        return data(request.complicationType, WidgetSnapshot.load(this) ?: WidgetSnapshot(), System.currentTimeMillis())
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? = data(type, WidgetSnapshot.preview(), System.currentTimeMillis())

    private fun data(type: ComplicationType, snapshot: WidgetSnapshot, now: Long): ComplicationData? {
        val run = snapshot.run(now)
        val tonight = snapshot.tonight
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, WearActivity::class.java).setData(Uri.parse(snapshot.onScreen?.let(LullTab.SCHEDULE::link) ?: LullTab.SCHEDULE.link)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val moon = MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_tile_night)).build()
        return when {
            run != null -> {
                val mode = run.phase(now)?.phase?.mode
                // Time left in the phase, as the widgets and the Apple Watch say it. It counts down by itself, and the
                // complication is redrawn every few minutes, so it moves on to the next phase soon after this one ends.
                val phaseEnds = run.phaseEnds.firstOrNull { it > now } ?: run.endsAt
                val left = TimeDifferenceComplicationText.Builder(TimeDifferenceStyle.SHORT_DUAL_UNIT, CountDownTimeReference(Instant.ofEpochMilli(phaseEnds))).build()
                val spoken = text(spokenRun(snapshot, run, now))
                when (type) {
                    ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(left, spoken).setMonochromaticImage(moon).setTapAction(tap).build()
                    ComplicationType.LONG_TEXT ->
                        LongTextComplicationData.Builder(text("${mode?.activity ?: "Finishing"} · ends ${clock(run.endsAt)}"), spoken)
                            .setTitle(text(run.schedule.name)).setMonochromaticImage(moon).setTapAction(tap).build()
                    ComplicationType.RANGED_VALUE ->
                        RangedValueComplicationData.Builder(run.progress(now).toFloat(), 0f, 1f, spoken)
                            .setText(left).setMonochromaticImage(moon).setTapAction(tap).build()
                    else -> null
                }
            }
            tonight != null -> {
                val spoken = text(spokenTonight(snapshot, tonight))
                val length = text(tonight.shortLength)
                when (type) {
                    ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(length, spoken).setMonochromaticImage(moon).setTapAction(tap).build()
                    ComplicationType.LONG_TEXT ->
                        LongTextComplicationData.Builder(text("Tonight: ${tonight.name}"), spoken).setMonochromaticImage(moon).setTapAction(tap).build()
                    ComplicationType.RANGED_VALUE ->
                        RangedValueComplicationData.Builder(0f, 0f, 1f, spoken).setText(length).setMonochromaticImage(moon).setTapAction(tap).build()
                    else -> null
                }
            }
            else -> {
                val spoken = text("Lull, no schedule yet")
                when (type) {
                    ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text("Lull"), spoken).setMonochromaticImage(moon).setTapAction(tap).build()
                    ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(text("No schedule yet"), spoken).setMonochromaticImage(moon).setTapAction(tap).build()
                    ComplicationType.RANGED_VALUE ->
                        RangedValueComplicationData.Builder(0f, 0f, 1f, spoken).setText(text("Lull")).setMonochromaticImage(moon).setTapAction(tap).build()
                    else -> null
                }
            }
        }
    }

    private fun text(value: String) = PlainComplicationText.Builder(value).build()
}

/**
 * Where the night is up to, as TalkBack reads it on the complication and the tile: the words VoiceOver reads on the
 * Apple Watch's complication. Once the last phase is over there's no phase or time left to say, only when it ends.
 */
internal fun spokenRun(snapshot: WidgetSnapshot, run: ActiveRun, now: Long): String {
    val current = run.phase(now)
    return snapshot.spoken(
        listOfNotNull(
            run.schedule.name,
            current?.let { "phase ${it.index + 1} of ${run.schedule.phases.size}" },
            current?.let { "${it.phase.mode.activity}, ${Format.spokenDuration(it.minutesLeft)} left" },
            "ends at ${clock(run.endsAt)}",
        ).joinToString(", "),
    )
}

/** Tonight's schedule before it starts, as TalkBack reads it on the complication and the tile. */
internal fun spokenTonight(snapshot: WidgetSnapshot, schedule: Schedule): String =
    snapshot.spoken("Tonight, ${schedule.name}, ${schedule.spokenSummary}")
