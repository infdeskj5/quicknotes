package com.infdesk5.quicknotes.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.infdesk5.quicknotes.model.Note
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ExternalNoteRepository(
    private val context: Context,
    private var treeUri: Uri?
) : NoteRepository {

    private var permissionCallback: ((Boolean) -> Unit)? = null

    override suspend fun listNotes(): List<Note> = withContext(Dispatchers.IO) {
        val tree = getTree() ?: return@withContext emptyList()

        try {
            tree.listFiles()
                .filter { it.isFile && isTextFile(it) }
                .sortedByDescending { it.lastModified() }
                .map { doc ->
                    Note(
                        id = doc.name ?: doc.uri.toString(),
                        name = doc.name ?: "unknown",
                        uri = doc.uri,
                        lastModified = doc.lastModified()
                    )
                }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun readNote(note: Note): String? = withContext(Dispatchers.IO) {
        try {
            note.uri?.let { uri ->
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun writeNote(note: Note, content: String): Boolean = withContext(Dispatchers.IO) {
        val uri = note.uri ?: return@withContext false

        try {
            val outputStream = try {
                context.contentResolver.openOutputStream(uri, "wt")
            } catch (e: Exception) {
                context.contentResolver.openOutputStream(uri)
            }

            outputStream?.use {
                it.write(content.toByteArray(Charsets.UTF_8))
                it.flush()
            }

            outputStream != null
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun createNote(name: String): Note? = withContext(Dispatchers.IO) {
        val tree = getTree() ?: return@withContext null

        try {
            val doc = tree.createFile("text/plain", name) ?: return@withContext null

            Note(
                id = doc.name ?: doc.uri.toString(),
                name = doc.name ?: name,
                uri = doc.uri,
                lastModified = doc.lastModified()
            )
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun deleteNote(note: Note): Boolean = withContext(Dispatchers.IO) {
        val uri = note.uri ?: return@withContext false

        try {
            DocumentFile.fromSingleUri(context, uri)?.delete() ?: false
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun renameNote(note: Note, newName: String): Boolean = withContext(Dispatchers.IO) {
        val uri = note.uri ?: return@withContext false

        try {
            val doc = DocumentFile.fromSingleUri(context, uri) ?: return@withContext false
            val success = doc.renameTo(newName)

            if (success) {
                note.uri = doc.uri
                note.name = newName
            }

            success
        } catch (e: Exception) {
            false
        }
    }

    override fun hasPermission(): Boolean {
        val uri = treeUri ?: return false

        return try {
            context.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission && it.isWritePermission
            }
        } catch (e: Exception) {
            false
        }
    }

    override fun requestPermission(callback: (Boolean) -> Unit) {
        permissionCallback = callback
    }

    fun handlePermissionResult(uri: Uri?): Boolean {
        if (uri == null) {
            permissionCallback?.invoke(false)
            return false
        }

        var readGranted = false
        var writeGranted = false

        // Try read permission separately.
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            readGranted = true
        } catch (_: Exception) {
        }

        // Try write permission separately.
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            writeGranted = true
        } catch (_: Exception) {
        }

        // Fallback: some devices prefer the combined call.
        if (!readGranted || !writeGranted) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                readGranted = true
                writeGranted = true
            } catch (_: Exception) {
            }
        }

        treeUri = uri

        val success = hasPermission() || (readGranted && writeGranted)
        permissionCallback?.invoke(success)
        return success
    }

    fun getTree(): DocumentFile? {
        return try {
            treeUri?.let { DocumentFile.fromTreeUri(context, it) }
        } catch (e: Exception) {
            null
        }
    }

    fun setTreeUri(uri: Uri?) {
        treeUri = uri
    }

    fun getTreeUri(): Uri? = treeUri

    private fun isTextFile(file: DocumentFile): Boolean {
        val name = file.name?.lowercase() ?: return false
        return name.endsWith(".txt") || name.endsWith(".md") || file.type?.startsWith("text/") == true
    }
}
