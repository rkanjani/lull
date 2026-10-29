package com.rohankanjani.lull.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bed
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.SignalCellularAlt1Bar
import androidx.compose.material.icons.filled.SignalCellularAlt2Bar
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rohankanjani.lull.AppModel
import com.rohankanjani.lull.BedJet
import com.rohankanjani.lull.bedjetkit.Generation
import com.rohankanjani.lull.bedjetkit.LinkState
import com.rohankanjani.lull.bedjetkit.NearbyBedJet
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSheet(model: AppModel, bedJet: BedJet, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // The BedJets nearby open over the settings, as a screen pushed onto them.
    var showsNearby by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Theme.background, dragHandle = null) {
        if (showsNearby) {
            SheetPage("Add a BedJet", back = { showsNearby = false }) {
                NearbyBedJets(model) { showsNearby = false }
            }
        } else {
            SheetPage(if (model.hasSeveralBedJets) "BedJets" else "BedJet", done = onDismiss) {
                DeviceSettings(model, bedJet, showNearby = { showsNearby = true }, dismiss = onDismiss)
            }
        }
    }
}

/** Opened from the pill's menu: the BedJets nearby, to add one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbySheet(model: AppModel, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Theme.background, dragHandle = null) {
        SheetPage("Add a BedJet", cancel = onDismiss) {
            NearbyBedJets(model, onChosen = onDismiss)
        }
    }
}

/** A sheet's title bar, with Done, Cancel or Back, over its scrolling sections. */
@Composable
private fun SheetPage(
    title: String,
    done: (() -> Unit)? = null,
    cancel: (() -> Unit)? = null,
    back: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Full height, like a sheet on iOS, so a short page doesn't shrink it.
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.width(88.dp)) {
                if (back != null) {
                    IconButton(back, Modifier.semantics { contentDescription = "Back" }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                    }
                }
                if (cancel != null) TextButton(cancel) { Text("Cancel", fontSize = 17.sp, color = Color.White) }
            }
            Text(
                title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            Row(Modifier.width(88.dp), horizontalArrangement = Arrangement.End) {
                if (done != null) TextButton(done) { Text("Done", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White) }
            }
        }
        content()
    }
}

@Composable
private fun DeviceSettings(model: AppModel, bedJet: BedJet, showNearby: () -> Unit, dismiss: () -> Unit) {
    val title = model.title(bedJet)
    val statusText = when (bedJet.linkState) {
        LinkState.READY -> if (model.demoMode) "Demo BedJet" else "Connected"
        LinkState.CONNECTING -> "Connecting…"
        LinkState.SEARCHING -> if (bedJet.isSlowToFind) "Not found" else "Searching…"
        LinkState.YIELDED -> "In use on another phone"
        LinkState.BLUETOOTH_OFF -> "Bluetooth is off"
        LinkState.UNAUTHORIZED -> "Bluetooth not allowed"
    }
    val help = when (bedJet.linkState) {
        LinkState.READY -> if (model.demoMode) "Demo mode uses a pretend BedJet so you can explore the app."
        else "Everything stays between this phone and your BedJet. No account, no cloud."
        LinkState.CONNECTING, LinkState.SEARCHING ->
            "Stand near your BedJet and force-quit the official BedJet app. The BedJet talks to one phone " +
                "at a time; phones with Lull hand it over to each other automatically."
        LinkState.YIELDED -> "The BedJet talks to one phone at a time, and another phone with Lull asked for it. Use any control here to take it back."
        LinkState.BLUETOOTH_OFF -> "Turn on Bluetooth in Quick Settings to connect."
        LinkState.UNAUTHORIZED -> "Allow Nearby devices for Lull in Settings to connect."
    }

    if (model.hasSeveralBedJets) {
        KnownBedJets(model, footer = "Every screen shows the ticked BedJet. Switch from the top of any screen too.", onAdd = showNearby)
    }

    Section(header = title, footer = help) {
        NicknameField(bedJet)
        Labeled("Status", statusText)
        bedJet.name?.let { Labeled("Name", it) }
        Labeled("Kind", bedJet.knownGeneration?.title ?: "Known once it connects")
        bedJet.firmware?.let { Labeled("Firmware", it) }
    }

    if (bedJet.isSlowToFind) {
        Section(
            header = "Can't find $title?",
            footer = "Lull keeps looking for this BedJet while it's open, and never connects to a different one by itself.",
        ) {
            Tip(Icons.Filled.Bed, "Lull works with BedJet 3 and BedJet V2. The original BedJet isn't supported.")
            Tip(Icons.Filled.Close, "Force-quit the BedJet app: open your recent apps, then swipe the BedJet app away.")
            Tip(Icons.Filled.Smartphone, "Close the BedJet app on any other phone or tablet nearby.")
            Tip(Icons.Filled.Power, "Unplug the BedJet for 10 seconds, then plug it back in.")
            NavigationRow(Icons.AutoMirrored.Filled.List, "Choose Another BedJet", enabled = model.canSwitch, onClick = showNearby)
        }
    }

    val extra = bedJet.status?.extra
    if (bedJet.isConnected && extra != null) {
        Section(header = "BedJet") {
            Toggle("Light ring", extra.ledEnabled) { on -> model.scope.launch { bedJet.setLED(on) } }
            Toggle("Mute beeps", extra.beepsMuted) { muted -> model.scope.launch { bedJet.setMuted(muted) } }
        }
    }

    if (bedJet.deviceSlots.isNotEmpty()) {
        Section(header = "Saved on the BedJet", footer = "Lull keeps tonight's schedule in one slot and leaves the others alone.") {
            bedJet.deviceSlots.forEachIndexed { index, program ->
                val name = program?.name ?: "Empty"
                val isLull = index == bedJet.slot
                Labeled(
                    "Slot ${index + 1}", if (isLull) "Lull · $name" else name,
                    spoken = if (isLull) "Used by Lull, ${program?.name ?: "empty"}" else name,
                )
            }
        }
    }

    if (!model.hasSeveralBedJets) {
        Section(footer = "Have a BedJet on each side of the bed? Add both, then switch between them from the top of any screen.") {
            NavigationRow(Icons.Filled.Add, "Add Another BedJet", enabled = model.canSwitch, onClick = showNearby)
        }
    }

    Section(footer = "The BedJet keeps running as it is, and its history stays on this phone in case you choose it again.") {
        Text(
            "Forget This BedJet",
            fontSize = 17.sp,
            color = Theme.red,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) {
                    dismiss()
                    model.forget(bedJet)
                }
                .padding(vertical = 13.dp),
        )
    }

    Section(header = "App") {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Temperature", fontSize = 17.sp, modifier = Modifier.weight(1f))
            SegmentedChoice(
                listOf(true to "°F", false to "°C"), model.useFahrenheit, { model.useFahrenheit = it }, Modifier.width(140.dp),
                spoken = { fahrenheit -> if (fahrenheit) "Fahrenheit" else "Celsius" },
            )
        }
        Toggle("Demo mode", model.demoMode) { model.demoMode = it }
    }
}

