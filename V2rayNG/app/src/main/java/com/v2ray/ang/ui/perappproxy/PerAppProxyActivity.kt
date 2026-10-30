package com.v2ray.ang.ui.perappproxy

import android.os.Bundle
import android.content.Intent
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.v2ray.ang.R
import com.v2ray.ang.dto.AppInfo
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppDropdownMenuItems
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.util.AppIconFetcher
import com.v2ray.ang.util.Utils

private enum class PerAppMenuAction(@StringRes val labelRes: Int) {
    SelectAll(R.string.menu_item_select_all),
    InvertSelection(R.string.menu_item_invert_selection),
    SelectProxyApps(R.string.menu_item_select_proxy_app),
    ImportSelection(R.string.menu_item_import_proxy_app),
    ExportSelection(R.string.menu_item_export_proxy_app)
}

class PerAppProxyActivity : BaseComponentActivity() {
    private val viewModel: PerAppProxyViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_OK, Intent().putExtra(com.v2ray.ang.handler.SettingsChangeManager.EXTRA_RULES_EDITOR, true))
        viewModel.loadApps(this)
    }

    @Composable
    override fun ScreenContent() {
        val apps by viewModel.displayedApps.collectAsStateWithLifecycle()
        val loading by viewModel.isLoading.collectAsStateWithLifecycle()
        val selected by viewModel.blacklist.collectAsStateWithLifecycle()
        val enabled by viewModel.perAppProxyEnabled.collectAsStateWithLifecycle()
        val bypass by viewModel.bypassApps.collectAsStateWithLifecycle()
        val selectedOnly by viewModel.selectedOnly.collectAsStateWithLifecycle()
        val showSystem by viewModel.showSystem.collectAsStateWithLifecycle()
        val failed by viewModel.loadFailed.collectAsStateWithLifecycle()
        PerAppProxyScreen(
            apps = apps, isLoading = loading, selected = if (enabled) selected else emptySet(),
            enabled = enabled, bypass = bypass, selectedOnly = selectedOnly,
            showSystem = showSystem, loadFailed = failed,
            onBack = { finish() },
            onApply = { setResult(RESULT_OK, Intent().putExtra(com.v2ray.ang.handler.SettingsChangeManager.EXTRA_RULES_EDITOR, true)
                .putExtra(com.v2ray.ang.handler.SettingsChangeManager.EXTRA_APPLY_RULES, true)); finish() },
            onMode = { useSelection, except ->
                viewModel.setBypassAppsEnabled(except)
                viewModel.setPerAppProxyEnabled(useSelection)
            },
            onToggle = viewModel::toggle,
            onSearch = viewModel::filterApps,
            onSelectedOnly = viewModel::setSelectedOnly,
            onShowSystem = viewModel::setShowSystem,
            onRetry = { viewModel.loadApps(this) },
            onMenu = { action ->
                when (action) {
                    PerAppMenuAction.SelectAll -> viewModel.selectAll()
                    PerAppMenuAction.InvertSelection -> viewModel.invertSelection()
                    PerAppMenuAction.SelectProxyApps -> viewModel.selectProxyAppAuto(this)
                    PerAppMenuAction.ImportSelection -> viewModel.importProxyApp(Utils.getClipboard(this), this)
                    PerAppMenuAction.ExportSelection -> {
                        Utils.setClipboard(this, viewModel.exportProxyApp())
                        toastSuccess(R.string.toast_success)
                    }
                }
            }
        )
    }
}

