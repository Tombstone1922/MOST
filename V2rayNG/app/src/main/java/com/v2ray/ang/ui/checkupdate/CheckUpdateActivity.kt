package com.v2ray.ang.ui.checkupdate

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar

class CheckUpdateActivity : BaseComponentActivity() {
    private val viewModel: CheckUpdateViewModel by viewModels()
    private var waitingForInstallPermission = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) viewModel.checkForUpdates()
    }
    override fun onResume() {
        super.onResume()
        if (waitingForInstallPermission) {
            waitingForInstallPermission = false
            if (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()) installUpdate()
        }
    }
    private fun installUpdate() {
        val file = viewModel.state.value.readyFile ?: return
        try {
            if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
                waitingForInstallPermission = true
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                return
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.cache", file)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) { viewModel.installFailed() }
    }
    @Composable override fun ScreenContent() {
        val state by viewModel.state.collectAsStateWithLifecycle()
        var repo by rememberSaveable(state.repo) { mutableStateOf(state.repo) }
        val busy = state.stage == MostUpdateStage.CHECKING || state.stage == MostUpdateStage.DOWNLOADING
        Scaffold(contentWindowInsets = WindowInsets(0), topBar = { AppTopBar(title = stringResource(R.string.most_ota_title), onBackClick = { finish() }, isLoading = busy) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState()).imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.most_ota_current, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(when (state.stage) {
                    MostUpdateStage.CHECKING -> R.string.most_ota_checking
                    MostUpdateStage.CURRENT -> R.string.most_ota_latest
                    MostUpdateStage.AVAILABLE -> R.string.most_ota_available
                    MostUpdateStage.DOWNLOADING -> R.string.most_ota_downloading
                    MostUpdateStage.READY -> R.string.most_ota_ready
                    else -> R.string.most_ota_hint
                }))
                state.result?.let { result -> if (result.hasUpdate) {
                    Text(result.latestVersion.orEmpty(), style = MaterialTheme.typography.titleLarge)
                    if (!result.releaseNotes.isNullOrBlank()) Text(result.releaseNotes.orEmpty())
                } }
                if (state.stage == MostUpdateStage.DOWNLOADING) {
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.most_ota_progress, (state.progress * 100).toInt()))
                }
                state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                if (state.stage == MostUpdateStage.AVAILABLE || (state.stage == MostUpdateStage.ERROR && state.result?.hasUpdate == true))
                    Button(onClick = viewModel::download, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.most_ota_download)) }
                if (state.stage == MostUpdateStage.READY) {
                    Button(onClick = { installUpdate() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.most_ota_install)) }
                    Text(stringResource(R.string.most_ota_install_hint), style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = viewModel::checkForUpdates, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.most_ota_check)) }
                Row(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.most_ota_auto), Modifier.weight(1f))
                    Switch(checked = state.auto, onCheckedChange = viewModel::setAuto)
                }
                OutlinedTextField(value = repo, onValueChange = { repo = it }, enabled = !busy, singleLine = true,
                    label = { Text(stringResource(R.string.most_ota_repo)) }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { viewModel.saveRepository(repo) }, enabled = !busy) { Text(stringResource(R.string.most_ota_save_repo)) }
            }
        }
    }
}
