package com.iptv.tvplayer.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.os.Process
import android.util.Log
import java.io.File
import java.security.MessageDigest

object SecurityCheck {
    private const val TAG = "SecurityCheck"

    /**
     * Set this to your release certificate SHA-256 signature hash (in uppercase, without colons).
     * If left empty or "YOUR_RELEASE_KEY_SHA256_HASH_HERE", signature checks are skipped.
     */
    private const val RELEASE_SIGNATURE_SHA256 = "YOUR_RELEASE_KEY_SHA256_HASH_HERE"

    /**
     * Run all security checks. Call this during application start.
     * Returns true if any security violation (debugger, repackage, emulator) is detected.
     */
    fun checkSecurity(context: Context): Boolean {
        // 1. Skip checks if this is a debug build (built locally for testing)
        val isDebuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebuggable) {
            Log.d(TAG, "Debug build detected. Skipping security checks.")
            return false
        }

        // 2. Check if debugger is attached
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) {
            Log.e(TAG, "Debugger detected!")
            return true
        }

        // 3. Verify application signature (Repackaging protection)
        if (!verifySignature(context)) {
            Log.e(TAG, "Signature verification failed! The app has been repackaged.")
            return true
        }

        // 4. Check for Xposed / Hook frameworks
        if (isXposedDetected()) {
            Log.e(TAG, "Xposed hook framework detected!")
            return true
        }

        return false
    }

    /**
     * Verify the signature of the app against the release signature.
     */
    private fun verifySignature(context: Context): Boolean {
        if (RELEASE_SIGNATURE_SHA256.isEmpty() || RELEASE_SIGNATURE_SHA256 == "YOUR_RELEASE_KEY_SHA256_HASH_HERE") {
            // Signature check disabled/not configured
            return true
        }

        try {
            val pm = context.packageManager
            val packageName = context.packageName
            val currentSignatureHash = getSignatureSHA256(context)

            Log.d(TAG, "Current Signature SHA-256: $currentSignatureHash")

            return currentSignatureHash.equals(RELEASE_SIGNATURE_SHA256, ignoreCase = true)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking signature", e)
        }
        return false
    }

    /**
     * Get the SHA-256 signature hash of the app's signing certificate.
     */
    fun getSignatureSHA256(context: Context): String {
        try {
            val pm = context.packageManager
            val packageName = context.packageName
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            }

            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }

            if (signatures != null && signatures.isNotEmpty()) {
                val certBytes = signatures[0].toByteArray()
                val md = MessageDigest.getInstance("SHA-256")
                val hashBytes = md.digest(certBytes)
                return hashBytes.joinToString("") { "%02X".format(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get signature SHA-256", e)
        }
        return ""
    }

    /**
     * Detect if running in Xposed environment by checking class stack trace elements.
     */
    private fun isXposedDetected(): Boolean {
        try {
            throw Exception("stack")
        } catch (e: Exception) {
            for (element in e.stackTrace) {
                val className = element.className
                if (className.contains("de.robv.android.xposed") || className.contains("com.saurik.substrate")) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Terminate the application immediately.
     */
    fun terminateApp() {
        Process.killProcess(Process.myPid())
        System.exit(1)
    }
}
