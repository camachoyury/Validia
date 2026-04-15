package com.camachoyury.validia.data.ai

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.camachoyury.validia.data.util.ModelCopier
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device inference engine using LiteRT-LM 0.10.0.
 *
 * SDK: com.google.ai.edge.litertlm:litertlm-android:0.10.0
 * Model format: .litertlm (Gemma 4 E2B — LiteRT native format)
 *
 * ──────────────────────────────────────────────────────────────
 * Why this SDK and not MediaPipe Tasks GenAI
 * ──────────────────────────────────────────────────────────────
 * With MediaPipe 0.10.33 + .litertlm we needed:
 *  1. Manually build the Gemma IT chat template
 *     (<start_of_turn>user...<end_of_turn><start_of_turn>model)
 *  2. Manual early-stop by detecting the closing ']' of the JSON
 *     because MediaPipe didn't recognize the EOS token of .litertlm
 *  3. Deferred session.close() inside future.addListener()
 *     to avoid "Previous invocation still processing"
 *
 * With LiteRT-LM 0.10.0:
 *  ✅ No manual chat template — ConversationConfig.systemInstruction
 *  ✅ No early-stop — native EOS token recognized correctly
 *  ✅ No callbacks or futures — native Kotlin Flow<Message>
 *  ✅ GPU/NPU support via Backend.GPU() / Backend.NPU()
 * ──────────────────────────────────────────────────────────────
 *
 * Filter Logcat by tag [TAG] = "Gemma4Debug" to see:
 *  - ⏱ Model load time (ms)
 *  - 🔢 Backend in use (CPU / GPU / NPU)
 *  - 🔄 Streaming tokens (Verbose)
 *  - ✅ Full response
 *  - ⏱ Total inference time (ms)
 */
