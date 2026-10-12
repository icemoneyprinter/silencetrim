package com.icemoneyprinter.track.audio

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Loads script.txt from the user's folder. Optional: it powers the scrolling script
 * during guided tapping and sentence display in the filmstrip.
 */
object ScriptLoader {

    private val NAMES = listOf("script.txt", "script.md")

    fun load(context: Context, treeUri: Uri): List<String>? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (c.moveToNext()) {
                val name = c.getString(nameIdx)?.lowercase() ?: continue
                if (name !in NAMES) continue
                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(idIdx))
                val text = context.contentResolver.openInputStream(docUri)?.use { stream ->
                    BufferedReader(InputStreamReader(stream)).use { it.readText() }
                } ?: continue
                val sentences = text
                    .split(Regex("[.!?]+\\s+|\\n+"))
                    .map { it.trim() }
                    .filter { it.length >= 3 }
                if (sentences.isNotEmpty()) return sentences
            }
        }
        return null
    }
}
