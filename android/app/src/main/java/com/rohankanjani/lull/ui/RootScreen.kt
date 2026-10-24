package com.rohankanjani.lull.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.view.View
import com.rohankanjani.lull.AppCommands
import com.rohankanjani.lull.AppModel
import com.rohankanjani.lull.LullTab
import com.rohankanjani.lull.BedJet
import com.rohankanjani.lull.bedjetkit.LinkState
import kotlinx.coroutines.launch

/** The BedJet every screen shows and controls, like iOS's `@Environment(BedJet.self)`. */
val LocalBedJet = compositionLocalOf<BedJet> { error("No BedJet chosen") }

@Composable
fun RootScreen(model: AppModel) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    // Widgets open the tab they show; tiles and shortcuts send commands.
    DisposableEffect(Unit) {
        AppCommands.handler = { command ->
            when (command) {
                is AppCommands.Command.Show -> {
                    // A link can name its BedJet, so tapping one side of the bed opens that side.
                    command.bedJet?.let(model::select)
                    selectedTab = command.tab.ordinal
                }
                AppCommands.Command.StartNight -> {
                    selectedTab = LullTab.SCHEDULE.ordinal
                    // A night already under way, from this phone or another, is left running and shown.
                    val shown = model.bedJet
                    if (shown != null && shown.run == null && shown.runningElsewhereStep == null && !shown.isBusy) {
                        model.scope.launch { shown.startTonight() }
                    }
                }
            }
        }
        onDispose { AppCommands.handler = null }
    }
    val view = LocalView.current
    var lastSelected by remember { mutableStateOf(model.selectedID) }
    LaunchedEffect(model.selectedID) {
        val old = lastSelected
        lastSelected = model.selectedID
        // Said once, when the person switches; scanning and background BedJets stay quiet.
        val bedJet = model.bedJet
        if (old == null || old == model.selectedID || bedJet == null) return@LaunchedEffect
        view.announce("Showing ${model.title(bedJet)}, ${bedJet.spokenConnectionLabel}")
    }

    val bedJet = model.bedJet
    if (bedJet == null) {
        // Lull only ever controls a BedJet the person picked.
        ChooseBedJetScreen(model)
    } else {
        CompositionLocalProvider(LocalBedJet provides bedJet) { Tabs(model, bedJet, selectedTab) { selectedTab = it } }
    }

    model.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { model.errorMessage = null },
            confirmButton = { TextButton({ model.errorMessage = null }) { Text("OK") } },
            title = { Text("Something went wrong") },
            text = { Text(message) },
            containerColor = Theme.raised,
        )
    }
}

@Suppress("DEPRECATION") // Still the way to speak a passing event; live regions suit text that stays on screen.
private fun View.announce(message: String) = announceForAccessibility(message)

