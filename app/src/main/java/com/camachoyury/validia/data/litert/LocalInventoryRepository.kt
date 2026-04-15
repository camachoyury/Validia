package com.camachoyury.validia.data.litert

import android.util.Log
import com.camachoyury.validia.data.ai.LiteRtInferenceEngine
import com.camachoyury.validia.data.ai.LocalInferenceEngine
import com.camachoyury.validia.domain.model.InventoryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * On-device inventory repository using LiteRT-LM / Gemma 4 E2B.
 *
 * Lifecycle:
 *  1. [initializeModel]    → Loads the model into RAM via [LocalInferenceEngine.initialize].
 *  2. [parseInventoryText] → 100% local inference; parses JSON with kotlinx.serialization.
 *  3. [closeModel]         → Releases native memory. Call in ViewModel.onCleared().
 *
 * Improvements over MediaPipe implementation:
 *  - No manual chat template: the SDK handles the Gemma IT format internally.
 *  - No manual early-stop: the EOS token is natively recognized by LiteRT-LM.
 *  - The repository only knows "system instructions" and "user input" — no custom formats.
 *
 * @param engine Injected local inference engine.
 */
class LocalInventoryRepository(
    private val engine: LocalInferenceEngine
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Few-Shot System Prompt
    //
    // Passed to ConversationConfig.systemInstruction in LiteRtInferenceEngine.
    // The SDK applies the correct chat template internally — no need for
    // <start_of_turn>user...<end_of_turn><start_of_turn>model manually.
    // ─────────────────────────────────────────────────────────────────────────
    private val systemInstructions = """
        You are an inventory assistant for stores and warehouses.
        Your only job is to extract products from a text and return them as JSON.

        STRICT RULES:
        - Return ONLY a valid JSON array. No extra text, no markdown.
        - Each object: "quantity" (Int), "productName" (String), "category" (String).
        - "category" MUST be one of: "Beverages", "Food" or "Cleaning".
        - If you cannot classify a product, default to "Food".

        EXAMPLES:

        Input: "got 24 Coca-Cola 20oz and 6 bags of Lay's chips"
        Output: [{"quantity":24,"productName":"Coca-Cola 20oz","category":"Beverages"},{"quantity":6,"productName":"Lay's Chips","category":"Food"}]

        Input: "order 4 Tide detergent and 3 bottles of vegetable oil"
        Output: [{"quantity":4,"productName":"Tide Detergent","category":"Cleaning"},{"quantity":3,"productName":"Vegetable Oil","category":"Food"}]

        Now process the following text:
    """.trimIndent()

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Initialization
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Loads Gemma 4 E2B into memory.
     * Expensive operation (~2-8 sec depending on device) — always on [Dispatchers.IO].
     *
     * @param modelPath Asset name (.litertlm) or absolute path to the file.
     */
    suspend fun initializeModel(modelPath: String): Unit = withContext(Dispatchers.IO) {
        engine.initialize(modelPath)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Offline inference
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parses the raw supplier text with Gemma on-device.
     * NEVER makes network calls.
     *
     * @param rawText Unstructured supplier text (e.g. "12 Cokes and 5 Taragüi").
     * @return List of extracted and classified [InventoryItem].
     */
    suspend fun parseInventoryText(
        rawText: String,
        onToken: (String) -> Unit = {}
    ): List<InventoryItem> =
        withContext(Dispatchers.IO) {
            require(rawText.isNotBlank()) { "Input text cannot be empty." }

            Log.d(LiteRtInferenceEngine.TAG, "  [repo] Input text: \"$rawText\"")

            try {
                // With LiteRT-LM the engine receives system prompt and user input separately.
                // The SDK applies the Gemma IT chat template internally — no manual formatting.
                val rawResponse = engine.generate(
                    systemPrompt = systemInstructions,
                    userInput = rawText,
                    onToken = onToken
                )

                Log.d(LiteRtInferenceEngine.TAG, "  [repo] Raw response received (${rawResponse.length} chars).")

                val cleanJson = extractCleanJsonArray(rawResponse)
                Log.d(LiteRtInferenceEngine.TAG, "  [repo] Extracted JSON: $cleanJson")

                val items = json.decodeFromString<List<InventoryItem>>(cleanJson)
                Log.d(LiteRtInferenceEngine.TAG, "  [repo] ✅ ${items.size} item(s) parsed:")
                items.forEachIndexed { i, item ->
                    Log.d(LiteRtInferenceEngine.TAG, "  [repo]   [$i] x${item.quantity} ${item.productName} (${item.category})")
                }
                items

            } catch (e: Exception) {
                Log.e(LiteRtInferenceEngine.TAG, "  [repo] ❌ Parsing failed: ${e.message}")
                throw IllegalStateException(
                    "Could not process the model response. Try with a clearer input text.",
                    e
                )
            }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Resource release
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Releases the RAM occupied by the Engine.
     * Must be called in [androidx.lifecycle.ViewModel.onCleared] to avoid memory leaks.
     */
    fun closeModel() = engine.close()

    // ─────────────────────────────────────────────────────────────────────────
    // Private utilities
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Extracts the first JSON array block from the raw model response.
     *
     * Strategy: finds the first '[' and last ']', ignoring any introductory text
     * or epilogue the model may add despite the strict prompt.
     * Makes parsing robust against partially malformed responses.
     */
    private fun extractCleanJsonArray(rawResponse: String): String {
        val start = rawResponse.indexOf('[')
        val end = rawResponse.lastIndexOf(']')

        if (start == -1 || end == -1 || end <= start) {
            throw IllegalStateException(
                "No valid JSON array found in model response.\n" +
                    "Response received: \"${rawResponse.take(200)}\""
            )
        }

        return rawResponse.substring(start, end + 1)
    }
}
