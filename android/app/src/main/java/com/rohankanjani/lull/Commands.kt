package com.rohankanjani.lull

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.rohankanjani.lull.bedjetkit.Mode
import kotlinx.serialization.Serializable

/** The app's tabs, which widgets, tiles and shortcuts open to. */
enum class LullTab {
    REMOTE, SCHEDULE, HISTORY;

    /** Opens Lull on this tab when a widget is tapped. */
    val link: String get() = "lull://${name.lowercase()}"

    /** Opens Lull on this tab showing one BedJet, from something outside Lull that shows it. */
    fun link(showing: String): String = "$link?bedjet=$showing"

    companion object {
        fun from(link: String?): LullTab? = entries.firstOrNull { it.link == link?.substringBefore('?') }

        /** The BedJet a link asks to show, if it names one. */
        fun bedJet(link: String?): String? =
            link?.substringAfter('?', "")?.split('&')?.firstOrNull { it.startsWith("bedjet=") }?.removePrefix("bedjet=")?.ifEmpty { null }
    }
}

/** Air from one Quick Remote button: a mode, how long, and the temperature and fan to run it at. */
data class RemotePreset(
    val mode: Mode,
    val minutes: Int,
    /** Half-degrees Celsius. Ignored for Off, and for modes the BedJet doesn't take a temperature for. */
    val temperature: Int,
    val fanPercent: Int,
) {
    /** "Cool · 30 min", as on the widget's button. */
    val title: String get() = if (mode == Mode.STANDBY) "Off" else "${mode.title} · ${Format.duration(minutes)}"

    /** What else it sets, such as "72° · Fan 40%", read out after the button's title. Null for Off. */
    fun setting(fahrenheit: Boolean): String? {
        if (mode == Mode.STANDBY) return null
        return Format.setting(mode, temperature.takeIf { mode.usesTemperature }, fanPercent, fahrenheit)
    }

    /**
     * Kept to what the BedJet takes: the mode's temperatures, the fan in steps of 5, and no longer than a phase in that
     * mode may run, so Turbo stops at 10 minutes and hot, fast Heat at the BedJet's safety cut-off.
     */
    val clamped: RemotePreset
        get() {
            val temperature = temperature.coerceIn(mode.temperatureRange)
            val fanPercent = ((fanPercent + 2) / 5 * 5).coerceIn(5, 100)
            val longest = Phase(mode, temperature, fanPercent, minutes).maxMinutes
            return RemotePreset(mode, minutes.coerceIn(1, longest), temperature, fanPercent)
        }

    companion object {
        /** Cool for 30 minutes, Heat for 15, and Off: the buttons a Quick Remote widget starts with. */
        val defaults = PresetChoice.defaults.map { it.preset(fahrenheit = false) }
    }
}

/**
 * One Quick Remote button as set up when editing the widget. Its temperature is in the degrees Lull shows, as the
 * person chose it, or null for the mode's usual temperature.
 */
@Serializable
data class PresetChoice(val mode: Mode, val minutes: Int, val degrees: Int? = null, val fanPercent: Int = 40) {
    /** What the button sets, in the BedJet's units and within what it takes. */
    fun preset(fahrenheit: Boolean): RemotePreset {
        val temperature = degrees?.let { Format.halfCelsius(it, fahrenheit) } ?: mode.defaultTemperature
        return RemotePreset(mode, minutes, temperature, fanPercent).clamped
    }

    companion object {
        val defaults = listOf(
            PresetChoice(Mode.COOL, 30),
            PresetChoice(Mode.HEAT, 15),
            PresetChoice(Mode.STANDBY, 30),
        )
    }
}

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

    /** The command an intent that opened the app carries, if any. */
    fun from(intent: Intent?): Command? = when {
        intent == null -> null
        intent.action == ACTION_START_NIGHT -> Command.StartNight(intent.getStringExtra(EXTRA_BEDJET))
        intent.action == ACTION_REMOTE_PRESET -> remotePreset(intent)?.let { Command.Remote(it, intent.getStringExtra(EXTRA_BEDJET)) }
        else -> LullTab.from(intent.dataString)?.let { Command.Show(it, LullTab.bedJet(intent.dataString)) }
    }

    /**
     * Opens Lull to start tonight, on `bedJet` when named, such as the one a Tonight widget shows. Each BedJet's has its
     * own address, so their buttons stay apart.
     */
    fun startNightIntent(context: Context, bedJet: String? = null): Intent =
        Intent(context, MainActivity::class.java).setAction(ACTION_START_NIGHT).apply {
            if (bedJet != null) {
                data = Uri.parse(LullTab.SCHEDULE.link(showing = bedJet))
                putExtra(EXTRA_BEDJET, bedJet)
            }
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    /**
     * Opens Lull to set a BedJet to what a Quick Remote button holds: `bedJet` when named, such as one side of the bed,
     * otherwise the one on screen. The address carries the values, so buttons set differently stay apart.
     */
    fun remoteIntent(context: Context, preset: RemotePreset, bedJet: String? = null): Intent {
        val link = "lull://remote?mode=${preset.mode.name}&minutes=${preset.minutes}&temperature=${preset.temperature}" +
            "&fan=${preset.fanPercent}" + (bedJet?.let { "&bedjet=$it" } ?: "")
        return Intent(ACTION_REMOTE_PRESET, Uri.parse(link), context, MainActivity::class.java)
            .putExtra(EXTRA_MODE, preset.mode.name)
            .putExtra(EXTRA_MINUTES, preset.minutes)
            .putExtra(EXTRA_TEMPERATURE, preset.temperature)
            .putExtra(EXTRA_FAN, preset.fanPercent)
            .apply { if (bedJet != null) putExtra(EXTRA_BEDJET, bedJet) }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
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
