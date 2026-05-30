summary: Construye un Agente de Inventario con IA Offline en Android con Google ADK + Gemma 4 E2B
id: offline-inventory-agent-adk-gemma-es
categories: Android, IA, Kotlin
status: Publicado
feedback link: https://github.com/camachoyury/validia/issues
tags: android, adk, gemma, litert, on-device-ai, kotlin, jetpack-compose

# Agente de Inventario con IA Offline usando Google ADK + Gemma en Android

## Descripción General
Duration: 10

¡Bienvenido a este codelab! Aprenderás cómo construir un **agente de inventario con IA completamente offline**
en Android usando el **Agent Development Kit (ADK) de Google** y **Gemma 4 E2B** ejecutándose localmente
mediante **LiteRT** (el nuevo nombre de TensorFlow Lite / Google AI Edge).

En este laboratorio construirás **Validia** — un asistente de inventario B2B inteligente para pequeños
comerciantes. Un proveedor llega y anuncia verbalmente su entrega (ej., _"Me llegaron 12 Cokes y 5 bolsas de papas"_).
La app extrae los productos, cantidades y categorías de ese texto en lenguaje natural — completamente
offline, sin necesidad de internet — y los registra en el inventario.

### Lo que Aprenderás

- Cómo integrar el **SDK Kotlin de Google ADK** en un proyecto Android
- Cómo implementar la **interfaz `Model` del ADK** para envolver un LLM local
- Cómo definir **herramientas del agente** usando las anotaciones `@Tool` y `@Param`
- Cómo usar **`InMemoryRunner`** para orquestar un loop de agente multi-turno
- Cómo manejar el **protocolo de dos turnos** y prevenir loops infinitos
- Cómo conectar la salida del ADK a una **UI de Jetpack Compose** mediante `StateFlow`

### Lo que Necesitarás

- Android Studio Narwhal (2025.1.1) o posterior
- Dispositivo Android o emulador con ABI `arm64-v8a` y API ≥ 26
- El archivo de modelo **Gemma 4 E2B** `.litertlm` (~3–6 GB, descargado por separado)
- Conocimientos básicos de corrutinas Kotlin y Jetpack Compose

---

## Configuración y Dependencias
Duration: 5

Configuremos los archivos de Gradle para incluir el SDK del ADK y el procesador de anotaciones KSP.

### 1. Agregar versiones del ADK al catálogo de versiones

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

### 2. Aplicar plugins y dependencias

`app/build.gradle.kts`
```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)          // KSP procesa las anotaciones @Tool en tiempo de compilación
}

android {
    defaultConfig {
        minSdk = 26                  // ADK requiere API 26+
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    // Evita la doble compresión de los archivos del modelo
    androidResources {
        noCompress += "litertlm"
        noCompress += "task"
    }

    packaging {
        resources {
            excludes += "META-INF/INDEX.LIST"   // evita error de archivo duplicado
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    implementation(libs.adk.core.android)    // Runtime del ADK
    ksp(libs.adk.processor)                  // Procesador de anotaciones KSP para @Tool
    implementation(libs.litert.lm.android)   // Google AI Edge — inferencia local
    // ... Compose, Lifecycle, etc.
}
```

### 3. Solucionar el conflicto de source-sets de AGP 9.x

Android Gradle Plugin 9.x ya no permite que plugins de terceros modifiquen los source sets de Kotlin
directamente. Agrega esta bandera a `gradle.properties`:

```properties
android.disallowKotlinSourceSets=false
```

> **Advertencia:** Sin esta bandera, el registro de fuentes generadas por KSP lanzará una
> `ProjectConfigurationException` durante la configuración en AGP 9.x.

---

## Cargar el Modelo Gemma
Duration: 10

Antes de conectar el ADK, necesitamos que el motor de inferencia local cargue Gemma 4 E2B en la RAM del
dispositivo. Abstraemos esto detrás de una interfaz `LocalInferenceEngine` para poder intercambiar
implementaciones o hacer mocks en tests.

