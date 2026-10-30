package com.rohankanjani.lull

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.rohankanjani.lull.bedjetkit.Mode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * One of Lull's automations as an app such as Tasker sends it: an action and its extras, with the same options as
 * the iPhone app's Shortcuts actions. BedJets and schedules are named as Lull shows them.
 */
data class AutomationCall(
    val action: String,
    /** Without one, the BedJet Lull shows. */
    val bedJet: String? = null,
    /** Without one, tonight's. */
    val schedule: String? = null,
    val mode: String? = null,
    /** In the unit Lull shows. */
    val temperature: Int? = null,
    val fan: Int? = null,
    val minutes: Int? = null,
    val on: Boolean? = null,
    val muted: Boolean? = null,
    /** Where the reply goes; without one, any app listening for it hears it. */
    val replyPackage: String? = null,
) {
    /** The tab that shows what the action did, when it is sent to Lull's screen. Reading the status changes nothing, so none. */
    val tab: LullTab?
        get() = when (action) {
            START_NIGHT -> LullTab.SCHEDULE
            GET_STATUS -> null
            else -> LullTab.REMOTE
        }

    /** The BedJet the call names, or the one Lull shows. */
    fun target(model: AppModel): BedJet {
        val name = bedJet ?: return model.automationTarget(null)
        val named = model.bedJets.firstOrNull { model.title(it).equals(name, ignoreCase = true) }
            ?: model.bedJets.singleOrNull { it.displayName.equals(name, ignoreCase = true) }
            ?: throw AutomationFailure("Lull doesn't know a BedJet called “$name”.")
        return model.automationTarget(named.id)
    }

    /** What the call asks the BedJet to do. Null for Get BedJet Status, which only reads what Lull knows. */
    fun request(model: AppModel): AutomationRequest? = when (action) {
        START_NIGHT -> AutomationRequest.StartNight(
            schedule?.let { name ->
                // Names can repeat, as with copies of programs read from a BedJet, so a name must pick out one.
                val named = model.schedules.filter { it.name.equals(name, ignoreCase = true) }
                when (named.size) {
                    0 -> throw AutomationFailure("Lull has no schedule called “$name”.")
                    1 -> named.first().id
                    else -> throw AutomationFailure("Lull has more than one schedule called “$name”. Rename one in Lull.")
                }
            },
        )
        TURN_ON -> AutomationRequest.Air(AirSettings(airMode(), temperature, fan, minutes))
        TURN_OFF -> AutomationRequest.TurnOff
        SET_TEMPERATURE -> AutomationRequest.Air(AirSettings(degrees = temperature ?: throw missing("temperature")))
        SET_FAN -> AutomationRequest.Air(AirSettings(fanPercent = fan ?: 40))
        SET_RUN_TIME -> AutomationRequest.Air(AirSettings(minutes = minutes ?: 60))
        SET_LIGHT_RING -> AutomationRequest.LightRing(on ?: true)
        MUTE_BEEPS -> AutomationRequest.MuteBeeps(muted ?: true)
        else -> null
    }

    /** Heat unless another is named, as on iPhone. */
    private fun airMode(): Mode {
        val name = mode ?: return Mode.HEAT
        return remoteModes.firstOrNull { it.automationName.equals(name.trim(), ignoreCase = true) }
            ?: throw AutomationFailure("Mode must be cool, heat, gentle_heat, turbo or dry.")
    }

    private fun missing(extra: String) = AutomationFailure("Add the “$extra” extra.")

    /** For handing to the worker. Only the extras the call has are kept. */
    fun toData(sentAt: Long): Data = Data.Builder().apply {
        putString(ACTION, action)
        putLong(SENT_AT, sentAt)
        bedJet?.let { putString(BEDJET, it) }
        schedule?.let { putString(SCHEDULE, it) }
        mode?.let { putString(MODE, it) }
        temperature?.let { putInt(TEMPERATURE, it) }
        fan?.let { putInt(FAN, it) }
        minutes?.let { putInt(MINUTES, it) }
        on?.let { putBoolean(ON, it) }
        muted?.let { putBoolean(MUTED, it) }
        replyPackage?.let { putString(REPLY_PACKAGE, it) }
    }.build()

    companion object {
        private const val PREFIX = "com.rohankanjani.lull."
        const val START_NIGHT = PREFIX + "START_NIGHT"
        const val TURN_ON = PREFIX + "TURN_ON"
        const val TURN_OFF = PREFIX + "TURN_OFF"
        const val SET_TEMPERATURE = PREFIX + "SET_TEMPERATURE"
        const val SET_FAN = PREFIX + "SET_FAN"
        const val SET_RUN_TIME = PREFIX + "SET_RUN_TIME"
        const val SET_LIGHT_RING = PREFIX + "SET_LIGHT_RING"
        const val MUTE_BEEPS = PREFIX + "MUTE_BEEPS"
        const val GET_STATUS = PREFIX + "GET_STATUS"
        /** How an action went, broadcast back to the app that sent it. */
        const val RESULT = PREFIX + "RESULT"
        val ACTIONS = listOf(START_NIGHT, TURN_ON, TURN_OFF, SET_TEMPERATURE, SET_FAN, SET_RUN_TIME, SET_LIGHT_RING, MUTE_BEEPS, GET_STATUS)

        const val BEDJET = "bedjet"
        const val SCHEDULE = "schedule"
        const val MODE = "mode"
        const val TEMPERATURE = "temperature"
        const val FAN = "fan"
        const val MINUTES = "minutes"
        const val ON = "on"
        const val MUTED = "muted"
        const val REPLY_PACKAGE = "reply_package"
        /** The options a call can carry, so Start Night with none is told from one that names a schedule. */
        val OPTIONS = listOf(BEDJET, SCHEDULE, MODE, TEMPERATURE, FAN, MINUTES, ON, MUTED, REPLY_PACKAGE)
        private const val ACTION = "action"
        private const val SENT_AT = "sent_at"

        /**
         * Reads a call from an action and its extras. Automation apps send numbers and switches as text, numbers
         * or booleans, so each is read whichever way it comes.
         */
        fun from(action: String?, extra: (String) -> Any?): AutomationCall? {
            if (action == null || action !in ACTIONS) return null
            return AutomationCall(
                action = action,
                bedJet = text(extra(BEDJET)),
                schedule = text(extra(SCHEDULE)),
                mode = text(extra(MODE)),
                temperature = number(extra(TEMPERATURE)),
                fan = number(extra(FAN)),
                minutes = number(extra(MINUTES)),
                on = switch(extra(ON)),
                muted = switch(extra(MUTED)),
                replyPackage = text(extra(REPLY_PACKAGE)),
            )
        }

        @Suppress("DEPRECATION")
        fun from(intent: Intent): AutomationCall? = from(intent.action) { intent.extras?.get(it) }

        fun from(data: Data): AutomationCall? = from(data.getString(ACTION)) { data.keyValueMap[it] }

        fun sentAt(data: Data): Long = data.getLong(SENT_AT, 0)

        private fun text(value: Any?): String? = value?.toString()?.trim()?.takeIf { it.isNotEmpty() }

        private fun number(value: Any?): Int? = when (value) {
            is Int -> value
            // Kept within an Int, as the other kinds are, rather than wrapping round to some other number.
            is Long -> value.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
            is Double -> value.takeUnless { it.isNaN() }?.roundToInt()
            is Float -> value.takeUnless { it.isNaN() }?.roundToInt()
            is String -> value.trim().toIntOrNull() ?: value.trim().toDoubleOrNull()?.takeUnless { it.isNaN() }?.roundToInt()
            else -> null
        }

        private fun switch(value: Any?): Boolean? = when (value) {
            is Boolean -> value
            is Number -> value.toDouble() != 0.0
            is String -> when (value.trim().lowercase()) {
                "true", "on", "yes", "1" -> true
                "false", "off", "no", "0" -> false
                else -> null
            }
            else -> null
        }
    }
}

