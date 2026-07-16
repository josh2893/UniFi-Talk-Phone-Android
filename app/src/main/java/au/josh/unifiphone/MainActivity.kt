package au.josh.unifiphone

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import au.josh.unifiphone.core.SipForegroundService
import au.josh.unifiphone.data.ThemeMode
import au.josh.unifiphone.kiosk.KioskManager
import au.josh.unifiphone.ui.screens.CallScreen
import au.josh.unifiphone.ui.screens.ContactsScreen
import au.josh.unifiphone.ui.screens.DoorbellScreen
import au.josh.unifiphone.ui.screens.HistoryScreen
import au.josh.unifiphone.ui.screens.HomeScreen
import au.josh.unifiphone.ui.screens.SettingsScreen
import au.josh.unifiphone.ui.theme.UniFiPhoneTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private var immersiveModeActive = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(wanted.toTypedArray())

        SipForegroundService.start(this)

        setContent {
            val vm: PhoneViewModel = viewModel()
            val settings by vm.settings.collectAsState()

            val dark = settings.doorbellEnabled || when (settings.themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }

            LaunchedEffect(settings.doorbellEnabled, settings.kioskEnabled) {
                immersiveModeActive = settings.doorbellEnabled || settings.kioskEnabled
                applyKioskSystemUi(immersiveModeActive)
                while (settings.doorbellEnabled || settings.kioskEnabled) {
                    delay(1_500)
                    applyKioskSystemUi(true)
                }
            }

            LaunchedEffect(settings.kioskEnabled) {
                if (settings.kioskEnabled) {
                    KioskManager.enterKiosk(this@MainActivity)
                } else {
                    KioskManager.exitKiosk(this@MainActivity)
                }
            }

            // Keep the system status/navigation bars in step with the theme.
            LaunchedEffect(dark) {
                val transparent = Color.Transparent.toArgb()
                if (dark) {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.dark(transparent),
                        navigationBarStyle = SystemBarStyle.dark(transparent),
                    )
                } else {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.light(transparent, transparent),
                        navigationBarStyle = SystemBarStyle.light(transparent, transparent),
                    )
                }
                applyKioskSystemUi(immersiveModeActive)
            }

            UniFiPhoneTheme(mode = if (settings.doorbellEnabled) ThemeMode.DARK else settings.themeMode) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Root(vm)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyKioskSystemUi(immersiveModeActive)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyKioskSystemUi(immersiveModeActive)
    }

    private fun applyKioskSystemUi(enabled: Boolean) {
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            insets.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insets.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            insets.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

private data class Tab(val label: String, val icon: @Composable () -> Unit)

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun Root(vm: PhoneViewModel) {
    val call by vm.engine.callState.collectAsState()
    val settings by vm.settings.collectAsState()
    val history by vm.directory.history.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var doorbellAdminOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(settings.doorbellEnabled) {
        if (!settings.doorbellEnabled) doorbellAdminOpen = false
    }

    if (settings.doorbellEnabled) {
        if (call.active && call.incoming) {
            CallScreen(vm, call)
        } else if (!doorbellAdminOpen || call.active) {
            DoorbellScreen(vm, onAdminUnlocked = { doorbellAdminOpen = true })
        } else {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    TopAppBar(
                        title = { Text("Settings") },
                        navigationIcon = {
                            IconButton(onClick = { doorbellAdminOpen = false }) {
                                Icon(Icons.Filled.ArrowBack, contentDescription = "Back to doorbell")
                            }
                        },
                    )
                },
            ) { padding ->
                androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
                    SettingsScreen(vm, initialTab = 1)
                }
            }
        }
        return
    }

    if (call.active) {
        // Full-screen call UI takes over whenever there's a call
        CallScreen(vm, call)
        return
    }

    val missedCount =
        if (settings.showMissedCalls) history.count { it.missed } else 0

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Dialpad, null) }, label = { Text("Phone") },
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.Contacts, null) }, label = { Text("Contacts") },
                )
                NavigationBarItem(
                    selected = tab == 2, onClick = { tab = 2 },
                    icon = {
                        if (missedCount > 0) {
                            BadgedBox(badge = { Badge { Text("$missedCount") } }) {
                                Icon(Icons.Filled.History, null)
                            }
                        } else Icon(Icons.Filled.History, null)
                    },
                    label = { Text("Recents") },
                )
                NavigationBarItem(
                    selected = tab == 3, onClick = { tab = 3 },
                    icon = { Icon(Icons.Filled.Settings, null) }, label = { Text("Settings") },
                )
            }
        },
    ) { padding ->
        androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> HomeScreen(vm)
                1 -> ContactsScreen(vm)
                2 -> HistoryScreen(vm)
                else -> SettingsScreen(vm)
            }
        }
    }
}
