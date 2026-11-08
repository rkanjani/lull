package com.rohankanjani.lull

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Bundle
import com.rohankanjani.lull.widgets.publishShortcuts
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.rohankanjani.lull.ui.LullTheme
import com.rohankanjani.lull.ui.RootScreen

class MainActivity : ComponentActivity() {
    /** A widget, tile or shortcut opened Lull again while it was already running. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        AppCommands.shown(intent)?.let(AppCommands::send)
    }

    /** The app's one model, as iOS keeps it in `LullApp`. An errand from the watch may have made it already. */
    private lateinit var model: AppModel

    /** Launch flags, the intent extras named as the iPhone app's launch arguments. */
    private fun launchOptions(): LaunchOptions {
        return LaunchOptions(
            ephemeral = intent.getBooleanExtra("ephemeral", false),
            demoElapsedMinutes = intent.getIntExtra("demoElapsedMinutes", 0),
            labelsDemoAsConnected = intent.getBooleanExtra("demoLabelConnected", false),
            logsConnections = intent.getBooleanExtra("logConnections", false),
            demoHistory = intent.getBooleanExtra("demoHistory", false),
            demoStartsUnchosen = intent.getBooleanExtra("demoChoose", false),
            demoFirstUnitAway = intent.getBooleanExtra("demoFirstUnitAway", false),
            noWakeTimes = intent.getBooleanExtra("noWakeTimes", false),
            demoFirmwareUpdate = intent.getBooleanExtra("demoFirmwareUpdate", false),
        )
    }

    private val bluetoothPermissions = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        model = (application as LullApplication).model(launchOptions(), onScreen = true, fresh = savedInstanceState == null)

        // The BedJet is released whenever Lull leaves the screen, and asked for again when it comes back. The app's
        // model is told, not this screen's: the last screen to stop may not be the one that started, and a launch with
        // flags may have made a new model since.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                Format.followClockSetting(this@MainActivity)
                if (started++ == 0) (application as LullApplication).model().appBecameActive()
            }
            override fun onStop(owner: LifecycleOwner) {
                if (--started == 0) (application as LullApplication).model().appMovedToBackground()
            }
        })

        publishShortcuts(this)
        // Only when first opened: a recreated activity keeps the intent that opened it, and showing its tab again would
        // undo the tab chosen since.
        if (savedInstanceState == null) AppCommands.shown(intent)?.let(AppCommands::send)

        setContent {
            val askForBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                model.permissionsChanged()
            }
            val askForNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                model.notificationPermissionAnswered()
            }
            // Asked the first time a BedJet runs, when the Live Update it allows would appear.
            LaunchedEffect(model.wantsNotificationPermission) {
                if (model.wantsNotificationPermission) askForNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            LaunchedEffect(model.demoMode) {
                val granted = bluetoothPermissions.all {
                    ContextCompat.checkSelfPermission(this@MainActivity, it) == PackageManager.PERMISSION_GRANTED
                }
                if (!model.demoMode && !granted) askForBluetooth.launch(bluetoothPermissions)
            }
            LullTheme { RootScreen(model) }
        }
    }

    companion object {
        /**
         * How many of Lull's screens are started. Another app can open one in its own task, and it starts before the
         * one it covers stops, so Lull only leaves the screen once the last one stops. A screen recreated for a change
         * of settings stops before its new one starts, so the BedJet is let go and asked for again, as before.
         */
        private var started = 0
    }
}
