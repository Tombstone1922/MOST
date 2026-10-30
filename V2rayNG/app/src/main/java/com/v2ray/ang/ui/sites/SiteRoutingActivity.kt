package com.v2ray.ang.ui.sites

import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar

class SiteRoutingActivity : BaseComponentActivity() {
    private val model: SiteRoutingViewModel by viewModels()
    @Composable override fun ScreenContent() {
        val state by model.state.collectAsStateWithLifecycle()
        SiteRoutingScreen(state, model::onAction) { finish() }
    }
}

@Composable
private fun SiteRoutingScreen(state: SiteRoutingUiState, action: (SiteRoutingAction) -> Unit, back: () -> Unit) {
    val editable = state.loaded && !state.loading && !state.saving
    Scaffold(topBar = { AppTopBar(title = stringResource(R.string.most_sites_title), onBackClick = back, isLoading = state.loading || state.saving) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
            .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.most_sites_description), style = MaterialTheme.typography.bodyMedium)
            Card {
                Column {
                    SiteSwitch(stringResource(R.string.most_sites_ru), state.ru, editable) { action(SiteRoutingAction.Ru(it)) }
                    SiteSwitch(stringResource(R.string.most_sites_rf), state.rf, editable) { action(SiteRoutingAction.Rf(it)) }
                    SiteSwitch(stringResource(R.string.most_sites_su), state.su, editable) { action(SiteRoutingAction.Su(it)) }
                }
            }
            OutlinedTextField(value = state.direct, onValueChange = { action(SiteRoutingAction.Direct(it)) }, enabled = editable,
                label = { Text(stringResource(R.string.most_sites_direct)) }, minLines = 3, maxLines = 7, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = state.proxy, onValueChange = { action(SiteRoutingAction.Proxy(it)) }, enabled = editable,
                label = { Text(stringResource(R.string.most_sites_proxy)) }, minLines = 3, maxLines = 7, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.most_sites_priority), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.issue?.let {
                Text(stringResource(when (it) { SiteRoutingIssue.LOAD -> R.string.most_sites_load_error; SiteRoutingIssue.INVALID -> R.string.most_sites_invalid; SiteRoutingIssue.SAVE -> R.string.most_sites_save_error }), color = MaterialTheme.colorScheme.error)
            }
            if (state.issue == SiteRoutingIssue.LOAD) OutlinedButton(onClick = { action(SiteRoutingAction.Load) }, enabled = !state.loading) { Text(stringResource(R.string.most_sites_retry)) }
            Button(onClick = { action(SiteRoutingAction.Save) }, enabled = editable, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.most_sites_save)) }
            if (state.saved) Text(stringResource(R.string.most_sites_saved), color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.most_sites_dns_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.most_sites_reconnect), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SiteSwitch(label: String, value: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}
        .toggleable(value = value, enabled = enabled, role = Role.Switch, onValueChange = change).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = null, enabled = enabled)
    }
}
