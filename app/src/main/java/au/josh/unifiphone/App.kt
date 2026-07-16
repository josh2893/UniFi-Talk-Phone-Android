package au.josh.unifiphone

import android.app.Application
import au.josh.unifiphone.core.SipEngine
import au.josh.unifiphone.data.DirectoryRepository
import au.josh.unifiphone.data.SettingsRepository
import au.josh.unifiphone.web.WebManagementServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class App : Application() {

    lateinit var settingsRepo: SettingsRepository
        private set
    lateinit var directoryRepo: DirectoryRepository
        private set
    lateinit var sipEngine: SipEngine
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var webManagementServer: WebManagementServer? = null

    override fun onCreate() {
        super.onCreate()
        settingsRepo = SettingsRepository(this)
        directoryRepo = DirectoryRepository(this)
        sipEngine = SipEngine(this, directoryRepo)
        sipEngine.start()

        appScope.launch { directoryRepo.load() }
        // Re-apply SIP config whenever settings change
        appScope.launch {
            settingsRepo.settings.collectLatest { sipEngine.applySettings(it) }
        }
        serverScope.launch {
            settingsRepo.settings
                .map { it.webManagementEnabled to it.webManagementPort }
                .distinctUntilChanged()
                .collectLatest { (enabled, port) ->
                    if (webManagementServer != null) delay(500)
                    webManagementServer?.stop()
                    webManagementServer = null
                    if (enabled) {
                        runCatching {
                            WebManagementServer(port, settingsRepo).also {
                                it.startServer()
                                webManagementServer = it
                            }
                        }
                    }
                }
        }
    }
}
