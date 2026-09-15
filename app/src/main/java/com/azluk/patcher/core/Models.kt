package com.azluk.patcher.core

import android.graphics.drawable.Drawable

// ── AppInfo ───────────────────────────────────────────────────────────────────

data class AppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val isSystemApp: Boolean,
    val apkPath: String,
    val versionName: String,
    val apkSizeMb: Float,
    var patchStatus: PatchStatus = PatchStatus.UNKNOWN,
    var opportunityCount: Int = 0
)

enum class PatchStatus { UNKNOWN, LIKELY, PATCHABLE, COMPLEX }

// ── PatchType ─────────────────────────────────────────────────────────────────

enum class PatchType(val key: String, val displayName: String, val description: String) {
    LICENSE_BYPASS  ("LICENSE_BYPASS",   "License Bypass",     "Removes Google Play LVL license verification. Works on most paid apps."),
    IAP_BYPASS      ("IAP_BYPASS",       "IAP Bypass",         "Spoofs in-app purchase state so items appear already bought."),
    SIGNATURE_BYPASS("SIGNATURE_BYPASS", "Signature Bypass",   "Tricks the app integrity check into accepting the patched APK."),
    REMOVE_ADS      ("REMOVE_ADS",       "Remove Ads",         "Kills AdMob, Facebook Audience, Unity Ads, AppLovin, IronSource SDKs."),
    FORCE_DEBUGGABLE("FORCE_DEBUGGABLE", "Force Debuggable",   "Enables ADB debugging and Frida hooking on the app."),
    SSL_BYPASS      ("SSL_BYPASS",       "SSL Pinning Bypass", "Disables certificate pinning for HTTPS traffic interception."),
    ROOT_BYPASS     ("ROOT_BYPASS",      "Root Bypass",        "Hides root from RootBeer and similar detection libraries.")
}

// ── ScanResult ────────────────────────────────────────────────────────────────

data class ScanResult(
    val patchType: String,
    val desc: String?,
    val dexIndex: Int,
    val offset: Int
)

// ── UI State sealed classes ───────────────────────────────────────────────────

sealed class PatchState {
    object Idle : PatchState()
    data class Running(val log: List<String>) : PatchState()
    data class Success(val outputPath: String, val log: List<String>) : PatchState()
    data class Failure(val error: String, val log: List<String>) : PatchState()
}

sealed class InstallState {
    object Idle : InstallState()
    object Installing : InstallState()
    object Success : InstallState()
    data class Failure(
        val code: String,
        val message: String,
        val description: String,
        val canRetry: Boolean
    ) : InstallState()
}
