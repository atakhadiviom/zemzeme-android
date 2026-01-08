package com.roman.zemzeme.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Represents the current state of the update process.
 */
sealed class UpdateState {
    /** No update available or not checked yet */
    object Idle : UpdateState()
    
    /** Checking server for new version */
    object Checking : UpdateState()
    
    /** New version available, not yet downloaded */
    data class Available(val info: UpdateInfo) : UpdateState()
    
    /** Downloading APK, progress is 0.0-1.0 */
    data class Downloading(val progress: Float, val info: UpdateInfo) : UpdateState()
    
    /** APK downloaded and ready to install */
    data class ReadyToInstall(val info: UpdateInfo, val apkPath: String) : UpdateState()
    
    /** Installation in progress */
    data class Installing(val info: UpdateInfo) : UpdateState()
    
    /** User action required (system dialog shown) */
    data class PendingUserAction(val info: UpdateInfo) : UpdateState()
    
    /** Installation completed successfully */
    object Success : UpdateState()
    
    /** An error occurred */
    data class Error(val message: String) : UpdateState()
}

/**
 * Information about an available update.
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val releaseNotes: String,
    val forceUpdate: Boolean
)

/**
 * Manages self-updates for the application using Android's PackageInstaller API.
 * 
 * Features:
 * - Automatic version checking on app launch
 * - Background download with progress
 * - Persistent downloaded APK (survives app restart)
 * - Silent installation on Android 12+ after first approval
 * 
 * Usage:
 * ```
 * val manager = UpdateManager.getInstance(context)
 * // Observe state
 * manager.updateState.collect { state -> ... }
 * // Check for updates (call on app launch)
 * manager.checkForUpdate()
 * // Install when ready
 * manager.installUpdate()
 * ```
 */
class UpdateManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "UpdateManager"
        
        /** Base URL for update server */
        private const val BASE_URL = "http://10.0.2.2:8000"
        
        /** Version check endpoint */
        private const val VERSION_URL = "$BASE_URL/version.json"
        
        /** Action for the update status broadcast */
        const val ACTION_UPDATE_STATUS = "com.bitchat.android.UPDATE_STATUS"
        
        /** Extra key for session ID in the broadcast */
        const val EXTRA_SESSION_ID = "session_id"
        
        /** Preferences file for persisting update state */
        private const val PREFS_NAME = "update_prefs"
        private const val PREF_CACHED_APK_PATH = "cached_apk_path"
        private const val PREF_CACHED_VERSION_CODE = "cached_version_code"
        private const val PREF_CACHED_VERSION_NAME = "cached_version_name"
        private const val PREF_LAST_CHECK_TIME = "last_check_time"
        
        /** How often to check for updates (1 hour) */
        private const val CHECK_INTERVAL_MS = 60 * 60 * 1000L
        
        /** Minimum time between checks to avoid spamming (5 minutes) */
        private const val MIN_CHECK_INTERVAL_MS = 5 * 60 * 1000L
        
        @Volatile
        private var INSTANCE: UpdateManager? = null
        
        fun getInstance(context: Context): UpdateManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: UpdateManager(context.applicationContext).also { INSTANCE = it }
            }
        }
        
        /** Get existing instance or null if not initialized */
        fun getInstanceOrNull(): UpdateManager? = INSTANCE
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()
    
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
    
    private var currentUpdateInfo: UpdateInfo? = null
    
    /** Job for periodic update checks */
    private var periodicCheckJob: Job? = null
    
    /** Whether periodic checks are running */
    private var isPeriodicCheckRunning = false
    
    init {
        // Check if we have a cached APK ready to install
        restoreCachedState()
        // Start periodic update checks
        startPeriodicChecks()
    }
    
    /**
     * Start periodic background update checks.
     * Checks immediately on start, then every CHECK_INTERVAL_MS.
     */
    fun startPeriodicChecks() {
        if (isPeriodicCheckRunning) {
            Log.d(TAG, "Periodic checks already running")
            return
        }
        
        isPeriodicCheckRunning = true
        periodicCheckJob = scope.launch {
            Log.i(TAG, "Starting periodic update checks (interval: ${CHECK_INTERVAL_MS / 1000 / 60} minutes)")
            
            while (isActive) {
                // Check if enough time has passed since last check
                val lastCheckTime = prefs.getLong(PREF_LAST_CHECK_TIME, 0)
                val timeSinceLastCheck = System.currentTimeMillis() - lastCheckTime
                
                if (timeSinceLastCheck >= MIN_CHECK_INTERVAL_MS) {
                    Log.d(TAG, "Performing periodic update check")
                    performUpdateCheck()
                    
                    // Record check time
                    prefs.edit().putLong(PREF_LAST_CHECK_TIME, System.currentTimeMillis()).apply()
                } else {
                    Log.d(TAG, "Skipping check, last check was ${timeSinceLastCheck / 1000}s ago")
                }
                
                // Wait for next check interval
                delay(CHECK_INTERVAL_MS)
            }
        }
    }
    
    /**
     * Stop periodic background update checks.
     */
    fun stopPeriodicChecks() {
        periodicCheckJob?.cancel()
        periodicCheckJob = null
        isPeriodicCheckRunning = false
        Log.i(TAG, "Stopped periodic update checks")
    }
    
    /**
     * Restore cached update state from preferences.
     * If a downloaded APK exists, set state to ReadyToInstall.
     */
    private fun restoreCachedState() {
        val cachedPath = prefs.getString(PREF_CACHED_APK_PATH, null)
        val cachedVersionCode = prefs.getInt(PREF_CACHED_VERSION_CODE, -1)
        val cachedVersionName = prefs.getString(PREF_CACHED_VERSION_NAME, null)
        
        if (cachedPath != null && cachedVersionCode > 0 && cachedVersionName != null) {
            val apkFile = File(cachedPath)
            if (apkFile.exists() && apkFile.length() > 0) {
                val currentVersionCode = getCurrentVersionCode()
                if (cachedVersionCode > currentVersionCode) {
                    Log.d(TAG, "Restored cached update: $cachedVersionName (code $cachedVersionCode)")
                    val info = UpdateInfo(
                        versionCode = cachedVersionCode,
                        versionName = cachedVersionName,
                        apkUrl = "",
                        releaseNotes = "",
                        forceUpdate = false
                    )
                    currentUpdateInfo = info
                    _updateState.value = UpdateState.ReadyToInstall(info, cachedPath)
                    return
                }
            }
        }
        // Clear stale cache
        clearCachedApk()
    }
    
    /**
     * Get current app version code.
     */
    fun getCurrentVersionCode(): Int {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get version code", e)
            0
        }
    }
    
    /**
     * Get current app version name.
     */
    fun getCurrentVersionName(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }
    
    /**
     * Check if the app can request package installations.
     */
    fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }
    
    /**
     * Check server for available updates.
     * If an update is available, automatically starts downloading.
     */
    fun checkForUpdate() {
        val currentState = _updateState.value
        if (currentState is UpdateState.Downloading || 
            currentState is UpdateState.Installing ||
            currentState is UpdateState.ReadyToInstall) {
            Log.d(TAG, "Update already in progress or ready, skipping check")
            return
        }
        
        scope.launch {
            performUpdateCheck()
        }
    }
    
    /**
     * Internal method to perform the actual update check.
     * Called by both checkForUpdate() and periodic checker.
     */
    private suspend fun performUpdateCheck() {
        val currentState = _updateState.value
        if (currentState is UpdateState.Downloading || 
            currentState is UpdateState.Installing ||
            currentState is UpdateState.ReadyToInstall) {
            Log.d(TAG, "Update already in progress or ready, skipping check")
            return
        }
        
        try {
            _updateState.value = UpdateState.Checking
            
            val updateInfo = fetchVersionInfo()
            if (updateInfo == null) {
                _updateState.value = UpdateState.Idle
                return
            }
            
            val currentVersionCode = getCurrentVersionCode()
            if (updateInfo.versionCode <= currentVersionCode) {
                Log.d(TAG, "No update available. Current: $currentVersionCode, Server: ${updateInfo.versionCode}")
                _updateState.value = UpdateState.Idle
                return
            }
            
            Log.i(TAG, "Update available: ${updateInfo.versionName} (${updateInfo.versionCode})")
            currentUpdateInfo = updateInfo
            _updateState.value = UpdateState.Available(updateInfo)
            
            // Auto-start download
            downloadUpdate(updateInfo)
            
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed", e)
            // Don't set error state for background checks - just log and stay idle
            if (_updateState.value is UpdateState.Checking) {
                _updateState.value = UpdateState.Idle
            }
        }
    }
    
    /**
     * Fetch version info from server.
     */
    private suspend fun fetchVersionInfo(): UpdateInfo? = withContext(Dispatchers.IO) {
        Log.d(TAG, "Fetching version info from $VERSION_URL")
        
        val request = Request.Builder()
            .url(VERSION_URL)
            .build()
        
        val response = httpClient.newCall(request).execute()
        
        if (!response.isSuccessful) {
            Log.e(TAG, "Version check failed: HTTP ${response.code}")
            return@withContext null
        }
        
        val body = response.body?.string() ?: return@withContext null
        
        try {
            val json = JSONObject(body)
            UpdateInfo(
                versionCode = json.getInt("versionCode"),
                versionName = json.getString("versionName"),
                apkUrl = json.getString("apkUrl"),
                releaseNotes = json.optString("releaseNotes", ""),
                forceUpdate = json.optBoolean("forceUpdate", false)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse version info", e)
            null
        }
    }
    
    /**
     * Download the update APK.
     */
    private suspend fun downloadUpdate(info: UpdateInfo) {
        try {
            _updateState.value = UpdateState.Downloading(0f, info)
            
            val apkUrl = if (info.apkUrl.startsWith("http")) {
                info.apkUrl
            } else {
                "$BASE_URL/${info.apkUrl}"
            }
            
            val apkFile = downloadApk(apkUrl) { progress ->
                _updateState.value = UpdateState.Downloading(progress, info)
            }
            
            // Cache the downloaded APK info
            prefs.edit()
                .putString(PREF_CACHED_APK_PATH, apkFile.absolutePath)
                .putInt(PREF_CACHED_VERSION_CODE, info.versionCode)
                .putString(PREF_CACHED_VERSION_NAME, info.versionName)
                .apply()
            
            _updateState.value = UpdateState.ReadyToInstall(info, apkFile.absolutePath)
            Log.i(TAG, "Update downloaded and ready to install: ${info.versionName}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            _updateState.value = UpdateState.Error(e.message ?: "Download failed")
        }
    }
    
    /**
     * Download APK from the given URL.
     */
    private suspend fun downloadApk(
        url: String,
        onProgress: (Float) -> Unit
    ): File = withContext(Dispatchers.IO) {
        Log.d(TAG, "Downloading APK from $url")
        
        val request = Request.Builder()
            .url(url)
            .build()
        
        val response = httpClient.newCall(request).execute()
        
        if (!response.isSuccessful) {
            throw IOException("Download failed: HTTP ${response.code}")
        }
        
        val body = response.body ?: throw IOException("Empty response body")
        val contentLength = body.contentLength()
        
        // Use files dir instead of cache for persistence
        val apkFile = File(context.filesDir, "pending_update.apk")
        if (apkFile.exists()) {
            apkFile.delete()
        }
        
        FileOutputStream(apkFile).use { output ->
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Long = 0
                var read: Int
                
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    bytesRead += read
                    
                    if (contentLength > 0) {
                        val progress = bytesRead.toFloat() / contentLength.toFloat()
                        onProgress(progress.coerceIn(0f, 1f))
                    } else {
                        onProgress(-1f)
                    }
                }
            }
        }
        
        Log.d(TAG, "Downloaded APK to ${apkFile.absolutePath} (${apkFile.length()} bytes)")
        apkFile
    }
    
    /**
     * Install the downloaded update.
     * Call this when user confirms they want to install.
     */
    fun installUpdate() {
        val state = _updateState.value
        val apkPath = when (state) {
            is UpdateState.ReadyToInstall -> state.apkPath
            else -> {
                Log.e(TAG, "Cannot install: no update ready. Current state: $state")
                return
            }
        }
        
        val info = currentUpdateInfo ?: return
        
        scope.launch {
            try {
                _updateState.value = UpdateState.Installing(info)
                installApk(File(apkPath))
            } catch (e: Exception) {
                Log.e(TAG, "Installation failed", e)
                _updateState.value = UpdateState.Error(e.message ?: "Installation failed")
            }
        }
    }
    
    /**
     * Legacy method for debug settings - downloads and installs immediately.
     */
    fun checkAndInstallUpdate() {
        val state = _updateState.value
        if (state is UpdateState.ReadyToInstall) {
            // If already downloaded, just install
            installUpdate()
        } else {
            // Otherwise check and download first
            checkForUpdate()
        }
    }
    
    /**
     * Install the APK using PackageInstaller API.
     */
    private suspend fun installApk(apkFile: File) = withContext(Dispatchers.IO) {
        Log.d(TAG, "Installing APK: ${apkFile.absolutePath}")
        
        val packageInstaller = context.packageManager.packageInstaller
        
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        
        params.setAppPackageName(context.packageName)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        
        val sessionId = packageInstaller.createSession(params)
        Log.d(TAG, "Created install session: $sessionId")
        
        packageInstaller.openSession(sessionId).use { session ->
            session.openWrite("update.apk", 0, apkFile.length()).use { output ->
                apkFile.inputStream().use { input ->
                    input.copyTo(output)
                }
                session.fsync(output)
            }
            
            val intent = Intent(context, UpdateReceiver::class.java).apply {
                action = ACTION_UPDATE_STATUS
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
            
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            
            Log.d(TAG, "Committing install session")
            session.commit(pendingIntent.intentSender)
        }
    }
    
    /**
     * Called by UpdateReceiver when installation status is received.
     */
    internal fun onInstallStatusReceived(status: Int, message: String?) {
        Log.d(TAG, "Install status received: $status, message: $message")
        
        val info = currentUpdateInfo
        
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "Update installed successfully")
                clearCachedApk()
                _updateState.value = UpdateState.Success
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                Log.i(TAG, "User action required for installation")
                if (info != null) {
                    _updateState.value = UpdateState.PendingUserAction(info)
                }
            }
            PackageInstaller.STATUS_FAILURE,
            PackageInstaller.STATUS_FAILURE_ABORTED,
            PackageInstaller.STATUS_FAILURE_BLOCKED,
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            PackageInstaller.STATUS_FAILURE_INVALID,
            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                val errorMsg = message ?: "Installation failed (status: $status)"
                Log.e(TAG, "Update failed: $errorMsg")
                _updateState.value = UpdateState.Error(errorMsg)
            }
            else -> {
                Log.w(TAG, "Unknown install status: $status")
                _updateState.value = UpdateState.Error("Unknown status: $status")
            }
        }
    }
    
    /**
     * Clear cached APK and preferences.
     */
    private fun clearCachedApk() {
        val cachedPath = prefs.getString(PREF_CACHED_APK_PATH, null)
        if (cachedPath != null) {
            File(cachedPath).delete()
        }
        prefs.edit()
            .remove(PREF_CACHED_APK_PATH)
            .remove(PREF_CACHED_VERSION_CODE)
            .remove(PREF_CACHED_VERSION_NAME)
            .apply()
    }
    
    /**
     * Dismiss the update (user chose not to install now).
     * The downloaded APK remains cached for later.
     */
    fun dismissUpdate() {
        val state = _updateState.value
        if (state is UpdateState.ReadyToInstall || state is UpdateState.PendingUserAction) {
            // Keep the downloaded APK but go back to ready state
            if (state is UpdateState.ReadyToInstall) {
                Log.d(TAG, "Update dismissed, APK cached for later")
            }
        }
    }
    
    /**
     * Check if there's a cached update ready to install.
     */
    fun hasCachedUpdate(): Boolean {
        return _updateState.value is UpdateState.ReadyToInstall
    }
    
    /**
     * Reset state to Idle and clear any errors.
     */
    fun resetState() {
        _updateState.value = UpdateState.Idle
    }
}
