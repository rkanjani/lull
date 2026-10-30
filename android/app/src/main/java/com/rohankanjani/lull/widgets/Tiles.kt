package com.rohankanjani.lull.widgets

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.rohankanjani.lull.AppCommands
import com.rohankanjani.lull.AutomationCall
import com.rohankanjani.lull.LullTab
import com.rohankanjani.lull.WidgetSnapshot

/** Starts tonight's schedule from Quick Settings. Lull needs to be open to reach the BedJet, so this opens it. */
class StartNightTile : TileService() {
    override fun onStartListening() {
        val tile = qsTile ?: return
        val snapshot = WidgetSnapshot.load(this) ?: WidgetSnapshot()
        val run = snapshot.run(System.currentTimeMillis())
        tile.label = if (run != null) "Night Running" else "Start Night"
        tile.subtitle = run?.schedule?.name ?: snapshot.tonight?.name ?: "No schedule"
        tile.state = if (run != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onClick() = openApp(AppCommands.startNightIntent(this))
}

/** Opens straight to the Remote. */
class RemoteTile : TileService() {
    override fun onStartListening() {
        val tile = qsTile ?: return
        tile.label = "BedJet Remote"
        tile.state = Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onClick() = openApp(AppCommands.openIntent(this, LullTab.REMOTE))
}

@SuppressLint("StartActivityAndCollapseDeprecated")
private fun TileService.openApp(intent: Intent) {
    if (Build.VERSION.SDK_INT >= 34) {
        startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    } else {
        @Suppress("DEPRECATION")
        startActivityAndCollapse(intent)
    }
}

/** Asks Quick Settings to redraw the tiles, after what they show has changed. */
fun requestTileUpdates(context: Context) {
    TileService.requestListeningState(context, ComponentName(context, StartNightTile::class.java))
}

/**
 * Lull's actions on a long press of its icon, and for the assistant: Start Night, Turn On, Turn Off and the Remote,
 * as the iPhone app offers them to Siri. They open Lull, which does them on screen.
 */
fun publishShortcuts(context: Context) {
    ShortcutManagerCompat.setDynamicShortcuts(
        context,
        listOf(
            ShortcutInfoCompat.Builder(context, "start-night")
                .setShortLabel("Start Night")
                .setLongLabel("Start tonight's schedule")
                .setIcon(IconCompat.createWithBitmap(Icons.Filled.NightsStay.toBitmap(Color(0xFF4DA3FF), 96)))
                .setIntent(AppCommands.startNightIntent(context))
                .build(),
            ShortcutInfoCompat.Builder(context, "turn-on")
                .setShortLabel("Turn On")
                .setLongLabel("Turn on your BedJet")
                .setIcon(IconCompat.createWithBitmap(Icons.Filled.PowerSettingsNew.toBitmap(Color(0xFF4DA3FF), 96)))
                .setIntent(AppCommands.actionIntent(context, AutomationCall.TURN_ON))
                .build(),
            ShortcutInfoCompat.Builder(context, "turn-off")
                .setShortLabel("Turn Off")
                .setLongLabel("Turn off your BedJet")
                .setIcon(IconCompat.createWithBitmap(Icons.Filled.Bedtime.toBitmap(Color(0xFF4DA3FF), 96)))
                .setIntent(AppCommands.actionIntent(context, AutomationCall.TURN_OFF))
                .build(),
            ShortcutInfoCompat.Builder(context, "remote")
                .setShortLabel("Remote")
                .setLongLabel("Open the BedJet remote")
                .setIcon(IconCompat.createWithBitmap(Icons.Filled.SettingsRemote.toBitmap(Color(0xFF4DA3FF), 96)))
                .setIntent(AppCommands.openIntent(context, LullTab.REMOTE))
                .build(),
        ),
    )
}
