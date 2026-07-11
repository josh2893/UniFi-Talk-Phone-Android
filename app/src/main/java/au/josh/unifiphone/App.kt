package au.josh.unifiphone

import android.app.Application
import au.josh.unifiphone.core.SipEngine
import au.josh.unifiphone.data.DirectoryRepository
import au.josh.unifiphone.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class App : Application() {

    lateinit var settingsRepo: SettingsRepository
        private set
    lateinit var directoryRepo: DirectoryRepository
        private set
    lateinit var sipEngine: SipEngine
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
    }
}
