package com.camachoyury.validia.data.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Utility for copying the Gemma model from assets/ to internal storage.
 *
 * Why is this needed?
 * LiteRT-LM `Engine` requires an absolute path on the filesystem.
 * Android assets don't have a directly accessible physical path — they can only
 * be opened as an InputStream. This class performs that copy exactly once.
 *
 * Optimizations:
 * - If the file already exists in filesDir, it is not copied again (idempotent).
 * - Uses streaming (8 KB buffer) to handle large files (>1 GB) without OOM.
 * - The copy runs on [Dispatchers.IO] to avoid blocking the main thread.
 */
object ModelCopier {

    private const val BUFFER_SIZE = 8 * 1024 // 8 KB

    /**
     * Copies the asset to internal storage if it does not already exist.
     *
     * @param context   Application context.
     * @param assetName Name of the file in assets/ (e.g. "gemma-4-E2B-it.litertlm").
     * @return Absolute path to the copied file in filesDir.
     */
    suspend fun ensureModelReady(
        context: Context,
        assetName: String
    ): String = withContext(Dispatchers.IO) {
        val destFile = File(context.filesDir, assetName)

        if (!destFile.exists()) {
            context.assets.open(assetName).use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                    output.flush()
                }
            }
        }

        destFile.absolutePath
    }
}
