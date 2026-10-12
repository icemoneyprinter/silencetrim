package com.icemoneyprinter.track.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.icemoneyprinter.track.model.ImageItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.MessageDigest

/** The narration file found in the folder (or null). */
data class NarrationInfo(
    val uri: Uri,
    val displayName: String,
    val isWav: Boolean,
)

/** What the scanner pulls out of the user's folder (plan M1 ingest). */
data class ScanResult(
    val images: List<ImageItem>,
    val narration: NarrationInfo?,
    val audioCandidates: List<NarrationInfo>,
    val scriptPresent: Boolean,
)

/**
 * SAF folder ingest. The user's contract (D6): any image names, one narration file,
 * optional script.txt — no renaming, ever. Ordering defaults to last-modified (fallback
 * for missing EXIF; EXIF parsing is cheap to add later but mtime matches the real
 * "collect images over an afternoon" workflow).
 */
object FolderScanner {

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "bmp")
    private val AUDIO_EXTENSIONS = setOf("wav", "mp3", "m4a", "aac", "opus", "ogg")
    private val SCRIPT_NAMES = setOf("script.txt", "script.md")

    suspend fun scan(
        context: Context,
        treeUri: Uri,
        onProgress: (scanned: Int, total: Int) -> Unit,
    ): ScanResult = withContext(Dispatchers.IO) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        data class Candidate(
            val uri: Uri,
            val name: String,
            val size: Long,
            val lastModified: Long,
        )

        val images = ArrayList<Candidate>()
        val audio = ArrayList<Candidate>()
        var scriptPresent = false

        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val modIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

            while (c.moveToNext()) {
                val docId = c.getString(idIdx) ?: continue
                val name = c.getString(nameIdx) ?: continue
                val mime = c.getString(mimeIdx) ?: ""
                val size = c.getLong(sizeIdx)
                val modified = c.getLong(modIdx)
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                val ext = name.substringAfterLast('.', "").lowercase()

                when {
                    mime.startsWith("image/") || ext in IMAGE_EXTENSIONS ->
                        images.add(Candidate(uri, name, size, modified))
                    mime.startsWith("audio/") || ext in AUDIO_EXTENSIONS ->
                        audio.add(Candidate(uri, name, size, modified))
                    name.lowercase() in SCRIPT_NAMES -> scriptPresent = true
                }
            }
        }

        // Sort by collection order: last-modified, name as tiebreaker.
        images.sortWith(compareBy({ it.lastModified }, { it.name }))

        // Content-hash dedupe (folder dumps always contain copies). This is the heavy
        // part, so it runs with progress surfaced to the UI.
        val seen = HashSet<String>()
        val deduped = ArrayList<ImageItem>()
        images.forEachIndexed { i, cand ->
            val hash = hashMd5(context.contentResolver.openInputStream(cand.uri))
            onProgress(i + 1, images.size)
            if (hash != null && seen.add(hash)) {
                deduped.add(
                    ImageItem(
                        uriString = cand.uri.toString(),
                        displayName = cand.name,
                        contentHash = hash,
                        sizeBytes = cand.size,
                        lastModified = cand.lastModified,
                    ),
                )
            }
        }

        val sorted = audio
            .sortedWith(
                compareByDescending<Candidate> { it.name.lowercase().endsWith(".wav") }
                    .thenByDescending { it.size },
            )
            .map { NarrationInfo(it.uri, it.name, it.name.lowercase().endsWith(".wav")) }

        ScanResult(
            images = deduped,
            narration = sorted.firstOrNull(),
            audioCandidates = sorted,
            scriptPresent = scriptPresent,
        )
    }

    private fun hashMd5(input: InputStream?): String? {
        if (input == null) return null
        val md = MessageDigest.getInstance("MD5")
        input.use { stream ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