### La interfaz `LocalInferenceEngine`

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

### La implementación `LiteRtInferenceEngine`

`data/ai/LiteRtInferenceEngine.kt`
```kotlin
class LiteRtInferenceEngine(private val context: Context) : LocalInferenceEngine {

    private var engine: Engine? = null

    override suspend fun initialize(modelPath: String) {
        // Copia el .litertlm de assets → filesDir (solo en el primer inicio)
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

### El Repositorio — gestor puro del ciclo de vida

`data/litert/LocalInventoryRepository.kt`
```kotlin
class LocalInventoryRepository(internal val engine: LocalInferenceEngine) {

    suspend fun initializeModel(modelPath: String) =
        withContext(Dispatchers.IO) { engine.initialize(modelPath) }

    fun closeModel() = engine.close()
}
```

> **Nota:** `LocalInventoryRepository` ya no contiene ingeniería de prompts ni parseo de JSON.
> Esa responsabilidad ahora vive en las herramientas ADK y en `LiteRtAdkModel`.

---

## Construir el Adaptador del Modelo ADK
Duration: 15

El runtime del ADK funciona con cualquier modelo que implemente la interfaz `Model` de
`com.google.adk.kt.models`. Creamos `LiteRtAdkModel` para hacer de puente entre el protocolo
del ADK y nuestro `LocalInferenceEngine` local.

### El contrato general

El ADK llama a `generateContent(request, stream)` y espera de vuelta un `Flow<LlmResponse>`.
La respuesta puede contener:
- Una **parte de texto** — la respuesta conversacional del modelo
- Una **parte `FunctionCall`** — una solicitud para invocar una herramienta

### Implementación completa

`data/ai/LiteRtAdkModel.kt`
```kotlin
class LiteRtAdkModel(private val localEngine: LocalInferenceEngine) : Model {

    override val name: String = "gemma-4-e2b-local"

