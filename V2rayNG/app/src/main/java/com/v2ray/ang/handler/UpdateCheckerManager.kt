package com.v2ray.ang.handler

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.helper.NotificationHelper
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

class MostUpdateException(val messageRes: Int) : Exception("MOST update failure: $messageRes")

/** Owns release discovery and APK verification. Installation remains Android-owned. */
object UpdateCheckerManager {
    private const val CERTIFICATE = "3c6f68986b7a4fdc964f8c6f33ce0b6cb20102c784ddbeac29884605b040a877"
    private const val BACKGROUND_WORK = "most-release-notifications"
    fun repository() = MmkvManager.decodeSettingsString("most_ota_repository", "Tombstone1922/MOST").orEmpty()
    fun saveRepository(value: String) {
        val clean = value.trim().removePrefix("https://github.com/").trimEnd('/')
        if (!clean.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) throw MostUpdateException(R.string.most_ota_bad_repo)
        check(MmkvManager.encodeSettings("most_ota_repository", clean))
        NotificationHelper.cancelMostUpdate(com.v2ray.ang.AngApplication.application)
    }
    @Suppress("DEPRECATION")
    fun currentVersionCode(): Int {
        // Output-specific ABI version codes can differ from the default BuildConfig value.
        val context = com.v2ray.ang.AngApplication.application
        val info = if (Build.VERSION.SDK_INT >= 33) context.packageManager.getPackageInfo(BuildConfig.APPLICATION_ID, PackageManager.PackageInfoFlags.of(0))
        else context.packageManager.getPackageInfo(BuildConfig.APPLICATION_ID, 0) // API 24–32 overload; remove when minSdk reaches 33.
        return archiveVersion(info).toInt()
    }
    fun autoCheck() = MmkvManager.decodeSettingsBool("most_ota_auto_check", true)
    suspend fun setAutoCheck(value: Boolean) = withContext(Dispatchers.IO) {
        check(MmkvManager.encodeSettings("most_ota_auto_check", value))
        syncBackgroundChecks()
    }

