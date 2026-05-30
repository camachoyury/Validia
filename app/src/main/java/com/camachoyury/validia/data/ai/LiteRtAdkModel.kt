package com.camachoyury.validia.data.ai

import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONException
import org.json.JSONObject

/**
 * Adapter connecting the ADK [Model] interface to the local LiteRT inference engine.
 *
 * ## Two-turn tool-calling protocol
 *
 * Turn 1 (user message):
 *   - LiteRT is invoked with the user text + tool instructions
 *   - If the raw text contains {"tool_call": {...}}, it is converted to a real
 *     [FunctionCall] Part so the ADK runner executes the corresponding @Tool function
 *
 * Turn 2 (post-tool result):
 *   - The ADK runner appends a [FunctionResponse] Part to the history
 *   - This model detects it and SHORT-CIRCUITS: LiteRT is NOT called again
 *   - A plain-text confirmation is returned immediately
 *   - This PREVENTS the infinite loop where Gemma would see the tool-call example
 *     in the history and re-generate another tool call indefinitely
 */
class LiteRtAdkModel(
    private val localEngine: LocalInferenceEngine
) : Model {

    override val name: String = "gemma-4-e2b-local"

    override fun generateContent(
        request: LlmRequest,
        stream: Boolean
    ): Flow<LlmResponse> = flow {

        val messages = request.contents

        // ── LOOP BREAKER ──────────────────────────────────────────────────────
        // If any message already contains a FunctionResponse, the ADK runner is
        // in its second (post-tool) turn. Returning plain text here is enough to
        // signal "done" to the runner — no need to call LiteRT again.
        // Without this guard, Gemma would see the tool-call example in the history
        // and generate another {"tool_call":...}, repeating forever.
        val isPostToolTurn = messages.any { content ->
            content.parts.any { it.functionResponse != null }
        }

        if (isPostToolTurn) {
            emit(confirmationResponse())
            return@flow
        }

        // ── TURN 1: normal inference ──────────────────────────────────────────
        val systemInstruction = request.config?.systemInstruction
        val functionDeclarations =
            request.config?.tools?.flatMap { it.functionDeclarations ?: emptyList() }

        val systemPrompt = buildSystemPrompt(
            baseInstruction = systemInstruction?.parts?.firstOrNull()?.text,
            tools           = functionDeclarations
        )

        // Serialize only the user-visible history (no FunctionResponse in turn 1)
        val userInput = serializeHistory(messages)

        val rawResponse = localEngine.generate(
            systemPrompt = systemPrompt,
            userInput    = userInput,
            onToken      = {}
        )

        // Convert manual JSON tool-call to real FunctionCall Part if present
        val toolCall = parseToolCall(rawResponse)

        val responseContent = if (toolCall != null) {
            Content(
                role  = Role.MODEL,
                parts = listOf(
                    Part(functionCall = FunctionCall(
                        name = toolCall.first,
                        args = toolCall.second
                    ))
                )
            )
        } else {
            Content(role = Role.MODEL, parts = listOf(Part(text = rawResponse)))
        }

        emit(llmResponse(responseContent))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun confirmationResponse() = llmResponse(
        Content(
            role  = Role.MODEL,
            parts = listOf(Part(text = "Inventory registered successfully."))
        )
    )

    private fun llmResponse(content: Content) = LlmResponse(
        content           = content,
        finishReason      = FinishReason.STOP,
        usageMetadata     = null,
        errorMessage      = null,
        partial           = false,
        interrupted       = false,
        modelVersion      = null,
        citationMetadata  = null,
        groundingMetadata = null
    )

    // ─────────────────────────────────────────────────────────────────────────
    // System prompt — only injected on turn 1
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildSystemPrompt(
        baseInstruction: String?,
        tools: List<FunctionDeclaration>?
    ): String {
        val sb = StringBuilder(baseInstruction ?: "You are a helpful AI assistant.")
        if (tools.isNullOrEmpty()) return sb.toString()

        sb.append("""
            
            
            === HOW TO CALL A TOOL ===
            Return ONLY this JSON (no markdown, no extra text):
            {"tool_call": {"name": "<tool_name>", "arguments": {"<param>": "<value>"}}}
            
            === EXAMPLE ===
            User: "I got 12 Cokes and 5 bags of chips"
            Your response:
            {"tool_call": {"name": "registrarEntradaInventario", "arguments": {"jsonProductos": "[{\"quantity\":12,\"productName\":\"Coca-Cola\",\"category\":\"Beverages\"},{\"quantity\":5,\"productName\":\"Chips\",\"category\":\"Food\"}]"}}}
            
            === TOOLS ===
        """.trimIndent())

        tools.forEach { decl ->
            sb.append("\n• ${decl.name}: ${decl.description}")
            decl.parameters?.properties?.forEach { (param, schema) ->
                sb.append("\n    - $param (${schema.type}): ${schema.description}")
            }
        }

        sb.append(
            "\n\nRULE: Extract products from any natural-language text and call " +
            "'registrarEntradaInventario' immediately. NEVER ask for more info."
        )

        return sb.toString()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // History serialization
    // ─────────────────────────────────────────────────────────────────────────

    private fun serializeHistory(messages: List<Content>): String =
        messages.joinToString("\n") { msg ->
            val role = when (msg.role) {
                Role.USER   -> "User"
                Role.MODEL  -> "Model"
                Role.SYSTEM -> "System"
                else        -> msg.role ?: "Unknown"
            }
            val text = msg.parts.joinToString(" ") { part ->
                part.text
                    ?: part.functionCall?.let { "[tool_call: ${it.name}]" }
                    ?: part.functionResponse?.let { "[tool_result: ${it.name}]" }
                    ?: ""
            }
            "$role: $text"
        }

    // ─────────────────────────────────────────────────────────────────────────
    // JSON tool-call detection
    // ─────────────────────────────────────────────────────────────────────────

    private fun parseToolCall(rawText: String): Pair<String, Map<String, Any>>? {
        val start = rawText.indexOf("{\"tool_call\"")
        if (start == -1) return null
        return try {
            val jsonStr = extractOutermostObject(rawText, start) ?: return null
            val root    = JSONObject(jsonStr)
            val tc      = root.getJSONObject("tool_call")
            val name    = tc.getString("name")
            val argsObj = tc.optJSONObject("arguments") ?: return Pair(name, emptyMap())
            val args    = mutableMapOf<String, Any>()
            argsObj.keys().forEach { key -> args[key] = argsObj.get(key) }
            Pair(name, args)
        } catch (_: JSONException) {
            null
        }
    }

    private fun extractOutermostObject(text: String, start: Int): String? {
        var depth = 0
        for (i in start until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return text.substring(start, i + 1) }
            }
        }
        return null
    }
}
