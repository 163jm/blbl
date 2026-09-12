package blbl.cat3399.core.update

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import blbl.cat3399.BuildConfig
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.net.await
import blbl.cat3399.core.net.ipv4OnlyDns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

object ApkUpdater {
    // GitHub 仓库：发布产物推送到该仓库的 release 分支（latest.txt + APK），
    // 同时也会创建 GitHub Release 作为兜底数据源。
    private const val GITHUB_OWNER = "aazz77"
    private const val GITHUB_REPO = "blbl"

    private const val JSDELIVR_BASE = "https://cdn.jsdelivr.net/gh/$GITHUB_OWNER/$GITHUB_REPO@release"
    private const val JSDELIVR_LATEST_TXT_URL = "$JSDELIVR_BASE/latest.txt"
    private const val GITHUB_LATEST_RELEASE_API_URL =
        "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    val TEST_APK_URL: String
        get() = apkUrlFor(BuildConfig.VERSION_NAME)
    val TEST_CHANGELOG_URL: String
        get() = JSDELIVR_LATEST_TXT_URL

    private const val COOLDOWN_MS = 5_000L

    @Volatile
    private var lastStartedAtMs: Long = 0L

    private val okHttpLazy: Lazy<OkHttpClient> =
        lazy {
            OkHttpClient.Builder()
                .dns(ipv4OnlyDns { BiliClient.prefs.ipv4OnlyEnabled })
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        }

    private val okHttp: OkHttpClient
        get() = okHttpLazy.value

    fun evictConnections() {
        if (okHttpLazy.isInitialized()) okHttp.connectionPool.evictAll()
    }

    sealed class Progress {
        data object Connecting : Progress()

        data class Downloading(
            val downloadedBytes: Long,
            val totalBytes: Long?,
            val bytesPerSecond: Long,
        ) : Progress() {
            val percent: Int? =
                totalBytes?.takeIf { it > 0 }?.let { total ->
                    ((downloadedBytes.toDouble() / total.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
                }

            val hint: String =
                buildString {
                    if (totalBytes != null && totalBytes > 0) {
                        append("${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)}")
                    } else {
                        append(formatBytes(downloadedBytes))
                    }
                    if (bytesPerSecond > 0) append("（${formatBytes(bytesPerSecond)}/s）")
                }
        }
    }

    /**
     * @param apkUrl 已知的 APK 直链（GitHub Release 场景下从 release 资源里直接拿到，
     *   避免再按命名规则拼接一次）。为空时使用 [apkUrlFor] 按版本号拼接 jsDelivr 直链。
     */
    data class RemoteUpdate(
        val versionName: String,
        val changelog: String = "",
        val apkUrl: String? = null,
        val versions: List<RemoteUpdate> = emptyList(),
    ) {
        val displayChangelog: String
            get() = changelog.ifBlank { "暂无更新日志" }
    }

    fun markStarted(nowMs: Long = System.currentTimeMillis()) {
        lastStartedAtMs = nowMs
    }

    fun cooldownLeftMs(nowMs: Long = System.currentTimeMillis()): Long {
        val last = lastStartedAtMs
        val left = (last + COOLDOWN_MS) - nowMs
        return left.coerceAtLeast(0)
    }

    /**
     * 获取最新版本信息。优先走 jsDelivr（对 release 分支里的 latest.txt 做 CDN 加速缓存），
     * 失败（网络问题 / jsDelivr 缓存还没同步到新分支等）时回退到 GitHub Releases API 直接查询。
     */
    suspend fun fetchLatestUpdate(url: String = TEST_CHANGELOG_URL): RemoteUpdate {
        return withContext(Dispatchers.IO) {
            try {
                fetchLatestUpdateFromJsDelivr(url)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                fetchLatestUpdateFromGithubRelease()
            }
        }
    }

    private suspend fun fetchLatestUpdateFromJsDelivr(url: String): RemoteUpdate {
        var lastError: Throwable? = null
        val maxAttempts = 3
        for (attempt in 1..maxAttempts) {
            try {
                val versionName = fetchLatestTxtOnce(url)
                return RemoteUpdate(versionName = versionName, apkUrl = apkUrlFor(versionName))
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                lastError = t
                val shouldRetry =
                    attempt < maxAttempts &&
                        (t is IOException || t.message?.startsWith("HTTP ") == true)
                if (!shouldRetry) throw t
                delay(400L * attempt)
            }
        }
        throw lastError ?: IllegalStateException("fetch latest version failed")
    }

    private fun fetchLatestTxtOnce(url: String): String {
        val req =
            Request.Builder()
                .url(url)
                .header("Cache-Control", "no-cache")
                .get()
                .build()
        okHttp.newCall(req).execute().use { r ->
            check(r.isSuccessful) { "HTTP ${r.code} ${r.message}" }
            val body = r.body ?: error("empty body")
            val versionName = body.string().trim().removePrefix("v")
            check(versionName.isNotBlank()) { "latest.txt 为空" }
            check(parseVersion(versionName) != null) { "latest.txt 内容不是合法版本号: $versionName" }
            return versionName
        }
    }

    private fun fetchLatestUpdateFromGithubRelease(): RemoteUpdate {
        val req =
            Request.Builder()
                .url(GITHUB_LATEST_RELEASE_API_URL)
                .header("Accept", "application/vnd.github+json")
                .get()
                .build()
        okHttp.newCall(req).execute().use { r ->
            check(r.isSuccessful) { "HTTP ${r.code} ${r.message}" }
            val body = r.body ?: error("empty body")
            val json = JSONObject(body.string())
            val tagName = json.optString("tag_name").trim()
            val versionName = tagName.removePrefix("v").trim()
            check(versionName.isNotBlank()) { "GitHub Release 未返回版本号" }
            val changelog = json.optString("body").trim()

            val assets = json.optJSONArray("assets")
            var apkUrl: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
                        break
                    }
                }
            }