/** How automations name a mode, as the iPhone app's Shortcuts actions do. */
val Mode.automationName: String
    get() = when (this) {
        Mode.COOL -> "cool"
        Mode.HEAT -> "heat"
        Mode.EXTENDED_HEAT -> "gentle_heat"
        Mode.TURBO -> "turbo"
        Mode.DRY -> "dry"
        Mode.WAIT -> "pause"
        Mode.STANDBY -> "off"
    }

/**
 * Lull's automations, for apps such as Tasker, MacroDroid or Samsung Modes and Routines: the iPhone app's Shortcuts
 * actions as intents. As on iPhone, an action runs without opening Lull when the BedJet is free. When it isn't, the
 * reply says so, and sending the same intent to Lull's screen finishes it there.
 */
class AutomationReceiver : BroadcastReceiver() {
    private companion object {
        const val QUEUE = "com.rohankanjani.lull.automations"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val call = AutomationCall.from(intent) ?: return
        val app = context.applicationContext as Application
        // Connecting and talking to the BedJet takes longer than a broadcast may, and Android won't start a foreground
        // service from the background, so it runs as an expedited job. Jobs join one queue, so they run one at a time in
        // the order they were sent, as Shortcuts runs actions: a Turn On sent just before a Set Fan comes first, and Get
        // BedJet Status, though it never connects, waits for the actions sent before it.
        val work = OneTimeWorkRequestBuilder<AutomationWorker>()
            .setInputData(call.toData(sentAt = System.currentTimeMillis()))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(QUEUE, ExistingWorkPolicy.APPEND_OR_REPLACE, work)
    }
}

class AutomationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.Main) {
        val app = applicationContext as Application
        val call = AutomationCall.from(inputData) ?: return@withContext Result.success()
        // Android may run a job late, or again after it was stopped; a bedtime action long after bedtime is worse than none.
        // A late status changes nothing, so it is still answered.
        val late = System.currentTimeMillis() - AutomationCall.sentAt(inputData) > 120_000
        if (late && call.action != AutomationCall.GET_STATUS) {
            reply(app, call, failure = "Android ran this action more than 2 minutes late, so Lull skipped it.")
        } else {
            runAutomation((app as LullApplication).model(), call, onScreen = false)
        }
        // Always a success, so a failed action never holds up the ones queued after it.
        Result.success()
    }
}

/**
 * Runs a call and replies with how it went. On Lull's screen it comes from Lull's own Turn On and Turn Off shortcuts,
 * which, like its widgets and tiles, set the BedJet only once it's free: one another phone has, or running a schedule
 * another phone started, is left as it is, shown in Lull for the person to decide.
 */
suspend fun runAutomation(model: AppModel, call: AutomationCall, onScreen: Boolean) {
    val app = model.application
    try {
        val target = call.target(model)
        // Reading the status changes nothing, so it leaves the BedJet on screen as it is, as on iPhone.
        val request = call.request(model) ?: return replyWithStatus(model, call, target)
        if (onScreen) {
            model.select(target.id)
            if (!target.reachesIfFree()) return
        }
        reply(app, call, done = model.perform(request, target, mayAsk = onScreen))
    } catch (error: CancellationException) {
        throw error
    } catch (error: AutomationError.NeedsLull) {
        reply(app, call, failure = "${error.message} Open Lull to finish.", needsLull = true)
    } catch (error: Exception) {
        val message = error.message.orEmpty()
        if (onScreen) model.errorMessage = message
        reply(app, call, failure = message)
    }
}

private fun replyWithStatus(model: AppModel, call: AutomationCall, target: BedJet) {
    val app = model.application
    val title = model.title(target)
    val report = model.status(target)
    reply(app, call, done = report.summary(title, model.useFahrenheit)) {
        putExtra("bedjet", title)
        putExtra("night_running", report.nightRunning)
        report.schedule?.let { putExtra("schedule", it) }
        report.nightEnds?.let { putExtra("night_ends", it) }
        putExtra("mode", report.mode.automationName)
        report.temperature?.let { putExtra("temperature", Format.degrees(it, model.useFahrenheit)) }
        putExtra("temperature_unit", if (model.useFahrenheit) "°F" else "°C")
        report.fanPercent?.let { putExtra("fan", it) }
        report.lastSeen?.let { putExtra("last_seen", it) }
    }
}

/** Tells the automation app how an action went, as Shortcuts shows it on iPhone. */
private fun reply(
    app: Application,
    call: AutomationCall,
    done: String? = null,
    failure: String? = null,
    needsLull: Boolean = false,
    details: Intent.() -> Unit = {},
) {
    val intent = Intent(AutomationCall.RESULT)
        .putExtra("action", call.action)
        .putExtra("ok", failure == null)
        .putExtra("needs_lull", needsLull)
        .putExtra("message", failure ?: done.orEmpty())
        .apply(details)
    call.replyPackage?.let(intent::setPackage)
    app.sendBroadcast(intent)
}