/** Kept on this phone only; the BedJet's own name is left alone. */
@Composable
private fun NicknameField(bedJet: BedJet) {
    val focus = LocalFocusManager.current
    // The example would clip at the largest text sizes, where the field is narrow.
    val example = if (isLargeText()) null else "Such as My side"
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Nickname", fontSize = 17.sp, modifier = Modifier.clearAndSetSemantics {})
        Spacer(Modifier.width(16.dp))
        BasicTextField(
            value = bedJet.nickname.orEmpty(),
            onValueChange = { bedJet.nickname = it.take(30).ifEmpty { null } },
            singleLine = true,
            textStyle = TextStyle(fontSize = 17.sp, color = Color.White, textAlign = TextAlign.End),
            cursorBrush = SolidColor(Theme.green),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            modifier = Modifier.weight(1f).semantics { contentDescription = "Nickname" },
            decorationBox = { field ->
                if (bedJet.nickname.isNullOrEmpty() && example != null) {
                    Text(example, fontSize = 17.sp, color = Theme.tertiary, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
                }
                field()
            },
        )
    }
    HorizontalDivider(color = Theme.hairline)
}

/** Every BedJet this phone knows, to switch between. Ticked is the one on screen; each says how it's connected. */
@Composable
private fun KnownBedJets(model: AppModel, footer: String, onAdd: (() -> Unit)? = null) {
    val shown = model.bedJet
    val waits = if (!model.canSwitch && shown != null) "Switching waits until the schedule finishes saving to ${model.title(shown)}." else null
    Section(header = "Your BedJets", footer = waits ?: footer) {
        model.bedJets.forEach { bedJet ->
            val isShown = bedJet.id == model.selectedID
            val title = model.title(bedJet)
            val enabled = isShown || model.canSwitch
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .selectable(selected = isShown, enabled = enabled, role = Role.RadioButton) { model.select(bedJet.id) }
                    .clearAndSetSemantics {
                        contentDescription = "$title, ${bedJet.spokenConnectionLabel}"
                        selected = isShown
                        role = Role.RadioButton
                        if (enabled) onClick { model.select(bedJet.id) }
                    }
                    .alpha(if (enabled) 1f else 0.4f)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, fontSize = 17.sp)
                    Text(
                        listOfNotNull(bedJet.knownGeneration?.title, bedJet.connectionLabel).joinToString(" · "),
                        fontSize = 13.sp, color = Theme.secondary,
                    )
                }
                if (isShown) Icon(Icons.Filled.Check, null, tint = Theme.green, modifier = Modifier.size(22.dp))
            }
            HorizontalDivider(color = Theme.hairline)
        }
        if (onAdd != null) NavigationRow(Icons.Filled.Add, "Add Another BedJet", enabled = model.canSwitch, onClick = onAdd)
    }
}

