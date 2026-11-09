package com.rohankanjani.lull

import com.rohankanjani.lull.bedjetkit.Mode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * What the watch asks the phone to do. The watch never reaches the BedJet itself: Lull on the phone holds the
 * Bluetooth link, so it connects, does the errand, lets go again, and hands back what the widgets show.
 *
 * Sent as JSON with its keys in order, the same bytes as the iPhone apps send, so one test pins both.
 */
sealed interface WatchCommand {
    /** The BedJet it's for, or null for the one on screen on the phone. */
    val bedJet: String?

    /** Starts tonight's schedule on a BedJet, or on the one on screen on the phone when none is named. */
    data class StartNight(override val bedJet: String? = null) : WatchCommand

    /** Ends the schedule running on a BedJet, turning it off. */
    data class EndNight(override val bedJet: String? = null) : WatchCommand

    /**
     * Sets a BedJet as the Remote does: a mode, and the temperature, fan and timer to run it at. From the watch's
     * Remote, only the settings changed there are sent on; null sets them all, as a Quick Remote button does.
     */
    data class Remote(
        val preset: RemotePreset,
        override val bedJet: String? = null,
        val changed: Set<RemoteSetting>? = null,
    ) : WatchCommand

    /** Asks what the BedJets are doing now, for a watch that hasn't heard in a while. */
    data object Refresh : WatchCommand {
        override val bedJet: String? get() = null
    }

    /**
     * Whether the phone says how it went. A refresh only hands over what the widgets show: the watch waits on one
     * errand at a time and takes any reply as that errand's, so a refresh's would end an errand sent while it ran.
     */
    val isAnswered: Boolean get() = this != Refresh

    val data: ByteArray
        get() = buildJsonObject {
            // In the order the iPhone app's sorted keys put them.
            bedJet?.let { put("bedJet", it) }
            // In a set order, so both phones send the same bytes.
            if (this@WatchCommand is Remote) changed?.let { changed ->
                put("changed", buildJsonArray { RemoteSetting.entries.filter { it in changed }.forEach { add(it.wireName) } })
            }
            if (this@WatchCommand is Remote) put("fanPercent", preset.fanPercent)
            put("kind", kind)
            if (this@WatchCommand is Remote) {
                put("minutes", preset.minutes)
                put("mode", remoteName(preset.mode))
                put("temperature", preset.temperature)
            }
        }.toString().toByteArray()

    private val kind: String
        get() = when (this) {
            is StartNight -> "startNight"
            is EndNight -> "endNight"
            is Remote -> "remote"
            Refresh -> "refresh"
        }

    companion object {
        fun from(data: ByteArray): WatchCommand? = runCatching {
            val values = lullJson.parseToJsonElement(data.decodeToString()).jsonObject
            // One named but unreadable is refused rather than taken as none named, which would mean the one on screen.
            val bedJet = if ("bedJet" in values) values.string("bedJet")?.takeIf(::isIdentifier) ?: return null else null
            when (values.string("kind")) {
                "startNight" -> StartNight(bedJet)
                "endNight" -> EndNight(bedJet)
                "refresh" -> Refresh
                "remote" -> {
                    // Named as the Quick Remote names them, so a mode the BedJet adds later can't be mistaken for another.
                    val mode = remoteNames.entries.firstOrNull { it.value == values.string("mode") }?.key ?: return null
                    val preset = RemotePreset(mode, values.int("minutes"), values.int("temperature"), values.int("fanPercent"))
                    // A setting a later version adds is left out, rather than refusing the rest.
                    val changed = values["changed"]?.jsonArray?.mapNotNull { name ->
                        RemoteSetting.entries.firstOrNull { it.wireName == name.jsonPrimitive.also { require(it.isString) }.content }
                    }?.toSet()
                    Remote(preset, bedJet, changed)
                }
                else -> null
            }
        }.getOrNull()

        /** Whether it reads as a BedJet's identifier, which both phones make as a UUID. */
        private fun isIdentifier(text: String): Boolean = runCatching { UUID.fromString(text).toString().equals(text, ignoreCase = true) }.getOrDefault(false)

        /** The iPhone app's names for the modes a Quick Remote button can set. */
        private val remoteNames = linkedMapOf(
            Mode.COOL to "cool",
            Mode.HEAT to "heat",
            Mode.EXTENDED_HEAT to "gentleHeat",
            Mode.TURBO to "turbo",
            Mode.DRY to "dry",
            Mode.STANDBY to "off",
        )

        private fun remoteName(mode: Mode): String = remoteNames[mode] ?: "off"
    }
}

/**
 * A setting on the Remote. The watch's Remote says which it changed, so the phone sends on only those and leaves the
 * rest as the BedJet has them, which may have moved on since the watch last heard. Every errand carries all four,
 * so a phone that doesn't know this sets them all, as before.
 */
enum class RemoteSetting(val wireName: String) {
    MODE("mode"),
    TEMPERATURE("temperature"),
    FAN("fan"),
    TIME("time"),
}

/** How an errand from the watch went. The phone answers at once that it's on it, then again once it's done. */
data class WatchReply(
    val ok: Boolean,
    /** What went wrong, in the words the phone's alert would use. */
    val message: String? = null,
) {
    val data: ByteArray
        get() = buildJsonObject {
            message?.let { put("message", it) }
            put("ok", ok)
        }.toString().toByteArray()

    companion object {
        val done = WatchReply(ok = true)

        fun failed(message: String) = WatchReply(ok = false, message = message)

        fun from(data: ByteArray): WatchReply? = runCatching {
            val values = lullJson.parseToJsonElement(data.decodeToString()).jsonObject
            WatchReply(values.getValue("ok").jsonPrimitive.content.toBooleanStrict(), values.string("message"))
        }.getOrNull()
    }
}

/** Where each kind of message goes between the phone and the watch. */
object WatchMessages {
    /** A command from the watch. */
    const val COMMAND = "/lull/command"
    /** How a command went. */
    const val REPLY = "/lull/reply"
    /** What the widgets show, which the watch shows too. */
    const val SNAPSHOT = "/lull/snapshot"
    /** Phones with Lull say so, which is how the watch finds one to ask (res/values/wear.xml). */
    const val PHONE_CAPABILITY = "lull_phone"
    /** Watches with Lull say so, which is who the phone hands what the widgets show. */
    const val WATCH_CAPABILITY = "lull_watch"
    /** How long the watch waits to hear how an errand went, in milliseconds. */
    const val PATIENCE_MILLIS = 30_000L
    /** What the watch says when it has waited that long, and the phone when it drops an errand too late to finish. */
    const val TOO_LATE = "Lull on your phone didn't answer in time. Open it there to check on your BedJet."
    /** What the phone says when the BedJet was busy with something else Lull was doing, so the errand left it alone. */
    const val BUSY = "Lull on your phone is busy with your BedJet. Try again in a moment."
}

private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int
