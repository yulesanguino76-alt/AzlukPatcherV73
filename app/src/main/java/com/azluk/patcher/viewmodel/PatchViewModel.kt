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

data class PatchUiState(
    val selectedPatches: Set<PatchType> = setOf(
        PatchType.LICENSE_BYPASS, PatchType.IAP_BYPASS,
        PatchType.REMOVE_ADS, PatchType.SIGNATURE_BYPASS
    ),
    val patchState: PatchState      = PatchState.Idle,
    val installState: InstallState  = InstallState.Idle,
    val scanResults: List<ScanResult> = emptyList(),
    val isScanning: Boolean         = false
)

class PatchViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(PatchUiState())
    val state: StateFlow<PatchUiState> = _state.asStateFlow()

    private val engine = ApkEngine(app)

    fun scan(pkg: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isScanning = true, scanResults = emptyList()) }
            val results = runCatching { engine.scan(pkg) }.getOrDefault(emptyList())
            _state.update { it.copy(isScanning = false, scanResults = results) }
        }
    }

    fun togglePatch(type: PatchType) {
        _state.update { s ->
            val set = s.selectedPatches.toMutableSet()
            if (type in set) set.remove(type) else set.add(type)
            s.copy(selectedPatches = set)
        }
    }

    fun patch(pkg: String) {
        val patches = _state.value.selectedPatches.toList()
        val log     = mutableListOf<String>()
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(patchState = PatchState.Running(log.toList())) }
            try {
                val out = engine.patch(pkg, patches) { msg ->
                    log.add(msg)
                    _state.update { it.copy(patchState = PatchState.Running(log.toList())) }
                }
                _state.update { it.copy(patchState = PatchState.Success(out.absolutePath, log.toList())) }
            } catch (e: Exception) {
                log.add("✗ ${e.message}")
                _state.update { it.copy(patchState = PatchState.Failure(e.message ?: "Unknown error", log.toList())) }
            }
        }
    }

    fun patchFile(file: File) {
        val patches = _state.value.selectedPatches.toList()
        val log     = mutableListOf<String>()
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(patchState = PatchState.Running(log.toList())) }
            try {
                val out = engine.patchExternal(file, patches) { msg ->
                    log.add(msg)
                    _state.update { it.copy(patchState = PatchState.Running(log.toList())) }
                }
                _state.update { it.copy(patchState = PatchState.Success(out.absolutePath, log.toList())) }
            } catch (e: Exception) {
                log.add("✗ ${e.message}")
                _state.update { it.copy(patchState = PatchState.Failure(e.message ?: "Unknown error", log.toList())) }
            }
        }
    }

    fun onInstallResult(status: Int, message: String?) {
        val installState = when (status) {
            PackageInstaller.STATUS_SUCCESS -> InstallState.Success
            PackageInstaller.STATUS_FAILURE_INVALID ->
                InstallState.Failure(
                    code        = "INSTALL_FAILURE_INVALID",
                    message     = "Invalid APK — signature or structure is corrupt.",
                    description = "PARSE_FAILED: APK signature or structure invalid. Usually means the signing block is corrupt.",
                    canRetry    = true
                )
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                InstallState.Failure(
                    code        = "INSTALL_FAILURE_CONFLICT",
                    message     = "Version conflict — uninstall the original app first.",
                    description = message ?: "A different version is already installed.",
                    canRetry    = false
                )
            PackageInstaller.STATUS_FAILURE_BLOCKED ->
                InstallState.Failure(
                    code        = "INSTALL_FAILURE_BLOCKED",
                    message     = "Installation blocked by policy.",
                    description = "Enable 'Install unknown apps' for AzlukPatcher in Settings.",
                    canRetry    = false
                )
            else ->
                InstallState.Failure(
                    code        = "INSTALL_FAILURE_$status",
                    message     = message ?: "Unknown error (code $status)",
                    description = "Unexpected installer error.",
                    canRetry    = true
                )
        }
        _state.update { it.copy(installState = installState) }
    }

    fun resetPatch() {
        _state.update { it.copy(patchState = PatchState.Idle, installState = InstallState.Idle) }
    }
}
