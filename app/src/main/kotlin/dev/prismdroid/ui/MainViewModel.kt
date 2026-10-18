package dev.prismdroid.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.prismdroid.auth.Account
import dev.prismdroid.auth.AccountStore
import dev.prismdroid.auth.MicrosoftAuth
import dev.prismdroid.install.*
import dev.prismdroid.launch.LaunchBuilder
import dev.prismdroid.meta.ManifestEntry
import dev.prismdroid.meta.Meta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class UiState(
    val accounts: List<Account> = emptyList(),
    val active: Account? = null,
    val instances: List<Instance> = emptyList(),
    val versions: List<ManifestEntry> = emptyList(),
    val deviceCode: MicrosoftAuth.DeviceCode? = null,
    val busy: Progress? = null,
    val error: String? = null,
)

/** Launch request handed to the Activity once everything is downloaded and the args are written. */
data class LaunchRequest(val launchFile: File, val logFile: File, val workDir: File)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    val launches = MutableStateFlow<LaunchRequest?>(null)
    private var loginJob: Job? = null

    init { refresh(); loadVersions() }

    fun refresh() {
        val acc = AccountStore.load()
        _state.value = _state.value.copy(
            accounts = acc.accounts, active = AccountStore.active(), instances = Instances.list())
    }

    private fun loadVersions() = viewModelScope.launch {
        runCatching { Meta.manifest() }.onSuccess { _state.value = _state.value.copy(versions = it) }
            .onFailure { fail(it) }
    }

    fun dismissError() { _state.value = _state.value.copy(error = null) }
    private fun fail(t: Throwable) { _state.value = _state.value.copy(error = t.message ?: t.toString(), busy = null) }

    fun signIn() {
        loginJob?.cancel()
        loginJob = viewModelScope.launch {
            try {
                val dc = MicrosoftAuth.requestDeviceCode()
                _state.value = _state.value.copy(deviceCode = dc)
                val msa = MicrosoftAuth.pollDeviceCode(dc)
                _state.value = _state.value.copy(deviceCode = null, busy = Progress("Signing in to Minecraft", 0, 0))
                val acc = MicrosoftAuth.loginMinecraft(msa)
                withContext(Dispatchers.IO) { AccountStore.upsert(acc) }
                _state.value = _state.value.copy(busy = null)
                refresh()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(deviceCode = null); fail(e)
            }
        }
    }

    fun cancelSignIn() { loginJob?.cancel(); _state.value = _state.value.copy(deviceCode = null) }

    fun selectAccount(uuid: String) {
        val f = AccountStore.load(); AccountStore.save(f.copy(active = uuid)); refresh()
    }

    fun removeAccount(uuid: String) { AccountStore.remove(uuid); refresh() }

    fun createInstance(name: String, version: String, fabric: Boolean, memoryMb: Int) = viewModelScope.launch {
        try {
            val loader = if (fabric) {
                _state.value = _state.value.copy(busy = Progress("Looking up Fabric", 0, 0))
                Loader("fabric", Meta.fabricLoaders(version).first())
            } else null
            Instances.create(InstanceConfig(name.ifBlank { version }, version, loader, memoryMb))
            _state.value = _state.value.copy(busy = null)
            refresh()
        } catch (e: Exception) { fail(e) }
    }

    fun deleteInstance(inst: Instance) { Instances.delete(inst); refresh() }

    fun play(inst: Instance, server: String?, demo: Boolean = false) = viewModelScope.launch {
        val ctx = getApplication<Application>()
        try {
            val acc = if (demo) {
                // Debug builds only: Minecraft's official demo mode, to test the runtime without signing in.
                check(dev.prismdroid.BuildConfig.DEBUG)
                Account("00000000000000000000000000000000", "Demo", "0", Long.MAX_VALUE, "")
            } else {
                val stored = AccountStore.active() ?: error("Sign in with a Microsoft account that owns Minecraft first.")
                _state.value = _state.value.copy(busy = Progress("Checking account", 0, 0))
                MicrosoftAuth.ensureFresh(stored).also { if (it !== stored) withContext(Dispatchers.IO) { AccountStore.upsert(it) } }
            }

            if (!RuntimeInstaller.isBundled(ctx)) error("This build has no Java runtime. Run native/build-jre.sh and rebuild the APK.")
            withContext(Dispatchers.IO) {
                RuntimeInstaller.ensureJre(ctx) { s -> _state.value = _state.value.copy(busy = Progress(s, 0, 0)) }
            }
            Installer.install(inst) { p -> _state.value = _state.value.copy(busy = p) }

            val cfg = inst.config.copy(lastPlayed = System.currentTimeMillis(), lastServer = server ?: inst.config.lastServer)
            val updated = Instances.update(inst, cfg)
            val file = withContext(Dispatchers.IO) { LaunchBuilder.build(updated, acc, server?.takeIf { it.isNotBlank() && !demo }, demo) }
            updated.logFile.parentFile?.mkdirs()
            _state.value = _state.value.copy(busy = null)
            launches.value = LaunchRequest(file, updated.logFile, updated.gameDir)
            refresh()
        } catch (e: Exception) { fail(e) }
    }
}
