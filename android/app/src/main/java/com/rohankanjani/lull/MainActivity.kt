package com.rohankanjani.lull

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Bundle
import com.rohankanjani.lull.widgets.publishShortcuts
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.rohankanjani.lull.ui.LullTheme
import com.rohankanjani.lull.ui.RootScreen

class MainActivity : ComponentActivity() {
    /** A widget, tile or shortcut opened Lull again while it was already running. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        AppCommands.from(intent)?.let(AppCommands::send)
    }

    private val model: AppModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val options = LaunchOptions(
                    ephemeral = intent.getBooleanExtra("ephemeral", false),
                    demoElapsedMinutes = intent.getIntExtra("demoElapsedMinutes", 0),
                    labelsDemoAsConnected = intent.getBooleanExtra("demoLabelConnected", false),
                    logsConnections = intent.getBooleanExtra("logConnections", false),
                    demoHistory = intent.getBooleanExtra("demoHistory", false),
                    demoStartsUnchosen = intent.getBooleanExtra("demoChoose", false),
                    demoFirstUnitAway = intent.getBooleanExtra("demoFirstUnitAway", false),
                )
                return AppModel(application, options) as T
            }
        }
    }

    private val bluetoothPermissions = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)

        // The BedJet is released whenever Lull leaves the screen, and asked for again when it comes back.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                Format.use24Hour = DateFormat.is24HourFormat(this@MainActivity)
                model.appBecameActive()
            }
            override fun onStop(owner: LifecycleOwner) = model.appMovedToBackground()
        })

        publishShortcuts(this)
        AppCommands.from(intent)?.let(AppCommands::send)

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
}