            return RemoteUpdate(
                versionName = versionName,
                changelog = changelog,
                apkUrl = apkUrl,
            )
        }
    }

    fun apkUrlFor(versionName: String): String {
        val cleanVersion = versionName.trim().removePrefix("v")
        return "$JSDELIVR_BASE/blbl-$cleanVersion-release.apk"
    }

    fun isRemoteNewer(remoteVersionName: String, currentVersionName: String = BuildConfig.VERSION_NAME): Boolean {
        val remote = parseVersion(remoteVersionName) ?: return false
        val current = parseVersion(currentVersionName) ?: return remoteVersionName.trim() != currentVersionName.trim()
        return compareVersion(remote, current) > 0
    }

    suspend fun downloadApkToCache(
        context: Context,
        url: String = TEST_APK_URL,
        onProgress: (Progress) -> Unit,
    ): File {
        onProgress(Progress.Connecting)

        val dir = File(context.cacheDir, "test_update").apply { mkdirs() }
        val part = File(dir, "update.apk.part")
        val target = File(dir, "update.apk")
        runCatching { part.delete() }
        runCatching { target.delete() }

        val req = Request.Builder().url(url).get().build()
        val call = okHttp.newCall(req)
        val res = call.await()
        res.use { r ->
            check(r.isSuccessful) { "HTTP ${r.code} ${r.message}" }
            val body = r.body ?: error("empty body")
            val total = body.contentLength().takeIf { it > 0 }
            withContext(Dispatchers.IO) {
                body.byteStream().use { input ->
                    FileOutputStream(part).use { output ->
                        val buf = ByteArray(32 * 1024)
                        var downloaded = 0L

                        var lastEmitAtMs = 0L
                        var speedAtMs = System.currentTimeMillis()
                        var speedBytes = 0L
                        var bytesPerSecond = 0L

                        while (true) {
                            ensureActive()
                            val read = input.read(buf)
                            if (read <= 0) break
                            output.write(buf, 0, read)
                            downloaded += read

                            // Speed estimate (1s window)
                            speedBytes += read
                            val nowMs = System.currentTimeMillis()
                            val speedElapsedMs = nowMs - speedAtMs
                            if (speedElapsedMs >= 1_000) {
                                bytesPerSecond = (speedBytes * 1_000L / speedElapsedMs.coerceAtLeast(1)).coerceAtLeast(0)
                                speedBytes = 0L
                                speedAtMs = nowMs
                            }

                            // UI progress: at most 5 updates per second.
                            if (nowMs - lastEmitAtMs >= 200) {
                                lastEmitAtMs = nowMs
                                onProgress(Progress.Downloading(downloadedBytes = downloaded, totalBytes = total, bytesPerSecond = bytesPerSecond))
                            }
                        }
                        output.fd.sync()
                    }
                }
            }
        }

        check(part.exists() && part.length() > 0) { "downloaded file is empty" }
        check(part.renameTo(target)) { "rename failed" }
        return target
    }

    fun installApk(context: Context, apkFile: File) {
        val uri = installUriFor(context, apkFile)
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        grantInstallerReadPermissions(context, intent, uri)
        context.startActivity(intent)
    }

    @SuppressLint("SetWorldReadable")
    private fun installUriFor(context: Context, apkFile: File): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val authority = "${context.packageName}.fileprovider"
            FileProvider.getUriForFile(context, authority, apkFile)
        } else {
            apkFile.setReadable(true, false)
            Uri.fromFile(apkFile)
        }
    }

    @Suppress("DEPRECATION")
    private fun grantInstallerReadPermissions(
        context: Context,
        intent: Intent,
        uri: Uri,
    ) {
        if (uri.scheme != "content") return

        val installers =
            context.packageManager.queryIntentActivities(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY,
            )
        for (installer in installers) {
            val packageName = installer.activityInfo?.packageName ?: continue
            context.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun formatBytes(bytes: Long): String {
        val b = bytes.coerceAtLeast(0)
        if (b < 1024) return "${b}B"
        val kb = b / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1fKB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1fMB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2fGB", gb)
    }

    private fun parseVersion(raw: String): List<Int>? {
        val cleaned = raw.trim().removePrefix("v")
        val digitsOnly =
            cleaned.takeWhile { ch ->
                ch.isDigit() || ch == '.'
            }
        if (digitsOnly.isBlank()) return null
        val parts = digitsOnly.split('.').filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        val nums = parts.map { it.toIntOrNull() ?: return null }
        return nums
    }

    private fun compareVersion(a: List<Int>, b: List<Int>): Int {
        val max = maxOf(a.size, b.size)
        for (i in 0 until max) {
            val ai = a.getOrElse(i) { 0 }
            val bi = b.getOrElse(i) { 0 }
            if (ai != bi) return ai.compareTo(bi)
        }
        return 0
    }
}