    /** Uses the existing :bg WorkManager owner, independent of the VPN/Activity lifetime. */
    private fun syncBackgroundChecks() {
        val context = com.v2ray.ang.AngApplication.application
        val manager = RemoteWorkManager.getInstance(context)
        if (autoCheck()) {
            val request = PeriodicWorkRequestBuilder<ReleaseCheckTask>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            manager.enqueueUniquePeriodicWork(BACKGROUND_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
                .get(20, TimeUnit.SECONDS)
        } else {
            manager.cancelUniqueWork(BACKGROUND_WORK).get(20, TimeUnit.SECONDS)
            NotificationHelper.cancelMostUpdate(context)
        }
    }

    /** Pure decision shared by the worker and its focused regression test. */
    internal fun shouldNotify(enabled: Boolean, installed: Int, available: Int, notified: Int): Boolean =
        enabled && available > installed && available > notified

    class ReleaseCheckTask(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
            if (!autoCheck()) return@withContext Result.success()
            val repo = repository()
            try {
                val result = checkForUpdate()
                ensureActive()
                if (repository() != repo || !autoCheck()) return@withContext Result.success()
                val key = "most_ota_notified:$repo"
                val notified = MmkvManager.decodeSettingsString(key)?.toIntOrNull() ?: 0
                if (shouldNotify(autoCheck(), currentVersionCode(), result.versionCode, notified)) {
                    if (NotificationHelper.notifyMostUpdate(applicationContext, result.latestVersion.orEmpty())) {
                        check(MmkvManager.encodeSettings(key, result.versionCode.toString()))
                    }
                } else if (!result.hasUpdate) NotificationHelper.cancelMostUpdate(applicationContext)
                Result.success()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "MOST release check failed: component=background", error)
                if (runAttemptCount < 2 && (error is java.io.IOException ||
                        error is MostUpdateException && error.messageRes == R.string.most_ota_server_error)) Result.retry()
                else Result.success()
            }
        }
    }
    suspend fun checkAutomatically(): Boolean = withContext(Dispatchers.IO) {
        try { syncBackgroundChecks()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) { LogUtil.e(AppConfig.TAG, "MOST release schedule failed: component=background", error) }
        if (!autoCheck()) return@withContext false
        val repo = repository()
        val key = "most_ota_cache:$repo"
        val cached = MmkvManager.decodeSettingsString(key).orEmpty().split(':', limit = 2)
        val timestamp = cached.firstOrNull()?.toLongOrNull() ?: 0L
        val previousCode = cached.getOrNull(1)?.toIntOrNull() ?: 0
        val now = System.currentTimeMillis()
        if (now - timestamp in 0..21_600_000) return@withContext previousCode > currentVersionCode()
        val code = try {
            val result = checkForUpdate()
            if (result.hasUpdate) result.versionCode else 0
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            LogUtil.w(AppConfig.TAG, "MOST automatic update check: ${error.javaClass.simpleName}")
            previousCode
        }
        coroutineContext.ensureActive()
        if (repository() != repo) return@withContext false
        check(MmkvManager.encodeSettings(key, "$now:$code"))
        code > currentVersionCode()
    }
    private fun client(proxy: Boolean, manifest: Boolean = false): OkHttpClient {
        val b = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(if (manifest) 45 else 0, TimeUnit.SECONDS).followSslRedirects(false)
        if (proxy) {
            b.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", SettingsManager.getHttpPort())))
            val user = SettingsManager.getSocksUsername(); val password = SettingsManager.getSocksPassword()
            if (!user.isNullOrBlank() && !password.isNullOrBlank()) b.proxyAuthenticator { _, response ->
                if (response.request.header("Proxy-Authorization") != null) null else response.request.newBuilder()
                    .header("Proxy-Authorization", Credentials.basic(user, password)).build()
            }
        }
        return b.build()
    }
    private fun request(url: String): Request {
        if (!runCatching { java.net.URI(url).scheme.equals("https", true) }.getOrDefault(false)) throw MostUpdateException(R.string.most_ota_bad_manifest)
        return Request.Builder().url(url).header("User-Agent", "MOST/${BuildConfig.VERSION_NAME}").build()
    }
    private fun readManifest(url: String): com.google.gson.JsonObject {
        var last: Exception? = null
        for (proxy in listOf(false, true)) {
            val http = client(proxy, manifest = true)
            try {
                http.newCall(request(url)).execute().use { response ->
                    if (response.code == 404) throw MostUpdateException(R.string.most_ota_not_published)
                    if (!response.isSuccessful) throw MostUpdateException(R.string.most_ota_server_error)
                    if (response.request.url.scheme != "https") throw MostUpdateException(R.string.most_ota_bad_manifest)
                    val bytes = java.io.ByteArrayOutputStream()
                    response.body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer); if (count < 0) break
                            if (bytes.size() + count > 1_048_576) throw MostUpdateException(R.string.most_ota_bad_manifest)
                            bytes.write(buffer, 0, count)
                        }
                    }
                    return JsonUtil.parseString(bytes.toString("UTF-8"))?.asJsonObject ?: throw MostUpdateException(R.string.most_ota_bad_manifest)
                }
            } catch (e: Exception) {
                if (e is MostUpdateException && e.messageRes in listOf(R.string.most_ota_not_published, R.string.most_ota_bad_manifest)) throw e
                last = e
            } finally {
                http.connectionPool.evictAll()
                http.dispatcher.executorService.shutdown()
            }
        }
        throw last ?: MostUpdateException(R.string.most_ota_server_error)
    }
    suspend fun checkForUpdate(includePreRelease: Boolean = false): CheckUpdateResult = withContext(Dispatchers.IO) {
        val repo = repository()
        if (!repo.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) throw MostUpdateException(R.string.most_ota_bad_repo)
        val m = readManifest("https://github.com/$repo/releases/latest/download/most-update.json")
        coroutineContext.ensureActive()
        val code = m.get("versionCode")?.asInt ?: throw MostUpdateException(R.string.most_ota_bad_manifest)
        val version = m.get("versionName")?.asString ?: throw MostUpdateException(R.string.most_ota_bad_manifest)
        val sha = m.get("sha256")?.asString.orEmpty().lowercase()
        val size = m.get("sizeBytes")?.asLong ?: 0
        val url = m.get("apkUrl")?.asString.orEmpty()
        request(url)
        if (code <= 0 || !sha.matches(Regex("[a-f0-9]{64}")) || size !in 1..209_715_200L) throw MostUpdateException(R.string.most_ota_bad_manifest)
        CheckUpdateResult(hasUpdate = code > currentVersionCode(), latestVersion = version,
            releaseNotes = m.get("notes")?.asString.orEmpty(), downloadUrl = url,
            versionCode = code, sha256 = sha, sizeBytes = size)
    }
    suspend fun download(context: Context, result: CheckUpdateResult, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "most-updates").apply { mkdirs() }
        val target = File(dir, "MOST-${result.versionCode}.apk")
        val part = File(dir, "MOST-${result.versionCode}.part.apk")
        var last: Exception? = null
        for (proxy in listOf(false, true)) try {
            val digest = MessageDigest.getInstance("SHA-256"); var total = 0L
            client(proxy).newCall(request(requireNotNull(result.downloadUrl))).execute().use { response ->
                if (!response.isSuccessful || response.request.url.scheme != "https") throw MostUpdateException(R.string.most_ota_server_error)
                response.body.byteStream().use { input -> part.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer); if (count < 0) break
                        total += count
                        if (total > result.sizeBytes) throw MostUpdateException(R.string.most_ota_bad_apk)
                        digest.update(buffer, 0, count); output.write(buffer, 0, count)
                        progress((total.toFloat() / result.sizeBytes).coerceIn(0f, 1f))
                    }
                } }
            }
            coroutineContext.ensureActive()
            if (total != result.sizeBytes || hex(digest.digest()) != result.sha256) throw MostUpdateException(R.string.most_ota_bad_apk)
            verifyArchive(context, part, result.versionCode)
            coroutineContext.ensureActive()
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw MostUpdateException(R.string.most_ota_bad_apk)
            return@withContext target
        } catch (cancelled: CancellationException) { part.delete(); throw cancelled
        } catch (e: Exception) {
            part.delete()
            if (e is MostUpdateException && e.messageRes in listOf(R.string.most_ota_bad_apk, R.string.most_ota_bad_signature)) throw e
            last = e
        }
        LogUtil.w(AppConfig.TAG, "MOST OTA download failed: ${last?.javaClass?.simpleName}")
        throw last ?: MostUpdateException(R.string.most_ota_server_error)
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun verifyArchive(context: Context, file: File, expected: Int) {
        val info = archiveInfo(context.packageManager, file.absolutePath) ?: throw MostUpdateException(R.string.most_ota_bad_apk)
        if (info.packageName != BuildConfig.APPLICATION_ID || archiveVersion(info) != expected.toLong() || expected <= currentVersionCode()) throw MostUpdateException(R.string.most_ota_bad_apk)
        val certs = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners?.toList().orEmpty() else legacySignatures(info)
        if (certs.size != 1 || certs.none { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) == CERTIFICATE }) throw MostUpdateException(R.string.most_ota_bad_signature)
    }
    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, path: String): PackageInfo? {
        // API 24–27 need GET_SIGNATURES; API 24–32 need the int overload. Remove each fallback when minSdk reaches its replacement.
        return if (Build.VERSION.SDK_INT >= 33) pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        else pm.getPackageArchiveInfo(path, if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
    }
    @Suppress("DEPRECATION")
    private fun legacySignatures(info: PackageInfo): List<android.content.pm.Signature> = info.signatures?.toList().orEmpty() // API 24–27; remove at minSdk 28.
    @Suppress("DEPRECATION")
    private fun archiveVersion(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong() // Remove int fallback at minSdk 28.
}
