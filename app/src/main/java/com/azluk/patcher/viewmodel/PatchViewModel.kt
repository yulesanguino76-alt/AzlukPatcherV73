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
    val selectedPatches: Set<PatchType> = emptySet(), // starts empty, filled after scan
    val patchState:  PatchState   = PatchState.Idle,
    val installState: InstallState = InstallState.Idle,
    val scanResults: List<ScanResult> = emptyList(),
    val isScanning: Boolean = false
)

class PatchViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(PatchUiState())
    val state: StateFlow<PatchUiState> = _state.asStateFlow()
    private val engine = ApkEngine(app)

    fun scan(pkg: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isScanning = true, scanResults = emptyList()) }
            val results = runCatching { engine.scan(pkg) }.getOrDefault(emptyList())

            // Auto-select detected patch types
            val detected = results.mapNotNull {
                try { PatchType.valueOf(it.patchType) } catch (_: Exception) { null }
            }.toSet()

            // If nothing detected, default to safe common patches
            val autoSelected = if (detected.isNotEmpty()) detected
                else setOf(PatchType.LICENSE_BYPASS, PatchType.REMOVE_ADS)

            _state.update { it.copy(
                isScanning   = false,
                scanResults  = results,
                selectedPatches = autoSelected
            )}
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
        if (patches.isEmpty()) return
        val log = mutableListOf<String>()
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
        val log = mutableListOf<String>()
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
            else -> InstallState.Failure(
                "INSTALL_FAILURE_$status",
                message ?: "Unknown error (code $status)",
                "Unexpected installer error.", true
            )
        }
        _state.update { it.copy(installState = ist) }
    }

    fun resetPatch() {
        _state.update { it.copy(
            patchState   = PatchState.Idle,
            installState = InstallState.Idle
        )}
    }
}
