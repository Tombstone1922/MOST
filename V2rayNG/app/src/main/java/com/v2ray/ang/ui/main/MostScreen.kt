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
import androidx.compose.ui.platform.LocalDensity
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
    var technicalExpanded by rememberSaveable { mutableStateOf(false) }
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }
    var deleteGuid by rememberSaveable { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var showConnections by rememberSaveable { mutableStateOf(false) }
    var renameGuid by rememberSaveable { mutableStateOf<String?>(null) }
    var renameDraft by rememberSaveable { mutableStateOf("") }
    BackHandler(enabled = tab != 0) { tab = 0 }
    val state by mainViewModel.uiState.collectAsStateWithLifecycle()
    val group by mainViewModel.serverGroupState(state.selectedGroupId).collectAsStateWithLifecycle()
    val loading by mainViewModel.isLoading.collectAsStateWithLifecycle()
    val rows = if (favoritesOnly) group.rows.filter { it.guid in state.favoriteGuids } else group.rows
    val countryMinWidth = (158 * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp
    val titles = listOf(R.string.most_home, R.string.most_servers, R.string.most_subscriptions, R.string.most_settings)
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
            columns = if (tab == 1 && (!showConnections || state.doubleColumnDisplay)) GridCells.Adaptive(countryMinWidth) else GridCells.Fixed(1),
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
            if (loading || state.routeBusy) item(span = { GridItemSpan(maxLineSpan) }) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (state.rulesPending && state.isRunning) item(span = { GridItemSpan(maxLineSpan) }) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.most_ux_pending), style = MaterialTheme.typography.titleSmall)
                        Button(onClick = { onAction(MainAction.ApplyRules) }, enabled = !state.rulesApplying && !state.routeBusy) {
                            Text(stringResource(if (state.rulesApplying) R.string.most_ux_applying else R.string.most_ux_apply))
                        }
                    }
                }
            }
            when (tab) {
                0 -> {
                    if (state.appUpdateAvailable) item(span = { GridItemSpan(maxLineSpan) }) {
                        MostLink(R.string.most_ota_banner, R.drawable.ic_check_update_24dp, { onNavigate(MainDestination.CheckUpdate) })
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        val health = state.health
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                            .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surface)))
                            .padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(stringResource(if (state.isRunning) mostHealthLabel(health?.state) else R.string.most_ready),
                                style = MaterialTheme.typography.headlineSmall)
                            val action = stringResource(if (state.isRunning) R.string.most_disconnect else R.string.most_connect)
                            Surface(onClick = { onAction(MainAction.ToggleService) },
                                enabled = state.isRunning || (state.selectedGuid != null && !state.routeBusy && !state.rulesApplying),
                                modifier = Modifier.size(104.dp).semantics { contentDescription = action; role = Role.Button }, shape = CircleShape,
                                color = if (state.isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = if (state.isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary) {
                                Box(contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_most_power), null, Modifier.size(42.dp)) }
                            }
                            Text(action, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            if (state.isRunning && !health?.ip.isNullOrBlank()) {
                                val exit = health!!.country.takeIf { it.isNotBlank() }?.let { java.util.Locale.forLanguageTag("und-$it").displayCountry }.orEmpty()
                                Text(listOf(exit, health.ip).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                            }
                            if (state.isRunning) mostHealthDetail(health?.state)?.let {
                                Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            }
                            if (state.isRunning && health?.state == com.v2ray.ang.dto.MostLinkState.RECOVERING && health.total > 0)
                                Text(stringResource(R.string.most_route_attempt, health.attempt, health.total))
                            if (state.selectedGuid == null) Button(onClick = { onAction(MainAction.OpenImport()) }) { Text(stringResource(R.string.most_import_title)) }
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        val profile = state.selectedProfile
                        Card(onClick = { tab = 1 }, shape = RoundedCornerShape(22.dp)) {
                            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(stringResource(R.string.most_ux_connection), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(if (state.mostRoute.enabled) routeCountryName(state.mostRoute.country) else profile?.let { countryName(it) } ?: stringResource(R.string.most_choose),
                                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    Text(if (state.mostRoute.enabled) stringResource(if (state.mostRoute.directOnly) R.string.most_direct else R.string.most_bridge) else profile?.let { routeName(it) }.orEmpty(),
                                        style = MaterialTheme.typography.bodyMedium)
                                }
                                Icon(painterResource(R.drawable.ic_expand_more_24dp), null)
                            }
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        MostLink(R.string.most_ux_rules, R.drawable.ic_routing_24dp, { tab = 3 })
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        TextButton(onClick = { detailsExpanded = !detailsExpanded }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(if (detailsExpanded) R.string.most_ux_hide_details else R.string.most_ux_details))
                        }
                        if (detailsExpanded) Card {
                            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                state.selectedProfile?.let { Text(it.remarks, style = MaterialTheme.typography.bodyMedium) }
                                if (state.mostRoute.enabled) Text(stringResource(R.string.most_route_auto_hint), style = MaterialTheme.typography.bodySmall)
                                Text(mainViewModel.formatStatus(state.status), style = MaterialTheme.typography.bodyMedium)
                                if ((state.health?.latencyMs ?: 0) > 0) Text(stringResource(R.string.most_delay, state.health!!.latencyMs))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { onAction(MainAction.TestCurrentServer) }, enabled = state.isRunning && !state.isTesting) { Text(stringResource(R.string.most_test)) }
                                    TextButton(onClick = { onAction(MainAction.RefreshConnectionHealth) }, enabled = state.isRunning) { Text(stringResource(R.string.most_health_refresh)) }
                                }
                            }
                        }
                    }
                    state.connectionFailure?.let { message -> item(span = { GridItemSpan(maxLineSpan) }) {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Column(Modifier.padding(18.dp)) {
                                Text(stringResource(message)); TextButton(onClick = { tab = 1 }) { Text(stringResource(R.string.most_choose)) }
                            }
                        }
                    } }
                }
                1 -> {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(stringResource(R.string.most_route_pick_country), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(state.groups, key = { it.id }) { groupItem ->
                                FilterChip(selected = state.selectedGroupId == groupItem.id,
                                    onClick = { onAction(MainAction.SelectGroup(groupItem.id)) }, enabled = !state.routeBusy, label = { Text(groupItem.remarks) })
                            }
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !showConnections, onClick = { showConnections = false }, label = { Text(stringResource(R.string.most_route_countries)) })
                            FilterChip(selected = showConnections, onClick = { showConnections = true }, label = { Text(stringResource(R.string.most_route_manual)) })
                        }
                    }
                    if (!showConnections) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Row(Modifier.fillMaxWidth().toggleable(value = state.directRoutes, enabled = !state.routeBusy,
                                role = androidx.compose.ui.semantics.Role.Checkbox,
                                onValueChange = { onAction(MainAction.ShowDirectRoutes(it)) }).padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = state.directRoutes, onCheckedChange = null, enabled = !state.routeBusy)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.most_route_direct))
                                    Text(stringResource(R.string.most_route_direct_hint), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(stringResource(R.string.most_route_country_hint), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (state.routeCountries.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(stringResource(R.string.most_route_unknown), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { showConnections = true }) { Text(stringResource(R.string.most_route_all)) }
                        }
                        items(state.routeCountries, key = { it.country }) { country ->
                            val selected = state.mostRoute.enabled && state.mostRoute.country == country.country &&
                                state.mostRoute.groupId == state.selectedGroupId && state.mostRoute.directOnly == state.directRoutes
                            Card(onClick = { onAction(MainAction.ChooseCountry(country.country)); tab = 0 }, enabled = !state.routeBusy,
                                modifier = Modifier.fillMaxWidth().semantics { this.selected = selected }, shape = RoundedCornerShape(24.dp),
                                colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
                                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Icon(painterResource(R.drawable.ic_most_servers), null, tint = MaterialTheme.colorScheme.primary)
                                    Text(routeCountryName(country.country), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text(stringResource(R.string.most_route_count, country.routes), style = MaterialTheme.typography.bodySmall)
                                    Text(stringResource(if (selected) R.string.most_route_selected else R.string.most_route_auto),
                                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            TextButton(onClick = { showConnections = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.most_route_all)) }
                        }
                    } else {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(stringResource(R.string.most_route_manual_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedTextField(value = state.searchQuery, onValueChange = { onAction(MainAction.Search(it)) },
                                label = { Text(stringResource(R.string.most_search)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(18.dp))
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                FilterChip(selected = favoritesOnly, onClick = { favoritesOnly = !favoritesOnly }, label = { Text(stringResource(R.string.most_favorites)) })
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick = { onAction(MainAction.SortByTestResults) }) { Icon(painterResource(R.drawable.ic_most_sort), stringResource(R.string.most_sort)) }
                                TextButton(onClick = { onAction(if (state.isTesting) MainAction.CancelTesting else MainAction.TestRealAllServers) }) {
                                    Text(stringResource(if (state.isTesting) R.string.most_stop else R.string.most_test))
                                }
                            }
                        }
                        if (rows.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(stringResource(if (favoritesOnly) R.string.most_no_favorites else R.string.most_no_servers), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        items(rows, key = { it.guid }) { row ->
                            MostServerRow(row, row.guid == state.selectedGuid, row.guid in state.favoriteGuids, row.guid == state.backupGuid, onAction)
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            TextButton(onClick = { tab = 3; technicalExpanded = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.most_ux_tools)) }
                        }
                    }
                }
                2 -> {
                    item { Text(stringResource(R.string.most_subscriptions), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
                    item { Text(stringResource(R.string.most_subscription_hint), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    state.subscriptionFailure?.let { failure -> item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.most_route_subscription_attention), style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(failure.messageRes))
                                TextButton(onClick = { onAction(MainAction.DismissSubscriptionFailure) }) { Text(stringResource(R.string.most_route_understood)) }
                            }
                        }
                    } }
                    item { Button(onClick = { onAction(MainAction.OpenImport()) }, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.most_import_title)) } }

                    if (state.subscriptions.isEmpty()) item {
                        Text(stringResource(R.string.most_subscriptions_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(state.subscriptions, key = { it.guid }) { item ->
                        Card(shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(item.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                        Text(stringResource(if (item.source.isBlank()) R.string.most_local_profiles_hint else R.string.most_subscription_source,
                                            item.source), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (item.source.isNotBlank()) {
                                        var menu by rememberSaveable(item.guid) { mutableStateOf(false) }
                                        Box {
                                            IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.ic_more_vert_24dp), stringResource(R.string.acc_more)) }
                                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                                DropdownMenuItem(text = { Text(stringResource(R.string.most_subscription_rename)) }, onClick = { menu = false; renameGuid = item.guid; renameDraft = item.title })
                                                DropdownMenuItem(text = { Text(stringResource(R.string.most_ux_delete)) }, onClick = { menu = false; deleteGuid = item.guid })
                                            }
                                        }
                                    }
                                }
                                Text(stringResource(R.string.most_subscription_servers, item.serverCount),
                                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                if (!item.enabled) Text(stringResource(R.string.most_subscription_disabled), color = MaterialTheme.colorScheme.error)
                                if (item.lastUpdated > 0 && item.source.isNotBlank()) Text(
                                    stringResource(R.string.most_subscription_updated,
                                        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(item.lastUpdated))),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilledTonalButton(onClick = { onAction(MainAction.SelectGroup(item.guid)); tab = 1 }, modifier = Modifier.weight(1f)) {
                                        Text(stringResource(R.string.most_open_servers))
                                    }
                                    if (item.source.isNotBlank()) OutlinedButton(onClick = { onAction(MainAction.UpdateSubscription(item.guid)) }, enabled = !loading && item.enabled) {
                                        Text(stringResource(R.string.most_subscription_update))
                                    }
                                }
                            }
                        }
                    }
                    item { MostLink(R.string.most_clipboard, R.drawable.ic_copy, { onAction(MainAction.PasteMostImport) }) }
                    item { MostLink(R.string.most_qr, R.drawable.ic_scan_24dp, { onAction(MainAction.ImportQRcode) }) }

                }
                3 -> {
                    item { Text(stringResource(R.string.most_settings), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
                    item { Text(stringResource(R.string.most_ux_rules), style = MaterialTheme.typography.titleMedium) }
                    item { MostLink(R.string.most_sites_title, R.drawable.ic_routing_24dp, { onNavigate(MainDestination.SiteRouting) }) }
                    item { MostLink(R.string.most_apps_title, R.drawable.ic_per_apps_24dp, { onNavigate(MainDestination.PerAppProxy) }) }
                    if (!state.mostRoute.enabled) item {
                        Row(Modifier.fillMaxWidth().toggleable(value = state.autoRecovery, role = Role.Switch,
                            onValueChange = { onAction(MainAction.SetAutoRecovery(it)) }).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.most_auto_recovery), Modifier.weight(1f)); Switch(checked = state.autoRecovery, onCheckedChange = null)
                        }
                    }
                    item { MostLink(R.string.most_ota_title, R.drawable.ic_check_update_24dp, { onNavigate(MainDestination.CheckUpdate) }) }
                    item { MostLink(R.string.most_preferences, R.drawable.ic_settings_24dp, { onNavigate(MainDestination.Settings) }) }
                    item { MostLink(R.string.most_about, R.drawable.ic_about_24dp, { onNavigate(MainDestination.About) }) }
                    item { TextButton(onClick = { technicalExpanded = !technicalExpanded }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.most_ux_tools)) } }
                    if (technicalExpanded) {
                        item { MostLink(R.string.most_routing, R.drawable.ic_routing_24dp, { onNavigate(MainDestination.Routing) }) }
                        item { MostLink(R.string.most_backup, R.drawable.ic_backup_24dp, { onNavigate(MainDestination.BackupRestore) }) }
                        item { MostLink(R.string.most_logs, R.drawable.ic_logcat_24dp, { onNavigate(MainDestination.Logcat) }) }
                        item { MostLink(R.string.title_user_asset_setting, R.drawable.ic_file_24dp, { onNavigate(MainDestination.UserAssets) }) }
                    }
                }
            }
        }
    }
    if (deleteGuid != null) AlertDialog(onDismissRequest = { deleteGuid = null },
        title = { Text(stringResource(R.string.most_ux_delete_subscription)) },
        text = { Text(stringResource(R.string.most_ux_delete_confirm)) },
        confirmButton = { TextButton(onClick = { deleteGuid?.let { onAction(MainAction.DeleteSubscription(it)) }; deleteGuid = null }) { Text(stringResource(R.string.most_ux_delete)) } },
        dismissButton = { TextButton(onClick = { deleteGuid = null }) { Text(stringResource(R.string.most_cancel)) } })
    if (renameGuid != null) AlertDialog(
        onDismissRequest = { renameGuid = null },
        title = { Text(stringResource(R.string.most_subscription_rename)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()
                .consumeWindowInsets(WindowInsets.navigationBars).imePadding()) {
                OutlinedTextField(value = renameDraft, onValueChange = { renameDraft = it },
                    label = { Text(stringResource(R.string.most_subscription_name)) }, singleLine = true,
                    supportingText = { Text(stringResource(R.string.most_subscription_name_hint)) })
            }
        },
        confirmButton = { TextButton(enabled = MostPresentation.validSubscriptionName(renameDraft), onClick = {
            renameGuid?.let { onAction(MainAction.RenameSubscription(it, renameDraft)) }; renameGuid = null
        }) { Text(stringResource(R.string.acc_save)) } },
        dismissButton = { TextButton(onClick = { renameGuid = null }) { Text(stringResource(R.string.most_cancel)) } },
    )
    if (state.importOpen) MostImportDialog(state, loading, onAction) {
        if (state.importResult != null) tab = 1
        onAction(MainAction.DismissImport)
    }
}

