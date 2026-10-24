package com.rohankanjani.lull.widgets

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.lifecycleScope
import com.rohankanjani.lull.AppModel
import com.rohankanjani.lull.Format
import com.rohankanjani.lull.Phase
import com.rohankanjani.lull.PresetChoice
import com.rohankanjani.lull.WidgetBedJet
import com.rohankanjani.lull.WidgetSnapshot
import com.rohankanjani.lull.bedjetkit.Mode
import com.rohankanjani.lull.defaultTemperature
import com.rohankanjani.lull.remoteModes
import com.rohankanjani.lull.ui.Card
import com.rohankanjani.lull.ui.Eyebrow
import com.rohankanjani.lull.ui.LabeledSlider
import com.rohankanjani.lull.ui.LullTheme
import com.rohankanjani.lull.ui.ModePicker
import com.rohankanjani.lull.ui.PrimaryButton
import com.rohankanjani.lull.ui.Theme
import com.rohankanjani.lull.ui.tint
import kotlinx.coroutines.launch

/**
 * Chooses which BedJet a Quick Remote widget sets and what its three buttons set it to, as the iPhone's widget does when
 * edited: a mode, how long, and the temperature and fan to run it at. Lists the BedJets the app last handed the widgets.
 */
class QuickRemoteConfigureActivity : ComponentActivity() {
    private val widgetId by lazy {
        intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Backing out leaves the widget as it was, or doesn't add a new one.
        setResult(RESULT_CANCELED, result())
        // Launchers open this from outside, so only a Quick Remote widget of Lull's own is set up here.
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider
        if (provider?.packageName != packageName || provider.className != QuickRemoteWidgetReceiver::class.java.name) return finish()
        // Degrees in the unit Lull shows, as the iPhone's widget editor takes them.
        val fahrenheit = getSharedPreferences("Lull", MODE_PRIVATE).getBoolean("useFahrenheit", AppModel.usesUSUnits())
        lifecycleScope.launch {
            val glanceId = GlanceAppWidgetManager(this@QuickRemoteConfigureActivity).getGlanceIdBy(widgetId)
            val state = QuickRemoteWidget().getAppWidgetState<Preferences>(this@QuickRemoteConfigureActivity, glanceId)
            val choices = QuickRemoteWidget.choices(state[QuickRemoteWidget.PRESETS])
            val snapshot = WidgetSnapshot.load(this@QuickRemoteConfigureActivity)
            val bedJet = state[PickedBedJet] ?: snapshot?.usualBedJet?.id
            setContent { LullTheme { QuickRemoteEditor(snapshot?.bedJets.orEmpty(), bedJet, choices, fahrenheit, ::save) } }
        }
    }

    private fun save(bedJetId: String?, choices: List<PresetChoice>) {
        lifecycleScope.launch {
            val glanceId = GlanceAppWidgetManager(this@QuickRemoteConfigureActivity).getGlanceIdBy(widgetId)
            updateAppWidgetState(this@QuickRemoteConfigureActivity, glanceId) { state ->
                if (bedJetId == null) state.remove(PickedBedJet) else state[PickedBedJet] = bedJetId
                state[QuickRemoteWidget.PRESETS] = QuickRemoteWidget.save(choices)
            }
            QuickRemoteWidget().update(this@QuickRemoteConfigureActivity, glanceId)
            setResult(RESULT_OK, result())
            finish()
        }
    }

    private fun result() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
}

/**
 * The BedJet the buttons set, by name, the three buttons, each a mode with its time, temperature and fan, and Save. A
 * null BedJet happens only before Lull knows one, when the buttons set the one on screen.
 */
@Composable
internal fun QuickRemoteEditor(
    bedJets: List<WidgetBedJet>,
    initialBedJet: String?,
    initial: List<PresetChoice>,
    fahrenheit: Boolean,
    onSave: (bedJetId: String?, choices: List<PresetChoice>) -> Unit,
) {
    var bedJetId by rememberSaveable { mutableStateOf(initialBedJet) }
    // Kept through the activity being recreated, such as when the phone turns, as the saved widget state is not.
    var choices by rememberSaveable(stateSaver = Saver(save = { QuickRemoteWidget.save(it) }, restore = { QuickRemoteWidget.choices(it) })) {
        mutableStateOf(initial)
    }
    // White text by default, as in the app, for the sliders' values.
    Surface(Modifier.fillMaxSize(), color = Theme.background, contentColor = Color.White) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Buttons", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
            Text("What each button sets your BedJet to.", color = Theme.secondary)
            Card(padding = 18.dp) {
                BedJetChooser("BedJet", bedJets, bedJetId) { bedJetId = it }
            }
            choices.forEachIndexed { index, choice ->
                Card(padding = 18.dp) {
                    ButtonEditor(index + 1, choice, fahrenheit) { edited -> choices = choices.toMutableList().also { it[index] = edited } }
                }
            }
            PrimaryButton("Save", onClick = { onSave(bedJetId, choices) }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ButtonEditor(number: Int, choice: PresetChoice, fahrenheit: Boolean, onChange: (PresetChoice) -> Unit) {
    val mode = choice.mode
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Eyebrow("Button $number", Modifier.semantics { heading() })
        ModePicker(remoteModes + Mode.STANDBY, mode) { picked ->
            // A new mode starts at its usual temperature, and its time shortens to what it may run.
            val longest = Phase(picked, picked.defaultTemperature, choice.fanPercent, choice.minutes).maxMinutes
            onChange(choice.copy(mode = picked, degrees = null, minutes = choice.minutes.coerceAtMost(longest)))
        }
        if (mode == Mode.STANDBY) return@Column
        val temperature = choice.preset(fahrenheit).temperature
        val longest = Phase(mode, temperature, choice.fanPercent, choice.minutes).maxMinutes
        val minutes = choice.minutes.coerceAtMost(longest)
        LabeledSlider(
            "Duration", Format.duration(minutes), minutes.toFloat(),
            onValueChange = { onChange(choice.copy(minutes = it.toInt())) },
            range = 5f..(longest / 5 * 5).coerceAtLeast(10).toFloat(), step = 5f, spokenValue = Format.spokenDuration(minutes),
        )
        if (mode.usesTemperature) {
            // The Remote's TemperatureSlider reads the unit from the app, which isn't running here; this is the same slider.
            val low = Format.degrees(mode.temperatureRange.first, fahrenheit)
            val high = maxOf(Format.degrees(mode.temperatureRange.last, fahrenheit), low + 1)
            LabeledSlider(
                "Temperature", "${Format.degrees(temperature, fahrenheit)}°", Format.degrees(temperature, fahrenheit).toFloat(),
                onValueChange = { onChange(choice.copy(degrees = it.toInt())) },
                range = low.toFloat()..high.toFloat(), tint = mode.tint,
            )
        }
        if (mode.usesFan) {
            LabeledSlider(
                "Fan", "${choice.fanPercent}%", choice.fanPercent.toFloat(),
                onValueChange = { onChange(choice.copy(fanPercent = it.toInt())) },
                range = 5f..100f, step = 5f, tint = mode.tint,
            )
        }
    }
}
