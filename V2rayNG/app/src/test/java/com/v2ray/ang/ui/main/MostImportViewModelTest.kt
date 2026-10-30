package com.v2ray.ang.ui.main

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModelStore
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.MostImportResult
import com.v2ray.ang.dto.SubscriptionUpdateResult
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.fmt.ImportTextIssue
import com.tencent.mmkv.MMKV
import com.v2ray.ang.handler.MmkvTestStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.kotlin.*

// Main dispatcher replacement remains experimental in pinned coroutines-test 1.11.0.
// Reevaluate/remove this opt-in when setMain/resetMain and scheduler controls become stable.
@OptIn(ExperimentalCoroutinesApi::class)
class MostImportViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var data: MainDataSource
    private lateinit var model: MainViewModel
    private lateinit var store: ViewModelStore
    private lateinit var log: MockedStatic<Log>
    private lateinit var storage: MmkvTestStorage
    private var selected: String? = null
    private var group = AppConfig.DEFAULT_SUBSCRIPTION_ID
    private var favorites = emptySet<String>()
    private var groups = listOf(SubscriptionCache(AppConfig.DEFAULT_SUBSCRIPTION_ID,SubscriptionItem(remarks="Manual")))
    private val link = "vless://id@example.test:443?type=tcp#NL"

    @BeforeEach fun setup() {
        Dispatchers.setMain(dispatcher)
        log = Mockito.mockStatic(Log::class.java)
        // LogUtil reads its log level from MMKV even though the import data source is mocked.
        // Supply that ambient dependency so this class also passes when run on its own.
        storage = MmkvTestStorage(mapOf("settingsStorage" to mock<MMKV>()))
        data = mock()
        whenever(data.mainServiceEvent).thenReturn(emptyFlow())
        whenever(data.getSelectedSubscriptionId()).thenAnswer { group }
        whenever(data.getSelectServer()).thenAnswer { selected }
        whenever(data.getFavoriteGuids()).thenAnswer { favorites }
        whenever(data.getSubscriptions()).thenAnswer { groups }
        whenever(data.getServerGuidList(any())).thenReturn(emptyList())
        doAnswer { group=it.getArgument(0);null }.whenever(data).setSelectedSubscriptionId(any())
        doAnswer { selected=it.getArgument(0);null }.whenever(data).setSelectServer(any())
        runBlocking { whenever(data.importMostProfiles(any())).thenReturn(MostImportResult(1,0,group,"new-guid")) }
        model=MainViewModel(mock<Application>(),data,dispatcher,dispatcher)
        store=ViewModelStore();store.put("main",model)
        dispatcher.scheduler.advanceUntilIdle()
    }
    @AfterEach fun cleanup() {
        store.clear();dispatcher.scheduler.advanceUntilIdle();storage.close();log.close();Dispatchers.resetMain()
    }
    private fun text(text: String) {
        model.onAction(MainAction.OpenImport(text));dispatcher.scheduler.advanceUntilIdle()
    }
    @Test fun pendingRulesReflectRepositoryAndFailedDeletionDoesNotRemoveUiRows() {
        whenever(data.hasPendingRules()).thenReturn(true)
        model.refreshRuleState(); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.uiState.value.rulesPending)
        whenever(data.hasPendingRules()).thenReturn(false)
        model.refreshRuleState(); dispatcher.scheduler.advanceUntilIdle()
        assertFalse(model.uiState.value.rulesPending)
        whenever(data.deleteSubscription("missing")).thenReturn(false)
        model.onAction(MainAction.DeleteSubscription("missing")); dispatcher.scheduler.advanceUntilIdle()
        verify(data).deleteSubscription("missing")
    }
    @Test fun initialEmptyAndInvalidStatesDisableImport() {
        assertFalse(model.uiState.value.importOpen);assertFalse(model.uiState.value.canImport)
        text("");assertEquals(ImportTextIssue.EMPTY,model.uiState.value.importPreview.issue)
        text("vless://@bad:443");assertEquals(ImportTextIssue.INVALID,model.uiState.value.importPreview.issue)
        assertFalse(model.uiState.value.canImport)
    }
    @Test fun successCountsDuplicatesAndSelectsByGuid() {
        text("$link\n$link");assertTrue(model.uiState.value.canImport)
        model.onAction(MainAction.ConfirmImport);assertTrue(model.uiState.value.importBusy)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(MostImportResult(1,1,group,"new-guid"),model.uiState.value.importResult)
        assertEquals("new-guid",model.uiState.value.selectedGuid)
        assertFalse(model.uiState.value.importBusy)
        model.onAction(MainAction.DismissImport)
        assertFalse(model.uiState.value.importOpen);assertEquals("",model.uiState.value.importText)
    }
    @Test fun existingSelectionIsPreserved() {
        selected="old-guid";model.setupGroupTab(true);dispatcher.scheduler.advanceUntilIdle()
        text(link);model.onAction(MainAction.ConfirmImport);dispatcher.scheduler.advanceUntilIdle()
        assertEquals("old-guid",model.uiState.value.selectedGuid)
    }
    @Test fun httpRequiresConsentAndSelectsImportedGroup() {
        val url="http://example.test/sub"
        groups=groups+SubscriptionCache("sub-guid",SubscriptionItem(remarks="Sub",url=url))
        whenever(data.importMostSubscription(url,true)).thenReturn(SubscriptionUpdateResult(configCount=3,successCount=1))
        text(url);assertFalse(model.uiState.value.canImport)
        model.onAction(MainAction.AllowImportHttp(true));assertTrue(model.uiState.value.canImport)
        model.onAction(MainAction.ConfirmImport);dispatcher.scheduler.advanceUntilIdle()
        assertEquals("sub-guid",model.uiState.value.selectedGroupId)
        assertEquals(3,model.uiState.value.importResult?.added)
    }
    @Test fun failedSubscriptionRetainsTextAndConsentForRetry() {
        val url="http://example.test/bad"
        whenever(data.importMostSubscription(url,true)).thenReturn(SubscriptionUpdateResult(failureCount=1))
        text(url);model.onAction(MainAction.AllowImportHttp(true));model.onAction(MainAction.ConfirmImport)
        dispatcher.scheduler.advanceUntilIdle()
        val state=model.uiState.value
        assertEquals(R.string.most_import_retry,state.importError);assertEquals(url,state.importText)
        assertTrue(state.importAllowHttp);assertTrue(state.importOpen);assertTrue(state.canImport)
        assertNull(state.importResult)
    }
    @Test fun storageFailureAndRepeatedTapDoNotReportSuccess() {
        runBlocking { whenever(data.importMostProfiles(any())).thenThrow(IllegalStateException("failure")) }
        text(link);model.onAction(MainAction.ConfirmImport);model.onAction(MainAction.ConfirmImport)
        dispatcher.scheduler.advanceUntilIdle()
        assertNotNull(model.uiState.value.importError);assertNull(model.uiState.value.importResult)
        runBlocking { verify(data,times(1)).importMostProfiles(listOf(link)) }
        assertFalse(model.isLoading.value)
    }
    @Test fun newerPreviewWinsAndCancelledFormKeepsItsDraft() {
        model.onAction(MainAction.OpenImport(link));model.onAction(MainAction.ChangeImportText("broken"))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(ImportTextIssue.UNSUPPORTED,model.uiState.value.importPreview.issue)
        model.onAction(MainAction.DismissImport);model.onAction(MainAction.OpenImport())
        assertEquals("broken",model.uiState.value.importText)
        assertFalse(model.uiState.value.canImport)
    }
    @Test fun restoreReloadsGroupSelectionAndFavoritesTogether() {
        group="restored-group";selected="restored-guid";favorites=setOf("restored-guid")
        groups=groups+SubscriptionCache(group,SubscriptionItem(remarks="Restored"))
        model.setupGroupTab(true);dispatcher.scheduler.advanceUntilIdle()
        assertEquals(group,model.uiState.value.selectedGroupId)
        assertEquals(selected,model.uiState.value.selectedGuid)
        assertEquals(favorites,model.uiState.value.favoriteGuids)
    }
}