class LiteRtInferenceEngine(
    private val context: Context
) : LocalInferenceEngine {

    private var engine: Engine? = null

    companion object {
        /** Filter this tag in Logcat to debug Gemma 4 inference. */
        const val TAG = "Gemma4Debug"

        // ── Generation parameters ─────────────────────────────────────────────
        // temperature=0.1 + topK=1 = maximum determinism for JSON outputs.
        // For creative responses increase temperature (0.7-1.0) and topK (10-40).
        private const val TEMPERATURE = 0.1   // Double — SamplerConfig API
        private const val TOP_P = 0.9         // Double — required by SamplerConfig
        private const val TOP_K = 1            // Int
        private const val SEED = 0             // Int — 0 = random seed
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Model loading
    // ─────────────────────────────────────────────────────────────────────────

    override suspend fun initialize(modelPath: String): Unit = withContext(Dispatchers.IO) {
        Log.d(TAG, "════════════════════════════════════════")
        Log.d(TAG, "  GEMMA 4 E2B — LOADING MODEL")
        Log.d(TAG, "════════════════════════════════════════")
        Log.d(TAG, "  SDK     : litertlm-android:0.10.0")
        Log.d(TAG, "  File    : $modelPath")
        Log.d(TAG, "  Backend : CPU (switch to Backend.GPU() for acceleration)")

        val loadStart = SystemClock.elapsedRealtime()

        try {
            val physicalPath = if (modelPath.startsWith("/")) {
                Log.d(TAG, "  Path    : absolute (no copy needed)")
                modelPath
            } else {
                Log.d(TAG, "  Path    : asset → copying to filesDir...")
                val path = ModelCopier.ensureModelReady(context, modelPath)
                Log.d(TAG, "  Copied  : $path")
                path
            }

            val engineConfig = EngineConfig(
                modelPath = physicalPath,
                // Backend.CPU() — maximum compatibility.
                // To enable GPU (requires libOpenCL.so on device):
                //   backend = Backend.GPU()
                // To enable NPU (requires vendor libraries):
                //   backend = Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
                backend = Backend.CPU(),
                // cacheDir speeds up subsequent loads (avoids kernel recompilation)
                cacheDir = context.cacheDir.path
            )

            val e = Engine(engineConfig)
            e.initialize()
            engine = e

            val loadMs = SystemClock.elapsedRealtime() - loadStart
            Log.d(TAG, "────────────────────────────────────────")
            Log.d(TAG, "  ✅ Model loaded in ${loadMs}ms")
            Log.d(TAG, "════════════════════════════════════════")

        } catch (e: Exception) {
            val loadMs = SystemClock.elapsedRealtime() - loadStart
            Log.e(TAG, "════════════════════════════════════════")
            Log.e(TAG, "  ❌ Failed to load Gemma 4 from '$modelPath' (${loadMs}ms)")
            Log.e(TAG, "  Type   : ${e.javaClass.simpleName}")
            Log.e(TAG, "  Message: ${e.message}")
            e.cause?.let { Log.e(TAG, "  Cause  : ${it.message}") }
            Log.e(TAG, "════════════════════════════════════════")
            throw e
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Inference with native Flow (no early-stop, no manual chat template)
    // ─────────────────────────────────────────────────────────────────────────

    override suspend fun generate(
        systemPrompt: String,
        userInput: String,
        onToken: (String) -> Unit
    ): String =
        withContext(Dispatchers.IO) {
            val e = engine
                ?: throw IllegalStateException(
                    "Engine not initialized. Call initialize() before generate()."
                )

            Log.d(TAG, "════════════════════════════════════════")
            Log.d(TAG, "  GEMMA 4 E2B — NEW INFERENCE")
            Log.d(TAG, "════════════════════════════════════════")
            Log.d(TAG, "  Temperature : $TEMPERATURE  |  TopP: $TOP_P  |  TopK: $TOP_K")
            Log.d(TAG, "────────────────────────────────────────")
            Log.d(TAG, "  SYSTEM PROMPT (${systemPrompt.length} chars):")
            systemPrompt.chunked(400).forEachIndexed { i, chunk ->
                Log.d(TAG, "  [sys:$i] $chunk")
            }
            Log.d(TAG, "  USER INPUT: \"$userInput\"")
            Log.d(TAG, "────────────────────────────────────────")
            Log.d(TAG, "  GENERATING TOKENS (streaming):")

            val inferenceStart = SystemClock.elapsedRealtime()

            // ConversationConfig injects the system instruction and sampling parameters.
            // The SDK applies the correct chat template for Gemma IT internally.
            // No need for <start_of_turn>user...<end_of_turn><start_of_turn>model.
            val conversationConfig = ConversationConfig(
                systemInstruction = Contents.of(systemPrompt),
                samplerConfig = SamplerConfig(
                    topK = TOP_K,
                    topP = TOP_P,
                    temperature = TEMPERATURE,
                    seed = SEED
                )
            )

            val accumulated = StringBuilder()

            try {
                // createConversation.use {} — closes resources automatically.
                // sendMessageAsync returns Flow<Message>: each emission is a token chunk.
                // The Flow completes when the model emits the EOS token (recognized natively).
                // No need to cancel manually or detect the closing ']' of the JSON.
                e.createConversation(conversationConfig).use { conversation ->
                    conversation
                        .sendMessageAsync(userInput)
                        .collect { message ->
                            val chunk = message.toString()
                            Log.v(TAG, "  [token] $chunk")
                            onToken(chunk)
                            accumulated.append(chunk)
                        }
                }

                val inferenceMs = SystemClock.elapsedRealtime() - inferenceStart
                val response = accumulated.toString()
                Log.d(TAG, "────────────────────────────────────────")
                Log.d(TAG, "  FULL RESPONSE (${response.length} chars):")
                response.chunked(500).forEachIndexed { i, chunk ->
                    Log.d(TAG, "  [resp:$i] $chunk")
                }
                Log.d(TAG, "────────────────────────────────────────")
                Log.d(TAG, "  ✅ Inference completed in ${inferenceMs}ms")
                Log.d(TAG, "════════════════════════════════════════")
                response

            } catch (e: Exception) {
                val inferenceMs = SystemClock.elapsedRealtime() - inferenceStart
                Log.e(TAG, "────────────────────────────────────────")
                Log.e(TAG, "  ❌ INFERENCE ERROR (${inferenceMs}ms)")
                Log.e(TAG, "  Type   : ${e.javaClass.simpleName}")
                Log.e(TAG, "  Message: ${e.message}")
                Log.e(TAG, "════════════════════════════════════════")
                throw e
            }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Model release
    // ─────────────────────────────────────────────────────────────────────────

    override fun close() {
        Log.d(TAG, "  [model] Releasing Engine from native RAM.")
        engine?.close()
        engine = null
    }
}
