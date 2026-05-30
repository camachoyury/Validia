summary: Build an Offline AI Inventory Agent on Android with Google ADK + Gemma 4 E2B
id: offline-inventory-agent-adk-gemma
categories: Android, AI, Kotlin
status: Published
feedback link: https://github.com/camachoyury/validia/issues
tags: android, adk, gemma, litert, on-device-ai, kotlin, jetpack-compose

# Offline AI Inventory Agent with Google ADK + Gemma on Android

## Overview
Duration: 10

Welcome to this codelab! You will learn how to build a fully **offline AI inventory agent**
on Android using **Google Agent Development Kit (ADK)** and **Gemma 4 E2B** running locally
via **LiteRT** (the new name for TensorFlow Lite / Google AI Edge).

In this lab you will build **Validia** — a smart B2B inventory assistant for small store owners.
A supplier arrives and verbally announces their delivery (e.g., _"I got 12 Cokes and 5 bags of chips"_).
The app extracts the products, quantities, and categories from that natural-language text — entirely
offline, no internet needed — and logs them into the inventory.

### What You'll Learn

- How to integrate the **Google ADK Kotlin SDK** into an Android project
- How to implement the **ADK `Model` interface** to wrap a local LLM
- How to define **agent tools** using `@Tool` and `@Param` annotations
- How to use **`InMemoryRunner`** to orchestrate a multi-turn agent loop
- How to handle the **two-turn tool-calling protocol** and prevent infinite loops
- How to connect ADK output back to a **Jetpack Compose UI** via `StateFlow`

### What You'll Need

- Android Studio Narwhal (2025.1.1) or later
- Android device or emulator with `arm64-v8a` ABI and API ≥ 26
- The **Gemma 4 E2B** `.litertlm` model file (~3–6 GB, downloaded separately)
- Basic knowledge of Kotlin coroutines and Jetpack Compose

---

## Setup & Dependencies
Duration: 5

Let's configure the Gradle build files to include the ADK SDK and the KSP annotation processor.

### 1. Add ADK versions to the version catalog

`gradle/libs.versions.toml`
```toml
[versions]
adkVersion      = "0.1.0"
ksp             = "2.0.21-1.0.28"
liteRtLmAndroid = "0.10.0"
kotlin          = "2.0.21"

[libraries]
adk-core-android  = { group = "com.google.adk",              name = "google-adk-kotlin-core-android", version.ref = "adkVersion" }
adk-processor     = { group = "com.google.adk",              name = "google-adk-kotlin-processor",      version.ref = "adkVersion" }
litert-lm-android = { group = "com.google.ai.edge.litertlm", name = "litertlm-android",                 version.ref = "liteRtLmAndroid" }

[plugins]
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

### 2. Apply plugins and dependencies

`app/build.gradle.kts`
```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)          // KSP processes @Tool annotations at compile time
}

