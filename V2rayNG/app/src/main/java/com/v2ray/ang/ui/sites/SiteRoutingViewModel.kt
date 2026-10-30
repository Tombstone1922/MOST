package com.v2ray.ang.ui.sites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.MostSiteRouting
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal interface SiteRoutingStore {
    fun load(): MostSiteRouting
    fun save(value: MostSiteRouting)
}
private object SettingsSiteRoutingStore : SiteRoutingStore {
    override fun load() = SettingsManager.getMostSiteRouting()
    override fun save(value: MostSiteRouting) = SettingsManager.saveMostSiteRouting(value)
}
internal enum class SiteRoutingIssue { LOAD, INVALID, SAVE }
internal data class SiteRoutingUiState(
    val ru: Boolean = false, val rf: Boolean = false, val su: Boolean = false,
    val direct: String = "", val proxy: String = "", val loading: Boolean = true,
    val loaded: Boolean = false, val saving: Boolean = false, val saved: Boolean = false,
    val issue: SiteRoutingIssue? = null,
)
internal sealed interface SiteRoutingAction {
    data object Load : SiteRoutingAction
    data object Save : SiteRoutingAction
    data class Ru(val enabled: Boolean) : SiteRoutingAction
    data class Rf(val enabled: Boolean) : SiteRoutingAction
    data class Su(val enabled: Boolean) : SiteRoutingAction
    data class Direct(val text: String) : SiteRoutingAction
    data class Proxy(val text: String) : SiteRoutingAction
}

/** Owns this editor's drafts and save lifetime; MMKV remains the only durable settings owner. */
internal class SiteRoutingViewModel(
    private val store: SiteRoutingStore = SettingsSiteRoutingStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val report: (Throwable) -> Unit = { LogUtil.e(AppConfig.TAG, "MOST site preferences operation failed", it) },
) : ViewModel() {
    private val _state = MutableStateFlow(SiteRoutingUiState())
    val state = _state.asStateFlow()
    private var operation: Job? = null
    init { load() }
    fun onAction(action: SiteRoutingAction) {
        if (action == SiteRoutingAction.Load) { if (!_state.value.saving) load(); return }
        val current = _state.value
        if (!current.loaded || current.loading || current.saving) return
        if (action == SiteRoutingAction.Save) { save(current); return }
        _state.value = when (action) {
            is SiteRoutingAction.Ru -> current.copy(ru = action.enabled)
            is SiteRoutingAction.Rf -> current.copy(rf = action.enabled)
            is SiteRoutingAction.Su -> current.copy(su = action.enabled)
            is SiteRoutingAction.Direct -> current.copy(direct = action.text)
            is SiteRoutingAction.Proxy -> current.copy(proxy = action.text)
            else -> current
        }.copy(saved = false, issue = null)
    }
    private fun load() {
        operation?.cancel()
        _state.value = _state.value.copy(loading = true, issue = null)
        operation = viewModelScope.launch {
            try {
                val value = withContext(io) { store.load() }
                _state.value = SiteRoutingUiState(value.ruDirect, value.rfDirect, value.suDirect,
                    value.directDomains.joinToString("\n"), value.proxyDomains.joinToString("\n"), loading = false, loaded = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { report(error); _state.value = _state.value.copy(loading = false, issue = SiteRoutingIssue.LOAD) }
        }
    }
    private fun save(draft: SiteRoutingUiState) {
        _state.value = draft.copy(saving = true, saved = false, issue = null)
        operation = viewModelScope.launch {
            val value = try {
                withContext(io) { MostSiteRouting(draft.ru, draft.rf, draft.su, MostSiteRouting.parse(draft.direct), MostSiteRouting.parse(draft.proxy)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _state.value = draft.copy(issue = SiteRoutingIssue.INVALID); return@launch }
            try {
                withContext(io) { store.save(value) }
                _state.value = draft.copy(direct = value.directDomains.joinToString("\n"), proxy = value.proxyDomains.joinToString("\n"), saved = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { report(error); _state.value = draft.copy(issue = SiteRoutingIssue.SAVE) }
        }
    }
}
