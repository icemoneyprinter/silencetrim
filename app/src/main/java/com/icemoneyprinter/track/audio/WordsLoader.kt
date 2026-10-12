package com.icemoneyprinter.track.audio

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.BufferedReader
import java.io.InputStreamReader

/** Loads the optional words.json timing file from the user's folder. */
object WordsLoader {

    fun load(context: Context, treeUri: Uri): WordsData? = loadTree(context, treeUri)

    fun loadDir(dir: java.io.File?): WordsData? {
        val file = java.io.File(dir ?: return null, "words.json")
        if (!file.exists()) return null
        return runCatching { WordsJson.parse(file.readText()) }.getOrNull()
    }

    private fun loadTree(context: Context, treeUri: Uri): WordsData? {
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
                if (c.getString(nameIdx)?.lowercase() != "words.json") continue
                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(idIdx))
                val text = context.contentResolver.openInputStream(docUri)?.use { stream ->
                    BufferedReader(InputStreamReader(stream)).use { it.readText() }
                } ?: continue
                return WordsJson.parse(text)
            }
        }
        return null
    }
}