android {
    defaultConfig {
        minSdk = 26                  // ADK requires API 26+
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    // Prevent double-compression of large model files
    androidResources {
        noCompress += "litertlm"
        noCompress += "task"
    }

    packaging {
        resources {
            excludes += "META-INF/INDEX.LIST"   // avoid duplicate-file build error
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    implementation(libs.adk.core.android)    // ADK runtime
    ksp(libs.adk.processor)                  // KSP annotation processor for @Tool
    implementation(libs.litert.lm.android)   // Google AI Edge — local inference
    // ... Compose, Lifecycle, etc.
}
```

### 3. Fix the AGP 9.x source-set conflict

Android Gradle Plugin 9.x no longer allows third-party plugins to modify Kotlin source sets
directly. Add this flag to `gradle.properties`:

```properties
android.disallowKotlinSourceSets=false
```

> **Warning:** Without this flag, KSP's generated source registration will throw a
> `ProjectConfigurationException` at configuration time on AGP 9.x.

---

## Load the Gemma Model
Duration: 10

Before connecting the ADK, we need the local inference engine to load Gemma 4 E2B into device RAM.
We abstract this behind a `LocalInferenceEngine` interface so we can swap implementations or mock
in tests.

### The `LocalInferenceEngine` interface

`data/ai/LocalInferenceEngine.kt`
```kotlin
interface LocalInferenceEngine {
    suspend fun initialize(modelPath: String)
    suspend fun generate(
        systemPrompt: String,
        userInput: String,
        onToken: (String) -> Unit
    ): String
    fun close()
}
```

### The `LiteRtInferenceEngine` implementation

`data/ai/LiteRtInferenceEngine.kt`
```kotlin
class LiteRtInferenceEngine(private val context: Context) : LocalInferenceEngine {

    private var engine: Engine? = null

    override suspend fun initialize(modelPath: String) {
        // Copy .litertlm from assets → filesDir (only on first launch)
        ModelCopier.copyIfNeeded(context, modelPath)
        val destPath = context.filesDir.absolutePath + "/" + modelPath
        engine = Engine(EngineConfig(modelPath = destPath)).initialize()
    }

    override suspend fun generate(
        systemPrompt: String,
        userInput: String,
        onToken: (String) -> Unit
    ): String {
        val conversation = engine!!.createConversation(systemPrompt)
        val result = StringBuilder()
        conversation.sendMessageAsync(userInput)
            .collect { msg -> onToken(msg.text ?: ""); result.append(msg.text) }
        return result.toString()
    }

    override fun close() = engine?.close().also { engine = null }
}
```

### The Repository — pure lifecycle manager

`data/litert/LocalInventoryRepository.kt`
```kotlin
class LocalInventoryRepository(internal val engine: LocalInferenceEngine) {

    suspend fun initializeModel(modelPath: String) =
        withContext(Dispatchers.IO) { engine.initialize(modelPath) }

    fun closeModel() = engine.close()
}
```

> **Note:** `LocalInventoryRepository` no longer contains any prompt engineering or JSON parsing.
> That responsibility now lives in the ADK tools and `LiteRtAdkModel`.

---

## Build the ADK Model Adapter
Duration: 15

The ADK runtime works with any model that implements the `Model` interface from
`com.google.adk.kt.models`. We create `LiteRtAdkModel` to bridge the ADK protocol
to our local `LocalInferenceEngine`.

### The overall contract

The ADK calls `generateContent(request, stream)` and expects a `Flow<LlmResponse>` back.
The response can contain either:
- A **text part** — the model's conversational reply
- A **`FunctionCall` part** — a request to invoke a tool

### Full implementation

`data/ai/LiteRtAdkModel.kt`
```kotlin
class LiteRtAdkModel(private val localEngine: LocalInferenceEngine) : Model {

    override val name: String = "gemma-4-e2b-local"

    override fun generateContent(
        request: LlmRequest,
        stream: Boolean
    ): Flow<LlmResponse> = flow {

        val messages = request.contents

        // ── LOOP BREAKER ─────────────────────────────────────────────────────
        // If any message already contains a FunctionResponse, the ADK runner is
        // in its second (post-tool) turn. Return plain text immediately to signal
        // "done" — do NOT call LiteRT again, or Gemma will re-generate another
        // tool call and create an infinite loop.
        val isPostToolTurn = messages.any { content ->
            content.parts.any { it.functionResponse != null }
        }
        if (isPostToolTurn) {
            emit(confirmationResponse())
            return@flow
        }

        // ── TURN 1: normal inference ──────────────────────────────────────────
        val systemPrompt = buildSystemPrompt(
            baseInstruction = request.config?.systemInstruction?.parts?.firstOrNull()?.text,
            tools           = request.config?.tools?.flatMap { it.functionDeclarations ?: emptyList() }
        )
        val userInput    = serializeHistory(messages)
        val rawResponse  = localEngine.generate(systemPrompt, userInput, onToken = {})

        // Detect the JSON tool-call pattern in Gemma's output
        val toolCall = parseToolCall(rawResponse)
        val responseContent = if (toolCall != null) {
            Content(role = Role.MODEL, parts = listOf(
                Part(functionCall = FunctionCall(toolCall.first, toolCall.second))
            ))
        } else {
            Content(role = Role.MODEL, parts = listOf(Part(text = rawResponse)))
        }
        emit(llmResponse(responseContent))
    }
}
```

### The `buildSystemPrompt` helper

This is where we teach Gemma the exact JSON format it must output to trigger a tool call:

```kotlin
private fun buildSystemPrompt(baseInstruction: String?, tools: List<FunctionDeclaration>?): String {
    val sb = StringBuilder(baseInstruction ?: "You are a helpful AI assistant.")
    if (tools.isNullOrEmpty()) return sb.toString()

    sb.append("""
        
        === HOW TO CALL A TOOL ===
        Return ONLY this JSON (no markdown, no extra text):
        {"tool_call": {"name": "<tool_name>", "arguments": {"<param>": "<value>"}}}
        
        === EXAMPLE ===
        User: "I got 12 Cokes and 5 bags of chips"
        Your response:
        {"tool_call": {"name": "registrarEntradaInventario",
          "arguments": {"jsonProductos": "[{\"quantity\":12,...}]"}}}
        
        === TOOLS ===
    """.trimIndent())

    tools.forEach { decl ->
        sb.append("\n• ${decl.name}: ${decl.description}")
        decl.parameters?.properties?.forEach { (param, schema) ->
            sb.append("\n    - $param (${schema.type}): ${schema.description}")
        }
    }

    sb.append("\n\nRULE: Extract products and call 'registrarEntradaInventario' immediately. NEVER ask for more info.")
    return sb.toString()
}
```

> **Critical:** The **loop breaker** (`isPostToolTurn` check) is the most important part.
> Without it, Turn 2 passes the full history (including the example JSON) back to Gemma,
> causing her to generate another tool call — creating an **infinite loop**.

---

## Define the Agent & Tools
Duration: 10

ADK tools are regular Kotlin functions annotated with `@Tool` and `@Param`. KSP reads
these annotations at compile time and generates the JSON function declarations that the
ADK runtime uses to describe available capabilities to the LLM.

### Create `InventoryTools`

`domain/InventoryAgentDefinition.kt`
```kotlin
class InventoryTools(
    private val onItemsParsed: (List<InventoryItem>) -> Unit = {}
) {

    @Tool("Registra la entrada de nuevos productos al inventario.")
    fun registrarEntradaInventario(
        @Param("Lista de productos en JSON. Ej: [{\"quantity\":12, \"productName\":\"Coca-Cola\", \"category\":\"Beverages\"}]")
        jsonProductos: String
    ): String {
        return try {
            val arr   = JSONArray(jsonProductos)
            val items = (0 until arr.length()).map { i ->
                arr.getJSONObject(i).run {
                    InventoryItem(
                        quantity    = optInt("quantity", 1),
                        productName = optString("productName", "Unknown"),
                        category    = optString("category", "Food")
                    )
                }
            }
            onItemsParsed(items)        // ← fires the ViewModel callback
            JSONObject(mapOf("status" to "success")).toString()
        } catch (e: Exception) {
            JSONObject(mapOf("status" to "error", "message" to e.message)).toString()
        }
    }

    @Tool("Consulta el stock disponible de un producto por SKU.")
    fun consultarStockProducto(
        @Param("Código SKU del producto (ej. SKU-123)") sku: String
    ): String {
        val stock = if (sku.uppercase().contains("123")) 42 else 0
        return JSONObject(mapOf("sku" to sku, "stock_disponible" to stock)).toString()
    }
}
```

> **Key insight:** The `onItemsParsed` callback is the bridge between the ADK world and the ViewModel.
> When the agent calls `registrarEntradaInventario`, the Kotlin function runs, parses the JSON, and fires
> the callback with typed `InventoryItem` objects — no string parsing needed in the ViewModel.

### Create the `LlmAgent`

```kotlin
object InventoryAgentFactory {
    fun createRootAgent(adkModel: LiteRtAdkModel, tools: InventoryTools): LlmAgent {
        return LlmAgent(
            name        = "inventory_agent",
            description = "Agente de recepción de inventarios offline B2B.",
            model       = adkModel,
            instruction = Instruction("""
                You are an offline inventory assistant for a store.

                YOUR ONLY JOB: Extract products from the user's text, build the JSON,
                and IMMEDIATELY call the tool 'registrarEntradaInventario'.

                CRITICAL RULES:
                - NEVER ask the user for more information.
                - NEVER say you cannot process the request.
                - 'category' MUST be exactly: "Beverages", "Food", or "Cleaning".
                - Call the tool on the FIRST response. No text before or after.
            """.trimIndent()),
            tools = tools.generatedTools()    // KSP-generated from @Tool annotations
        )
    }
}
```

> `tools.generatedTools()` is a KSP-generated extension function. The KSP processor reads all
> `@Tool`-annotated methods in `InventoryTools` at compile time and generates the JSON schema
> descriptors and dispatch glue code automatically.

---

## Wire the ADK Runner in the ViewModel
Duration: 15

The `InventoryViewModel` wires everything together: it owns the `InMemoryRunner`,
exposes UI state as `StateFlow`, and connects the tool callback back to the Compose UI.

### ADK setup

`presentation/inventory/InventoryViewModel.kt`
```kotlin
class InventoryViewModel(
    private val repository: LocalInventoryRepository,
    private val modelPath: String
) : ViewModel() {

    // Callback bridge: ADK tool execution → ViewModel UI update
    private val inventoryTools = InventoryTools { items ->
        resolveWithItems(items)         // fires as soon as the tool runs
    }

    private val adkModel = LiteRtAdkModel(repository.engine)

    private val agent = InventoryAgentFactory.createRootAgent(
        adkModel = adkModel,
        tools    = inventoryTools
    )

    private val runner = InMemoryRunner(
        agent          = agent,
        sessionService = InMemorySessionService()
    )

    @Volatile private var resolved = false
    // ... StateFlow declarations
}
```

### Running the agent on user input

```kotlin
fun processText(rawText: String) {
    if (rawText.isBlank()) return

    resolved = false
    _uiState.value = InventoryUiState.Processing

    val startMs = SystemClock.elapsedRealtime()

    viewModelScope.launch {
        try {
            runner.runAsync(
                userId     = "store-manager",
                sessionId  = "session-${System.currentTimeMillis()}",  // fresh session every time
                newMessage = Content(
                    role  = Role.USER,
                    parts = listOf(Part(text = rawText))
                )
            )
            .catch  { e -> resolveWithError(e.message ?: "Runner error") }
            .takeWhile { !resolved }          // auto-cancels once tool fires
            .collect { event ->
                // Update debug panel
                _debugState.value = _debugState.value?.copy(
                    tokenCount = (_debugState.value?.tokenCount ?: 0) + 1,
                    elapsedMs  = SystemClock.elapsedRealtime() - startMs
                )
                // If turn complete but tool never fired → show informative error
                if (event.turnComplete && !resolved) {
                    resolveWithError(
                        "The agent did not register any products.\n" +
                        "Try: \"I received 12 Cokes and 5 bags of chips\""
                    )
                }
            }
        } catch (e: Exception) {
            resolveWithError(e.message ?: "Unexpected error")
        }
    }
}
```

> **Note:** Using a fresh `sessionId` per request prevents `InMemorySessionService` from
> accumulating stale conversation history, which would cause the loop breaker to misfire.

---

## The Two-Turn Protocol Explained
Duration: 10

Understanding what happens inside the ADK runner is critical to avoid bugs like infinite loops.

### Complete execution flow

```
User  →  ViewModel.processText("I got 12 Cokes")
              │
              ▼  runner.runAsync()

━━━━━━━━━━━━━━━ TURN 1 ━━━━━━━━━━━━━━━
InMemoryRunner → LiteRtAdkModel.generateContent(request)
    // isPostToolTurn = false → proceed with LiteRT
LiteRtAdkModel → LocalInferenceEngine.generate(systemPrompt, "I got 12 Cokes")
    // Gemma 4 E2B generates offline:
    {"tool_call":{"name":"registrarEntradaInventario","arguments":{...}}}
LiteRtAdkModel → emit(LlmResponse(Part(functionCall=FunctionCall(...))))
InMemoryRunner detects FunctionCall part in response
InMemoryRunner → InventoryTools.registrarEntradaInventario(jsonProductos)
    // Kotlin function runs, parses JSON
    onItemsParsed(items) → ViewModel.resolveWithItems() → UI updated ✅

━━━━━━━━━━━━━━━ TURN 2 ━━━━━━━━━━━━━━━
InMemoryRunner appends FunctionResponse to history, calls model again
LiteRtAdkModel.generateContent(request with FunctionResponse)
    // isPostToolTurn = TRUE → SHORT CIRCUIT, LiteRT is NOT called
    emit(LlmResponse("Inventory registered successfully."))
InMemoryRunner emits Event(turnComplete=true)
ViewModel: resolved=true → takeWhile cancels flow collection ✅
```

### Why the loop breaker is essential

| Turn | History contains | Action | Result |
|------|-----------------|--------|--------|
| 1 | User message only | Call LiteRT → Gemma generates tool call | `FunctionCall` part emitted |
| 2 | User + FunctionCall + **FunctionResponse** | **SHORT CIRCUIT** — LiteRT skipped | Confirmation text, flow ends |

Without the `isPostToolTurn` guard, Turn 2 would call `LocalInferenceEngine.generate()` with the full
history including the tool-call example. Gemma would see that example and generate **another**
`{"tool_call": ...}`, creating an **infinite loop**.

> **Warning:** If you remove the `isPostToolTurn` check, the app will appear to "freeze" while
> Gemma runs in an infinite loop consuming 100% CPU. Always include the loop breaker when
> bridging a local LLM to ADK tool calling.

---

## Build & Run
Duration: 5

### 1. Place the model file

Copy your `gemma-4-E2B-it.litertlm` to:
```
app/src/main/assets/gemma-4-E2B-it.litertlm
```

> **Warning:** The model is ~3–6 GB. **Never commit it to Git.** Add `*.litertlm` to `.gitignore`.

### 2. Build the project

```bash
./gradlew :app:assembleDebug
```

### 3. Install and launch

```bash
./gradlew :app:installDebug
```

### 4. Try these prompts

Once the model loads (~8 seconds), enter these phrases in the text field:

| Input | Expected result |
|-------|----------------|
| "I received 12 Cokes 20oz and 5 bags of Lays chips" | 2 items: Beverages + Food |
| "Llegaron 6 detergentes Ariel y 3 jabones Dove" | 2 items: Cleaning category |
| "Me llegaron 24 aguas Evian" | 1 item: Beverages |

### 5. Check Logcat for the ADK flow

```bash
adb logcat -s InventoryViewModel Gemma4Debug
```

You should see:
```
D InventoryViewModel: ADK event — turnComplete=false functionCalls=1
D InventoryViewModel: onItemsParsed fired — 2 items received from ADK tool
D InventoryViewModel: ADK event — turnComplete=true functionCalls=0
```

---

## Congratulations!
Duration: 2

### You built an offline AI agent on Android! 🏆

You connected Google ADK to a local Gemma 4 E2B model running on-device using LiteRT —
with real tool calling, no internet, and a production-ready two-turn protocol.

### What you accomplished

- ✅ Integrated **Google ADK Kotlin SDK 0.1.0** into an Android project
- ✅ Implemented the **ADK `Model` interface** for a local LLM
- ✅ Defined **`@Tool` functions** that the agent calls autonomously
- ✅ Used **`InMemoryRunner`** to orchestrate multi-turn agent loops
- ✅ Solved the **infinite loop problem** with a post-tool short-circuit
- ✅ Connected ADK events to a **Jetpack Compose UI** via `StateFlow`

### Architecture summary

| Layer | Class | Responsibility |
|-------|-------|----------------|
| UI | `SmartInventoryScreen` | Compose UI observing StateFlow |
| ViewModel | `InventoryViewModel` | Owns runner, exposes UI state |
| ADK Orchestration | `InMemoryRunner` | Drives the agent loop |
| ADK Agent | `LlmAgent` | Agent definition + tools |
| ADK Model Bridge | `LiteRtAdkModel` | Translates ADK ↔ LiteRT |
| Local Inference | `LiteRtInferenceEngine` | Runs Gemma 4 E2B on-device |
| Tools | `InventoryTools` | `@Tool` Kotlin functions |
| Repository | `LocalInventoryRepository` | Engine lifecycle management |

### What's next?

- **Add a Room database** — persist inventory items across sessions
- **Add more `@Tool` functions** — update stock, generate reports, search by SKU
- **Enable GPU/NPU acceleration** — set `EngineConfig.accelerator = GPU`
- **Try a larger model** — swap Gemma 4 4B for higher accuracy
- **Add streaming tokens to the UI** — pass `onToken` up to the Compose layer for a typewriter effect

### Resources

- [ADK Documentation](https://google.github.io/adk-docs/)
- [Google AI Edge LiteRT](https://ai.google.dev/edge/litert)
- [Gemma models on Hugging Face](https://huggingface.co/collections/google/gemma-3-67573858b04a18d4cf94a3c5)
- [More Google Codelabs](https://codelabs.developers.google.com)