@Composable
private fun Tabs(model: AppModel, bedJet: BedJet, selectedTab: Int, select: (Int) -> Unit) {
    Scaffold(
        containerColor = Theme.background,
        // Each screen pads for the status bar itself, so it can scroll beneath it.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            Column {
                HorizontalDivider(color = Theme.hairline)
                NavigationBar(containerColor = Theme.background, tonalElevation = 0.dp) {
                    val colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.White,
                        selectedTextColor = Color.White,
                        unselectedIconColor = Theme.tertiary,
                        unselectedTextColor = Theme.tertiary,
                        indicatorColor = Theme.raised,
                    )
                    NavigationBarItem(
                        selected = selectedTab == 0, onClick = { select(0) },
                        icon = { Icon(Icons.Filled.SettingsRemote, null) }, label = { Text("Remote") }, colors = colors,
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1, onClick = { select(1) },
                        icon = { Icon(Icons.Filled.NightsStay, null) }, label = { Text("Schedule") }, colors = colors,
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2, onClick = { select(2) },
                        icon = { Icon(Icons.Filled.History, null) }, label = { Text("History") }, colors = colors,
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (selectedTab) {
                0 -> RemoteScreen(model)
                1 -> ScheduleScreen(model)
                else -> HistoryScreen(model)
            }
        }
    }

    // Remade for each BedJet, so switching to another isn't announced as its connection or night changing.
    key(bedJet.id) { Announcements(bedJet) }

    if (bedJet.needsSlotChoice) {
        AlertDialog(
            onDismissRequest = { bedJet.needsSlotChoice = false },
            title = { Text("Your BedJet's three slots are full", textAlign = TextAlign.Center) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Lull keeps tonight's schedule in one slot on the BedJet. Pick one to reuse. " +
                            "Everything on the BedJet is already copied into your schedules.",
                        color = Theme.secondary,
                    )
                    Spacer(Modifier.height(8.dp))
                    bedJet.deviceSlots.forEachIndexed { index, program ->
                        TextButton(
                            onClick = {
                                bedJet.needsSlotChoice = false
                                model.scope.launch { bedJet.startTonight(replacingSlot = index) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Replace “${program?.name ?: "Slot ${index + 1}"}”") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ bedJet.needsSlotChoice = false }) { Text("Cancel") } },
            containerColor = Theme.raised,
        )
    }
}

/** Changes TalkBack users would otherwise only notice by exploring the screen again. */
@Composable
private fun Announcements(bedJet: BedJet) {
    val view = LocalView.current
    fun announce(message: String) = view.announce(message)

    var lastState by remember { mutableStateOf(bedJet.linkState) }
    LaunchedEffect(bedJet.linkState) {
        val old = lastState
        lastState = bedJet.linkState
        if (old == bedJet.linkState) return@LaunchedEffect
        when (bedJet.linkState) {
            LinkState.READY -> announce("BedJet connected")
            LinkState.YIELDED -> announce("BedJet in use on another phone")
            LinkState.BLUETOOTH_OFF -> announce("Bluetooth is off")
            LinkState.UNAUTHORIZED -> announce("Bluetooth not allowed for Lull")
            LinkState.SEARCHING, LinkState.CONNECTING -> if (old == LinkState.READY) announce("BedJet disconnected")
        }
    }
    LaunchedEffect(bedJet.isSlowToFind) {
        if (bedJet.isSlowToFind) announce("BedJet not found. The BedJet button at the top has tips.")
    }
    val running = bedJet.run?.schedule?.name
    var lastRunning by remember { mutableStateOf(running) }
    LaunchedEffect(running) {
        val old = lastRunning
        lastRunning = running
        if (old == running) return@LaunchedEffect
        if (running != null) announce("Started $running") else if (old != null) announce("Night ended")
    }
}

/** Screen scaffold shared by the tabs: large title on the left, the BedJet on screen and its connection on the right. */
@Composable
fun Screen(model: AppModel, title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    var showsDevice by remember { mutableStateOf(false) }
    var showsChooser by remember { mutableStateOf(false) }
    val heading = @Composable {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (subtitle != null) Eyebrow(subtitle)
            Text(title, fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Theme.background)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // At the largest text sizes the title takes the full width and the connection sits beneath it.
        if (isLargeText()) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                heading()
                ConnectionPill(model, openSettings = { showsDevice = true }, openChooser = { showsChooser = true })
            }
        } else {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // A long BedJet name wraps rather than the screen's title.
                Box(Modifier.weight(1f)) { heading() }
                ConnectionPill(model, openSettings = { showsDevice = true }, openChooser = { showsChooser = true })
            }
        }
        content()
    }
    if (showsDevice) DeviceSheet(model, LocalBedJet.current) { showsDevice = false }
    if (showsChooser) NearbySheet(model) { showsChooser = false }
}

/**
 * Names the BedJet on screen and says how its connection is going, in words as well as the dot. Opens its
 * settings; with several BedJets it switches between them.
 */
@Composable
fun ConnectionPill(model: AppModel, openSettings: () -> Unit, openChooser: () -> Unit) {
    val bedJet = LocalBedJet.current
    val title = model.title(bedJet)
    if (model.hasSeveralBedJets) {
        var open by remember { mutableStateOf(false) }
        val haptics = LocalHapticFeedback.current
        Box {
            PillFace(
                title, bedJet.connectionLabel, bedJet.isConnected, switches = true,
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = "BedJet, $title, ${bedJet.spokenConnectionLabel}"
                    role = Role.DropdownList
                    onClick(label = "Switch BedJet, add another, or open BedJet settings") { open = true; true }
                },
                onClick = { open = true },
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Theme.raised) {
                // Picking another is refused until then, so say why.
                if (!model.canSwitch) {
                    Text(
                        "Switching waits until the schedule finishes saving to $title.",
                        fontSize = 13.sp, color = Theme.secondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).width(240.dp),
                    )
                }
                model.bedJets.forEach { other ->
                    val isShown = other === bedJet
                    DropdownMenuItem(
                        text = { Text(model.title(other)) },
                        leadingIcon = { Icon(Icons.Filled.Check, null, modifier = Modifier.alpha(if (isShown) 1f else 0f)) },
                        onClick = {
                            open = false
                            if (!isShown) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            model.select(other.id)
                        },
                        modifier = Modifier.semantics { selected = isShown },
                    )
                }
                HorizontalDivider(color = Theme.hairline)
                DropdownMenuItem(
                    text = { Text("Add Another BedJet") }, leadingIcon = { Icon(Icons.Filled.Add, null) },
                    onClick = { open = false; openChooser() },
                    enabled = model.canSwitch,
                )
                DropdownMenuItem(
                    text = { Text("BedJet Settings") }, leadingIcon = { Icon(Icons.Filled.Settings, null) },
                    onClick = { open = false; openSettings() },
                )
            }
        }
    } else {
        PillFace(
            title, bedJet.connectionLabel, bedJet.isConnected,
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = "BedJet, $title, ${bedJet.spokenConnectionLabel}"
                role = Role.Button
                onClick(label = "Show the connection, BedJet settings and help finding it") { openSettings(); true }
            },
            onClick = openSettings,
        )
    }
}

@Composable
private fun PillFace(title: String, state: String, isConnected: Boolean, modifier: Modifier, switches: Boolean = false, onClick: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val dimmed by pulse.animateFloat(1f, 0.3f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dot")
    Pill(modifier.heightIn(min = 44.dp), onClick = onClick) {
        Box(
            Modifier
                .size(8.dp)
                .alpha(if (isConnected) 1f else dimmed)
                .background(if (isConnected) Theme.green else Theme.neutral, CircleShape),
        )
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(state, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Theme.secondary)
        }
        if (switches) Icon(Icons.Filled.UnfoldMore, null, tint = Theme.secondary, modifier = Modifier.size(14.dp))
    }
}
