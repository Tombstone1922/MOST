package com.v2ray.ang.ui.checkupdate

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.R
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.handler.MostUpdateException
import com.v2ray.ang.handler.UpdateCheckerManager
import com.v2ray.ang.ui.base.BaseViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

enum class MostUpdateStage { IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, READY, ERROR }
data class MostUpdateState(val stage: MostUpdateStage = MostUpdateStage.IDLE, val repo: String = "Tombstone1922/MOST",
    val auto: Boolean = true, val result: CheckUpdateResult? = null, val progress: Float = 0f, val error: Int? = null, val readyFile: File? = null)
class CheckUpdateViewModel(application: Application) : BaseViewModel(application) {
    private val _state = MutableStateFlow(MostUpdateState())
    val state = _state.asStateFlow()
    private var job: Job? = null
    fun checkForUpdates() {
        if (job?.isActive == true) return
        job = viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(stage = MostUpdateStage.CHECKING, error = null, repo = UpdateCheckerManager.repository(), auto = UpdateCheckerManager.autoCheck()) }
            try {
                val result = UpdateCheckerManager.checkForUpdate()
                _state.update { it.copy(stage = if (result.hasUpdate) MostUpdateStage.AVAILABLE else MostUpdateStage.CURRENT, result = result) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (e: Exception) { _state.update { it.copy(stage = MostUpdateStage.ERROR, error = (e as? MostUpdateException)?.messageRes ?: R.string.most_ota_server_error) } }
        }
    }
    fun saveRepository(value: String) {
        if (job?.isActive == true) return
        viewModelScope.launch(Dispatchers.IO) {
            try { UpdateCheckerManager.saveRepository(value); _state.update { it.copy(repo = UpdateCheckerManager.repository()) }; checkForUpdates()
            } catch (e: Exception) { _state.update { it.copy(error = (e as? MostUpdateException)?.messageRes ?: R.string.most_ota_server_error) } }
        }
    }
    private var autoJob: Job? = null
    fun setAuto(value: Boolean) {
        if (autoJob?.isActive == true) return
        autoJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                UpdateCheckerManager.setAutoCheck(value)
                _state.update { it.copy(auto = value, error = null) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                com.v2ray.ang.util.LogUtil.e(com.v2ray.ang.AppConfig.TAG, "MOST update schedule failed: component=settings", error)
                _state.update { it.copy(auto = UpdateCheckerManager.autoCheck(), error = R.string.most_ota_server_error) }
            }
        }
    }
    fun download() {
        val result = _state.value.result?.takeIf { it.hasUpdate } ?: return
        if (job?.isActive == true) return
        job = viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(stage = MostUpdateStage.DOWNLOADING, progress = 0f, error = null, readyFile = null) }
            try {
                val file = UpdateCheckerManager.download(getApplication(), result) { value -> _state.update { it.copy(progress = value) } }
                _state.update { it.copy(stage = MostUpdateStage.READY, readyFile = file) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (e: Exception) { _state.update { it.copy(stage = MostUpdateStage.ERROR, error = (e as? MostUpdateException)?.messageRes ?: R.string.most_ota_server_error) } }
        }
    }
    fun installFailed() { _state.update { it.copy(error = R.string.most_ota_install_error) } }
}
