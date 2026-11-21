package com.rohankanjani.lull.wear

import android.content.Context
import android.graphics.Bitmap
import androidx.concurrent.futures.ResolvableFuture
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.LayoutElementBuilders.Image
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.compose.ui.graphics.toArgb
import com.google.common.util.concurrent.ListenableFuture
import com.rohankanjani.lull.Format
import com.rohankanjani.lull.LiveStatus
import com.rohankanjani.lull.LullTab
import com.rohankanjani.lull.Phase
import com.rohankanjani.lull.WidgetSnapshot
import com.rohankanjani.lull.summary
import com.rohankanjani.lull.ui.Theme
import com.rohankanjani.lull.widgets.ending
import com.rohankanjani.lull.widgets.nightCurveBitmap
import java.io.ByteArrayOutputStream

/**
 * Tonight as a tile, the Wear OS twin of the Apple Watch's Tonight complication and the phone's Start Night control:
 * tonight's schedule with Start Night, or where the night is up to once it's running. It reads what the watch last
 * heard from Lull on the phone, and plays the night out by the clock.
 */
class TonightTile : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        Format.followClockSetting(this)
        val now = System.currentTimeMillis()
        val snapshot = WidgetSnapshot.load(this) ?: WidgetSnapshot()
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(resourcesVersion(snapshot, now))
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(this, snapshot, now)))
            // Redrawn as the night moves on: every few minutes, and as each phase begins and the night ends.
            .setFreshnessIntervalMillis(snapshot.redraws(now).firstOrNull { it > now }?.let { it - now } ?: 0)
            .build()
        return ResolvableFuture.create<TileBuilders.Tile>().apply { set(tile) }
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        val now = System.currentTimeMillis()
        val snapshot = WidgetSnapshot.load(this) ?: WidgetSnapshot()
        val resources = ResourceBuilders.Resources.Builder().setVersion(requestParams.version)
        curve(snapshot, now)?.let { (phases, progress) ->
            val density = resources().displayMetrics.density
            val bitmap = nightCurveBitmap(phases, (CURVE_WIDTH * density).toInt(), (CURVE_HEIGHT * density).toInt(), density, progress, 2.5f)
            val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            resources.addIdToImageMapping(
                CURVE,
                ResourceBuilders.ImageResource.Builder()
                    .setInlineResource(
                        ResourceBuilders.InlineImageResource.Builder()
                            .setData(png)
                            .setWidthPx(bitmap.width)
                            .setHeightPx(bitmap.height)
                            .setFormat(ResourceBuilders.IMAGE_FORMAT_UNDEFINED)
                            .build(),
                    )
                    .build(),
            )
        }
        return ResolvableFuture.create<ResourceBuilders.Resources>().apply { set(resources.build()) }
    }

    private fun resources() = applicationContext.resources

    companion object {
        private const val CURVE = "curve"
        private const val CURVE_WIDTH = 150f
        private const val CURVE_HEIGHT = 34f

        /** The curve the tile draws, with how far through the night it is, or null with no schedule. */
        private fun curve(snapshot: WidgetSnapshot, now: Long): Pair<List<Phase>, Double?>? {
            snapshot.run(now)?.let { return it.schedule.phases to it.progress(now) }
            return snapshot.tonight?.let { it.phases to null }
        }

        /** Changes whenever the curve does, so the watch asks for it again; it moves along every minute or so. */
        private fun resourcesVersion(snapshot: WidgetSnapshot, now: Long): String =
            "${curve(snapshot, now)?.let { (phases, progress) -> phases.hashCode() * 31 + ((progress ?: -1.0) * 200).toInt() }}"

        fun layout(context: Context, snapshot: WidgetSnapshot, now: Long): LayoutElementBuilders.LayoutElement {
            val run = snapshot.run(now)
            val tonight = snapshot.tonight
            val column = Column.Builder().setWidth(expand()).setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            when {
                run != null -> {
                    val current = run.phase(now)
                    val about = described(spokenRun(snapshot, run, now))
                    about.addContent(text(snapshot.headed(current?.let { "Phase ${it.index + 1} of ${run.schedule.phases.size}" } ?: "Finishing").uppercase(), 11f, Theme.secondary.toArgb(), bold = true))
                    about.addContent(text(run.schedule.name, 16f, WHITE, bold = true))
                    about.addContent(gap(4f))
                    about.addContent(curveImage())
                    about.addContent(gap(4f))
                    about.addContent(text("${Format.shortDuration(current?.minutesLeft ?: 0)} left", 14f, WHITE, bold = true))
                    about.addContent(text(LiveStatus.Night(run).ending, 12f, Theme.secondary.toArgb()))
                    column.addContent(about.build())
                }
                tonight != null -> {
                    val about = described(spokenTonight(snapshot, tonight))
                    about.addContent(text(snapshot.headed("Tonight").uppercase(), 11f, Theme.secondary.toArgb(), bold = true))
                    about.addContent(text(tonight.name, 16f, WHITE, bold = true))
                    about.addContent(text(tonight.summary, 12f, Theme.secondary.toArgb()))
                    about.addContent(gap(4f))
                    about.addContent(curveImage())
                    column.addContent(about.build())
                    column.addContent(gap(6f))
                    // Outside the description, so TalkBack still finds Start Night as a button of its own.
                    column.addContent(startNight(context, snapshot.onScreen))
                }
                else -> {
                    column.addContent(text("No schedule yet", 15f, WHITE, bold = true))
                    column.addContent(text("Open Lull to plan how your night feels.", 12f, Theme.secondary.toArgb(), lines = 3))
                }
            }
            return Box.Builder()
                .setWidth(expand())
                .setHeight(expand())
                .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(open(context, "open", snapshot.onScreen, startNight = false)).build())
                .addContent(column.build())
                .build()
        }

        private const val WHITE = 0xFFFFFFFF.toInt()

        /**
         * What the tile says about the night, which TalkBack reads as one, in the complication's words, rather than line
         * by line with the eyebrow in capitals and the time left as "1h 20m".
         */
        private fun described(spoken: String) =
            Column.Builder()
                .setWidth(expand())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .setModifiers(ModifiersBuilders.Modifiers.Builder().setSemantics(ModifiersBuilders.Semantics.Builder().setContentDescription(spoken).build()).build())

        private fun text(value: String, size: Float, color: Int, bold: Boolean = false, lines: Int = 1) =
            Text.Builder()
                .setText(value)
                .setMaxLines(lines)
                .setFontStyle(
                    FontStyle.Builder()
                        .setSize(sp(size))
                        .setColor(argb(color))
                        .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL)
                        .build(),
                )
                .build()

        private fun gap(height: Float) = Spacer.Builder().setHeight(dp(height)).build()

        // Images by resource ID, handed over in onTileResourcesRequest. Newer tiles can declare them with the layout
        // instead (ProtoLayoutScope), which older watches don't support.
        @Suppress("DEPRECATION")
        private fun curveImage() = Image.Builder().setResourceId(CURVE).setWidth(dp(CURVE_WIDTH)).setHeight(dp(CURVE_HEIGHT)).build()

        /** Start Night, which opens the watch app to ask the phone, as the phone's Start Night control opens Lull. */
        private fun startNight(context: Context, bedJet: String?): LayoutElementBuilders.LayoutElement =
            Box.Builder()
                .setWidth(dp(120f))
                .setHeight(dp(40f))
                .setModifiers(
                    ModifiersBuilders.Modifiers.Builder()
                        .setBackground(
                            ModifiersBuilders.Background.Builder()
                                .setColor(argb(WHITE))
                                .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(20f)).build())
                                .build(),
                        )
                        .setClickable(open(context, "startNight", bedJet, startNight = true))
                        .setSemantics(ModifiersBuilders.Semantics.Builder().setContentDescription("Start Night").build())
                        .build(),
                )
                .addContent(text("Start Night", 14f, 0xFF000000.toInt(), bold = true))
                .build()

        private fun open(context: Context, id: String, bedJet: String?, startNight: Boolean): ModifiersBuilders.Clickable {
            val activity = ActionBuilders.AndroidActivity.Builder()
                .setPackageName(context.packageName)
                .setClassName(WearActivity::class.java.name)
                .addKeyToExtraMapping(WearActivity.EXTRA_LINK, ActionBuilders.stringExtra(bedJet?.let(LullTab.SCHEDULE::link) ?: LullTab.SCHEDULE.link))
            if (startNight) {
                activity.addKeyToExtraMapping(WearActivity.EXTRA_START_NIGHT, ActionBuilders.booleanExtra(true))
                activity.addKeyToExtraMapping(WearActivity.EXTRA_TOKEN, ActionBuilders.stringExtra(StartNightToken.get(context)))
            }
            return ModifiersBuilders.Clickable.Builder()
                .setId(id)
                .setOnClick(ActionBuilders.LaunchAction.Builder().setAndroidActivity(activity.build()).build())
                .build()
        }
    }
}

