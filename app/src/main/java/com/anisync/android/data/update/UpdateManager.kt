package com.anisync.android.data.update

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.anisync.android.BuildConfig
import com.anisync.android.data.update.UpdateManager.Companion.VERSION_SEGMENT_COUNT
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the full app update lifecycle: checking for updates via a releases API (see [RELEASES_URL]),
 * downloading APKs, and triggering installation.
 *
 * Exposes [updateState] as a [StateFlow] so both the settings screen and the main activity
 * can observe the same state without duplication.
 *
 * The download runs in its own [CoroutineScope] so it survives navigation changes
 * (e.g., the user leaving the Updates screen mid-download).
 */
@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "UpdateManager"
        /**
         * The release list to check, or null to never check. Must never be the upstream AniSync
         * repository: those releases are the AniList app.
         *
         * Forgejo answers in the same shape (`tag_name`, `prerelease`, `assets[].name` /
         * `browser_download_url`) at `https://<host>/api/v1/repos/<owner>/<repo>/releases`.
         */
        private val RELEASES_URL: String? = "https://api.github.com/repos/MaizeShark/YamSync/releases"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val DOWNLOAD_READ_TIMEOUT_MS = 60_000
        private const val DOWNLOAD_BUFFER_SIZE = 8192
        private const val VERSION_SEGMENT_COUNT = 3
        private const val VERSION_SEGMENT_MULTIPLIER = 1000
        private const val APK_DIR = "apk"
        private const val DOWNLOADED_APK = "latest.apk"
        private const val TEMP_APK = "latest.apk.tmp"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private var downloadJob: Job? = null


    /**
     * Queries the releases API and returns a typed result.
     * Also updates [updateState] so observers (dialogs) react immediately.
     *
     * @param allowPrerelease Whether to include pre-release tags in the comparison.
     * @return [UpdateCheckResult] indicating the outcome.
     */
    suspend fun checkForUpdate(allowPrerelease: Boolean): UpdateCheckResult {
        _updateState.value = UpdateState.Checking
        return withContext(Dispatchers.IO) {
            try {
                val url = URL(
                    RELEASES_URL ?: run {
                        _updateState.value = UpdateState.Idle
                        return@withContext UpdateCheckResult.Error(
                            IllegalStateException("No update source configured")
                        )
                    }
                )
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/vnd.github.v3+json")
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                }

                try {
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        _updateState.value = UpdateState.Idle
                        return@withContext UpdateCheckResult.Error(
                            Exception("Releases API returned HTTP ${connection.responseCode}")
                        )
                    }

                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val releases = json.parseToJsonElement(response).jsonArray
                    val currentVersionCode = versionToCode(BuildConfig.VERSION_NAME)

                    var latestRelease: Release? = null
                    var latestVersionCode = currentVersionCode

                    for (element in releases) {
                        val releaseJson = element.jsonObject
                        val isPrerelease =
                            releaseJson["prerelease"]?.jsonPrimitive?.boolean ?: false
                        if (isPrerelease && !allowPrerelease) continue

                        val tagName =
                            releaseJson["tag_name"]?.jsonPrimitive?.content ?: continue
                        val versionCode = versionToCode(tagName)

                        if (versionCode > latestVersionCode) {
                            val assets = releaseJson["assets"]?.jsonArray ?: continue
                            val downloadUrl = apkFor(assets)

                            if (!downloadUrl.isNullOrEmpty()) {
                                val authorObj = releaseJson["author"]?.jsonObject
                                val authorName = authorObj?.get("login")?.jsonPrimitive?.content
                                val authorAvatarUrl =
                                    authorObj?.get("avatar_url")?.jsonPrimitive?.content

                                latestVersionCode = versionCode
                                latestRelease = Release(
                                    tagName = tagName,
                                    prerelease = isPrerelease,
                                    body = releaseJson["body"]?.jsonPrimitive?.content ?: "",
                                    downloadUrl = downloadUrl,
                                    authorName = authorName,
                                    authorAvatarUrl = authorAvatarUrl
                                )
                            }
                        }
                    }

                    if (latestRelease != null) {
                        _updateState.value = UpdateState.UpdateAvailable(latestRelease)
                        UpdateCheckResult.Available(latestRelease)
                    } else {
                        _updateState.value = UpdateState.Idle
                        UpdateCheckResult.UpToDate
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check for updates", e)
                _updateState.value = UpdateState.Idle
                UpdateCheckResult.Error(e)
            }
        }
    }


    /**
     * Starts downloading the APK for [release] in the background.
     * Progress and completion are reflected in [updateState].
     *
     * Downloads to a temporary file first, then atomically renames to `latest.apk`
     * on success so a partial download can never be mistaken for a valid APK.
     *
     * @param onError Callback invoked on the Main dispatcher if the download fails.
     */
    fun startDownload(release: Release, onError: (Exception) -> Unit = {}) {
        if (downloadJob?.isActive == true) return

        downloadJob = scope.launch {
            _updateState.value = UpdateState.Downloading(release, 0)
            try {
                val finalFile = withContext(Dispatchers.IO) {
                    val apkDir = apkDir() ?: throw Exception("External cache directory unavailable")
                    apkDir.mkdirs()
                    val tempFile = File(apkDir, TEMP_APK)
                    val targetFile = File(apkDir, DOWNLOADED_APK)

                    try {
                        val url = URL(release.downloadUrl)
                        val connection = (url.openConnection() as HttpURLConnection).apply {
                            connectTimeout = CONNECT_TIMEOUT_MS
                            readTimeout = DOWNLOAD_READ_TIMEOUT_MS
                            instanceFollowRedirects = true
                        }

                        connection.inputStream.use { input ->
                            tempFile.outputStream().use { output ->
                                val fileLength = connection.contentLength.toLong()
                                val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                                var totalBytesRead = 0L
                                var bytesRead: Int

                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    ensureActive()
                                    output.write(buffer, 0, bytesRead)
                                    totalBytesRead += bytesRead
                                    if (fileLength > 0) {
                                        val progress =
                                            (totalBytesRead * 100 / fileLength).toInt()
                                                .coerceIn(0, 100)
                                        _updateState.value =
                                            UpdateState.Downloading(release, progress)
                                    }
                                }
                                output.flush()
                            }
                        }

                        // Atomic rename: delete old file first, then rename temp
                        if (targetFile.exists()) targetFile.delete()
                        if (!tempFile.renameTo(targetFile)) {
                            throw Exception("Failed to finalize downloaded APK")
                        }
                        targetFile
                    } catch (e: Exception) {
                        // Clean up temp file on any failure
                        tempFile.delete()
                        throw e
                    }
                }

                _updateState.value = UpdateState.ReadyToInstall(release, finalFile)
            } catch (e: CancellationException) {
                Log.i(TAG, "Download cancelled")
                _updateState.value = UpdateState.Idle
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                // Revert to UpdateAvailable so the user can retry
                _updateState.value = UpdateState.UpdateAvailable(release)
                onError(e)
            }
        }
    }

    /**
     * Cancels an in-progress download and resets state to Idle.
     */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
    }


    /**
     * Launches the system package installer for the downloaded APK.
     * Uses [FileProvider] to grant the installer read access.
     */
    fun installApk() {
        try {
            val apkFile = File(apkDir(), DOWNLOADED_APK)
            if (!apkFile.exists()) {
                Log.e(TAG, "APK file not found at ${apkFile.absolutePath}")
                return
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                setDataAndType(uri, "application/vnd.android.package-archive")
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch APK installer", e)
        }
    }


    /**
     * Where the downloaded APK lives. External *cache* rather than external files, so the system
     * can reclaim it under storage pressure, Auto Backup leaves it out, and the in-app clear
     * button reaches it. See [com.anisync.android.data.CacheInventory].
     */
    private fun apkDir(): File? = context.externalCacheDir?.resolve(APK_DIR)

    /**
     * Drops anything left over from an earlier download. The APK is only needed between the
     * download finishing and the user tapping install, and the app is not restarted in that
     * window, so anything still here at startup is either an installed update or an abandoned
     * one. A `.tmp` survives only when the process was killed mid-download.
     */
    fun cleanUpDownloads() {
        val dir = apkDir()
        if (dir == null) {
            Log.w(TAG, "No external cache directory, leaving any downloaded APK in place")
            return
        }
        listOf(TEMP_APK, DOWNLOADED_APK)
            .map { File(dir, it) }
            .filter { it.exists() }
            .forEach { file ->
                val bytes = file.length()
                if (file.delete()) {
                    Log.i(TAG, "Reclaimed ${file.name} ($bytes bytes)")
                } else {
                    Log.w(TAG, "Could not delete ${file.absolutePath}")
                }
            }
    }

    /**
     * Dismisses the current update dialog / state.
     * Cannot dismiss while a download is actively in progress;
     * call [cancelDownload] first.
     */
    fun dismissUpdate() {
        val currentState = _updateState.value
        if (currentState is UpdateState.Downloading) return
        downloadJob?.cancel()
        downloadJob = null
        _updateState.value = UpdateState.Idle
    }

    /**
     * Fetches the actual latest release (debug builds only).
     * Bypasses version comparison so the update dialog can be tested with real release notes.
     */
    suspend fun fetchLatestRelease(allowPrerelease: Boolean): UpdateCheckResult {
        _updateState.value = UpdateState.Checking
        return withContext(Dispatchers.IO) {
            try {
                val url = URL(
                    RELEASES_URL ?: run {
                        _updateState.value = UpdateState.Idle
                        return@withContext UpdateCheckResult.Error(
                            IllegalStateException("No update source configured")
                        )
                    }
                )
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/vnd.github.v3+json")
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                }

                try {
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        _updateState.value = UpdateState.Idle
                        return@withContext UpdateCheckResult.Error(
                            Exception("Releases API returned HTTP ${connection.responseCode}")
                        )
                    }

                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val releases = json.parseToJsonElement(response).jsonArray

                    for (element in releases) {
                        val releaseJson = element.jsonObject
                        val isPrerelease =
                            releaseJson["prerelease"]?.jsonPrimitive?.boolean ?: false
                        if (isPrerelease && !allowPrerelease) continue

                        val tagName =
                            releaseJson["tag_name"]?.jsonPrimitive?.content ?: continue
                        val assets = releaseJson["assets"]?.jsonArray ?: continue
                        val downloadUrl = apkFor(assets)

                        if (!downloadUrl.isNullOrEmpty()) {
                            val authorObj = releaseJson["author"]?.jsonObject
                            val authorName = authorObj?.get("login")?.jsonPrimitive?.content
                            val authorAvatarUrl =
                                authorObj?.get("avatar_url")?.jsonPrimitive?.content

                            val release = Release(
                                tagName = tagName,
                                prerelease = isPrerelease,
                                body = releaseJson["body"]?.jsonPrimitive?.content ?: "",
                                downloadUrl = downloadUrl,
                                authorName = authorName,
                                authorAvatarUrl = authorAvatarUrl
                            )
                            _updateState.value = UpdateState.UpdateAvailable(release)
                            return@withContext UpdateCheckResult.Available(release)
                        }
                    }

                    _updateState.value = UpdateState.Idle
                    UpdateCheckResult.UpToDate
                } finally {
                    connection.disconnect()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch latest release", e)
                _updateState.value = UpdateState.Idle
                UpdateCheckResult.Error(e)
            }
        }
    }


    /**
     * Converts a semver-like version string to a comparable integer code.
     * Always uses exactly [VERSION_SEGMENT_COUNT] segments for consistent comparison.
     *
     * Examples:
     * - "1.0.1" -> 1_000_001
     * - "v2.3.0" -> 2_003_000
     * - "1.0" -> 1_000_000
     */
    /**
     * The download URL of the APK built for this device. Releases carry one APK per ABI (plus a
     * universal one), named `YamSync-v<version>-<abi>-release.apk`; installing the wrong ABI fails
     * or runs emulated, so the device's preferred ABIs are tried in order, then the universal APK.
     */
    private fun apkFor(assets: kotlinx.serialization.json.JsonArray): String? {
        val apks = assets.mapNotNull { asset ->
            val obj = asset.jsonObject
            val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val url = obj["browser_download_url"]?.jsonPrimitive?.content ?: return@mapNotNull null
            if (name.endsWith(".apk")) name to url else null
        }
        return pickApk(apks.map { it.first }, android.os.Build.SUPPORTED_ABIS.toList())
            ?.let { chosen -> apks.first { it.first == chosen }.second }
    }

    internal fun versionToCode(version: String): Int {
        val cleanVersion = version.replace(Regex("[^0-9.]"), "")
        val parts = cleanVersion.split(".")
        var code = 0
        for (i in 0 until VERSION_SEGMENT_COUNT) {
            val part = parts.getOrNull(i)?.toIntOrNull() ?: 0
            code = code * VERSION_SEGMENT_MULTIPLIER + part
        }
        return code
    }
}

/**
 * Picks the APK for a device from release asset [names]: the first of [deviceAbis] that some name
 * mentions, else a universal build, else whatever APK there is.
 */
internal fun pickApk(names: List<String>, deviceAbis: List<String>): String? {
    for (abi in deviceAbis) {
        names.firstOrNull { "-$abi-" in it || it.endsWith("-$abi.apk") }?.let { return it }
    }
    return names.firstOrNull { "universal" in it } ?: names.firstOrNull()
}