    override fun generateContent(
        request: LlmRequest,
        stream: Boolean
    ): Flow<LlmResponse> = flow {

        val messages = request.contents

        // ── CORTADOR DE LOOP ─────────────────────���────────────────────────────
        // Si algún mensaje ya contiene un FunctionResponse, el runner del ADK está
        // en su segundo turno (post-herramienta). Retornar texto plano de inmediato
        // señala "listo" — NO llamar a LiteRT de nuevo, o Gemma regenerará otro
        // tool call y creará un loop infinito.
        val isPostToolTurn = messages.any { content ->
            content.parts.any { it.functionResponse != null }
        }
        if (isPostToolTurn) {
            emit(confirmationResponse())
            return@flow
        }

        // ── TURNO 1: inferencia normal ────────────────────────────────────────
        val systemPrompt = buildSystemPrompt(
            baseInstruction = request.config?.systemInstruction?.parts?.firstOrNull()?.text,
            tools           = request.config?.tools?.flatMap { it.functionDeclarations ?: emptyList() }
        )
        val userInput    = serializeHistory(messages)
        val rawResponse  = localEngine.generate(systemPrompt, userInput, onToken = {})

        // Detectar el patrón JSON de tool call en la salida de Gemma
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

### El helper `buildSystemPrompt()`

Aquí le enseñamos a Gemma el formato JSON exacto que debe generar para disparar un tool call:

```kotlin
private fun buildSystemPrompt(baseInstruction: String?, tools: List<FunctionDeclaration>?): String {
    val sb = StringBuilder(baseInstruction ?: "Eres un asistente de IA útil.")
    if (tools.isNullOrEmpty()) return sb.toString()

    sb.append("""
        
        === CÓMO LLAMAR UNA HERRAMIENTA ===
        Devuelve SOLO este JSON (sin markdown, sin texto extra):
        {"tool_call": {"name": "<nombre_herramienta>", "arguments": {"<param>": "<valor>"}}}
        
        === EJEMPLO ===
        Usuario: "Llegaron 12 Cokes y 5 bolsas de papas"
        Tu respuesta:
        {"tool_call": {"name": "registrarEntradaInventario",
          "arguments": {"jsonProductos": "[{\"quantity\":12,...}]"}}}
        
        === HERRAMIENTAS ===
    """.trimIndent())

    tools.forEach { decl ->
        sb.append("\n• ${decl.name}: ${decl.description}")
        decl.parameters?.properties?.forEach { (param, schema) ->
            sb.append("\n    - $param (${schema.type}): ${schema.description}")
        }
    }

    sb.append("\n\nREGLA: Extrae productos y llama a 'registrarEntradaInventario' inmediatamente. NUNCA pidas más información.")
    return sb.toString()
}
```

> **Crítico:** El **cortador de loop** (verificación `isPostToolTurn`) es la parte más importante.
> Sin él, el Turno 2 pasa el historial completo (incluido el JSON de ejemplo) de vuelta a Gemma,
> lo que hace que genere otro tool call — creando un **loop infinito**.

---

## Definir el Agente y las Herramientas
Duration: 10

Las herramientas del ADK son funciones Kotlin normales anotadas con `@Tool` y `@Param`. KSP lee
estas anotaciones en tiempo de compilación y genera las declaraciones de función JSON que el
runtime del ADK usa para describir las capacidades disponibles al LLM.

### Crear `InventoryTools`

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
            onItemsParsed(items)        // ← dispara el callback del ViewModel
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

> **Clave:** El callback `onItemsParsed` es el puente entre el mundo del ADK y el ViewModel.
> Cuando el agente llama a `registrarEntradaInventario`, la función Kotlin se ejecuta, parsea el JSON
> y dispara el callback con objetos `InventoryItem` tipados — no se necesita parseo de strings en el ViewModel.

### Crear el `LlmAgent`

```kotlin
object InventoryAgentFactory {
    fun createRootAgent(adkModel: LiteRtAdkModel, tools: InventoryTools): LlmAgent {
        return LlmAgent(
            name        = "inventory_agent",
            description = "Agente de recepción de inventarios offline B2B.",
            model       = adkModel,
            instruction = Instruction("""
                Eres un asistente de inventario offline para una tienda.

                TU ÚNICO TRABAJO: Extraer productos del texto del usuario, construir el JSON
                y llamar INMEDIATAMENTE a la herramienta 'registrarEntradaInventario'.

                REGLAS CRÍTICAS:
                - NUNCA pidas más información al usuario.
                - NUNCA digas que no puedes procesar la solicitud.
                - 'category' DEBE ser exactamente: "Beverages", "Food" o "Cleaning".
                - Llama la herramienta en la PRIMERA respuesta. Sin texto antes ni después.
            """.trimIndent()),
            tools = tools.generatedTools()    // Generado por KSP a partir de las anotaciones @Tool
        )
    }
}
```

> `tools.generatedTools()` es una función de extensión generada por KSP. El procesador KSP lee todos
> los métodos anotados con `@Tool` en `InventoryTools` en tiempo de compilación y genera automáticamente
> los descriptores de esquema JSON y el código de dispatch.

---

## Conectar el Runner del ADK en el ViewModel
Duration: 15

El `InventoryViewModel` conecta todo: posee el `InMemoryRunner`, expone el estado de la UI como
`StateFlow` y conecta el callback de la herramienta de vuelta a la UI de Compose.

### Configuración del ADK

`presentation/inventory/InventoryViewModel.kt`
```kotlin
class InventoryViewModel(
    private val repository: LocalInventoryRepository,
    private val modelPath: String
) : ViewModel() {

    // Puente de callback: ejecución de herramienta ADK → actualización de UI del ViewModel
    private val inventoryTools = InventoryTools { items ->
        resolveWithItems(items)         // se dispara en cuanto corre la herramienta
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
    // ... declaraciones de StateFlow
}
```

### Ejecutar el agente con el input del usuario

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
                sessionId  = "session-${System.currentTimeMillis()}",  // sesión nueva cada vez
                newMessage = Content(
                    role  = Role.USER,
                    parts = listOf(Part(text = rawText))
                )
            )
            .catch  { e -> resolveWithError(e.message ?: "Error del runner") }
            .takeWhile { !resolved }          // se cancela automáticamente cuando dispara la herramienta
            .collect { event ->
                // Actualizar panel de debug
                _debugState.value = _debugState.value?.copy(
                    tokenCount = (_debugState.value?.tokenCount ?: 0) + 1,
                    elapsedMs  = SystemClock.elapsedRealtime() - startMs
                )
                // Si el turno completa pero nunca se llamó la herramienta → mostrar error informativo
                if (event.turnComplete && !resolved) {
                    resolveWithError(
                        "El agente no registró ningún producto.\n" +
                        "Intenta: \"Llegaron 12 Cokes y 5 bolsas de papas\""
                    )
                }
            }
        } catch (e: Exception) {
            resolveWithError(e.message ?: "Error inesperado")
        }
    }
}
```

> **Nota:** Usar un `sessionId` nuevo por solicitud (mediante `System.currentTimeMillis()`) evita que
> `InMemorySessionService` acumule historial de conversación obsoleto, lo que dispararía el cortador
> de loop antes de tiempo.

---

## El Protocolo de Dos Turnos Explicado
Duration: 10

Entender qué sucede dentro del runner del ADK es crítico para evitar bugs como los loops infinitos.

### Flujo completo de ejecución

```
Usuario  →  ViewModel.processText("Llegaron 12 Cokes")
              │
              ▼  runner.runAsync()

