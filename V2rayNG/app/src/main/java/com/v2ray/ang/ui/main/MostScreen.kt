package com.v2ray.ang.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem

@Composable
internal fun MostScreen(
    mainViewModel: MainViewModel,
    onAction: (MainAction) -> Unit,
    onNavigate: (MainDestination) -> Unit,
) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    if (advanced) {
        MainScreen(mainViewModel, onAction, onNavigate)
        BackHandler { advanced = false }
        return
    }
    BackHandler(enabled = tab != 0) { tab = 0 }
    val state by mainViewModel.uiState.collectAsStateWithLifecycle()
    val group by mainViewModel.serverGroupState(state.selectedGroupId).collectAsStateWithLifecycle()
    val loading by mainViewModel.isLoading.collectAsStateWithLifecycle()
    val rows = if (favoritesOnly) group.rows.filter { it.guid in state.favoriteGuids } else group.rows
    val titles = listOf(R.string.most_home, R.string.most_servers, R.string.most_links, R.string.most_settings)
    val icons = listOf(R.drawable.ic_most_power, R.drawable.ic_most_servers, R.drawable.ic_subscriptions_24dp, R.drawable.ic_settings_24dp)
    Scaffold(
        bottomBar = {
            NavigationBar {
                titles.forEachIndexed { index, title ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Icon(painterResource(icons[index]), contentDescription = null) },
                        label = { Text(stringResource(title), maxLines = 1) },
                    )
                }
            }
        }
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(if (tab == 1 && state.doubleColumnDisplay) 2 else 1),
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_most_brand), null, Modifier.size(38.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.app_name), fontSize = 26.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                        Text(stringResource(R.string.most_tagline), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    AssistChip(onClick = { onAction(MainAction.OpenImport()) }, enabled = !loading, label = { Text(stringResource(R.string.most_add)) }, leadingIcon = {
                        Icon(painterResource(R.drawable.ic_add_24dp), null, Modifier.size(16.dp))
                    })
                }
            }
            if (loading) item(span = { GridItemSpan(maxLineSpan) }) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            when (tab) {
                0 -> {
                    if (state.appUpdateAvailable) item(span = { GridItemSpan(maxLineSpan) }) {
                        MostLink(R.string.most_ota_banner, R.drawable.ic_check_update_24dp, { onNavigate(MainDestination.CheckUpdate) })
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp))
                                .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surface)))
                                .padding(22.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(stringResource(if (state.isRunning) R.string.most_connected else R.string.most_ready),
                                        style = MaterialTheme.typography.titleLarge)
                                    Text(stringResource(if (state.isRunning) R.string.most_disconnect else R.string.most_connect),
                                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                }
                                val action = stringResource(if (state.isRunning) R.string.most_disconnect else R.string.most_connect)
                                Surface(
                                    onClick = { onAction(MainAction.ToggleService) },
                                    enabled = state.isRunning || state.selectedGuid != null,
                                    modifier = Modifier.size(88.dp).semantics { contentDescription = action; role = Role.Button },
                                    shape = CircleShape,
                                    color = if (state.isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    contentColor = if (state.isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                                    shadowElevation = if (state.isRunning) 8.dp else 0.dp,
                                ) { Box(contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_most_power), null, Modifier.size(38.dp)) } }
                            }
                            Text(mainViewModel.formatStatus(state.status), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        MostLink(R.string.most_apps_title, R.drawable.ic_per_apps_24dp, { onNavigate(MainDestination.PerAppProxy) })
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(stringResource(R.string.most_selected), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        val profile = state.selectedProfile
                        Card(onClick = { tab = 1 }, shape = RoundedCornerShape(22.dp)) {
                            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(profile?.let { countryName(it) } ?: stringResource(R.string.most_choose), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                if (profile != null) {
                                    Text(profile.remarks, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(routeName(profile), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                } else Text(stringResource(R.string.most_first_subscription), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = { tab = 1 }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.most_choose)) }
                            OutlinedButton(onClick = { onAction(MainAction.TestCurrentServer) }, enabled = state.isRunning && !state.isTesting, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.most_test)) }
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (state.isRunning) {
                                    val health = state.health
                                    val label = when (health?.state) {
                                        com.v2ray.ang.dto.MostLinkState.ONLINE -> R.string.most_health_online
                                        com.v2ray.ang.dto.MostLinkState.RECOVERING -> R.string.most_health_recovering
                                        com.v2ray.ang.dto.MostLinkState.NO_INTERNET -> R.string.most_health_failed
                                        com.v2ray.ang.dto.MostLinkState.NO_NETWORK -> R.string.most_health_no_network
                                        com.v2ray.ang.dto.MostLinkState.UNSUPPORTED -> R.string.most_health_unsupported
                                        else -> R.string.most_health_checking
                                    }
                                    Text(stringResource(label), style = MaterialTheme.typography.titleMedium,
                                        color = if (health?.state == com.v2ray.ang.dto.MostLinkState.NO_INTERNET) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                                    if (!health?.ip.isNullOrBlank()) {
                                        Text(stringResource(R.string.most_exit_ip, health!!.ip))
                                        if (health.country.isNotBlank()) Text(stringResource(R.string.most_exit_country,
                                            java.util.Locale.forLanguageTag("und-" + health.country).displayCountry))
                                    } else if (health?.state == com.v2ray.ang.dto.MostLinkState.ONLINE) Text(stringResource(R.string.most_exit_unknown))
                                    TextButton(onClick = { onAction(MainAction.RefreshConnectionHealth) }) { Text(stringResource(R.string.most_health_refresh)) }
                                }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(R.string.most_auto_recovery), Modifier.weight(1f))
                                    Switch(checked = state.autoRecovery, onCheckedChange = { onAction(MainAction.SetAutoRecovery(it)) })
                                }
                                Text(stringResource(if (state.backupGuid.isBlank()) R.string.most_backup_hint else R.string.most_backup_configured),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (state.selectedGuid == null) item(span = { GridItemSpan(maxLineSpan) }) {
                        Button(onClick = { onAction(MainAction.OpenImport()) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.most_import_title)) }
                    }
                }
                1 -> {
                    item(span = { GridItemSpan(maxLineSpan) }) { Text(stringResource(R.string.most_servers), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        OutlinedTextField(value = state.searchQuery, onValueChange = { onAction(MainAction.Search(it)) }, label = { Text(stringResource(R.string.most_search)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(18.dp))
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(state.groups, key = { it.id }) { groupItem ->
                                FilterChip(selected = state.selectedGroupId == groupItem.id, onClick = { onAction(MainAction.SelectGroup(groupItem.id)) }, label = { Text(groupItem.remarks) })
                            }
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(selected = favoritesOnly, onClick = { favoritesOnly = !favoritesOnly }, label = { Text(stringResource(R.string.most_favorites)) })
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { onAction(MainAction.SortByTestResults) }) { Icon(painterResource(R.drawable.ic_most_sort), stringResource(R.string.most_sort)) }
                            TextButton(onClick = { onAction(if (state.isTesting) MainAction.CancelTesting else MainAction.TestRealAllServers) }) { Text(stringResource(if (state.isTesting) R.string.most_stop else R.string.most_test)) }
                        }
                    }
                    if (rows.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(stringResource(if (favoritesOnly) R.string.most_no_favorites else R.string.most_no_servers), Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(rows, key = { it.guid }) { row ->
                        MostServerRow(row, row.guid == state.selectedGuid, row.guid in state.favoriteGuids, row.guid == state.backupGuid, onAction)
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) { TextButton(onClick = { onAction(MainAction.Search("")); advanced = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.most_advanced)) } }
                }
                2 -> {
                    item { Text(stringResource(R.string.most_subscriptions), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
                    item { Text(stringResource(R.string.most_subscription_hint), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    item { Button(onClick = { onAction(MainAction.OpenImport()) }, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.most_import_title)) } }
                    item { OutlinedButton(onClick = { onAction(MainAction.UpdateSubscriptions) }, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.most_refresh)) } }
                    items(state.groups.filter { it.id.isNotEmpty() }, key = { it.id }) { item ->
                        Card(onClick = { onAction(MainAction.SelectGroup(item.id)); tab = 1 }) {
                            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(item.remarks, style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.most_open_servers), color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    item { MostLink(R.string.most_clipboard, R.drawable.ic_copy, { onAction(MainAction.PasteMostImport) }) }
                    item { MostLink(R.string.most_qr, R.drawable.ic_scan_24dp, { onAction(MainAction.ImportQRcode) }) }
                    item { MostLink(R.string.most_manage_subscriptions, R.drawable.ic_edit_24dp, { onNavigate(MainDestination.Subscriptions) }) }
                }
                3 -> {
                    item { Text(stringResource(R.string.most_settings), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
                    item { MostLink(R.string.most_preferences, R.drawable.ic_settings_24dp, { onNavigate(MainDestination.Settings) }) }
                    item { MostLink(R.string.most_apps_title, R.drawable.ic_per_apps_24dp, { onNavigate(MainDestination.PerAppProxy) }) }
                    item { MostLink(R.string.most_routing, R.drawable.ic_routing_24dp, { onNavigate(MainDestination.Routing) }) }
                    item { MostLink(R.string.most_backup, R.drawable.ic_backup_24dp, { onNavigate(MainDestination.BackupRestore) }) }
                    item { MostLink(R.string.most_logs, R.drawable.ic_logcat_24dp, { onNavigate(MainDestination.Logcat) }) }
                    item { MostLink(R.string.most_ota_title, R.drawable.ic_check_update_24dp, { onNavigate(MainDestination.CheckUpdate) }) }
                    item { MostLink(R.string.most_about, R.drawable.ic_about_24dp, { onNavigate(MainDestination.About) }) }
                    item { Text(stringResource(R.string.most_privacy), Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
    if (state.importOpen) MostImportDialog(state, loading, onAction) {
        if (state.importResult != null) tab = 1
        onAction(MainAction.DismissImport)
    }
}

@Composable
private fun MostServerRow(row: ServerRowUiModel, selected: Boolean, favorite: Boolean, backup: Boolean, onAction: (MainAction) -> Unit) {
    val name = countryName(row.profile)
    Card(
        onClick = { onAction(MainAction.SelectServer(row.guid)) },
        modifier = Modifier.fillMaxWidth().semantics { this.selected = selected },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(routeName(row.profile), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                IconToggleButton(checked = favorite, onCheckedChange = { onAction(MainAction.ToggleFavorite(row.guid)) }) {
                    Icon(painterResource(if (favorite) R.drawable.ic_most_star_filled else R.drawable.ic_most_star), stringResource(if (favorite) R.string.most_unfavorite else R.string.most_favorite))
                }
            }
            FilterChip(selected = backup, onClick = { onAction(MainAction.SetBackup(row.guid)) },
                label = { Text(stringResource(if (backup) R.string.most_backup_selected else R.string.most_backup_choose)) })
            Text(row.remarks, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(row.profile.network.orEmpty().uppercase(), style = MaterialTheme.typography.labelSmall)
                Text(
                    when {
                        row.testDelayMillis > 0 -> stringResource(R.string.most_delay, row.testDelayMillis)
                        row.testDelayMillis < 0 -> stringResource(R.string.most_unavailable)
                        else -> stringResource(R.string.most_untested)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (row.testDelayMillis < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MostLink(title: Int, icon: Int, onClick: () -> Unit) {
    Card(onClick = onClick, shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(painterResource(icon), null, tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun MostImportDialog(state: MainUiState, loading: Boolean, onAction: (MainAction) -> Unit, onDismiss: () -> Unit) {
    val preview = state.importPreview
    val issue = when (preview.issue) {
        com.v2ray.ang.fmt.ImportTextIssue.EMPTY -> R.string.most_import_empty
        com.v2ray.ang.fmt.ImportTextIssue.TOO_LARGE -> R.string.most_import_large
        com.v2ray.ang.fmt.ImportTextIssue.UNSUPPORTED -> R.string.most_import_unsupported
        com.v2ray.ang.fmt.ImportTextIssue.INVALID -> R.string.most_import_invalid
        com.v2ray.ang.fmt.ImportTextIssue.MIXED -> R.string.most_import_mixed
        com.v2ray.ang.fmt.ImportTextIssue.MULTIPLE_SUBSCRIPTIONS -> R.string.most_import_many_subscriptions
        null -> null
    }
    AlertDialog(
        onDismissRequest = { if (!state.importBusy) onDismiss() },
        title = { Text(stringResource(R.string.most_import_title)) },
        text = {
            Column(
                Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState())
                    .consumeWindowInsets(WindowInsets.navigationBars).imePadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val result = state.importResult
                if (result != null) {
                    Text(stringResource(R.string.most_import_success, result.added, result.duplicates), style = MaterialTheme.typography.titleMedium)
                } else {
                    Text(stringResource(R.string.most_import_hint))
                    OutlinedTextField(
                        value = state.importText,
                        onValueChange = { onAction(MainAction.ChangeImportText(it)) },
                        enabled = !state.importBusy,
                        label = { Text(stringResource(R.string.most_import_field)) },
                        modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 7,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onAction(MainAction.PasteMostImport) }, enabled = !state.importBusy) {
                            Text(stringResource(R.string.most_import_paste))
                        }
                        TextButton(onClick = { onAction(MainAction.ChangeImportText("")) }, enabled = !state.importBusy && state.importText.isNotEmpty()) {
                            Text(stringResource(R.string.most_import_clear))
                        }
                    }
                    when {
                        state.importAnalyzing -> Text(stringResource(R.string.most_import_analyzing))
                        issue != null -> Text(stringResource(issue), color = if (preview.issue == com.v2ray.ang.fmt.ImportTextIssue.EMPTY) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                        preview.subscription != null -> Text(stringResource(R.string.most_import_subscription))
                        else -> Text(stringResource(R.string.most_import_preview, preview.profiles.size, preview.duplicates))
                    }
                    if (preview.needsHttpConsent) Row(
                        Modifier.fillMaxWidth().toggleable(value = state.importAllowHttp, enabled = !state.importBusy,
                            role = Role.Checkbox, onValueChange = { onAction(MainAction.AllowImportHttp(it)) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = state.importAllowHttp, onCheckedChange = null, enabled = !state.importBusy)
                        Text(stringResource(R.string.most_allow_http), style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.importBusy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.most_import_working))
                    }
                    state.importError?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (state.importResult != null) onDismiss() else onAction(MainAction.ConfirmImport) },
                enabled = state.importResult != null || (state.canImport && !loading)) {
                Text(stringResource(if (state.importResult != null) R.string.most_import_done else R.string.most_import))
            }
        },
        dismissButton = {
            if (state.importResult == null) TextButton(onClick = onDismiss, enabled = !state.importBusy) {
                Text(stringResource(R.string.most_cancel))
            }
        },
    )
}

@Composable
private fun countryName(profile: ProfileItem): String = stringResource(when (MostPresentation.country(profile.remarks, profile.server)) {
    "nl" -> R.string.most_nl
    "de" -> R.string.most_de
    "pl" -> R.string.most_pl
    "fi" -> R.string.most_fi
    "msk" -> R.string.most_msk
    else -> R.string.most_server
})

@Composable
private fun routeName(profile: ProfileItem): String = stringResource(
    if (MostPresentation.isBridge(profile.remarks)) R.string.most_bridge else R.string.most_direct
)
