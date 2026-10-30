package com.rohankanjani.lull.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.ArrowCircleDown
import androidx.compose.material.icons.filled.Bed
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.SignalCellularAlt1Bar
import androidx.compose.material.icons.filled.SignalCellularAlt2Bar
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.rohankanjani.lull.AppModel
import com.rohankanjani.lull.BedJet
import com.rohankanjani.lull.WiFiActivity
import com.rohankanjani.lull.WiFiNetworks
import com.rohankanjani.lull.bedjetkit.Codec
import com.rohankanjani.lull.bedjetkit.Generation
import com.rohankanjani.lull.bedjetkit.LinkState
import com.rohankanjani.lull.bedjetkit.NearbyBedJet
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSheet(model: AppModel, bedJet: BedJet, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // The BedJets nearby and the Wi-Fi network open over the settings, as screens pushed onto them.
    var showsNearby by rememberSaveable { mutableStateOf(false) }
    var showsWiFi by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Theme.background, dragHandle = null) {
        if (showsNearby) {
            SheetPage("Add a BedJet", back = { showsNearby = false }) {
                NearbyBedJets(model) { showsNearby = false }
            }
        } else if (showsWiFi) {
            SheetPage("Wi-Fi", back = { showsWiFi = false }) {
                WiFiSetup(model, bedJet)
            }
        } else {
            SheetPage(if (model.hasSeveralBedJets) "BedJets" else "BedJet", done = onDismiss) {
                DeviceSettings(model, bedJet, showNearby = { showsNearby = true }, showWiFi = { showsWiFi = true }, dismiss = onDismiss)
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
private fun DeviceSettings(model: AppModel, bedJet: BedJet, showNearby: () -> Unit, showWiFi: () -> Unit, dismiss: () -> Unit) {
    val title = model.title(bedJet)
    // Restarting to check for firmware drops the connection for a few seconds; it's expected, not lost.
    val statusText = if (bedJet.isRestarting) "Restarting…" else when (bedJet.linkState) {
        LinkState.READY -> if (model.demoMode) "Demo BedJet" else "Connected"
        LinkState.CONNECTING -> "Connecting…"
        LinkState.SEARCHING -> if (bedJet.isSlowToFind) "Not found" else "Searching…"
        LinkState.YIELDED -> "In use on another phone"
        LinkState.BLUETOOTH_OFF -> "Bluetooth is off"
        LinkState.UNAUTHORIZED -> "Bluetooth not allowed"
    }
    val help = when (if (bedJet.isRestarting) LinkState.READY else bedJet.linkState) {
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

    val extra = bedJet.settings
    // Kept, but off, while the BedJet restarts to check for firmware, so the sheet doesn't jump about.
    if ((bedJet.isConnected || bedJet.isRestarting) && extra != null) {
        Section(header = "BedJet") {
            Toggle("Light ring", extra.ledEnabled, enabled = bedJet.isConnected) { on -> model.scope.launch { bedJet.setLED(on) } }
            Toggle("Mute beeps", extra.beepsMuted, enabled = bedJet.isConnected) { muted -> model.scope.launch { bedJet.setMuted(muted) } }
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

    // A BedJet V2's firmware can't be updated from a phone.
    if (bedJet.knownGeneration == Generation.V3) {
        FirmwareSection(model, bedJet)
        WiFiSection(bedJet, showWiFi)
    }

    if (!model.hasSeveralBedJets) {
        Section(footer = "Have a BedJet on each side of the bed? Add both, then switch between them from the top of any screen.") {
            NavigationRow(Icons.Filled.Add, "Add Another BedJet", enabled = model.canSwitch, onClick = showNearby)
        }
    }

    Section(footer = "The BedJet keeps running as it is, and its history stays on this phone in case you choose it again.") {
        ButtonRow("Forget This BedJet", enabled = true, color = Theme.red, divider = false) {
            dismiss()
            model.forget(bedJet)
        }
    }

    Section(header = "App") {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Temperature", fontSize = 17.sp, modifier = Modifier.weight(1f))
            SingleChoiceSegmentedButtonRow(Modifier.width(140.dp)) {
                listOf(true to "°F", false to "°C").forEachIndexed { index, (fahrenheit, label) ->
                    SegmentedButton(
                        selected = model.useFahrenheit == fahrenheit,
                        onClick = { model.useFahrenheit = fahrenheit },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                        icon = {},
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = Theme.neutral.copy(alpha = 0.5f),
                            activeContentColor = Color.White,
                            inactiveContainerColor = Theme.raised,
                            inactiveContentColor = Color.White,
                            activeBorderColor = Theme.hairline,
                            inactiveBorderColor = Theme.hairline,
                        ),
                        modifier = Modifier.semantics { contentDescription = if (fahrenheit) "Fahrenheit" else "Celsius" },
                    ) { Text(label) }
                }
            }
        }
        Toggle("Demo mode", model.demoMode) { model.demoMode = it }
    }
}

/** Kept on this phone only; the BedJet's own name is left alone. */
@Composable
private fun NicknameField(bedJet: BedJet) {
    TextRow("Nickname", bedJet.nickname.orEmpty(), example = "Such as My side", autoCorrect = true, imeAction = ImeAction.Done) {
        bedJet.nickname = it.take(30).ifEmpty { null }
    }
}

/** A BedJet 3 downloads its own firmware over Wi-Fi. Lull gives it a network and asks it to check. */
@Composable
private fun FirmwareSection(model: AppModel, bedJet: BedJet) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    val footer = if (bedJet.updateWaitsForSchedule) {
        "A schedule is running. Check once it has finished, since updating restarts your BedJet."
    } else if (!bedJet.isConnected && !bedJet.isRestarting) {
        "Lull checks for updates while it's connected to your BedJet."
    } else {
        "Your BedJet checks for updates itself, over Wi-Fi, and restarts to do it, which turns off the air for a few seconds. If there's a newer version, it installs it straight away."
    }
    Section(header = "Firmware", footer = footer) {
        if (bedJet.updateAvailable) Tip(Icons.Filled.ArrowCircleDown, "Your BedJet says newer firmware is available.")
        val activity = bedJet.wifiActivity?.takeIf { it.purpose == WiFiActivity.Purpose.UPDATING }
        WiFiButton("Check for Update", activity, enabled = bedJet.canUpdateFirmware, divider = activity?.outcome != null) { confirming = true }
        if (activity?.outcome != null) WiFiOutcomeRow(activity.outcome, activity.failed, divider = false)
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Check for a firmware update?") },
            text = {
                Text("Your BedJet restarts to check, which turns off the air for a few seconds. If there's a newer version, it installs it straight away. Keep it plugged in until it's back.")
            },
            confirmButton = {
                TextButton({
                    confirming = false
                    model.scope.launch { bedJet.updateFirmware() }
                }) { Text("Check") }
            },
            dismissButton = { TextButton({ confirming = false }) { Text("Cancel") } },
            containerColor = Theme.raised,
        )
    }
}

/** The network a BedJet 3 downloads its firmware over, and setting it. */
@Composable
private fun WiFiSection(bedJet: BedJet, showWiFi: () -> Unit) {
    Section(header = "Wi-Fi", footer = "Your BedJet uses Wi-Fi only to download firmware updates.") {
        NavigationRow(Icons.Filled.Wifi, "Network", enabled = true, value = bedJet.wifiNetwork, onClick = showWiFi)
    }
}

/** A button that says what the BedJet is doing in place of its title while it's at what the button asked for. */
@Composable
private fun WiFiButton(text: String, activity: WiFiActivity?, enabled: Boolean, divider: Boolean, onClick: () -> Unit) {
    if (activity == null || !activity.isRunning) {
        ButtonRow(text, enabled, divider = divider, onClick = onClick)
        return
    }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = false, role = Role.Button) {}.padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The words say it's under way, so TalkBack reads just those.
        CircularProgressIndicator(Modifier.size(18.dp).clearAndSetSemantics {}, color = Theme.green, strokeWidth = 2.dp)
        Text(activity.step, fontSize = 17.sp, color = Theme.secondary)
    }
    if (divider) HorizontalDivider(color = Theme.hairline)
}

