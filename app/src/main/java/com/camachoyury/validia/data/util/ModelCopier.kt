package com.camachoyury.validia.data.util

import android.content.Context

/**
 * ═══════════════════════════════════════════════════════════════
 * PASO 6 — Copia el modelo al almacenamiento interno
 * ═══════════════════════════════════════════════════════════════
 *
 * El SDK LiteRT-LM requiere una ruta absoluta en el filesystem.
 * Los assets de Android no tienen ruta física — solo se pueden
 * abrir como InputStream. Esta clase realiza la copia exactamente
 * una vez (idempotente).
 *
 * ¿Qué implementar?
 *  1. Verificar si el archivo ya existe en filesDir (si existe, retornar su ruta)
 *  2. Abrir el asset como InputStream con context.assets.open(assetName)
 *  3. Escribirlo en filesDir con FileOutputStream usando un buffer de 8 KB
 *  4. Retornar la ruta absoluta del archivo copiado
 *
 * Tip: Usá .use { } para cerrar los streams automáticamente.
 * Tip: Ejecutá todo en withContext(Dispatchers.IO).
 */
object ModelCopier {

    // TODO Paso 6.1 — Declarar la constante BUFFER_SIZE = 8 * 1024

    /**
     * Copia el asset a filesDir si no existe.
     * @param assetName Nombre del archivo en assets/ (ej: "gemma-4-E2B-it.litertlm")
     * @return Ruta absoluta del archivo en filesDir.
     */
    suspend fun ensureModelReady(
        context: Context,
        assetName: String
    ): String {
        // TODO Paso 6.2 — Implementar la lógica de copia
        //
        // val destFile = File(context.filesDir, assetName)
        // if (!destFile.exists()) { ... copiar ... }
        // return destFile.absolutePath

        TODO("Paso 6: Implementar la copia de assets/ a filesDir")
    }
}
