package com.v2ray.ang.core

import android.app.Service
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.MostConnectionHealth
import com.v2ray.ang.dto.MostLinkState
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** The daemon owns the monitor across hot reloads and cancels it before releasing the tunnel. */
internal class MostConnectionWatchdog(
    private val service: Service,
    private val scope: CoroutineScope,
    private val running: () -> Boolean,
    private val reloading: () -> Boolean,
    private val activeGuid: () -> String,
    private val revision: () -> Long,
    private val appliedRequest: () -> String,
    private val applySelection: (() -> Boolean) -> Boolean,
    private val reload: (String, String, Long, MostRouteSelection, () -> Boolean) -> Boolean,
    private val deliver: (MostConnectionHealth) -> Unit,
) {
    private fun publish(value: MostConnectionHealth, request: String = appliedRequest()) {
        deliver(value.copy(routeRequestId = request))
    }
    @Volatile private var generation = 0L
    private var job: Job? = null
    @Synchronized
    fun stop() { generation++; job?.cancel(); job = null }
    @Synchronized
    fun start() {
        stop()
        val token = generation
        job = scope.launch {
            fun lease() = token == generation && isActive
            fun allowed() = lease() && running()
            var failures = 0
            var manualAttempts = 0
            var lastRecovery = 0L
            var cycleRetryAt = 0L
            var exhausted = false
            var previousSelection: MostRouteSelection? = null
            val tried = linkedSetOf<String>()
            try {
                publish(MostConnectionHealth(activeGuid()))
                delay(2500)
                while (lease()) {
                    if (reloading()) { delay(1000); continue }
                    if (!running()) break
                    val selection = SettingsManager.getMostRouteSelection()
                    if (selection != previousSelection) {
                        failures = 0; manualAttempts = 0; lastRecovery = 0; cycleRetryAt = 0
                        exhausted = false; tried.clear(); previousSelection = selection
                    }
                    val guid = activeGuid()
                    if (selection.requestId != appliedRequest() || MmkvManager.getSelectServer() != guid) {
                        if (!lease()) break
                        applySelection { lease() }
                        if (!allowed()) break
                        delay(2500)
                        continue
                    }
                    val connectivity = service.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    if (connectivity?.activeNetwork == null) {
                        failures = 0
                        publish(MostConnectionHealth(guid, MostLinkState.NO_NETWORK), selection.requestId)
                        delay(5000); continue
                    }
                    val version = revision()
                    var health = inspect(guid, SettingsManager.mostHealthPort, selection.enabled)
                    ensureActive()
                    if (!allowed() || version != revision() || selection != SettingsManager.getMostRouteSelection() ||
                        MmkvManager.getSelectServer() != guid) continue
                    if (selection.enabled && health.state == MostLinkState.ONLINE && !MostRoutePolicy.exitMatches(selection.country, health.country))
                        health = health.copy(state = MostLinkState.WRONG_COUNTRY)
                    if (health.state == MostLinkState.ONLINE) {
                        failures = 0; manualAttempts = 0; lastRecovery = 0; exhausted = false; tried.clear(); cycleRetryAt = 0
                        if (selection.enabled) SettingsManager.rememberMostRoute(selection, guid)
                    } else if (health.state in setOf(MostLinkState.NO_INTERNET, MostLinkState.SERVER_UNAVAILABLE, MostLinkState.WRONG_COUNTRY)) failures++
                    publish(if (exhausted && health.state != MostLinkState.ONLINE) health.copy(state = MostLinkState.NO_ROUTE) else health, selection.requestId)
                    val now = SystemClock.elapsedRealtime()
                    if (failures >= 2 && selection.enabled && (lastRecovery == 0L || now - lastRecovery >= 15_000)) {
                        val candidates = SettingsManager.getMostRouteCandidates(selection.groupId)
                        tried += guid
                        if (exhausted && now >= cycleRetryAt) { tried.clear(); exhausted = false }
                        val target = MostRoutePolicy.choose(selection, candidates, tried, SettingsManager.getMostLastRoute(selection))
                        if (target == null) {
                            if (!exhausted) cycleRetryAt = now + 60_000
                            exhausted = true
                            publish(health.copy(state = MostLinkState.NO_ROUTE, attempt = tried.size,
                                total = MostRoutePolicy.eligible(selection, candidates).size), selection.requestId)
                        } else {
                            tried += target
                            if (!allowed()) break
                            publish(MostConnectionHealth(guid, MostLinkState.RECOVERING, attempt = tried.size,
                                total = MostRoutePolicy.eligible(selection, candidates).size), selection.requestId)
                            lastRecovery = now; failures = 0
                            reload(target, guid, version, selection) { lease() }
                            if (!allowed()) break
                            delay(2500)
                            continue
                        }
                    } else if (failures >= 2 && !selection.enabled && SettingsManager.isMostAutoRecoveryEnabled() &&
                        (lastRecovery == 0L || now - lastRecovery >= 60_000)) {
                        if (manualAttempts >= 2 && now - lastRecovery >= 120_000) manualAttempts = 0
                        if (manualAttempts < 2) {
                            val backup = SettingsManager.getMostBackupGuid()
                            val target = if (manualAttempts == 1 && backup.isNotBlank() && backup != guid &&
                                MmkvManager.decodeServerConfig(backup) != null) backup else guid
                            publish(MostConnectionHealth(guid, MostLinkState.RECOVERING), selection.requestId)
                            lastRecovery = now; manualAttempts++; failures = 0
                            reload(target, guid, version, selection) { lease() }
                            if (!allowed()) break
                            delay(2500)
                            continue
                        }
                    }
                    delay(if (health.state == MostLinkState.ONLINE || health.state == MostLinkState.UNSUPPORTED) 45_000 else if (selection.enabled) 2500 else 10_000)
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "MOST watchdog failed: profile=${activeGuid()}", error)
                if (lease() && runCatching { running() }.getOrDefault(false))
                    publish(MostConnectionHealth(activeGuid(), MostLinkState.SERVER_UNAVAILABLE))
            }
        }
    }

    private suspend fun inspect(guid: String, port: Int, automatic: Boolean): MostConnectionHealth {
        if (port <= 0) return MostConnectionHealth(guid, MostLinkState.UNSUPPORTED)
        val client = OkHttpClient.Builder().proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
            .connectTimeout(if (automatic) 3 else 4, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(if (automatic) 5 else 7, TimeUnit.SECONDS).build()
        val started = System.nanoTime()
        var lastError: Exception? = null
        try {
            try {
                client.newCall(Request.Builder().url("https://www.cloudflare.com/cdn-cgi/trace").build()).execute().use { response ->
                    currentCoroutineContext().ensureActive()
                    if (response.isSuccessful) {
                        val values = response.body.string().lineSequence().mapNotNull { line ->
                            val at = line.indexOf('='); if (at > 0) line.substring(0, at) to line.substring(at + 1).trim() else null
                        }.toMap()
                        val ip = values["ip"].orEmpty()
                        if (Utils.isPureIpAddress(ip)) return MostConnectionHealth(guid, MostLinkState.ONLINE, ip,
                            values["loc"].orEmpty().takeIf { it.matches(Regex("[A-Z]{2}")) }.orEmpty(),
                            (System.nanoTime() - started) / 1_000_000)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { lastError = error }
            currentCoroutineContext().ensureActive()
            try {
                client.newCall(Request.Builder().url("https://www.gstatic.com/generate_204").build()).execute().use { response ->
                    currentCoroutineContext().ensureActive()
                    if (response.code == 204) return MostConnectionHealth(guid, MostLinkState.ONLINE)
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { lastError = error }
            lastError?.let { LogUtil.e(AppConfig.TAG, "MOST forced-proxy checks failed: profile=$guid", it) }
            val connectivity = service.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val validated = connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            return MostConnectionHealth(guid, if (validated) MostLinkState.SERVER_UNAVAILABLE else MostLinkState.NO_INTERNET)
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
