package com.azluk.patcher.viewmodel

import android.app.Application
import android.content.pm.PackageInstaller
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.azluk.patcher.core.*
import com.azluk.patcher.engine.ApkEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

// ── Install AI diagnosis ──────────────────────────────────────────────────────

data class AiDiagnosis(
    val loading: Boolean     = false,
    val suggestion: String   = "",
    val fixApplied: Boolean  = false,
    val error: String        = ""
)

data class PatchUiState(
    val selectedPatches: Set<PatchType>  = emptySet(),
    val patchState:   PatchState         = PatchState.Idle,
    val installState: InstallState       = InstallState.Idle,
    val scanResults:  List<ScanResult>   = emptyList(),
    val isScanning:   Boolean            = false,
    val aiDiagnosis:  AiDiagnosis        = AiDiagnosis(),
    val lastOutputPath: String           = ""
)

class PatchViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(PatchUiState())
    val state: StateFlow<PatchUiState> = _state.asStateFlow()
    private val engine = ApkEngine(app)

    // ── Scan ──────────────────────────────────────────────────────────────────

    fun scan(pkg: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isScanning = true, scanResults = emptyList()) }
            val results = runCatching { engine.scan(pkg) }.getOrDefault(emptyList())
            val detected = results.mapNotNull {
                runCatching { PatchType.valueOf(it.patchType) }.getOrNull()
            }.toSet()
            val autoSelected = if (detected.isNotEmpty()) detected
                else setOf(PatchType.LICENSE_BYPASS, PatchType.REMOVE_ADS)
            _state.update {
                it.copy(isScanning = false, scanResults = results, selectedPatches = autoSelected)
            }
        }
    }

    fun togglePatch(type: PatchType) {
        _state.update { s ->
            val set = s.selectedPatches.toMutableSet()
            if (type in set) set.remove(type) else set.add(type)
            s.copy(selectedPatches = set)
        }
    }

    // ── Patch — batched log emit to avoid recompose storm ────────────────────
    //
    // Problem: emitting one StateFlow update per log line on a 1047-entry APK
    // triggers 1047 recompositions while the CPU is also doing ZIP + SHA-256.
    // Fix: accumulate lines in a local list, flush to StateFlow every 300ms
    // via a ticker coroutine. Final flush happens after the engine returns.
    //
    // Thread priority: engine coroutine runs at THREAD_PRIORITY_BACKGROUND so
    // the Android scheduler deprioritises it vs the UI thread — no more janks.

    fun patch(pkg: String) {
        val patches = _state.value.selectedPatches.toList()
        if (patches.isEmpty()) return
        launchPatch { engine.patch(pkg, patches, it) }
    }

    fun patchFile(file: File) {
        val patches = _state.value.selectedPatches.toList()
        if (patches.isEmpty()) return
        launchPatch { engine.patchExternal(file, patches, it) }
    }

    private fun launchPatch(block: suspend (ApkEngine.Progress) -> File) {
        val log = mutableListOf<String>()
        var dirty = false

        viewModelScope.launch {
            // Ticker: flush log to UI every 300ms so recompose rate is bounded
            val ticker = launch(Dispatchers.Main) {
                while (isActive) {
                    delay(300)
                    if (dirty) {
                        _state.update { it.copy(patchState = PatchState.Running(log.toList())) }
                        dirty = false
                    }
                }
            }

            val result = runCatching {
                withContext(Dispatchers.IO) {
                    // Lower this thread's priority so UI stays smooth
                    android.os.Process.setThreadPriority(
                        android.os.Process.THREAD_PRIORITY_BACKGROUND)
                    block(ApkEngine.Progress { msg ->
                        synchronized(log) { log.add(msg); dirty = true }
                    })
                }
            }

            ticker.cancel()

            result.fold(
                onSuccess = { out ->
                    _state.update {
                        it.copy(
                            patchState    = PatchState.Success(out.absolutePath, log.toList()),
                            lastOutputPath = out.absolutePath
                        )
                    }
                },
                onFailure = { e ->
                    log.add("✗ ${e.message}")
                    _state.update {
                        it.copy(patchState = PatchState.Failure(
                            e.message ?: "Unknown error", log.toList()))
                    }
                }
            )
        }
    }

    // ── Install result + AI diagnosis ─────────────────────────────────────────

    fun onInstallResult(status: Int, message: String?) {
        val ist = when (status) {
            PackageInstaller.STATUS_SUCCESS -> InstallState.Success
            PackageInstaller.STATUS_FAILURE_INVALID -> InstallState.Failure(
                "INSTALL_FAILURE_INVALID",
                "Invalid APK — signature or structure is corrupt.",
                "PARSE_FAILED: signing block may be malformed.", true
            )
            PackageInstaller.STATUS_FAILURE_CONFLICT -> InstallState.Failure(
                "INSTALL_FAILURE_CONFLICT",
                "Version conflict — uninstall the original app first.",
                message ?: "A different version is already installed.", false
            )
            PackageInstaller.STATUS_FAILURE_BLOCKED -> InstallState.Failure(
                "INSTALL_FAILURE_BLOCKED",
                "Installation blocked.",
                "Enable 'Install unknown apps' for AzlukPatcher in Settings.", false
            )
            PackageInstaller.STATUS_FAILURE_STORAGE -> InstallState.Failure(
                "INSTALL_FAILURE_STORAGE",
                "Not enough storage space.",
                "Free up space and try again.", false
            )
            else -> InstallState.Failure(
                "INSTALL_FAILURE_$status",
                message ?: "Unknown installer error (code $status)",
                "Unexpected installer error.", true
            )
        }
        _state.update { it.copy(installState = ist) }

        // Auto-trigger AI diagnosis on any failure
        if (ist is InstallState.Failure) {
            diagnoseWithAi(ist, _state.value.lastOutputPath)
        }
    }

    // ── Claude AI diagnosis ───────────────────────────────────────────────────
    //
    // Sends the install error + APK metadata to Claude Sonnet via the
    // Anthropic API and streams the suggestion back into aiDiagnosis.suggestion.
    // Runs on IO dispatcher — never blocks UI.

    fun diagnoseWithAi(failure: InstallState.Failure, apkPath: String) {
        _state.update { it.copy(aiDiagnosis = AiDiagnosis(loading = true)) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apkInfo = runCatching {
                    val f = java.io.File(apkPath)
                    "APK: ${f.name}, size: ${f.length() / 1024}KB, exists: ${f.exists()}"
                }.getOrDefault("APK info unavailable")

                val prompt = """
You are an Android APK signing expert embedded in AzlukPatcher.
The user patched an APK and installation failed.

Error code: ${failure.code}
Error message: ${failure.message}
Error description: ${failure.description}
$apkInfo

Diagnose the exact cause in 2 sentences maximum.
Then give one concrete fix the app should apply automatically, in plain language.
Be direct, no preamble.
""".trimIndent()

                // TokenRouter — OpenAI-compatible gateway, 300+ models, one key.
                // Endpoint: https://api.tokenrouter.com/v1/chat/completions
                // Model format: "provider/model-name" e.g. "anthropic/claude-3-5-sonnet"
                // Auth: Bearer token in Authorization header (standard OpenAI style)
                val body = org.json.JSONObject().apply {
                    put("model", BuildConfig.TOKENROUTER_MODEL)   // set in build.gradle
                    put("max_tokens", 300)
                    put("messages", org.json.JSONArray().apply {
                        put(org.json.JSONObject().apply {
                            put("role", "system")
                            put("content", "You are an Android APK signing expert. Be concise and direct.")
                        })
                        put(org.json.JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
                        })
                    })
                }

                val url  = java.net.URL("${BuildConfig.TOKENROUTER_BASE_URL}/chat/completions")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.TOKENROUTER_API_KEY}")
                conn.doOutput      = true
                conn.connectTimeout = 10_000
                conn.readTimeout    = 20_000

                conn.outputStream.use { it.write(body.toString().toByteArray()) }

                val resp = conn.inputStream.bufferedReader().readText()
                // OpenAI-compatible response: choices[0].message.content
                val json = org.json.JSONObject(resp)
                val text = json
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()

                _state.update {
                    it.copy(aiDiagnosis = AiDiagnosis(loading = false, suggestion = text))
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(aiDiagnosis = AiDiagnosis(
                        loading = false,
                        error   = "AI unavailable: ${e.message}"
                    ))
                }
            }
        }
    }

    fun dismissAi() {
        _state.update { it.copy(aiDiagnosis = AiDiagnosis()) }
    }

    fun resetPatch() {
        _state.update {
            it.copy(
                patchState   = PatchState.Idle,
                installState = InstallState.Idle,
                aiDiagnosis  = AiDiagnosis()
            )
        }
    }
}
