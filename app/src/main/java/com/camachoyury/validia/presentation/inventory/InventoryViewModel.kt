package com.camachoyury.validia.presentation.inventory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.camachoyury.validia.data.litert.LocalInventoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ═══════════════════════════════════════════════════════════════
 * PASO 11 — ViewModel
 * ═══════════════════════════════════════════════════════════════
 *
 * Orquesta el ciclo completo:
 *   1. Al crearse, carga el modelo (initializeModel)
 *   2. Cuando el usuario procesa texto, ejecuta la inferencia (processText)
 *   3. Al destruirse, libera la RAM nativa del Engine (onCleared)
 *
 * ¿Qué implementar?
 *
 * initializeModel():
 *   - Emitir InventoryUiState.InitializingModel
 *   - Llamar repository.initializeModel(modelPath)
 *   - En éxito: emitir InventoryUiState.Ready
 *   - En error: emitir InventoryUiState.Error(e.message)
 *
 * processText(rawText):
 *   - Emitir InventoryUiState.Processing
 *   - Iniciar DebugState(isRunning = true)
 *   - Llamar repository.parseInventoryText(rawText) { token ->
 *       actualizar _debugState con tokenCount + 1 y elapsedMs
 *     }
 *   - En éxito: emitir InventoryUiState.Success(items)
 *   - En error: emitir InventoryUiState.Error(...)
 *
 * onCleared():
 *   - Llamar repository.closeModel() ← ¡crítico para evitar memory leaks!
 */
class InventoryViewModel(
    private val repository: LocalInventoryRepository,
    private val modelPath: String
) : ViewModel() {

    private val _uiState = MutableStateFlow<InventoryUiState>(InventoryUiState.Idle)
    val uiState: StateFlow<InventoryUiState> = _uiState.asStateFlow()

    private val _rawText = MutableStateFlow("")
    val rawText: StateFlow<String> = _rawText.asStateFlow()

    private val _debugState = MutableStateFlow<DebugState?>(null)
    val debugState: StateFlow<DebugState?> = _debugState.asStateFlow()

    init {
        // TODO Paso 11.1 — Llamar initializeModel() aquí para cargar el modelo
        // al crearse el ViewModel
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TODO Paso 11.2 — Implementar initializeModel()
    // ─────────────────────────────────────────────────────────────────────────
    private fun initializeModel() {
        viewModelScope.launch {
            // TODO: emitir InitializingModel → llamar repository.initializeModel(modelPath)
            //       → emitir Ready (o Error si falla)
        }
    }

    fun retryInitialization() = initializeModel()

    // ─────────────────────────────────────────────────────────────────────────
    // TODO Paso 11.3 — Implementar processText()
    // ─────────────────────────────────────────────────────────────────────────
    fun processText(rawText: String) {
        viewModelScope.launch {
            // TODO: emitir Processing → iniciar DebugState →
            //       llamar repository.parseInventoryText con onToken para actualizar debugState →
            //       emitir Success(items) o Error
        }
    }

    fun onTextChanged(newText: String) {
        _rawText.value = newText
        if (_uiState.value is InventoryUiState.Error) {
            _uiState.value = InventoryUiState.Ready
        }
    }

    fun reset() {
        _rawText.value = ""
        _uiState.value = InventoryUiState.Ready
        _debugState.value = null
    }

    // ──────────────────────���──────────────────────────────────────────────────
    // TODO Paso 11.4 — Implementar onCleared() para liberar recursos nativos
    // ───────────────────────────────────────────────��─────────────────────────
    override fun onCleared() {
        super.onCleared()
        // TODO: llamar repository.closeModel()
        // ⚠️ Si no lo hacés, el Engine reserva varios GB de RAM nativa
        // incluso después de que el usuario cierra la pantalla.
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Factory — wiring manual sin Hilt
    // ─────────────────────────────────────────────────────────────────────────
    class Factory(
        private val repository: LocalInventoryRepository,
        private val modelPath: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            InventoryViewModel(repository, modelPath) as T
    }
}
