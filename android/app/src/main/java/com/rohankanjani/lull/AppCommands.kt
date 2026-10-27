package com.rohankanjani.lull

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.rohankanjani.lull.bedjetkit.Mode

/**
 * Passes what a widget, tile or shortcut asked for to the app, which picks it up once its screens are ready.
 * Those all open the app, which may start before its screens are, so commands wait here until then.
 */
object AppCommands {
    sealed interface Command {
        /** Opens a tab, showing one BedJet when the link names it. */
        data class Show(val tab: LullTab, val bedJet: String? = null) : Command
        /** Starts tonight on a BedJet, or on the one on screen when none is named. */
        data class StartNight(val bedJet: String? = null) : Command
        /** A Quick Remote button: sets a BedJet to a preset, or the one on screen when none is named. */
        data class Remote(val preset: RemotePreset, val bedJet: String? = null) : Command
    }

    /** What a widget, tile or shortcut asks for by starting the app. */
    const val ACTION_START_NIGHT = "com.rohankanjani.lull.START_NIGHT"
    const val ACTION_REMOTE_PRESET = "com.rohankanjani.lull.REMOTE_PRESET"
    private const val EXTRA_MODE = "mode"
    private const val EXTRA_MINUTES = "minutes"
    private const val EXTRA_TEMPERATURE = "temperature"
    private const val EXTRA_FAN = "fan"
    private const val EXTRA_BEDJET = "bedjet"

    var handler: ((Command) -> Unit)? = null
        set(value) {
            field = value
            deliver()
        }

    private val pending = mutableListOf<Command>()

    fun send(command: Command) {
        pending += command
        deliver()
    }

    /**
     * The command an intent from a widget, tile or shortcut carries, if any. Only [CommandActivity] reads these, as no
     * other app can open it.
     */
    fun from(intent: Intent?): Command? = when {
        intent == null -> null
        intent.action == ACTION_START_NIGHT -> Command.StartNight(intent.getStringExtra(EXTRA_BEDJET))
        intent.action == ACTION_REMOTE_PRESET -> remotePreset(intent)?.let { Command.Remote(it, intent.getStringExtra(EXTRA_BEDJET)) }
        else -> shown(intent)
    }

    /**
     * The tab a link that opened Lull's screen asks for, if any. Any app can open that screen, so a link is all it acts
     * on: starting a night or setting the BedJet goes through [CommandActivity].
     */
    fun shown(intent: Intent?): Command.Show? =
        LullTab.from(intent?.dataString)?.let { Command.Show(it, LullTab.bedJet(intent?.dataString)) }

    /**
     * Opens Lull to start tonight, on `bedJet` when named, such as the one a Tonight widget shows. Each BedJet's has its
     * own address, so their buttons stay apart.
     */
    fun startNightIntent(context: Context, bedJet: String? = null): Intent =
        Intent(context, CommandActivity::class.java).setAction(ACTION_START_NIGHT).apply {
            if (bedJet != null) {
                data = Uri.parse(LullTab.SCHEDULE.link(showing = bedJet))
                putExtra(EXTRA_BEDJET, bedJet)
            }
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Opens Lull to set a BedJet to what a Quick Remote button holds: `bedJet` when named, such as one side of the bed,
     * otherwise the one on screen. The address carries the values, so buttons set differently stay apart.
     */
    fun remoteIntent(context: Context, preset: RemotePreset, bedJet: String? = null): Intent {
        val link = "lull://remote?mode=${preset.mode.name}&minutes=${preset.minutes}&temperature=${preset.temperature}" +
            "&fan=${preset.fanPercent}" + (bedJet?.let { "&bedjet=$it" } ?: "")
        return Intent(ACTION_REMOTE_PRESET, Uri.parse(link), context, CommandActivity::class.java)
            .putExtra(EXTRA_MODE, preset.mode.name)
            .putExtra(EXTRA_MINUTES, preset.minutes)
            .putExtra(EXTRA_TEMPERATURE, preset.temperature)
            .putExtra(EXTRA_FAN, preset.fanPercent)
            .apply { if (bedJet != null) putExtra(EXTRA_BEDJET, bedJet) }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** The preset a Quick Remote button's intent carries, or null when it names no mode Lull knows. */
    private fun remotePreset(intent: Intent): RemotePreset? {
        val mode = intent.getStringExtra(EXTRA_MODE)?.let { name -> Mode.entries.firstOrNull { it.name == name } } ?: return null
        val usual = PresetChoice(mode, 30).preset(fahrenheit = false)
        return RemotePreset(
            mode,
            intent.getIntExtra(EXTRA_MINUTES, usual.minutes),
            intent.getIntExtra(EXTRA_TEMPERATURE, usual.temperature),
            intent.getIntExtra(EXTRA_FAN, usual.fanPercent),
        )
    }

    fun openIntent(context: Context, tab: LullTab, showing: String? = null): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(showing?.let(tab::link) ?: tab.link), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private fun deliver() {
        val handler = handler ?: return
        val commands = pending.toList()
        pending.clear()
        commands.forEach(handler)
    }
}

/**
 * Where a widget, tile or shortcut's Start Night and Quick Remote buttons open Lull. It isn't exported, so only Lull's
 * own buttons reach it: Lull's screen is open to every app, which could otherwise start Heat with a button of its own
 * making. It passes the command on and brings Lull's screen forward, which acts on it once it's ready.
 */
class CommandActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCommands.from(intent)?.let(AppCommands::send)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
