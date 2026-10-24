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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.lifecycleScope
import com.rohankanjani.lull.WidgetBedJet
import com.rohankanjani.lull.WidgetSnapshot
import com.rohankanjani.lull.ui.Card
import com.rohankanjani.lull.ui.Eyebrow
import com.rohankanjani.lull.ui.LullTheme
import com.rohankanjani.lull.ui.PrimaryButton
import com.rohankanjani.lull.ui.Theme
import kotlinx.coroutines.launch

/**
 * Picks which BedJet a Tonight or Last Night widget shows, or which two a Both Sides widget shows, by name, as
 * the iPhone's widgets do when edited, so a widget by each side of the bed shows its own side. Listed from what the app
 * last handed the widgets. A new widget starts on the BedJet on screen in Lull (Both Sides with it on the left), and when
 * Lull knows one BedJet or none, there's nothing to pick, so it's set up without asking.
 */
class BedJetConfigureActivity : ComponentActivity() {
    private val widgetId by lazy {
        intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Backing out leaves the widget as it was, or doesn't add a new one.
        setResult(RESULT_CANCELED, result())
        // Launchers open this from outside, so only one of Lull's own widgets is set up here.
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider
        val widget = provider?.takeIf { it.packageName == packageName }?.let { widgetFor(it.className) } ?: return finish()
        lifecycleScope.launch {
            val glanceId = GlanceAppWidgetManager(this@BedJetConfigureActivity).getGlanceIdBy(widgetId)
            val state = widget.getAppWidgetState<Preferences>(this@BedJetConfigureActivity, glanceId)
            val snapshot = WidgetSnapshot.load(this@BedJetConfigureActivity) ?: WidgetSnapshot()
            if (widget is BothSidesWidget) {
                // A new widget starts with the BedJet on screen on the left, as an iPhone's does until it's edited.
                val (left, right) = snapshot.sides(state[PickedLeft] ?: snapshot.usualBedJet?.id, state[PickedRight])
                fun pick(state: MutablePreferences, left: String?, right: String?) {
                    if (left != null) state[PickedLeft] = left
                    if (right != null) state[PickedRight] = right
                }
                // With two, which goes on the left is still a choice.
                if (snapshot.bedJets.size <= 1 && state[PickedLeft] == null) {
                    save(widget, glanceId) { pick(it, left?.id, right?.id) }
                } else {
                    setContent {
                        LullTheme {
                            BothSidesPicker(snapshot.bedJets, left?.id, right?.id) { l, r -> save(widget, glanceId) { pick(it, l, r) } }
                        }
                    }
                }
            } else {
                val selected = state[PickedBedJet] ?: snapshot.usualBedJet?.id
                if (snapshot.bedJets.size <= 1 && state[PickedBedJet] == null) {
                    save(widget, glanceId) { if (selected != null) it[PickedBedJet] = selected }
                } else {
                    setContent { LullTheme { BedJetPicker(snapshot.bedJets, selected) { id -> save(widget, glanceId) { it[PickedBedJet] = id } } } }
                }
            }
        }
    }

    private fun save(widget: GlanceAppWidget, glanceId: GlanceId, edit: (MutablePreferences) -> Unit) {
        lifecycleScope.launch {
            updateAppWidgetState(this@BedJetConfigureActivity, glanceId) { edit(it) }
            widget.update(this@BedJetConfigureActivity, glanceId)
            setResult(RESULT_OK, result())
            finish()
        }
    }

    private fun result() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)

    companion object {
        /** The widget a launcher is setting up, by its receiver, among those that show a BedJet picked by name. */
        fun widgetFor(receiver: String): GlanceAppWidget? = when (receiver) {
            TonightWidgetReceiver::class.java.name -> TonightWidget()
            LastNightWidgetReceiver::class.java.name -> LastNightWidget()
            BothSidesWidgetReceiver::class.java.name -> BothSidesWidget()
            else -> null
        }
    }
}

/** Each BedJet the app last handed the widgets, by name, the one the widget shows checked. Choosing one sets it. */
@Composable
internal fun BedJetPicker(bedJets: List<WidgetBedJet>, selected: String?, onChoose: (bedJetId: String) -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Theme.background).systemBarsPadding().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("BedJet", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        Text("Which BedJet this widget shows.", color = Theme.secondary)
        if (bedJets.isEmpty()) Text("Open Lull to choose your BedJet.", color = Theme.secondary)
        bedJets.forEach { bedJet ->
            BedJetRow(bedJet.name, isSelected = bedJet.id == selected, color = Theme.raised) { onChoose(bedJet.id) }
        }
    }
}

/** The BedJet on each side of a Both Sides widget, by name, and Save. */
@Composable
internal fun BothSidesPicker(bedJets: List<WidgetBedJet>, left: String?, right: String?, onSave: (left: String?, right: String?) -> Unit) {
    // Kept through the activity being recreated, such as when the phone turns.
    var leftId by rememberSaveable { mutableStateOf(left) }
    var rightId by rememberSaveable { mutableStateOf(right) }
    Column(
        Modifier.fillMaxSize().background(Theme.background).systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("BedJets", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        Text("Which BedJet each side of this widget shows.", color = Theme.secondary)
        Card(padding = 18.dp) { BedJetChooser("Left side", bedJets, leftId) { leftId = it } }
        Card(padding = 18.dp) { BedJetChooser("Right side", bedJets, rightId) { rightId = it } }
        PrimaryButton("Save", onClick = { onSave(leftId, rightId) }, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * A titled list of BedJets by name to choose one from, as on the iPhone. One a widget was set to that has since been
 * forgotten stays listed until another is chosen.
 */
@Composable
internal fun BedJetChooser(title: String, bedJets: List<WidgetBedJet>, selected: String?, onChoose: (String) -> Unit) {
    val choices = bedJets.map { it.id to it.name } +
        listOfNotNull(selected?.takeIf { id -> bedJets.none { it.id == id } }?.let { it to "Forgotten BedJet" })
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow(title, Modifier.semantics { heading() })
        if (choices.isEmpty()) Text("Open Lull to choose your BedJet.", color = Theme.secondary)
        choices.forEach { (id, name) ->
            val isSelected = id == selected
            BedJetRow(name, isSelected, color = if (isSelected) Theme.raised else Color.Transparent) { onChoose(id) }
        }
    }
}

@Composable
private fun BedJetRow(name: String, isSelected: Boolean, color: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(color)
            .selectable(isSelected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
        if (isSelected) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White)
    }
}
