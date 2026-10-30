package com.v2ray.ang.ui.sites

import androidx.lifecycle.ViewModelStore
import com.v2ray.ang.core.MostSiteRouting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

// The pinned coroutines-test dispatcher replacement is experimental; remove this opt-in once stable.
@OptIn(ExperimentalCoroutinesApi::class)
class SiteRoutingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val owners = ViewModelStore()
    private class Store : SiteRoutingStore {
        var value = MostSiteRouting(); var failLoad = false; var failSave = false; var unapplied = false
        override fun pending() = unapplied
        override fun load(): MostSiteRouting { check(!failLoad); return value }
        override fun save(value: MostSiteRouting) { check(!failSave); this.value = value }
    }
    private fun model(store: Store, key: String = "sites"): SiteRoutingViewModel = SiteRoutingViewModel(store, dispatcher) {}.also { owners.put(key, it) }
    @BeforeEach fun before() { Dispatchers.setMain(dispatcher) }
    @AfterEach fun after() { owners.clear(); Dispatchers.resetMain() }
    @Test fun loadsEditsSavesAndReopensUsingOnePreferenceRecord() = runTest(dispatcher) {
        val store = Store(); val model = model(store); assertTrue(model.state.value.loading); advanceUntilIdle()
        assertFalse(model.state.value.ru); assertEquals("", model.state.value.direct)
        model.onAction(SiteRoutingAction.Ru(true)); model.onAction(SiteRoutingAction.Rf(true)); model.onAction(SiteRoutingAction.Su(true))
        model.onAction(SiteRoutingAction.Direct("HTTPS://Bank.Example/login")); model.onAction(SiteRoutingAction.Proxy("keep.ru"))
        model.onAction(SiteRoutingAction.Save); assertTrue(model.state.value.saving); advanceUntilIdle()
        assertTrue(model.state.value.saved); assertEquals(listOf("bank.example"), store.value.directDomains)
        val reopened = model(store, "reopen"); advanceUntilIdle(); assertTrue(reopened.state.value.ru); assertTrue(reopened.state.value.rf); assertTrue(reopened.state.value.su)
        assertEquals("keep.ru", reopened.state.value.proxy)
        reopened.onAction(SiteRoutingAction.Direct("")); reopened.onAction(SiteRoutingAction.Proxy("")); reopened.onAction(SiteRoutingAction.Save); advanceUntilIdle()
        assertTrue(store.value.directDomains.isEmpty()); assertTrue(store.value.proxyDomains.isEmpty())
    }
    @Test fun invalidAndFailedSavePreservePreviousRulesAndDraft() = runTest(dispatcher) {
        val store = Store(); store.value = MostSiteRouting(ruDirect = true)
        val model = model(store); advanceUntilIdle(); model.onAction(SiteRoutingAction.Proxy("bad[.ru")); model.onAction(SiteRoutingAction.Save); advanceUntilIdle()
        assertEquals(SiteRoutingIssue.INVALID, model.state.value.issue); assertTrue(store.value.ruDirect); assertTrue(store.value.proxyDomains.isEmpty())
        model.onAction(SiteRoutingAction.Proxy("keep.ru")); store.failSave = true; model.onAction(SiteRoutingAction.Save); advanceUntilIdle()
        assertEquals(SiteRoutingIssue.SAVE, model.state.value.issue); assertEquals("keep.ru", model.state.value.proxy); assertTrue(store.value.proxyDomains.isEmpty())
    }
    @Test fun applyRequiresSavedRulesAndDaemonAcknowledgement() = runTest(dispatcher) {
        val store = Store(); store.unapplied = true; val model = model(store); advanceUntilIdle()
        assertTrue(model.state.value.pending)
        model.onAction(SiteRoutingAction.Direct("example.ru")); model.onAction(SiteRoutingAction.Apply)
        assertFalse(model.state.value.applyRequested)
        model.onAction(SiteRoutingAction.Save); advanceUntilIdle(); assertTrue(model.state.value.pending)
        model.onAction(SiteRoutingAction.Apply); assertTrue(model.state.value.applyRequested)
        store.unapplied = false; val reopened = model(store, "acknowledged"); advanceUntilIdle(); assertFalse(reopened.state.value.pending)
    }
    @Test fun loadFailureRequiresRetryBeforeSaving() = runTest(dispatcher) {
        val store = Store(); store.failLoad = true; val model = model(store); advanceUntilIdle()
        assertEquals(SiteRoutingIssue.LOAD, model.state.value.issue); assertFalse(model.state.value.loaded)
        model.onAction(SiteRoutingAction.Save); assertFalse(model.state.value.saving)
        store.failLoad = false; model.onAction(SiteRoutingAction.Load); advanceUntilIdle(); assertTrue(model.state.value.loaded); assertNull(model.state.value.issue)
    }
}
