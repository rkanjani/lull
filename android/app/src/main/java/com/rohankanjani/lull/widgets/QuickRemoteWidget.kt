package com.rohankanjani.lull.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
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
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.currentState
import com.rohankanjani.lull.AppCommands
import com.rohankanjani.lull.AppModel
import com.rohankanjani.lull.Format
import com.rohankanjani.lull.LiveStatus
import com.rohankanjani.lull.LullTab
import com.rohankanjani.lull.PresetChoice
import com.rohankanjani.lull.RemotePreset
import com.rohankanjani.lull.WidgetSnapshot
import com.rohankanjani.lull.activity
import com.rohankanjani.lull.title
import com.rohankanjani.lull.bedjetkit.Mode
import com.rohankanjani.lull.ui.Theme
import com.rohankanjani.lull.ui.icon
import com.rohankanjani.lull.ui.tint
import kotlinx.serialization.builtins.ListSerializer

/**
 * A few taps for a hot or cold night: each button opens Lull and sets the widget's BedJet to it. Editing the widget
 * chooses the BedJet, by name, and what each button sets.
 */
class QuickRemoteWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        WidgetUpdates.scheduleNext(context, WidgetSnapshot.load(context) ?: WidgetSnapshot())
        provideContent {
            val snapshot = currentSnapshot()
            val presets = choices(currentState(PRESETS)).map { it.preset(snapshot.useFahrenheit) }
            QuickRemoteContent(snapshot, presets, currentState(PickedBedJet), System.currentTimeMillis())
        }
    }

    companion object {
        /** What this widget's buttons set, as chosen by editing it; none is the three a new widget has. */
        val PRESETS = stringPreferencesKey("presets")
        private val serializer = ListSerializer(PresetChoice.serializer())

        fun choices(saved: String?): List<PresetChoice> =
            saved?.let { runCatching { AppModel.json.decodeFromString(serializer, it) }.getOrNull() }
                ?.takeIf { it.size == PresetChoice.defaults.size } ?: PresetChoice.defaults

        fun save(choices: List<PresetChoice>): String = AppModel.json.encodeToString(serializer, choices)
    }
}

class QuickRemoteWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = QuickRemoteWidget()
}

@Composable
fun QuickRemoteContent(snapshot: WidgetSnapshot, presets: List<RemotePreset>, bedJetId: String?, now: Long) {
    val context = LocalContext.current
    val isMedium = LocalSize.current.width >= TonightWidget.MEDIUM.width
    Column(
        GlanceModifier
            .fillMaxSize()
            .widgetBackground()
            .clickable(actionStartActivity(AppCommands.openIntent(context, LullTab.REMOTE, showing = bedJetId)))
            .padding(WidgetPadding),
    ) {
        Status(snapshot, bedJetId, now, isMedium)
        if (isMedium) {
            // The buttons take the height left, as on the iPhone.
            Gap(8.dp)
            Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                presets.forEachIndexed { index, preset ->
                    if (index > 0) Spacer(GlanceModifier.width(8.dp))
                    PresetButton(preset, bedJetId, snapshot.useFahrenheit, isStacked = true, GlanceModifier.defaultWeight().fillMaxHeight())
                }
            }
        } else {
            Spacer(GlanceModifier.defaultWeight())
            presets.forEachIndexed { index, preset ->
                if (index > 0) Gap(6.dp)
                PresetButton(preset, bedJetId, snapshot.useFahrenheit, isStacked = false, GlanceModifier.fillMaxWidth())
            }
        }
    }
}

/**
 * What the BedJet is doing, so a tap is made knowing: "❄ COOLING  0:24:10", under its name once there's more than one
 * to tell apart, as on the Tonight widget.
 */
@Composable
private fun Status(snapshot: WidgetSnapshot, bedJetId: String?, now: Long, isMedium: Boolean) {
    val bedJet = snapshot.bedJet(bedJetId)
    val live = bedJet?.live(now)
    if (live == null) {
        // A widget set to a BedJet that has since been forgotten says so; its buttons do nothing.
        WidgetEyebrow(bedJet?.name ?: if (bedJetId == null) "BedJet Remote" else "Forgotten BedJet")
        return
    }
    if (snapshot.hasSeveralBedJets) {
        WidgetEyebrow(bedJet.name)
        Gap(3.dp)
    }
    val mode = when (live) {
        is LiveStatus.Night -> live.run.phase(now)?.phase?.mode ?: Mode.STANDBY
        is LiveStatus.Air -> live.mode
    }
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // The small size has room for the mode's symbol beside the time, not its name.
        if (isMedium) {
            ModeLabel(mode)
        } else {
            Box(GlanceModifier.semantics { contentDescription = mode.activity }) { WidgetIcon(mode.icon, mode.tint, 14.dp) }
        }
        // At the far end, as on the iPhone. A spacer doesn't push the system's timer view over, so its box takes the room.
        Box(GlanceModifier.defaultWeight(), contentAlignment = Alignment.CenterEnd) {
            Countdown(live.endsAt, 13.sp, Theme.secondary)
        }
    }
}

/**
 * One button. The medium size's are tall and narrow, so the mode's icon, its name and the time stack; the small size's
 * are short and wide, so they read along one line: "Cool · 30 min".
 */
@Composable
private fun PresetButton(preset: RemotePreset, bedJetId: String?, fahrenheit: Boolean, isStacked: Boolean, modifier: GlanceModifier) {
    val context = LocalContext.current
    val tint = preset.mode.tint
    val button = modifier
        .background(tint.copy(alpha = 0.16f))
        .cornerRadius(12.dp)
        .clickable(actionStartActivity(AppCommands.remoteIntent(context, preset, bedJetId)))
        .semantics {
            contentDescription = if (preset.mode == Mode.STANDBY) "Turn off" else "${preset.title}, ${preset.setting(fahrenheit)}"
        }
    if (isStacked) {
        Column(
            button.padding(horizontal = 4.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WidgetIcon(preset.mode.icon, tint, 18.dp)
            Gap(4.dp)
            Text(preset.mode.title, style = widgetText(13.sp, tint, FontWeight.Medium), maxLines = 1)
            if (preset.mode != Mode.STANDBY) {
                Gap(2.dp)
                Text(Format.duration(preset.minutes), style = widgetText(12.sp, tint.copy(alpha = 0.75f), FontWeight.Medium), maxLines = 1)
            }
        }
    } else {
        Row(
            button.padding(horizontal = 8.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WidgetIcon(preset.mode.icon, tint, 13.dp)
            Spacer(GlanceModifier.width(6.dp))
            Text(preset.title, style = widgetText(13.sp, tint, FontWeight.Medium), maxLines = 1)
        }
    }
}