@Composable
private fun PerAppProxyScreen(
    apps: List<AppInfo>, isLoading: Boolean, selected: Set<String>,
    enabled: Boolean, bypass: Boolean, selectedOnly: Boolean,
    showSystem: Boolean, loadFailed: Boolean,
    onBack: () -> Unit, onApply: () -> Unit, onMode: (Boolean, Boolean) -> Unit,
    onToggle: (String) -> Unit, onSearch: (String) -> Unit,
    onSelectedOnly: (Boolean) -> Unit, onShowSystem: (Boolean) -> Unit,
    onRetry: () -> Unit, onMenu: (PerAppMenuAction) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) { onSearch(query) }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.most_apps_title),
                onBackClick = onBack,
                isLoading = isLoading,
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(painterResource(R.drawable.ic_more_vert_24dp), stringResource(R.string.acc_more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            AppDropdownMenuItems(PerAppMenuAction.entries, { it.labelRes }) {
                                menuOpen = false
                                onMenu(it)
                            }
                        }
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Text(
                        stringResource(R.string.most_apps_apply_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = onApply, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text(stringResource(R.string.most_apps_apply))
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "modes") {
                Column {
                    Text(stringResource(R.string.most_apps_heading), style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(16.dp))
                    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.padding(6.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                ModeRow(R.string.most_apps_all, R.string.most_apps_all_short, !enabled, Modifier.weight(1f)) { onMode(false, bypass) }
                                ModeRow(R.string.most_apps_only, R.string.most_apps_only_short, enabled && !bypass, Modifier.weight(1f)) { onMode(true, false) }
                                ModeRow(R.string.most_apps_except, R.string.most_apps_except_short, enabled && bypass, Modifier.weight(1f)) { onMode(true, true) }
                            }
                            Text(
                                stringResource(if (!enabled) R.string.most_apps_all_hint else if (bypass) R.string.most_apps_except_hint else R.string.most_apps_only_hint),
                                Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            run {
                item(key = "search") {
                    Column {
                        Text(
                            stringResource(R.string.most_apps_selected_count, selected.size),
                            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                            style = MaterialTheme.typography.titleMedium
                        )
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it; onSearch(it) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(18.dp),
                            label = { Text(stringResource(R.string.most_apps_search)) },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_search_24dp), null) },
                            trailingIcon = {
                                if (query.isNotEmpty()) {
                                    IconButton(onClick = { query = ""; onSearch("") }) {
                                        Icon(painterResource(R.drawable.ic_most_clear), stringResource(R.string.most_apps_clear_search))
                                    }
                                }
                            }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = selectedOnly, onClick = { onSelectedOnly(!selectedOnly) },
                                label = { Text(stringResource(R.string.most_apps_selected_filter)) })
                            FilterChip(selected = showSystem, onClick = { onShowSystem(!showSystem) },
                                label = { Text(stringResource(R.string.most_apps_system_filter)) })
                        }
                    }
                }
                if (enabled && selected.isEmpty()) {
                    item(key = "empty_selection") {
                        Text(
                            stringResource(if (bypass) R.string.most_apps_empty_except else R.string.most_apps_empty_only),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
                if (loadFailed) {
                    item(key = "failed") {
                        Text(stringResource(R.string.most_apps_load_failed))
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.most_apps_retry)) }
                    }
                } else if (apps.isEmpty() && !isLoading) {
                    item(key = "no_results") {
                        Text(stringResource(R.string.most_apps_no_results), Modifier.padding(vertical = 24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(apps, key = { it.packageName }) { app ->
                    val checked = app.packageName in selected
                    AppRouteRow(app, checked, if (!enabled) true else if (bypass) !checked else checked) { onToggle(app.packageName) }
                }
            }
        }
    }
}

@Composable
private fun ModeRow(@StringRes title: Int, @StringRes shortTitle: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val fullTitle = stringResource(title)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    ) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 68.dp)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .semantics { contentDescription = fullTitle }
                .padding(horizontal = 8.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(shortTitle), modifier = Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun AppRouteRow(app: AppInfo, checked: Boolean, viaVpn: Boolean, onToggle: () -> Unit) {
    val status = stringResource(if (viaVpn) R.string.most_apps_vpn else R.string.most_apps_direct)
    val context = LocalContext.current
    val icon = remember(app.packageName, context) {
        ImageRequest.Builder(context).data("appicon:${app.packageName}")
            .fetcherFactory(AppIconFetcher.Factory(context)).build()
    }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth()
                .toggleable(checked, role = Role.Checkbox, onValueChange = { onToggle() })
                .semantics(mergeDescendants = true) {
                    contentDescription = app.appName
                    stateDescription = status
                }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(icon, null, modifier = Modifier.size(42.dp), error = painterResource(R.drawable.ic_image_24dp))
            Column(Modifier.weight(1f).padding(horizontal = 14.dp).clearAndSetSemantics {}) {
                Text(app.appName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(status, style = MaterialTheme.typography.bodySmall,
                    color = if (viaVpn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Checkbox(checked, onCheckedChange = null, modifier = Modifier.clearAndSetSemantics {})
        }
    }
}
