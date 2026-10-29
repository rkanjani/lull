package com.rohankanjani.lull.wear

import android.content.ComponentName
import android.content.Context
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.rohankanjani.lull.WidgetSnapshot

/** What the watch keeps between launches: the BedJet picked on it, and what the phone last handed over. */
interface WatchStore {
    var picked: String?
    /** When the watch last asked the phone what the BedJets are doing. */
    var askedAt: Long?
    /** Whether the Remote page shows when air turns off as a time of day rather than a duration. */
    var remoteShowsTime: Boolean
    fun load(): WidgetSnapshot?
    fun save(snapshot: WidgetSnapshot)
}

/**
 * Kept on the watch, where the tile and the complication read it too. They show the BedJet the watch shows, so what's
 * saved shows it as the one on screen.
 */
class DeviceStore(context: Context, private val saves: Boolean = true) : WatchStore {
    private val context = context.applicationContext
    private val prefs = context.getSharedPreferences("LullWatch", Context.MODE_PRIVATE)

    override var picked: String?
        get() = prefs.getString("bedJet", null)
        set(value) = prefs.edit().putString("bedJet", value).apply()

    override var askedAt: Long?
        get() = prefs.getLong("askedAt", 0).takeIf { it > 0 }
        set(value) = prefs.edit().putLong("askedAt", value ?: 0).apply()

    override var remoteShowsTime: Boolean
        get() = prefs.getBoolean("remoteShowsTime", false)
        set(value) = prefs.edit().putBoolean("remoteShowsTime", value).apply()

    override fun load(): WidgetSnapshot? = if (saves) WidgetSnapshot.load(context) else null

    override fun save(snapshot: WidgetSnapshot) {
        if (!saves) return
        if (forTileAndComplication(snapshot, picked).save(context)) redraw(context)
    }

    companion object {
        /**
         * What the tile and the complication are handed: the BedJet the watch shows as the one on screen, with its own
         * night, schedule and history, as a widget set to it would draw.
         */
        fun forTileAndComplication(snapshot: WidgetSnapshot, picked: String?): WidgetSnapshot =
            snapshot.showing(WatchModel.bedJet(snapshot, picked)?.id)

        /** Redraws the tile and the complication from what was just saved. */
        fun redraw(context: Context) {
            TileService.getUpdater(context).requestUpdate(TonightTile::class.java)
            ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, TonightComplicationService::class.java))
                .requestUpdateAll()
        }
    }
}
