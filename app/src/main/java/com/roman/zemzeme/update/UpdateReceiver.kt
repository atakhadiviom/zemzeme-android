package com.roman.zemzeme.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * BroadcastReceiver that handles installation status callbacks from PackageInstaller.
 * 
 * This receiver is triggered when the PackageInstaller session completes (success or failure)
 * or when user action is required (STATUS_PENDING_USER_ACTION).
 * 
 * For STATUS_PENDING_USER_ACTION, the receiver automatically launches the system's
 * confirmation dialog so the user can approve the installation.
 * 
 * On STATUS_SUCCESS, the app will automatically restart to apply the update.
 */
class UpdateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "UpdateReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val sessionId = intent.getIntExtra(UpdateManager.EXTRA_SESSION_ID, -1)
        
        Log.d(TAG, "Received install status: status=$status, sessionId=$sessionId, message=$message")
        
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // User action required - typically for first-time install or when
                // the app is not the "installer of record".
                // Launch the confirmation activity provided by the system.
                Log.i(TAG, "STATUS_PENDING_USER_ACTION - launching confirmation dialog")
                
                @Suppress("DEPRECATION")
                val confirmIntent: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                
                if (confirmIntent != null) {
                    Log.i(TAG, "Launching user confirmation dialog for installation")
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(confirmIntent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to launch confirmation activity", e)
                    }
                } else {
                    Log.e(TAG, "STATUS_PENDING_USER_ACTION but no EXTRA_INTENT provided")
                }
            }
            
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "Package installation successful! Restarting app...")
                
                // Notify manager first
                UpdateManager.getInstanceOrNull()?.onInstallStatusReceived(status, message)
                
                // Restart the app to apply the update
                restartApp(context)
                return // Don't call manager again
            }
            
            PackageInstaller.STATUS_FAILURE -> {
                Log.e(TAG, "Installation failed: $message")
            }
            
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Log.e(TAG, "Installation aborted: $message")
            }
            
            PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                Log.e(TAG, "Installation blocked: $message")
            }
            
            PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                Log.e(TAG, "Installation conflict: $message")
            }
            
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                Log.e(TAG, "Package incompatible: $message")
            }
            
            PackageInstaller.STATUS_FAILURE_INVALID -> {
                Log.e(TAG, "Invalid package: $message")
            }
            
            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                Log.e(TAG, "Storage error: $message")
            }
            
            else -> {
                Log.w(TAG, "Unknown status received: $status")
            }
        }
        
        // Notify UpdateManager of the status change
        UpdateManager.getInstanceOrNull()?.onInstallStatusReceived(status, message)
    }
    
    /**
     * Restart the app after a successful update.
     */
    private fun restartApp(context: Context) {
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val packageManager = context.packageManager
                val intent = packageManager.getLaunchIntentForPackage(context.packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    context.startActivity(intent)
                    
                    // Kill the current process
                    android.os.Process.killProcess(android.os.Process.myPid())
                } else {
                    Log.e(TAG, "Could not get launch intent for restart")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart app", e)
            }
        }, 500) // Small delay to ensure UI can update
    }
}
