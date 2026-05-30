package com.camachoyury.validia.data.litert

import com.camachoyury.validia.data.ai.LocalInferenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository that owns the lifecycle of the local LiteRT inference engine.
 *
 * Responsibilities:
 *  1. [initializeModel] — load Gemma into RAM (called once on ViewModel init)
 *  2. [closeModel]      — release native memory (called in ViewModel.onCleared)
 *
 * Inference is driven entirely by the ADK layer ([LiteRtAdkModel] + [InMemoryRunner]).
 * This repository only manages engine initialization and teardown.
 *
 * @param engine Injected local inference engine (production: [LiteRtInferenceEngine]).
 */
class LocalInventoryRepository(
    internal val engine: LocalInferenceEngine
) {
    suspend fun initializeModel(modelPath: String): Unit = withContext(Dispatchers.IO) {
        engine.initialize(modelPath)
    }

    fun closeModel() = engine.close()
}