━━━━━━━━━━━━━━━ TURNO 1 ━━━━━━━━━━━━━━━
InMemoryRunner → LiteRtAdkModel.generateContent(request)
    // isPostToolTurn = false → proceder con LiteRT
LiteRtAdkModel → LocalInferenceEngine.generate(systemPrompt, "Llegaron 12 Cokes")
    // Gemma 4 E2B genera offline:
    {"tool_call":{"name":"registrarEntradaInventario","arguments":{...}}}
LiteRtAdkModel → emit(LlmResponse(Part(functionCall=FunctionCall(...))))
InMemoryRunner detecta la parte FunctionCall en la respuesta
InMemoryRunner → InventoryTools.registrarEntradaInventario(jsonProductos)
    // La función Kotlin se ejecuta, parsea el JSON
    onItemsParsed(items) → ViewModel.resolveWithItems() → UI actualizada ✅

━━━━━━━━━━━━━━━ TURNO 2 ━━━━━━━━━━━━━━━
InMemoryRunner agrega FunctionResponse al historial y llama al modelo de nuevo
LiteRtAdkModel.generateContent(request con FunctionResponse)
    // isPostToolTurn = VERDADERO → CORTOCIRCUITO, LiteRT NO se llama
    emit(LlmResponse("Inventario registrado exitosamente."))