@Composable
private fun MostServerRow(row: ServerRowUiModel, selected: Boolean, favorite: Boolean, backup: Boolean, onAction: (MainAction) -> Unit) {
    var menu by rememberSaveable(row.guid) { mutableStateOf(false) }
    Card(onClick = { onAction(MainAction.SelectServer(row.guid)) }, modifier = Modifier.fillMaxWidth().semantics { this.selected = selected },
        shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(countryName(row.profile), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(routeName(row.profile), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                IconToggleButton(checked = favorite, onCheckedChange = { onAction(MainAction.ToggleFavorite(row.guid)) }) {
                    Icon(painterResource(if (favorite) R.drawable.ic_most_star_filled else R.drawable.ic_most_star), stringResource(if (favorite) R.string.most_unfavorite else R.string.most_favorite))
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.ic_more_vert_24dp), stringResource(R.string.acc_more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(if (backup) R.string.most_backup_selected else R.string.most_backup_choose)) }, onClick = { menu = false; onAction(MainAction.SetBackup(row.guid)) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.most_ux_edit)) }, onClick = { menu = false; onAction(MainAction.EditServer(row.guid, row.profile)) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.most_ux_copy_link)) }, onClick = { menu = false; onAction(MainAction.ShareClipboard(row.guid)) })
                    }
                }
            }
            Text(row.remarks, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(when { row.testDelayMillis > 0 -> stringResource(R.string.most_delay, row.testDelayMillis); row.testDelayMillis < 0 -> stringResource(R.string.most_unavailable); else -> stringResource(R.string.most_untested) },
                style = MaterialTheme.typography.bodySmall, color = if (row.testDelayMillis < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
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


@Composable
private fun routeCountryName(code: String): String = stringResource(when (code) {
    "nl" -> R.string.most_nl
    "de" -> R.string.most_de
    "pl" -> R.string.most_pl
    "fi" -> R.string.most_fi
    "msk" -> R.string.most_route_russia
    else -> R.string.most_server
})

private fun mostHealthLabel(state: com.v2ray.ang.dto.MostLinkState?): Int = when (state) {
    com.v2ray.ang.dto.MostLinkState.ONLINE -> R.string.most_health_online
    com.v2ray.ang.dto.MostLinkState.RECOVERING -> R.string.most_health_recovering
    com.v2ray.ang.dto.MostLinkState.NO_NETWORK -> R.string.most_route_no_network_title
    com.v2ray.ang.dto.MostLinkState.NO_INTERNET -> R.string.most_route_no_internet_title
    com.v2ray.ang.dto.MostLinkState.SERVER_UNAVAILABLE -> R.string.most_route_server_unavailable_title
    com.v2ray.ang.dto.MostLinkState.NO_ROUTE -> R.string.most_route_none_title
    com.v2ray.ang.dto.MostLinkState.WRONG_COUNTRY -> R.string.most_route_wrong_country_title
    com.v2ray.ang.dto.MostLinkState.UNSUPPORTED -> R.string.most_health_unsupported
    else -> R.string.most_health_checking
}

private fun mostHealthDetail(state: com.v2ray.ang.dto.MostLinkState?): Int? = when (state) {
    com.v2ray.ang.dto.MostLinkState.NO_NETWORK -> R.string.most_route_no_network
    com.v2ray.ang.dto.MostLinkState.NO_INTERNET -> R.string.most_route_no_internet
    com.v2ray.ang.dto.MostLinkState.SERVER_UNAVAILABLE -> R.string.most_route_server_unavailable
    com.v2ray.ang.dto.MostLinkState.NO_ROUTE -> R.string.most_route_none
    com.v2ray.ang.dto.MostLinkState.WRONG_COUNTRY -> R.string.most_route_wrong_country
    else -> null
}
