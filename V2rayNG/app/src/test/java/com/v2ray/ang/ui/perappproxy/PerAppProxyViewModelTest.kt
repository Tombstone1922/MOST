package com.v2ray.ang.ui.perappproxy

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModelStore
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.AppInfo
import com.v2ray.ang.handler.MmkvTestStorage
import com.v2ray.ang.handler.SettingsChangeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.kotlin.*

// Dispatchers.setMain/resetMain remain experimental in coroutines-test 1.11.0.
// Remove this opt-in when those APIs become stable.
@OptIn(ExperimentalCoroutinesApi::class)
class PerAppProxyViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var mmkv: MMKV
    private lateinit var storage: MmkvTestStorage
    private lateinit var log: MockedStatic<Log>
    private lateinit var app: Application
    private val booleans = mutableMapOf<String, Boolean>()
    private var packages: MutableSet<String> = mutableSetOf()
    private val stores = mutableListOf<ViewModelStore>()
    private var failLoad = false
    private val installed = listOf(
        AppInfo("Browser", "test.browser", false, 0),
        AppInfo("Chat", "test.chat", false, 0),
        AppInfo("System", "test.system", true, 0),
        AppInfo("MOST", BuildConfig.APPLICATION_ID, false, 0)
    )

    @BeforeEach fun setup() {
        Dispatchers.setMain(dispatcher)
        SettingsChangeManager.consumeRestartService()
        log = Mockito.mockStatic(Log::class.java)
        mmkv = mock()
        whenever(mmkv.decodeBool(any(), any())).thenAnswer { booleans[it.getArgument(0)] ?: it.getArgument<Boolean>(1) }
        whenever(mmkv.decodeStringSet(any())).thenAnswer { packages.toMutableSet() }
        whenever(mmkv.encode(any(), any<Boolean>())).thenAnswer {
            booleans[it.getArgument(0)] = it.getArgument(1); true
        }
        whenever(mmkv.encode(any(), any<Set<String>>())).thenAnswer {
            packages = it.getArgument<Set<String>>(1).toMutableSet(); true
        }
        storage = MmkvTestStorage(mapOf("settingsStorage" to mmkv))
        app = mock()
        whenever(app.applicationContext).thenReturn(app)
    }
    private fun model(): PerAppProxyViewModel = PerAppProxyViewModel(app, {
        if (failLoad) error("unavailable") else installed
    }, dispatcher, dispatcher).also { model ->
        stores += ViewModelStore().apply { put("apps", model) }
    }
    @AfterEach fun cleanup() {
        stores.forEach { it.clear() }
        dispatcher.scheduler.advanceUntilIdle()
        storage.close(); log.close(); Dispatchers.resetMain()
        SettingsChangeManager.consumeRestartService()
    }
    @Test fun changingEitherModeFlagRequestsReconnectAndPersists() {
        val model = model()
        assertFalse(model.perAppProxyEnabled.value)
        model.setPerAppProxyEnabled(true)
        assertTrue(SettingsChangeManager.consumeRestartService())
        model.setBypassAppsEnabled(true)
        assertTrue(SettingsChangeManager.consumeRestartService())
        val reopened = model()
        assertTrue(reopened.perAppProxyEnabled.value)
        assertTrue(reopened.bypassApps.value)
        model.setPerAppProxyEnabled(false)
        assertTrue(SettingsChangeManager.consumeRestartService())
    }
    @Test fun unchangedModeDoesNotRequestReconnect() {
        val model = model()
        model.setPerAppProxyEnabled(false); model.setBypassAppsEnabled(false)
        assertFalse(SettingsChangeManager.consumeRestartService())
    }
    @Test fun togglingSelectionPersistsAndRequestsReconnect() {
        val model = model()
        model.toggle("test.browser")
        assertEquals(setOf("test.browser"), model().blacklist.value)
        assertTrue(SettingsChangeManager.consumeRestartService())
        model.toggle("test.browser")
        assertTrue(model().blacklist.value.isEmpty())
    }
    @Test fun searchAndFiltersKeepSelectedSystemAppsAndHideSelf() {
        val model = model()
        model.loadApps(app); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(setOf("test.browser", "test.chat"), model.displayedApps.value.map { it.packageName }.toSet())
        model.filterApps("browser"); model.filterApps("  CHAT "); dispatcher.scheduler.advanceUntilIdle()
        assertEquals("test.chat", model.displayedApps.value.single().packageName)
        model.filterApps(""); model.setShowSystem(true); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(3, model.displayedApps.value.size)
        model.toggle("test.system"); model.setShowSystem(false); model.setSelectedOnly(true); dispatcher.scheduler.advanceUntilIdle()
        assertEquals("test.system", model.displayedApps.value.single().packageName)
        model.toggle("test.system"); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.displayedApps.value.isEmpty())
        model.filterApps("missing"); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.displayedApps.value.isEmpty())
    }
    @Test fun loadFailureCanBeRetried() {
        val model = model(); failLoad = true
        model.loadApps(app); dispatcher.scheduler.advanceUntilIdle()
        assertTrue(model.loadFailed.value); assertFalse(model.isLoading.value)
        failLoad = false
        model.loadApps(app); dispatcher.scheduler.advanceUntilIdle()
        assertFalse(model.loadFailed.value); assertEquals(2, model.displayedApps.value.size)
    }
}
