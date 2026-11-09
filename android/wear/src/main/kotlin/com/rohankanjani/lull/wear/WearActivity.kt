package com.rohankanjani.lull.wear

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.activity.compose.setContent
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.rohankanjani.lull.Format
import com.rohankanjani.lull.LullTab
import com.rohankanjani.lull.WidgetSnapshot
import com.rohankanjani.lull.wear.ui.WatchPage
import com.rohankanjani.lull.wear.ui.WatchRoot
import com.rohankanjani.lull.widgets.toBitmap
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.launch

class WearActivity : ComponentActivity() {
    private lateinit var model: WatchModel
    private val pager = PagerState { WatchPage.entries.size }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = makeModel()
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                Format.followClockSetting(this@WearActivity)
                model.refreshIfStale()
            }
        })
        if (savedInstanceState == null) handle(intent)
        publishShortcuts()
        setContent { WatchRoot(model, pager) }
    }

    override fun onDestroy() {
        (model.phone as? PhoneLink)?.deactivate()
        super.onDestroy()
    }

    /** A tile, complication or shortcut opened the watch app again while it was already running. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /**
     * Launch flags match the phone apps': `demo` uses a pretend phone with the sample BedJets, `ephemeral` keeps
     * nothing, `demoHistory` keeps the sample nights, and `demoElapsedMinutes` makes a night started in demo appear that
     * far along.
     */
    private fun makeModel(): WatchModel {
        val store = DeviceStore(this, saves = !intent.getBooleanExtra("ephemeral", false))
        if (!intent.getBooleanExtra("demo", false)) return WatchModel(PhoneLink(this), store, lifecycleScope)
        var sample = WidgetSnapshot.preview()
        if (!intent.getBooleanExtra("demoHistory", false)) {
            sample = sample.copy(recentHistory = emptyList(), bedJets = sample.bedJets.map { it.copy(recentHistory = emptyList()) })
        }
        val phone = DemoPhone(sample, lifecycleScope).apply { elapsedMinutes = intent.getIntExtra("demoElapsedMinutes", 0) }
        return WatchModel(phone, store, lifecycleScope)
    }

    /** Start Night and the Remote when the app icon is held, as on the phone. */
    private fun publishShortcuts() {
        val startNight = Intent(this, WearActivity::class.java).setAction(ACTION_START_NIGHT)
            .putExtra(EXTRA_TOKEN, StartNightToken.get(this))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        // A shortcut's intent needs an action, so the Remote's is a link, as a complication's is.
        val remote = Intent(Intent.ACTION_VIEW, Uri.parse(LullTab.REMOTE.link), this, WearActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        ShortcutManagerCompat.setDynamicShortcuts(
            this,
            listOf(
                ShortcutInfoCompat.Builder(this, "start-night")
                    .setShortLabel("Start Night")
                    .setLongLabel("Start tonight's schedule")
                    .setIcon(IconCompat.createWithResource(this, R.drawable.ic_tile_night))
                    .setIntent(startNight)
                    .build(),
                ShortcutInfoCompat.Builder(this, "remote")
                    .setShortLabel("Remote")
                    .setLongLabel("Open the BedJet remote")
                    .setIcon(IconCompat.createWithBitmap(Icons.Filled.SettingsRemote.toBitmap(Color.White, 96)))
                    .setIntent(remote)
                    .build(),
            ),
        )
    }

    /** Start Night from the tile or the launcher's shortcut, and the page a tile, complication or shortcut shows. */
    private fun handle(intent: Intent?) {
        intent ?: return
        val link = intent.dataString ?: intent.getStringExtra(EXTRA_LINK)
        // The BedJet a tile or complication shows. One showing the BedJet already shown leaves the watch following the
        // phone, if it was.
        LullTab.bedJet(link)?.let { id -> if (id != model.bedJet?.id && model.snapshot.bedJets.any { it.id == id }) model.pickedID = id }
        val page = when (LullTab.from(link)) {
            LullTab.SCHEDULE -> WatchPage.TONIGHT
            LullTab.REMOTE -> WatchPage.REMOTE
            LullTab.HISTORY -> WatchPage.LAST_NIGHT
            null -> null
        }
        if (page != null) lifecycleScope.launch { pager.scrollToPage(page.ordinal) }
        val asksToStartNight = intent.action == ACTION_START_NIGHT || intent.getBooleanExtra(EXTRA_START_NIGHT, false)
        if (asksToStartNight && StartNightToken.matches(this, intent.getStringExtra(EXTRA_TOKEN))) {
            lifecycleScope.launch { pager.scrollToPage(WatchPage.TONIGHT.ordinal) }
            model.startNight(LullTab.bedJet(link))
        }
    }

    companion object {
        /** The phone app's action, so a shortcut reads the same on both. */
        const val ACTION_START_NIGHT = "com.rohankanjani.lull.START_NIGHT"
        /** What a tile asks for: tiles can open an activity with extras, but not with an action or a link. */
        const val EXTRA_START_NIGHT = "startNight"
        /** Sent with Start Night, to show it came from Lull's own tile or shortcut: see [StartNightToken]. */
        const val EXTRA_TOKEN = "token"
        const val EXTRA_LINK = "link"
    }
}

/**
 * The secret Lull's own tile and shortcut send with Start Night. A tile can only open an activity that's exported, so
 * any app on the watch can open this one; without the secret, any of them could start a night on the BedJet. On the
 * phone the same buttons go through CommandActivity, which isn't exported, and on the Apple Watch starting a night takes
 * an App Intent, which other apps can't run. Asked without it, the watch app only opens, at the page asked for.
 */
object StartNightToken {
    private const val FILE = "LullWatchStartNight"
    private const val KEY = "token"

    /** Made the first time it's needed, and kept in a file only Lull can read. */
    @Synchronized
    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return prefs.getString(KEY, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY, it).apply() }
    }

    /** Compared in constant time, so how long a wrong guess takes says nothing about the right one. */
    fun matches(context: Context, token: String?): Boolean =
        token != null && MessageDigest.isEqual(token.toByteArray(), get(context).toByteArray())
}