/** How joining Wi-Fi or checking for firmware went, below the button that asked. */
@Composable
private fun WiFiOutcomeRow(outcome: String, failed: Boolean, divider: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) {}.padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            if (failed) Icons.Filled.Warning else Icons.Filled.CheckCircle, null,
            tint = if (failed) Theme.warm else Theme.green, modifier = Modifier.size(22.dp),
        )
        Text(outcome, fontSize = 17.sp)
    }
    if (divider) HorizontalDivider(color = Theme.hairline)
}

/**
 * Gives a BedJet 3 the Wi-Fi network it fetches firmware over: one from the networks nearby, or any name, such as a
 * hidden network's. Lull remembers the name it gave, never the password.
 */
@Composable
private fun WiFiSetup(model: AppModel, bedJet: BedJet) {
    val context = LocalContext.current
    val networks = remember { WiFiNetworks(context) }
    var allowed by remember { mutableStateOf(bedJet.isDemo || networks.allowed) }
    var approximate by remember { mutableStateOf(!bedJet.isDemo && networks.approximate) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        allowed = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true
        approximate = networks.approximate
    }
    LaunchedEffect(Unit) {
        // Approximate location was already an answer to Lull's question, so it's sent to Settings, as on iOS.
        if (!allowed && !approximate && !asked) {
            asked = true
            ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    // Coming back from Settings may have allowed it.
    LifecycleResumeEffect(Unit) {
        allowed = bedJet.isDemo || networks.allowed
        approximate = !bedJet.isDemo && networks.approximate
        onPauseOrDispose {}
    }
    DisposableEffect(allowed) {
        val stop = if (allowed && !bedJet.isDemo) networks.start() else ({})
        onDispose { stop() }
    }
    val choices = if (bedJet.isDemo) WiFiNetworks.demo else networks.choices

    // The network picked from the list; null while the name is typed in.
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    var typed by rememberSaveable { mutableStateOf(bedJet.wifiNetwork.orEmpty()) }
    // Not saved with the screen's state, which can be written to disk.
    var password by remember { mutableStateOf("") }
    val current = choices.firstOrNull { it.current }?.name
    LaunchedEffect(current) {
        // Offer this phone's network, unless another name is already there.
        if (current != null && picked == null && (typed.isEmpty() || typed == current)) {
            picked = current
            typed = ""
        }
    }
    LaunchedEffect(choices) {
        // A network a later scan no longer hears stays the one joined, typed in where it can be seen.
        val gone = picked?.takeIf { name -> choices.none { it.name == name } } ?: return@LaunchedEffect
        typed = gone
        picked = null
    }
    val network = picked ?: typed
    val problem = Codec.wifiProblem(network, password)
    val activity = bedJet.wifiActivity?.takeIf { it.purpose == WiFiActivity.Purpose.JOINING }
    val showsSettings = !allowed && (asked || approximate)

    Section(
        header = "Network",
        footer = if (allowed) {
            "BedJet 3 can only join 2.4 GHz networks, so only those are listed. For a hidden network, choose Other Network."
        } else if (approximate) {
            "To list the networks nearby, turn on Precise Location for Lull in Settings. Or choose " +
                "Other Network: BedJet 3 can only join 2.4 GHz networks, not 5 GHz ones, whose names often end in “5G”."
        } else {
            "To list the networks nearby, allow location access for Lull in Settings. Lull uses it only here. Or choose " +
                "Other Network: BedJet 3 can only join 2.4 GHz networks, not 5 GHz ones, whose names often end in “5G”."
        },
    ) {
        choices.forEach { choice ->
            NetworkRow(choice.name, detail = if (choice.current) "This phone's network" else null, picked = picked == choice.name) {
                picked = choice.name
            }
        }
        NetworkRow("Other Network…", picked = picked == null, divider = picked == null || showsSettings) { picked = null }
        if (picked == null) {
            TextRow("Name", typed, example = "Network name", imeAction = ImeAction.Next, divider = showsSettings) { typed = it }
        }
        if (showsSettings) {
            ButtonRow("Open Settings", enabled = true, divider = false) {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            }
        }
    }
    // The list above says which networks a BedJet 3 can join, so this only says what happens to the details.
    Section(footer = "Lull hands the name and password to your BedJet, and remembers the name but never the password.") {
        // Shown as typed, to check a long Wi-Fi password, and so the phone doesn't offer to save it for Lull.
        TextRow("Password", password, keyboard = KeyboardType.Password, imeAction = ImeAction.Done, divider = false) { password = it }
    }
    Section(
        footer = if (problem != null && (network.isNotEmpty() || password.isNotEmpty())) problem
        else "Your BedJet tries the network straight away and says how it went.",
    ) {
        WiFiButton("Join Network", activity, enabled = bedJet.canJoinWiFi && problem == null, divider = activity?.outcome != null) {
            model.scope.launch { bedJet.joinWiFi(network, password) }
        }
        if (activity?.outcome != null) WiFiOutcomeRow(activity.outcome, activity.failed, divider = false)
    }
}

/** A Wi-Fi network to pick, ticked when it's the one picked. */
@Composable
private fun NetworkRow(name: String, detail: String? = null, picked: Boolean, divider: Boolean = true, pick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = picked, role = Role.RadioButton, onClick = pick)
            .clearAndSetSemantics {
                contentDescription = if (detail != null) "$name, $detail" else name
                selected = picked
                role = Role.RadioButton
                onClick { pick(); true }
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, fontSize = 17.sp)
            if (detail != null) Text(detail, fontSize = 13.sp, color = Theme.secondary)
        }
        if (picked) Icon(Icons.Filled.Check, null, tint = Theme.green, modifier = Modifier.size(22.dp))
    }
    if (divider) HorizontalDivider(color = Theme.hairline)
}

