package com.infdesk5.quicknotes.storage

import android.content.Context
import android.content.SharedPreferences
import com.infdesk5.quicknotes.model.Note
import com.infdesk5.quicknotes.model.NoteMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

enum class StorageMode { LOCAL, EXTERNAL }

class NoteManager(
    private val context: Context,
    private val prefs: SharedPreferences
) {

    companion object {
        private const val KEY_STORAGE_MODE = "storage_mode"
        private const val KEY_TREE_URI = "tree_uri"
        private const val KEY_METADATA = "note_metadata"
        private const val KEY_TOP_INSET_PERCENT = "top_inset_percent"
        private const val KEY_APP_COLOR = "app_color"
        private const val KEY_KEYBOARD_ON_SELECT = "keyboard_on_select"
        private const val KEY_CLOSE_SETTINGS_ON_SELECT = "close_settings_on_select"
        private const val KEY_SCROLLER_SIZE = "scroller_size"
        private const val KEY_SHOW_SCROLLER = "show_scroller"
        private const val KEY_SEARCH_HIGHLIGHT = "search_highlight"
        private const val KEY_SEARCH_CURRENT_HIGHLIGHT = "search_current_highlight"
        private const val KEY_SHOW_KEYBOARD_ON_OPEN = "show_keyboard_on_open_note"

        private const val KEY_LAST_NOTE_ID = "last_note_id"
        private const val KEY_SLOT_SCROLL_X = "slot_scroll_x"
        private const val KEY_SLOT_MAX_CHARS = "slot_max_chars"
        private const val KEY_LAST_SCROLL_Y = "last_scroll_y"
        private const val KEY_LAST_SELECTION_START = "last_selection_start"
        private const val KEY_LAST_SELECTION_END = "last_selection_end"

        private const val DEFAULT_APP_COLOR = 0xFF1E8E3E.toInt()
    }

    val localRepo = LocalNoteRepository(context)
    val externalRepo = ExternalNoteRepository(context, getTreeUri())

    var storageMode: StorageMode
        get() = StorageMode.valueOf(
            prefs.getString(KEY_STORAGE_MODE, StorageMode.LOCAL.name) ?: StorageMode.LOCAL.name
        )
        set(value) = prefs.edit().putString(KEY_STORAGE_MODE, value.name).apply()

    val activeRepository: NoteRepository
        get() = when (storageMode) {
            StorageMode.LOCAL -> localRepo
            StorageMode.EXTERNAL -> externalRepo
        }

    var metadata: NoteMetadata = loadMetadata()

    var appColor: Int
        get() = prefs.getInt(KEY_APP_COLOR, DEFAULT_APP_COLOR)
        set(value) = prefs.edit().putInt(KEY_APP_COLOR, value).apply()

    var showKeyboardOnOpenNote: Boolean
        get() = prefs.getBoolean(KEY_SHOW_KEYBOARD_ON_OPEN, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_KEYBOARD_ON_OPEN, value).apply()

    var topInsetPercent: Int
        get() = prefs.getInt(KEY_TOP_INSET_PERCENT, 45)
        set(value) = prefs.edit().putInt(KEY_TOP_INSET_PERCENT, value).apply()

    var scrollerSizePercent: Int
        get() = prefs.getInt(KEY_SCROLLER_SIZE, 100)
        set(value) = prefs.edit().putInt(KEY_SCROLLER_SIZE, value).apply()

    var showScroller: Boolean
        get() = prefs.getBoolean(KEY_SHOW_SCROLLER, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_SCROLLER, value).apply()

    var keyboardOnSelect: Boolean
        get() = prefs.getBoolean(KEY_KEYBOARD_ON_SELECT, false)
        set(value) = prefs.edit().putBoolean(KEY_KEYBOARD_ON_SELECT, value).apply()

    var closeSettingsOnSelect: Boolean
        get() = prefs.getBoolean(KEY_CLOSE_SETTINGS_ON_SELECT, false)
        set(value) = prefs.edit().putBoolean(KEY_CLOSE_SETTINGS_ON_SELECT, value).apply()
    
    var searchHighlightColor: Int
        get() = prefs.getInt(KEY_SEARCH_HIGHLIGHT, 0x809C27B0.toInt())
        set(value) = prefs.edit().putInt(KEY_SEARCH_HIGHLIGHT, value).apply()

    var searchCurrentHighlightColor: Int
        get() = prefs.getInt(KEY_SEARCH_CURRENT_HIGHLIGHT, 0xCCE040FB.toInt())
        set(value) = prefs.edit().putInt(KEY_SEARCH_CURRENT_HIGHLIGHT, value).apply()

    var lastNoteId: String?
        get() = prefs.getString(KEY_LAST_NOTE_ID, null)
        set(value) = prefs.edit().putString(KEY_LAST_NOTE_ID, value).apply()

    var slotScrollX: Int
        get() = prefs.getInt(KEY_SLOT_SCROLL_X, 0)
        set(value) = prefs.edit().putInt(KEY_SLOT_SCROLL_X, value).apply()

    var slotMaxChars: Int
        get() = prefs.getInt(KEY_SLOT_MAX_CHARS, 0)
        set(value) = prefs.edit().putInt(KEY_SLOT_MAX_CHARS, value).apply()

    var lastScrollY: Int
        get() = prefs.getInt(KEY_LAST_SCROLL_Y, 0)
        set(value) = prefs.edit().putInt(KEY_LAST_SCROLL_Y, value).apply()

    var lastSelectionStart: Int
        get() = prefs.getInt(KEY_LAST_SELECTION_START, 0)
        set(value) = prefs.edit().putInt(KEY_LAST_SELECTION_START, value).apply()

    var lastSelectionEnd: Int
        get() = prefs.getInt(KEY_LAST_SELECTION_END, 0)
        set(value) = prefs.edit().putInt(KEY_LAST_SELECTION_END, value).apply()

    fun getTreeUri() = prefs.getString(KEY_TREE_URI, null)?.let { android.net.Uri.parse(it) }

    fun setTreeUri(uri: android.net.Uri?) {
        prefs.edit().putString(KEY_TREE_URI, uri?.toString()).apply()
        externalRepo.setTreeUri(uri)
    }

    fun loadMetadata(): NoteMetadata {
        val json = prefs.getString(KEY_METADATA, null)
        return if (json != null) NoteMetadata.fromJson(json) else NoteMetadata(appColor = appColor)
    }

    fun saveMetadata() {
        prefs.edit().putString(KEY_METADATA, metadata.toJson()).apply()
    }

    suspend fun listNotes(): List<Note> = activeRepository.listNotes()

    suspend fun readNote(note: Note): String? = activeRepository.readNote(note)

    suspend fun writeNote(note: Note, content: String): Boolean {
        val success = activeRepository.writeNote(note, content)
        if (success) note.lastModified = System.currentTimeMillis()
        return success
    }

    suspend fun createNote(name: String): Note? = activeRepository.createNote(name)

    suspend fun deleteNote(note: Note): Boolean = activeRepository.deleteNote(note)

    suspend fun renameNote(note: Note, newName: String): Boolean =
        activeRepository.renameNote(note, newName)

    suspend fun syncNotes(): SyncResult = withContext(Dispatchers.IO) {
        val localNotes = localRepo.listNotes()
        val externalNotes = externalRepo.listNotes()

        var copied = 0
        var updated = 0

        for (extNote in externalNotes) {
            val localMatch = localNotes.find { it.name == extNote.name }

            if (localMatch == null) {
                val content = externalRepo.readNote(extNote)
                if (content != null) {
                    localRepo.createNote(extNote.name)?.let {
                        localRepo.writeNote(it, content)
                        copied++
                    }
                }
            } else if (extNote.lastModified > localMatch.lastModified) {
                externalRepo.readNote(extNote)?.let {
                    localRepo.writeNote(localMatch, it)
                    updated++
                }
            } else if (localMatch.lastModified > extNote.lastModified) {
                localRepo.readNote(localMatch)?.let {
                    externalRepo.writeNote(extNote, it)
                    updated++
                }
            }
        }

        for (localNote in localNotes) {
            if (externalNotes.none { it.name == localNote.name }) {
                localRepo.readNote(localNote)?.let { content ->
                    externalRepo.createNote(localNote.name)?.let {
                        externalRepo.writeNote(it, content)
                        copied++
                    }
                }
            }
        }

        SyncResult(copied, updated)
    }

    suspend fun exportBackup(): String? = withContext(Dispatchers.IO) {
        try {
            val tree = externalRepo.getTree() ?: return@withContext null
            val backupsDir = tree.findFile("backups") ?: tree.createDirectory("backups")
            ?: return@withContext null

            val timestamp = System.currentTimeMillis()
            val fileName = "quicknotes_backup_$timestamp.zip"

            val backupFile = backupsDir.createFile("application/zip", fileName)
                ?: return@withContext null

            val notes = localRepo.listNotes()

            val baos = ByteArrayOutputStream()

            ZipOutputStream(baos).use { zip ->
                for (note in notes) {
                    val content = localRepo.readNote(note) ?: continue
                    zip.putNextEntry(ZipEntry(note.name))
                    zip.write(content.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }

            context.contentResolver.openOutputStream(backupFile.uri)?.use { os ->
                os.write(baos.toByteArray())
                os.flush()
            }

            "backups/$fileName"
        } catch (e: Exception) {
            null
        }
    }

    suspend fun importBackup(): Boolean = withContext(Dispatchers.IO) {
        try {
            val tree = externalRepo.getTree() ?: return@withContext false
            val backupsDir = tree.findFile("backups") ?: return@withContext false

            val backups = backupsDir.listFiles()
                .filter { it.name?.endsWith(".zip") == true }
                .sortedByDescending { it.lastModified() }

            if (backups.isEmpty()) return@withContext false

            val latestBackup = backups.first()

            val inputStream = context.contentResolver.openInputStream(latestBackup.uri)
                ?: return@withContext false

            ZipInputStream(inputStream).use { zip ->
                var entry = zip.nextEntry

                while (entry != null) {
                    val content = zip.readBytes().toString(Charsets.UTF_8)

                    val note = localRepo.createNote(entry.name)
                        ?: localRepo.listNotes().find { it.name == entry.name }

                    if (note != null) {
                        localRepo.writeNote(note, content)
                    }

                    entry = zip.nextEntry
                }
            }

            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun importBackupFromUri(uri: android.net.Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext false

            ZipInputStream(inputStream).use { zip ->
                var entry = zip.nextEntry

                while (entry != null) {
                    if (!entry.isDirectory) {
                        val content = zip.readBytes().toString(Charsets.UTF_8)

                        val note = localRepo.createNote(entry.name)
                            ?: localRepo.listNotes().find { it.name == entry.name }

                        if (note != null) {
                            localRepo.writeNote(note, content)
                        }
                    }

                    entry = zip.nextEntry
                }
            }

            true
        } catch (e: Exception) {
            false
        }
    }

    data class SyncResult(val copied: Int, val updated: Int)
}
