package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.dto.LocateTarget

/** Locale-neutral state formatted only when it reaches the main UI. */
sealed interface MainStatus {
    data object Disconnected : MainStatus
    data object Connected : MainStatus
    data object Testing : MainStatus
    data class TestProgress(val progress: String) : MainStatus
    data class ConnectionTest(val result: ConnectionTestResult) : MainStatus
}

/**
 * Main UI state
 */
data class MainUiState(
    val groups: List<GroupMapItem> = emptyList(),
    val selectedGroupId: String = "",
    val selectedGuid: String? = null,
    val isRunning: Boolean = false,
    val isTesting: Boolean = false,
    val status: MainStatus = MainStatus.Disconnected,
    val locateTarget: LocateTarget? = null,
    val confirmRemove: Boolean = false,
    val doubleColumnDisplay: Boolean = false,
    val shareQRCodeBitmap: android.graphics.Bitmap? = null,
    val favoriteGuids: Set<String> = emptySet(),
    val selectedProfile: com.v2ray.ang.dto.entities.ProfileItem? = null,
    val searchQuery: String = "",
    val importOpen: Boolean = false,
    val importText: String = "",
    val importAllowHttp: Boolean = false,
    val importPreview: com.v2ray.ang.fmt.ImportTextPreview = com.v2ray.ang.fmt.ImportTextPreview(issue = com.v2ray.ang.fmt.ImportTextIssue.EMPTY),
    val importAnalyzing: Boolean = false,
    val importBusy: Boolean = false,
    val health: com.v2ray.ang.dto.MostConnectionHealth? = null,
    val autoRecovery: Boolean = true,
    val backupGuid: String = "",
    val appUpdateAvailable: Boolean = false,
    val importError: Int? = null,
    val importResult: com.v2ray.ang.dto.MostImportResult? = null,
) {
    val canImport: Boolean get() = importPreview.ready && !importAnalyzing && !importBusy &&
        importResult == null && (!importPreview.needsHttpConsent || importAllowHttp)
}

/**
 * All possible user interaction intents
 */
sealed interface MainAction {
    data object CheckAppUpdates : MainAction
    data object RefreshConnectionHealth : MainAction
    data class SetAutoRecovery(val enabled: Boolean) : MainAction
    data class SetBackup(val guid: String) : MainAction
    data class OpenImport(val text: String? = null) : MainAction
    data class ChangeImportText(val text: String) : MainAction
    data class AllowImportHttp(val allow: Boolean) : MainAction
    data object ConfirmImport : MainAction
    data object DismissImport : MainAction
    data object PasteMostImport : MainAction
    data class ToggleFavorite(val guid: String) : MainAction
    data class ImportSubscription(val address: String, val allowHttp: Boolean) : MainAction
    data object Initialize : MainAction
    data object RefreshGroups : MainAction
    data object ToggleService : MainAction
    data object TestCurrentServer : MainAction
    data object TestAllServers : MainAction
    data object TestRealAllServers : MainAction
    data object CancelTesting : MainAction
    data object RemoveAllServers : MainAction
    data object RemoveDuplicateServers : MainAction
    data object RemoveInvalidServers : MainAction
    data object SortByTestResults : MainAction
    data object UpdateSubscriptions : MainAction
    data object ExportAll : MainAction

    data object ImportQRcode : MainAction
    data object ImportClipboard : MainAction
    data object ImportConfigLocal : MainAction
    data class ImportManually(val type: Int) : MainAction
    data object RestartService : MainAction
    data object LocateSelectedServer : MainAction

    data class SelectGroup(val groupId: String) : MainAction
    data class SelectServer(val guid: String) : MainAction
    data class RemoveServer(val guid: String) : MainAction
    data class EditServer(val guid: String, val profile: com.v2ray.ang.dto.entities.ProfileItem) : MainAction
    data class Search(val query: String) : MainAction
    data class ShareQRCode(val guid: String) : MainAction
    data class ShareClipboard(val guid: String) : MainAction
    data class ShareFullContent(val guid: String) : MainAction
    data object DismissQRCodeDialog : MainAction

    data class ImportBatchConfig(val configText: String) : MainAction

    data object LocateHandled : MainAction
}
