package com.rohankanjani.lull.wear.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.RotaryScrollEvent
import androidx.compose.ui.input.rotary.onPreRotaryScrollEvent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import com.rohankanjani.lull.ui.Theme
import com.rohankanjani.lull.wear.WatchModel
import kotlinx.coroutines.delay

/** The watch's pages, as the phone's tabs: Tonight, the Remote and Last Night. */
enum class WatchPage(val title: String) {
    TONIGHT("Tonight"), REMOTE("Remote"), LAST_NIGHT("Last Night")
}

@Composable
fun WatchRoot(model: WatchModel, pager: PagerState) {
    var choosing by remember { mutableStateOf(false) }
    // Played out by the clock between handovers from the phone, as the widgets are.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    // Swiping back closes the picker, as it does any screen on a watch, rather than leaving Lull.
    BackHandler(enabled = choosing) { choosing = false }
    AppScaffold {
        // Every page is kept, as the Apple Watch's are, so what's set on the Remote but not yet sent is still there
        // after a look at another page or at the picker, which is drawn over them as the Apple Watch's sheet is.
        VerticalPager(
            pager,
            Modifier
                .fillMaxSize()
                .background(Theme.background)
                // Out of TalkBack's reach under the picker, as under the sheet.
                .then(if (choosing) Modifier.clearAndSetSemantics { } else Modifier),
            beyondViewportPageCount = WatchPage.entries.size - 1,
        ) { index ->
            val page = WatchPage.entries[index]
            Page(page, model, isCurrent = pager.currentPage == index && !choosing, onPick = { choosing = true }) {
                when (page) {
                    WatchPage.TONIGHT -> TonightPage(model, now)
                    WatchPage.REMOTE -> RemotePage(model)
                    WatchPage.LAST_NIGHT -> LastNightPage(model, now)
                }
            }
        }
        if (choosing) BedJetPicker(model) { choosing = false }
        AlertDialog(
            visible = model.errorMessage != null,
            onDismissRequest = { model.errorMessage = null },
            confirmButton = { AlertDialogDefaults.ConfirmButton({ model.errorMessage = null }) },
            title = { Text("Something went wrong", textAlign = TextAlign.Center) },
            text = { Text(model.errorMessage.orEmpty(), textAlign = TextAlign.Center) },
        )
    }
}

/**
 * One page: its title, which BedJet it shows when there's more than one, and what it shows, scrolled by the bezel. The
 * page that's shown takes the bezel, and takes it back once the picker closes.
 */
@Composable
private fun Page(page: WatchPage, model: WatchModel, isCurrent: Boolean, onPick: () -> Unit, content: @Composable () -> Unit) {
    val scroll = rememberScrollState()
    val focus = remember { FocusRequester() }
    val crown = remember { mutableStateOf<((RotaryScrollEvent) -> Boolean)?>(null) }
    LaunchedEffect(isCurrent) { if (isCurrent) focus.requestFocus() }
    CompositionLocalProvider(LocalCrown provides crown) {
        Column(
            Modifier
                .fillMaxSize()
                // A setting tapped on the page turns with the bezel; otherwise the bezel scrolls the page. Ahead of the
                // scrolling, which would otherwise take every turn first: see LocalCrown.
                .onPreRotaryScrollEvent { event -> crown.value?.invoke(event) ?: false }
                .rotaryScrollable(RotaryScrollableDefaults.behavior(scroll), focus)
                .verticalScroll(scroll)
                // A round screen narrows at the top and bottom, so the heading is centred and the page ends well clear.
                .padding(start = 20.dp, end = 20.dp, top = 30.dp, bottom = 44.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(page.title, Modifier.semantics { heading() }, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (model.hasSeveralBedJets) {
                    // Named, so it's clear which side of the bed is shown.
                    Text(
                        model.bedJet?.name ?: "BedJet",
                        Modifier
                            .widthIn(max = 120.dp)
                            .heightIn(min = 32.dp)
                            .clip(CircleShape)
                            .background(Theme.raised)
                            .border(BorderStroke(1.dp, Theme.hairline), CircleShape)
                            .clickable(role = Role.Button, onClickLabel = "Switches BedJet", onClick = onPick)
                            .semantics { contentDescription = "BedJet, ${model.bedJet?.name ?: "none"}" }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
            content()
        }
    }
}

/** Which BedJet the watch shows, by name, when Lull knows more than one. It takes the bezel from the page under it. */
@Composable
private fun BedJetPicker(model: WatchModel, onDone: () -> Unit) {
    val scroll = rememberScrollState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(
        Modifier
            .fillMaxSize()
            .background(Theme.background)
            .rotaryScrollable(RotaryScrollableDefaults.behavior(scroll), focus)
            .verticalScroll(scroll)
            .padding(horizontal = 14.dp, vertical = 30.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("BedJets", Modifier.fillMaxWidth().semantics { heading() }, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        for (bedJet in model.snapshot.bedJets) {
            val isShown = bedJet.id == model.bedJet?.id
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Theme.raised)
                    .clickable(role = Role.Button) {
                        model.pickedID = bedJet.id
                        onDone()
                    }
                    .semantics(mergeDescendants = true) { selected = isShown }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(bedJet.name, Modifier.weight(1f), fontSize = 15.sp, maxLines = 1)
                if (isShown) Icon(Icons.Filled.Check, contentDescription = null, tint = Theme.secondary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