InMemoryRunner emite Event(turnComplete=true)
ViewModel: resolved=true → takeWhile cancela la recolección del flow ✅
```

### Por qué el cortador de loop es esencial

| Turno | El historial contiene | Acción | Resultado |
|-------|----------------------|--------|-----------|
| 1 | Solo mensaje del usuario | Llamar LiteRT → Gemma genera tool call | Parte `FunctionCall` emitida |
| 2 | Usuario + FunctionCall + **FunctionResponse** | **CORTOCIRCUITO** — LiteRT omitido | Texto de confirmación, flow termina |

Sin la guardia `isPostToolTurn`, el Turno 2 llamaría a `LocalInferenceEngine.generate()` con el historial
completo incluyendo el ejemplo de tool call. Gemma vería ese ejemplo y generaría **otro**
`{"tool_call": ...}`, creando un **loop infinito**.

> **Advertencia:** Si eliminas la verificación `isPostToolTurn`, la app parecerá "congelada" mientras
> Gemma corre en un loop infinito consumiendo el 100% de la CPU. Siempre incluye el cortador de loop
> cuando conectes un LLM local al tool calling del ADK.

---

## Compilar y Ejecutar
Duration: 5

### 1. Colocar el archivo del modelo

Copia tu `gemma-4-E2B-it.litertlm` a:
```
app/src/main/assets/gemma-4-E2B-it.litertlm
```

> **Advertencia:** El modelo pesa ~3–6 GB. **Nunca lo subas a Git.** Agrega `*.litertlm` a `.gitignore`.

### 2. Compilar el proyecto

```bash
./gradlew :app:assembleDebug
```

### 3. Instalar y lanzar

```bash
./gradlew :app:installDebug
```

### 4. Prueba estos prompts

Una vez que el modelo cargue (~8 segundos), ingresa estas frases en el campo de texto:

| Entrada | Resultado esperado |
|---------|-------------------|
| "Llegaron 12 Cokes 20oz y 5 bolsas de papas Lays" | 2 ítems: Beverages + Food |
| "Llegaron 6 detergentes Ariel y 3 jabones Dove" | 2 ítems: categoría Cleaning |
| "Me llegaron 24 aguas Evian" | 1 ítem: Beverages |

### 5. Verificar el flujo del ADK en Logcat

```bash
adb logcat -s InventoryViewModel Gemma4Debug
```

Deberías ver:
```
D InventoryViewModel: ADK event — turnComplete=false functionCalls=1
D InventoryViewModel: onItemsParsed fired — 2 items received from ADK tool
D InventoryViewModel: ADK event — turnComplete=true functionCalls=0
```

---

## ¡Felicitaciones!
Duration: 2

### ¡Construiste un agente de IA offline en Android! 🏆

Conectaste Google ADK a un modelo Gemma 4 E2B corriendo en el dispositivo con LiteRT —
con tool calling real, sin internet, y un protocolo de dos turnos listo para producción.

### Lo que lograste

- ✅ Integrar el **SDK Kotlin de Google ADK 0.1.0** en un proyecto Android
- ✅ Implementar la **interfaz `Model` del ADK** para un LLM local
- ✅ Definir **funciones `@Tool`** que el agente llama autónomamente
- ✅ Usar **`InMemoryRunner`** para orquestar loops de agente multi-turno
- ✅ Resolver el **problema del loop infinito** con un cortocircuito post-herramienta
- ✅ Conectar los eventos del ADK a una **UI de Jetpack Compose** mediante `StateFlow`

### Resumen de arquitectura

| Capa | Clase | Responsabilidad |
|------|-------|----------------|
| UI | `SmartInventoryScreen` | UI de Compose observando StateFlow |
| ViewModel | `InventoryViewModel` | Posee el runner, expone el estado de UI |
| Orquestación ADK | `InMemoryRunner` | Impulsa el loop del agente |
| Agente ADK | `LlmAgent` | Definición del agente + herramientas |
| Puente Modelo ADK | `LiteRtAdkModel` | Traduce ADK ↔ LiteRT |
| Inferencia Local | `LiteRtInferenceEngine` | Ejecuta Gemma 4 E2B en el dispositivo |
| Herramientas | `InventoryTools` | Funciones Kotlin con `@Tool` |
| Repositorio | `LocalInventoryRepository` | Gestión del ciclo de vida del motor |

### ¿Qué sigue?

- **Agregar una base de datos Room** — persistir los ítems de inventario entre sesiones
- **Agregar más funciones `@Tool`** — actualizar stock, generar reportes, buscar por SKU
- **Habilitar aceleración GPU/NPU** — configurar `EngineConfig.accelerator = GPU`
- **Probar un modelo más grande** — cambiar a Gemma 4 4B para mayor precisión
- **Agregar tokens en streaming a la UI** — pasar `onToken` hasta la capa de Compose para efecto de máquina de escribir

### Recursos

- [Documentación del ADK](https://google.github.io/adk-docs/)
- [Google AI Edge LiteRT](https://ai.google.dev/edge/litert)
- [Modelos Gemma en Hugging Face](https://huggingface.co/collections/google/gemma-3-67573858b04a18d4cf94a3c5)
- [Más Google Codelabs](https://codelabs.developers.google.com)
