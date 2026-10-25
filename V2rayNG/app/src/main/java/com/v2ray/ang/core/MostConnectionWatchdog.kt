package com.v2ray.ang.core

import android.app.Service
import android.content.Context
import android.net.ConnectivityManager
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

/** Continuous monitoring has a different lifetime from one-shot latency tests.
 * The daemon manager owns the scope and explicitly stops this object before teardown. */
internal class MostConnectionWatchdog(
    private val service: Service,
    private val scope: CoroutineScope,
    private val running: () -> Boolean,
    private val reloading: () -> Boolean,
    private val activeGuid: () -> String,
    private val revision: () -> Long,
    private val reload: (String, String, Long, () -> Boolean) -> Boolean,
    private val publish: (MostConnectionHealth) -> Unit,
) {
    @Volatile private var generation = 0L
    private var job: Job? = null
    fun stop() { generation++; job?.cancel(); job = null }
    fun start() {
        stop()
        val token = generation
        job = scope.launch {
            fun allowed() = token == generation && isActive && running()
            var failures = 0
            var attempts = 0
            var lastRecovery = 0L
            try {
                publish(MostConnectionHealth(activeGuid()))
                delay(2500)
                while (token == generation && isActive) {
                    if (reloading()) { delay(1000); continue }
                    if (!running()) {
                        if (reloading()) { delay(1000); continue }
                        break
                    }
                    val guid = activeGuid()
                    if (MmkvManager.getSelectServer() != guid) { failures = 0; delay(3000); continue }
                    val network = service.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    if (network?.activeNetwork == null) {
                        failures = 0
                        publish(MostConnectionHealth(guid, MostLinkState.NO_NETWORK)); delay(5000); continue
                    }
                    val version = revision()
                    val health = inspect(guid, SettingsManager.mostHealthPort)
                    ensureActive()
                    if (!allowed() || version != revision() || MmkvManager.getSelectServer() != guid) continue
                    publish(health)
                    if (health.state == MostLinkState.ONLINE) { failures = 0; attempts = 0 }
                    else if (health.state == MostLinkState.NO_INTERNET) failures++
                    val now = SystemClock.elapsedRealtime()
                    if (failures >= 2 && SettingsManager.isMostAutoRecoveryEnabled() &&
                        (lastRecovery == 0L || now - lastRecovery >= 60_000)) {
                        if (attempts >= 2 && now - lastRecovery >= 120_000) attempts = 0
                        if (attempts < 2) {
                            val backup = SettingsManager.getMostBackupGuid()
                            val target = if (attempts == 1 && backup.isNotBlank() && backup != guid && MmkvManager.decodeServerConfig(backup) != null) backup else guid
                            if (!allowed() || MmkvManager.getSelectServer() != guid) continue
                            publish(MostConnectionHealth(guid, MostLinkState.RECOVERING))
                            lastRecovery = now; attempts++; failures = 0
                            val recovered = reload(target, guid, version) { token == generation && isActive }
                            if (!allowed()) break
                            if (!recovered) {
                                publish(MostConnectionHealth(guid, MostLinkState.NO_INTERNET))
                            }
                            delay(5000)
                        }
                    }
                    delay(if (health.state == MostLinkState.ONLINE || health.state == MostLinkState.UNSUPPORTED) 45_000 else 10_000)
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "MOST watchdog failed: profile=${activeGuid()}", error)
                if (token == generation && runCatching { running() }.getOrDefault(false)) publish(MostConnectionHealth(activeGuid(), MostLinkState.NO_INTERNET))
            }
        }
    }
    private suspend fun inspect(guid: String, port: Int): MostConnectionHealth {
        if (port <= 0) return MostConnectionHealth(guid, MostLinkState.UNSUPPORTED)
        val client = OkHttpClient.Builder().proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
            .connectTimeout(4, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).callTimeout(7, TimeUnit.SECONDS).build()
        val started = System.nanoTime()
        try {
            client.newCall(Request.Builder().url("https://www.cloudflare.com/cdn-cgi/trace").build()).execute().use { response ->
                currentCoroutineContext().ensureActive()
                if (response.isSuccessful) {
                    val values = response.body.string().lineSequence().mapNotNull { line ->
                        val at = line.indexOf('='); if (at > 0) line.substring(0, at) to line.substring(at + 1).trim() else null
                    }.toMap()
                    val ip = values["ip"].orEmpty()
                    if (Utils.isPureIpAddress(ip)) return MostConnectionHealth(guid, MostLinkState.ONLINE, ip,
                        values["loc"].orEmpty().takeIf { it.matches(Regex("[A-Z]{2}")) }.orEmpty(), (System.nanoTime() - started) / 1_000_000)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { /* A diagnostic provider failure alone must not trigger failover. */ }
        currentCoroutineContext().ensureActive()
        try {
            client.newCall(Request.Builder().url("https://www.gstatic.com/generate_204").build()).execute().use { response ->
                currentCoroutineContext().ensureActive()
                if (response.code == 204) return MostConnectionHealth(guid, MostLinkState.ONLINE)
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { /* Two independent forced-proxy checks have now failed. */ }
        return MostConnectionHealth(guid, MostLinkState.NO_INTERNET)
    }
}