/** BedJets advertising nearby, to pick one. Looking never connects to any of them or changes anything. */
@Composable
private fun NearbyBedJets(model: AppModel, onChosen: () -> Unit = {}) {
    DisposableEffect(Unit) {
        model.startLooking()
        onDispose { model.stopLooking() }
    }
    Section(
        header = "Nearby",
        footer = "A BedJet shows here while no phone is connected to it. If yours is missing, close the BedJet app, and Lull on other phones, then wait a moment. " +
            "Signal is a hint, not proof: the strongest isn't always the closest. A code after a name tells apart BedJets with the same name; give yours a nickname in BedJet settings.",
    ) {
        model.nearby.forEach { unit ->
            val title = model.title(unit)
            val kind = unit.generation?.title ?: "BedJet"
            val choose = {
                model.choose(unit)
                onChosen()
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(enabled = model.canSwitch, role = Role.Button, onClick = choose)
                    .clearAndSetSemantics {
                        contentDescription = "$title, $kind, ${unit.signal.title.lowercase()} signal"
                        role = Role.Button
                        if (model.canSwitch) onClick(label = "Adds this BedJet and shows it") { choose(); true }
                    }
                    .alpha(if (model.canSwitch) 1f else 0.4f)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, fontSize = 17.sp)
                    Text("$kind · ${unit.signal.title} signal", fontSize = 13.sp, color = Theme.secondary)
                }
                Icon(
                    when (unit.signal) {
                        NearbyBedJet.Signal.STRONG -> Icons.Filled.SignalCellularAlt
                        NearbyBedJet.Signal.FAIR -> Icons.Filled.SignalCellularAlt2Bar
                        NearbyBedJet.Signal.WEAK -> Icons.Filled.SignalCellularAlt1Bar
                    },
                    null, tint = Theme.secondary, modifier = Modifier.size(20.dp),
                )
            }
            HorizontalDivider(color = Theme.hairline)
        }
        when (model.lookingState) {
            LinkState.BLUETOOTH_OFF -> Tip(Icons.Filled.BluetoothDisabled, "Turn on Bluetooth in Quick Settings to look for BedJets.", divider = false)
            LinkState.UNAUTHORIZED -> Tip(Icons.Filled.Lock, "Allow Nearby devices for Lull in Settings to look for BedJets.", divider = false)
            else -> Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Theme.secondary, strokeWidth = 2.dp)
                Text(if (model.nearby.isEmpty()) "Looking for BedJets…" else "Looking for more…", fontSize = 17.sp, color = Theme.secondary)
            }
        }
    }
}

/** Shown until a BedJet is chosen: on first launch, and after forgetting the one on screen. */
@Composable
fun ChooseBedJetScreen(model: AppModel) {
    // Outside the tabs' scaffold, so it sets the text colour itself.
    Surface(Modifier.fillMaxSize(), color = Theme.background, contentColor = Color.White) { ChooseBedJet(model) }
}

@Composable
private fun ChooseBedJet(model: AppModel) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(bottom = 32.dp),
    ) {
        Text(
            "Choose Your BedJet", fontSize = 34.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp).padding(top = 8.dp).semantics { heading() },
        )
        Section {
            Text(
                "Pick the BedJet this phone should control. Lull only ever connects to BedJets you pick, so a partner's stays theirs.",
                fontSize = 17.sp, color = Theme.secondary, modifier = Modifier.padding(vertical = 12.dp),
            )
        }
        if (model.bedJets.isNotEmpty()) KnownBedJets(model, footer = "Pick one to show, or add another below.")
        NearbyBedJets(model)
        Section(footer = "No BedJet nearby? Demo mode uses pretend ones so you can explore the app.") {
            Toggle("Demo mode", model.demoMode) { model.demoMode = it }
        }
    }
}

val Generation.title: String
    get() = when (this) {
        Generation.V3 -> "BedJet 3"
        Generation.V2 -> "BedJet V2"
    }

@Composable
private fun NavigationRow(icon: ImageVector, text: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = Theme.green, modifier = Modifier.size(22.dp))
        Text(text, fontSize = 17.sp, color = Theme.green, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Theme.tertiary, modifier = Modifier.size(22.dp))
    }
}

/** A grouped list section, like a Settings screen. */
@Composable
private fun Section(header: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp).padding(top = 20.dp)) {
        if (header != null) {
            Text(
                header.uppercase(), fontSize = 13.sp, color = Theme.secondary,
                modifier = Modifier.padding(start = 16.dp, bottom = 6.dp).semantics {
                    heading()
                    contentDescription = header
                },
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Theme.raised)
                .padding(horizontal = 16.dp),
        ) {
            content()
        }
        if (footer != null) {
            Text(footer, fontSize = 13.sp, color = Theme.secondary, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp))
        }
    }
}

@Composable
private fun Tip(icon: ImageVector, text: String, divider: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, tint = Theme.green, modifier = Modifier.size(22.dp))
        Text(text, fontSize = 17.sp)
    }
    if (divider) HorizontalDivider(color = Theme.hairline)
}

@Composable
private fun Labeled(label: String, value: String, spoken: String = value) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clearAndSetSemantics { contentDescription = "$label, $spoken" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 17.sp)
        Spacer(Modifier.width(16.dp))
        Text(value, fontSize = 17.sp, color = Theme.secondary, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
    HorizontalDivider(color = Theme.hairline)
}

