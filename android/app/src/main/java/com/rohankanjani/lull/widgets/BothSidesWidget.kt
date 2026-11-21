package com.rohankanjani.lull.widgets

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.currentState
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import com.rohankanjani.lull.AppCommands
import com.rohankanjani.lull.LullTab
import com.rohankanjani.lull.WidgetBedJet
import com.rohankanjani.lull.WidgetSnapshot
import com.rohankanjani.lull.ui.Theme

/** Two BedJets, one on each side of the bed, side by side, each picked by editing the widget. */
class BothSidesWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        WidgetUpdates.scheduleNext(context, WidgetSnapshot.load(context) ?: WidgetSnapshot())
        provideContent { BothSidesContent(currentSnapshot(), currentState(PickedLeft), currentState(PickedRight), System.currentTimeMillis()) }
    }
}

class BothSidesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = BothSidesWidget()
}

private val SideGap = 14.dp

@Composable
fun BothSidesContent(snapshot: WidgetSnapshot, left: String?, right: String?, now: Long) {
    // Each side's share of the room inside the margins, either side of the line between them.
    val side = (LocalSize.current.width - WidgetPadding * 2 - SideGap * 2 - 1.dp) / 2
    val (leftSide, rightSide) = snapshot.sides(left, right)
    Row(GlanceModifier.fillMaxSize().widgetBackground().padding(WidgetPadding)) {
        Side(snapshot, leftSide, side, now)
        Spacer(GlanceModifier.width(SideGap))
        Box(GlanceModifier.width(1.dp).fillMaxHeight().background(Theme.hairline)) {}
        Spacer(GlanceModifier.width(SideGap))
        Side(snapshot, rightSide, side, now)
    }
}

@Composable
private fun Side(snapshot: WidgetSnapshot, bedJet: WidgetBedJet?, width: Dp, now: Long) {
    val context = LocalContext.current
    val sized = GlanceModifier.width(width).fillMaxHeight()
    if (bedJet != null) {
        val tab = bedJet.live(now)?.tab ?: LullTab.SCHEDULE
        Box(sized.clickable(actionStartActivity(AppCommands.openIntent(context, tab, showing = bedJet.id)))) {
            BedJetSummary(bedJet, snapshot, now, width)
        }
    } else {
        // None known yet, only the one, or one set to a BedJet forgotten since.
        val message = when (snapshot.bedJets.size) {
            0 -> "Open Lull to choose your BedJet."
            1 -> "Add a second BedJet in Lull."
            else -> "Edit the widget to choose a BedJet."
        }
        Column(
            sized
                .clickable(actionStartActivity(AppCommands.openIntent(context, LullTab.REMOTE)))
                .semantics { contentDescription = message },
        ) {
            WidgetIcon(Icons.Filled.AddCircleOutline, Theme.secondary, 20.dp)
            Spacer(GlanceModifier.defaultWeight())
            Text(message, style = widgetText(13.sp, Theme.secondary, FontWeight.Medium))
        }
    }
}