/** A label with a field to type into, like a row in Settings. */
@Composable
private fun TextRow(
    label: String,
    value: String,
    example: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    autoCorrect: Boolean = false,
    imeAction: ImeAction,
    divider: Boolean = true,
    onChange: (String) -> Unit,
) {
    val focus = LocalFocusManager.current
    // The example would clip at the largest text sizes, where the field is narrow.
    val shownExample = if (isLargeText()) null else example
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 17.sp, modifier = Modifier.clearAndSetSemantics {})
        Spacer(Modifier.width(16.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = 17.sp, color = Color.White, textAlign = TextAlign.End),
            cursorBrush = SolidColor(Theme.green),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None, autoCorrectEnabled = autoCorrect, keyboardType = keyboard, imeAction = imeAction,
            ),
            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }, onDone = { focus.clearFocus() }),
            modifier = Modifier.weight(1f).semantics { contentDescription = label },
            decorationBox = { field ->
                if (value.isEmpty() && shownExample != null) {
                    Text(shownExample, fontSize = 17.sp, color = Theme.tertiary, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
                }
                field()
            },
        )
    }
    if (divider) HorizontalDivider(color = Theme.hairline)
}

/** A button that fills its row, like one in a Settings list. */
@Composable
private fun ButtonRow(text: String, enabled: Boolean, color: Color = Theme.green, divider: Boolean = true, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 17.sp,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(vertical = 13.dp),
    )
    if (divider) HorizontalDivider(color = Theme.hairline)
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
private fun NavigationRow(icon: ImageVector, text: String, enabled: Boolean, value: String? = null, onClick: () -> Unit) {
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
        Text(text, fontSize = 17.sp, color = if (value == null) Theme.green else Color.White, modifier = Modifier.weight(1f))
        if (value != null) Text(value, fontSize = 17.sp, color = Theme.secondary, textAlign = TextAlign.End)
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

@Composable
private fun Toggle(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 17.sp, modifier = Modifier.alpha(if (enabled) 1f else 0.4f))
        Switch(
            checked, onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Theme.green, checkedThumbColor = Color.White, checkedBorderColor = Theme.green,
                uncheckedTrackColor = Theme.neutral.copy(alpha = 0.35f), uncheckedThumbColor = Color.White, uncheckedBorderColor = Color.Transparent,
                // Dimmed but still reading on or off, like iOS, rather than Material's grey.
                disabledCheckedTrackColor = Theme.green.copy(alpha = 0.4f), disabledCheckedThumbColor = Color.White.copy(alpha = 0.6f),
                disabledCheckedBorderColor = Color.Transparent,
                disabledUncheckedTrackColor = Theme.neutral.copy(alpha = 0.15f), disabledUncheckedThumbColor = Color.White.copy(alpha = 0.6f),
                disabledUncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}
