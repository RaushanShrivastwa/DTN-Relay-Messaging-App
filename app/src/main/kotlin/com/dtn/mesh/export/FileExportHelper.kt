package com.dtn.mesh.export

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes [ResearchExportBundle] to device storage.
 *
 * Files are written to the app's external files directory under `exports/<timestamp>/`.
 * Retrievable via `adb pull /sdcard/Android/data/com.dtn.mesh/files/exports/`.
 */
object FileExportHelper {

    private const val TAG = "FileExport"

    /**
     * Write all export data to timestamped directory.
     * @return The directory path where files were written, or null on failure.
     */
    fun writeToStorage(context: Context, bundle: ResearchExportBundle): String? {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val exportDir = File(context.getExternalFilesDir(null), "exports/$timestamp")

        if (!exportDir.mkdirs() && !exportDir.exists()) {
            Log.e(TAG, "Failed to create export directory: ${exportDir.absolutePath}")
            return null
        }

        return try {
            File(exportDir, "encounters.csv").writeText(bundle.encountersCsv)
            File(exportDir, "contacts.csv").writeText(bundle.contactsCsv)
            File(exportDir, "decisions.csv").writeText(bundle.decisionsCsv)
            File(exportDir, "messages.csv").writeText(bundle.messagesCsv)
            File(exportDir, "q_tables.json").writeText(bundle.qTableJson)
            File(exportDir, "metadata.json").writeText(bundle.metadata)

            Log.i(TAG, "Export written to ${exportDir.absolutePath}")
            exportDir.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Export write failed", e)
            null
        }
    }
}
