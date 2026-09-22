package com.infdesk5.quicknotes

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.util.TypedValue
import android.view.ActionMode
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.Toolbar
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.infdesk5.quicknotes.model.Note
import com.infdesk5.quicknotes.storage.NoteManager
import com.infdesk5.quicknotes.storage.StorageMode
import com.infdesk5.quicknotes.ui.NoteSlotBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    companion object {
        private const val KEY_CURRENT_NOTE_ID = "current_note_id"
        private const val KEY_EDIT_TEXT = "edit_text"
        private const val KEY_LAST_SAVED_TEXT = "last_saved_text"

        private const val AUTOSAVE_DELAY_MS = 700L
        private const val SCROLL_TO_END_TIMEOUT_MS = 1200L
        private const val MAX_TOP_INSET_PERCENT = 90

        private const val EXTRA_NOTE_ID = "extra_note_id"
    }

    private lateinit var noteManager: NoteManager
    private lateinit var rootLayout: View
    private lateinit var editText: EditText
    private lateinit var noteScroll: ObservableScrollView
    private lateinit var noteSlotBar: NoteSlotBar
    private lateinit var searchBar: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var fastScroller: View

    private lateinit var btnSave: View
    private lateinit var btnNew: View
    private lateinit var btnSearchNote: View
    private lateinit var btnNotes: View
    private lateinit var btnUndo: View
    private lateinit var btnRedo: View
    private lateinit var btnSettings: View

    private var currentNote: Note? = null
    private var allNotes: List<Note> = emptyList()

    private var saveJob: Job? = null
    private var isLoading = false
    private var isProgrammaticTextChange = false
    private var lastSavedText = ""

    private var isTextSelectionActionMode = false
    private var scrollToEndUntil = 0L
    private var scrollToEndWhenKeyboardVisible = false

    private var restoringLastNote = false
    private var pendingKeyboardOnResume = false

    private var showKeyboardRunnable: Runnable? = null
    private var keyboardRetryRunnable: Runnable? = null

    private var searchMatches = mutableListOf<Int>()
    private var currentSearchIndex = 0
    private var currentSearchQuery = ""

    private val undoRedo = UndoRedoManager()

    private val globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        scrollToEndIfRequested()
        updateFastScroller()
    }

    private val textWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            if (!isProgrammaticTextChange && !isLoading) {
                undoRedo.beforeUserTextChanged(s?.toString() ?: "", count, after)
            }
        }

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

        override fun afterTextChanged(s: Editable?) {
            if (!isProgrammaticTextChange && !isLoading) scheduleSave()
        }
    }

    private val importBackupLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult

            lifecycleScope.launch {
                if (noteManager.importBackupFromUri(uri)) {
                    toast(getString(R.string.backup_imported))
                    refreshNotes()
                } else {
                    toast(getString(R.string.backup_failed))
                }
            }
        }

    private val pickFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri == null) {
                toast(getString(R.string.folder_needed))
                return@registerForActivityResult
            }

            if (noteManager.externalRepo.handlePermissionResult(uri)) {
                noteManager.setTreeUri(uri)
                lifecycleScope.launch { refreshNotes() }
            } else {
                toast("Permission was not persisted.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setActionBar(findViewById<Toolbar>(R.id.toolbar))
        actionBar?.setDisplayShowTitleEnabled(false)
        actionBar?.setDisplayShowHomeEnabled(false)

        rootLayout = findViewById(R.id.root_layout)
        noteScroll = findViewById(R.id.note_scroll)
        editText = findViewById(R.id.note_edit)
        noteSlotBar = findViewById(R.id.note_slot_bar)
        searchBar = findViewById(R.id.search_bar)
        searchInput = findViewById(R.id.search_input)
        fastScroller = findViewById(R.id.fast_scroller)

        btnSave = findViewById(R.id.btn_save)
        btnNew = findViewById(R.id.btn_new)
        btnSearchNote = findViewById(R.id.btn_search_note)
        btnNotes = findViewById(R.id.btn_notes)
        btnUndo = findViewById(R.id.btn_undo)
        btnRedo = findViewById(R.id.btn_redo)
        btnSettings = findViewById(R.id.btn_settings)

        editText.showSoftInputOnFocus = false

        noteManager = NoteManager(this, getPreferences(MODE_PRIVATE))

        setupToolbarButtons()
        setupFastScroller()
        setupClickToFocus()
        setupSearchBar()
        setupNoteSlotBar()

        undoRedo.onAvailabilityChanged = { updateToolbarButtons() }
        updateToolbarButtons()

        noteScroll.setSmoothScrollingEnabled(false)

        applyTopInset()
        applyAppColor()
        applyScrollerSize()
        applyScrollerVisibility()

        noteScroll.onScrollChangedListener = { _, _ -> updateFastScroller() }

        rootLayout.viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            if (insets.isVisible(WindowInsetsCompat.Type.ime())) {
                if (scrollToEndWhenKeyboardVisible) {
                    noteScroll.post { scrollToEnd() }
                    scrollToEndWhenKeyboardVisible = false
                }
        
                if (
                    currentNote != null &&
                    searchBar.visibility != View.VISIBLE &&
                    !isTextSelectionActionMode
                ) {
                    bringCursorIntoView()
                }
            }
        
            ViewCompat.onApplyWindowInsets(view, insets)
        }

        editText.addTextChangedListener(textWatcher)

        val shortcutNoteId = intent.getStringExtra(EXTRA_NOTE_ID)

        if (savedInstanceState != null) {
            val noteId = savedInstanceState.getString(KEY_CURRENT_NOTE_ID)
            val text = savedInstanceState.getString(KEY_EDIT_TEXT) ?: ""
            lastSavedText = savedInstanceState.getString(KEY_LAST_SAVED_TEXT) ?: text

            setTextWithoutWatcher(text)
            undoRedo.clear()

            lifecycleScope.launch {
                refreshNotes()

                if (noteId != null) {
                    restoringLastNote = noteManager.lastNoteId == noteId
                    openNoteById(noteId)
                } else {
                    restoringLastNote = true
                    openLastNote()
                }
            }
        } else {
            lifecycleScope.launch {
                refreshNotes()

                if (shortcutNoteId != null) {
                    openNoteById(shortcutNoteId)
                } else {
                    restoringLastNote = true
                    openLastNote()
                }
            }
        }
    }

    override fun onDestroy() {
        rootLayout.viewTreeObserver.removeOnGlobalLayoutListener(globalLayoutListener)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putString(KEY_CURRENT_NOTE_ID, currentNote?.id)
        outState.putString(KEY_EDIT_TEXT, editText.text.toString())
        outState.putString(KEY_LAST_SAVED_TEXT, lastSavedText)
    }

    override fun onPause() {
        pendingKeyboardOnResume = false
        cancelKeyboardRetries()

        saveLastUiState()
        saveJob?.cancel()
        saveCurrentNoteBlocking()

        super.onPause()
    }

    override fun onStop() {
        saveCurrentNoteBlocking()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()

        if (
            currentNote != null &&
            searchBar.visibility != View.VISIBLE &&
            noteManager.showKeyboardOnOpenNote
        ) {
            if (hasWindowFocus()) {
                rootLayout.post { showKeyboardReliably() }
            } else {
                pendingKeyboardOnResume = true
            }
        } else {
            pendingKeyboardOnResume = false
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        if (hasFocus && pendingKeyboardOnResume) {
            pendingKeyboardOnResume = false

            if (
                currentNote != null &&
                searchBar.visibility != View.VISIBLE &&
                noteManager.showKeyboardOnOpenNote
            ) {
                rootLayout.post { showKeyboardReliably() }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        rootLayout.post {
            applyTopInset()
            applyScrollerSize()
            applyScrollerVisibility()
            updateFastScroller()
            updateSlotBar()
            updateToolbarButtons()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        intent.getStringExtra(EXTRA_NOTE_ID)?.let { id ->
            lifecycleScope.launch { openNoteById(id) }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (searchBar.visibility == View.VISIBLE) {
            hideSearch()
        } else {
            finish()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            scrollToEndUntil = 0L
            scrollToEndWhenKeyboardVisible = false
            cancelKeyboardRetries()
        }

        return super.dispatchTouchEvent(ev)
    }

    // ===== TEXT SELECTION / ACTION MODE =====

    override fun onActionModeStarted(mode: ActionMode?) {
        super.onActionModeStarted(mode)

        isTextSelectionActionMode = true

        showKeyboardRunnable?.let {
            editText.removeCallbacks(it)
            showKeyboardRunnable = null
        }

        cancelKeyboardRetries()

        if (noteManager.keyboardOnSelect) {
            showKeyboard()
        } else {
            hideKeyboard()
        }
    }

    override fun onActionModeFinished(mode: ActionMode?) {
        super.onActionModeFinished(mode)
        isTextSelectionActionMode = false
    }

    // ===== CUSTOM TOOLBAR =====

    private fun setupToolbarButtons() {
        btnSave.setOnClickListener {
            lifecycleScope.launch {
                saveCurrentNoteNow()
                toast(getString(R.string.saved))
            }
        }

        btnNew.setOnClickListener {
            createNewNote()
        }

        btnSearchNote.setOnClickListener {
            showInNoteSearch()
        }

        btnNotes.setOnClickListener {
            showNotesMenu()
        }

        btnUndo.setOnClickListener {
            undo()
        }

        btnRedo.setOnClickListener {
            redo()
        }

        btnSettings.setOnClickListener {
            showSettingsMenu()
        }
    }

    private fun updateToolbarButtons() {
        btnUndo.isEnabled = undoRedo.canUndo
        btnRedo.isEnabled = undoRedo.canRedo

        btnUndo.alpha = if (undoRedo.canUndo) 1f else 0.35f
        btnRedo.alpha = if (undoRedo.canRedo) 1f else 0.35f
    }

    // ===== SETTINGS BOTTOM MENU =====

    private fun showSettingsMenu() {
        val items = arrayOf(
            getString(R.string.top_height),
            getString(R.string.scroller_size),
            getString(R.string.show_scroller),
            getString(R.string.max_slots),
            getString(R.string.slot_length),
            getString(R.string.app_color),
            getString(R.string.search_highlight_color),
            getString(R.string.current_search_color),
            getString(R.string.keyboard_on_select),
            getString(R.string.show_keyboard_on_open),
            getString(
                R.string.storage_mode,
                if (noteManager.storageMode == StorageMode.LOCAL) {
                    getString(R.string.storage_local)
                } else {
                    getString(R.string.storage_external)
                }
            ),
            getString(R.string.sync_notes),
            getString(R.string.import_backup),
            getString(R.string.export_backup),
            getString(R.string.choose_folder)
        )

        val scrollView = ScrollView(this)

        val listView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val outValue = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)

        items.forEachIndexed { index, title ->
            val itemView = TextView(this).apply {
                text = title
                textSize = 16f
                setTextColor(ContextCompat.getColor(context, android.R.color.white))
                setPadding(dp(16), dp(14), dp(16), dp(14))
                setBackgroundResource(outValue.resourceId)
                isClickable = true
                isFocusable = true

                setOnClickListener {
                    handleSettingsItemClick(index)
                }
            }

            listView.addView(itemView)
        }

        scrollView.addView(listView)

        val builder = AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings))
            .setView(scrollView)
            .setNegativeButton(getString(R.string.cancel), null)

        val dialog = builder.create()
        dialog.show()

        dialog.window?.setGravity(Gravity.BOTTOM)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val maxHeight = (resources.displayMetrics.heightPixels * 0.40).toInt()

        scrollView.post {
            if (scrollView.height > maxHeight) {
                scrollView.layoutParams = scrollView.layoutParams.apply {
                    height = maxHeight
                }
                scrollView.requestLayout()
            }
        }
    }

    private fun handleSettingsItemClick(index: Int) {
        when (index) {
            0 -> showTopHeightDialog()

            1 -> showScrollerSizeDialog()

            2 -> {
                noteManager.showScroller = !noteManager.showScroller
                applyScrollerVisibility()
            }

            3 -> showSlotCountDialog()

            4 -> showSlotLengthDialog()

            5 -> showColorPicker()

            6 -> showSearchColorPicker(false)

            7 -> showSearchColorPicker(true)

            8 -> {
                noteManager.keyboardOnSelect = !noteManager.keyboardOnSelect
                toast(if (noteManager.keyboardOnSelect) "Enabled" else "Disabled")
            }

            9 -> {
                noteManager.showKeyboardOnOpenNote = !noteManager.showKeyboardOnOpenNote
                toast(if (noteManager.showKeyboardOnOpenNote) "Enabled" else "Disabled")
            }

            10 -> toggleStorageMode()

            11 -> syncNotes()

            12 -> importBackupLauncher.launch(arrayOf("application/zip"))

            13 -> exportBackup()

            14 -> lifecycleScope.launch {
                saveCurrentNoteNow()
                pickFolderLauncher.launch(null)
            }
        }
    }

    // ===== BOTTOM DIALOG HELPER =====

    private fun showBottomDialog(
        title: String,
        view: View,
        positiveText: String? = null,
        onPositive: (() -> Unit)? = null
    ) {
        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(view)
            .setNegativeButton(getString(R.string.cancel), null)

        if (positiveText != null && onPositive != null) {
            builder.setPositiveButton(positiveText) { _, _ ->
                onPositive()
            }
        }

        val dialog = builder.create()
        dialog.show()

        dialog.window?.setGravity(Gravity.BOTTOM)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun showBottomDialogSimple(
        title: String,
        items: Array<String>,
        onItemClick: (Int) -> Unit
    ) {
        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(items) { _, which ->
                onItemClick(which)
            }
            .setNegativeButton(getString(R.string.cancel), null)

        val dialog = builder.create()
        dialog.show()

        dialog.window?.setGravity(Gravity.BOTTOM)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    // ===== NOTE MANAGEMENT =====

    private suspend fun refreshNotes() {
        val rawNotes = noteManager.listNotes()
        val metaEntries = noteManager.metadata.notes

        val orderedNotes = mutableListOf<Note>()

        for (meta in metaEntries) {
            val note = rawNotes.find { it.id == meta.id }

            if (note != null) {
                note.slotColor = meta.slotColor
                orderedNotes.add(note)
            }
        }

        for (note in rawNotes) {
            if (metaEntries.none { it.id == note.id }) {
                orderedNotes.add(note)
            }
        }

        allNotes = orderedNotes
        updateSlotBar()
    }

    private fun updateSlotBar() {
        noteSlotBar.setNotes(
            allNotes,
            noteManager.metadata.slotCount,
            noteManager.appColor,
            currentNote?.id,
            noteManager.slotMaxChars
        )

        val x = noteManager.slotScrollX

        noteSlotBar.post {
            noteSlotBar.scrollTo(x, 0)
        }
    }

    private fun setupNoteSlotBar() {
        noteSlotBar.onScrollChangedListener = { x ->
            noteManager.slotScrollX = x
        }

        noteSlotBar.setOnSlotClickListener { note ->
            lifecycleScope.launch { openNote(note) }
        }

        noteSlotBar.setOnSlotReorderListener { from, to ->
            lifecycleScope.launch {
                val mutableNotes = allNotes.toMutableList()
        
                if (from >= 0 && from < mutableNotes.size && from != to) {
                    val item = mutableNotes.removeAt(from)
        
                    if (to >= 0 && to < mutableNotes.size) {
                        mutableNotes.add(to, item)
                    } else {
                        mutableNotes.add(item)
                    }
        
                    allNotes = mutableNotes
        
                    updateSlotBar()
                    saveNoteOrder()
                }
            }
        }
    }

    private fun saveNoteOrder() {
        noteManager.metadata.notes.clear()

        allNotes.forEachIndexed { i, n ->
            noteManager.metadata.notes.add(
                com.infdesk5.quicknotes.model.NoteMetaEntry(
                    n.id,
                    n.name,
                    i,
                    n.slotColor
                )
            )
        }

        noteManager.saveMetadata()
    }

    private suspend fun openNote(note: Note) {
        saveCurrentNoteNow()

        val text = withContext(Dispatchers.IO) { noteManager.readNote(note) }

        if (text == null) {
            toast(getString(R.string.could_not_open_note))
            return
        }

        val isRestore = restoringLastNote && noteManager.lastNoteId == note.id

        currentNote = note
        noteManager.lastNoteId = note.id

        editText.visibility = View.INVISIBLE

        setTextWithoutWatcher(text)
        undoRedo.clear()
        lastSavedText = text

        editText.post {
            if (isRestore && !noteManager.showKeyboardOnOpenNote) {
                scrollToEndUntil = 0L
                scrollToEndWhenKeyboardVisible = false
        
                val savedStart = noteManager.lastSelectionStart.coerceIn(0, text.length)
                val savedEnd = noteManager.lastSelectionEnd.coerceIn(0, text.length)
        
                val start = minOf(savedStart, savedEnd)
                val end = maxOf(savedStart, savedEnd)
        
                try {
                    editText.setSelection(start, end)
                } catch (_: Exception) {
                }
        
                val restoreScroll = {
                    noteScroll.scrollTo(
                        0,
                        noteManager.lastScrollY.coerceIn(0, noteScroll.getMaxScroll())
                    )
                }
        
                noteScroll.post(restoreScroll)
                noteScroll.postDelayed(restoreScroll, 150)
        
                editText.visibility = View.VISIBLE
            } else {
                try {
                    editText.setSelection(text.length)
                } catch (_: Exception) {
                }
        
                noteScroll.scrollTo(0, noteScroll.getMaxScroll())
                editText.visibility = View.VISIBLE
                requestScrollToEnd()
                requestNoteKeyboard()
            }
        }

        restoringLastNote = false
        updateSlotBar()
    }

    private suspend fun openNoteById(noteId: String) {
        val note = allNotes.find { it.id == noteId }

        if (note != null) {
            openNote(note)
        } else {
            refreshNotes()

            allNotes.find { it.id == noteId }?.let {
                openNote(it)
            } ?: openLastNote()
        }
    }

    private suspend fun openLastNote() {
        if (allNotes.isEmpty()) refreshNotes()

        val note = noteManager.lastNoteId?.let { id ->
            allNotes.find { it.id == id }
        } ?: allNotes.firstOrNull()

        if (note != null) {
            openNote(note)
        } else {
            createNewNote()
        }
    }

    private fun createNewNote() {
        val input = EditText(this)
        val defaultName = NoteUtils.newNoteName()

        input.setText(defaultName)

        val dotIndex = defaultName.lastIndexOf('.')

        if (dotIndex > 0) {
            input.setSelection(0, dotIndex)
        } else {
            input.selectAll()
        }

        showBottomDialog(
            getString(R.string.new_note),
            wrapInPadding(input),
            getString(R.string.create)
        ) {
            val name = input.text.toString().trim()

            if (name.isEmpty()) {
                toast(getString(R.string.name_cannot_be_empty))
                return@showBottomDialog
            }

            val fullName = if (name.contains('.')) name else "$name.txt"

            lifecycleScope.launch {
                saveCurrentNoteNow()

                val note = noteManager.createNote(fullName)

                if (note == null) {
                    toast(getString(R.string.could_not_create_note))
                    return@launch
                }

                currentNote = note
                noteManager.lastNoteId = note.id

                setTextWithoutWatcher("")
                undoRedo.clear()
                lastSavedText = ""

                setCursorEndAndShowKeyboard()
                refreshNotes()
            }
        }

        input.postDelayed({
            input.requestFocus()
            input.selectAll()
            showKeyboardFor(input)
        }, 300)
    }

    private fun wrapInPadding(view: View): View {
        return LinearLayout(this).apply {
            setPadding(dp(20), dp(12), dp(20), dp(4))

            addView(
                view,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun showNotesMenu() {
        lifecycleScope.launch {
            saveCurrentNoteNow()

            val notes = noteManager.listNotes()

            if (notes.isEmpty()) {
                toast(getString(R.string.no_notes_found))
                return@launch
            }

            val names = notes.map { it.displayName }.toTypedArray()

            val builder = AlertDialog.Builder(this@MainActivity)
                .setTitle(getString(R.string.notes))
                .setItems(names) { _, which ->
                    lifecycleScope.launch { openNote(notes[which]) }
                }
                .setNeutralButton(getString(R.string.search_all_notes)) { _, _ ->
                    showCrossNoteSearch()
                }
                .setNegativeButton(getString(R.string.cancel), null)

            val dialog = builder.create()
            dialog.show()

            dialog.window?.setGravity(Gravity.BOTTOM)
            dialog.window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )

            dialog.listView.setOnItemLongClickListener { _, _, position, _ ->
                dialog.dismiss()
                showNoteOptionsDialog(notes[position])
                true
            }
        }
    }

    private fun showNoteOptionsDialog(note: Note) {
        val options = arrayOf(
            getString(R.string.assign_to_slot),
            getString(R.string.delete),
            getString(R.string.rename),
            getString(R.string.create_shortcut)
        )

        showBottomDialogSimple(note.displayName, options) { which ->
            when (which) {
                0 -> assignNoteToSlot(note)
                1 -> deleteNote(note)
                2 -> renameNote(note)
                3 -> createNoteShortcut(note)
            }
        }
    }

    private fun assignNoteToSlot(note: Note) {
        val maxSlots = noteManager.metadata.slotCount
        val options = (1..maxSlots).map { "Slot $it" }.toTypedArray()

        showBottomDialogSimple(getString(R.string.assign_to_slot), options) { which ->
            lifecycleScope.launch {
                val mutableNotes = allNotes.toMutableList()

                val sourceIndex = mutableNotes.indexOfFirst { it.id == note.id }
                if (sourceIndex == -1) return@launch

                if (sourceIndex == which) return@launch

                if (which < mutableNotes.size) {
                    val displaced = mutableNotes[which]

                    mutableNotes[which] = note
                    mutableNotes[sourceIndex] = displaced
                } else {
                    mutableNotes.removeAt(sourceIndex)
                    mutableNotes.add(note)
                }

                allNotes = mutableNotes
                updateSlotBar()
                saveNoteOrder()
            }
        }
    }

    private fun deleteNote(note: Note) {
        val builder = AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete))
            .setMessage(getString(R.string.delete_note_confirm))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                lifecycleScope.launch {
                    if (noteManager.deleteNote(note)) {
                        toast(getString(R.string.note_deleted))

                        if (currentNote?.id == note.id) {
                            currentNote = null
                            openLastNote()
                        }

                        refreshNotes()
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)

        val dialog = builder.create()
        dialog.show()

        dialog.window?.setGravity(Gravity.BOTTOM)
    }

    private fun renameNote(note: Note) {
        val input = EditText(this)

        input.setText(note.displayName)
        input.selectAll()

        showBottomDialog(
            getString(R.string.rename_note),
            wrapInPadding(input),
            getString(R.string.rename)
        ) {
            val newName = input.text.toString().trim()

            if (newName.isEmpty()) {
                toast(getString(R.string.name_cannot_be_empty))
                return@showBottomDialog
            }

            val fullName = if (newName.contains('.')) newName else "$newName.txt"

            lifecycleScope.launch {
                noteManager.renameNote(note, fullName)
                refreshNotes()
                updateSlotBar()
            }
        }

        input.postDelayed({
            input.requestFocus()
            input.selectAll()
            showKeyboardFor(input)
        }, 300)
    }

    private fun createNoteShortcut(note: Note) {
        try {
            val manager = getSystemService(ShortcutManager::class.java)

            if (manager.isRequestPinShortcutSupported) {
                val intent = Intent(this, MainActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    putExtra(EXTRA_NOTE_ID, note.id)

                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK or
                            Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                            Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                }

                val size = (48 * resources.displayMetrics.density).toInt()

                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)

                val bgPaint = Paint().apply {
                    color = Color.BLACK
                    isAntiAlias = true
                }

                canvas.drawCircle(size / 2f, size / 2f, size / 2f, bgPaint)

                val pencil = ContextCompat.getDrawable(this, android.R.drawable.ic_menu_edit)

                pencil?.setTint(0xFF9C27B0.toInt())
                pencil?.setBounds(size / 4, size / 4, size * 3 / 4, size * 3 / 4)
                pencil?.draw(canvas)

                val shortcut = ShortcutInfo.Builder(this, note.id)
                    .setShortLabel(note.displayName)
                    .setIcon(android.graphics.drawable.Icon.createWithBitmap(bitmap))
                    .setIntent(intent)
                    .build()

                manager.requestPinShortcut(shortcut, null)

                toast(getString(R.string.shortcut_created))
            }
        } catch (e: Exception) {
            toast("Shortcut creation failed")
        }
    }

    // ===== SEARCH =====

    private fun setupSearchBar() {
        findViewById<ImageButton>(R.id.search_prev).setOnClickListener { navigateSearch(-1) }
        findViewById<ImageButton>(R.id.search_next).setOnClickListener { navigateSearch(1) }
        findViewById<ImageButton>(R.id.search_close).setOnClickListener { hideSearch() }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                performInNoteSearch(s?.toString() ?: "")
            }

            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun showInNoteSearch() {
        searchBar.visibility = View.VISIBLE
        noteSlotBar.visibility = View.GONE

        searchInput.requestFocus()
        showKeyboardFor(searchInput)
    }

    private fun hideSearch() {
        searchBar.visibility = View.GONE
        noteSlotBar.visibility = View.VISIBLE

        searchInput.text.clear()
        clearSearchHighlights()
        hideKeyboard()

        editText.requestFocus()
    }

    private fun performInNoteSearch(query: String) {
        clearSearchHighlights()

        searchMatches.clear()
        currentSearchIndex = 0
        currentSearchQuery = query

        if (query.length < 2) return

        val text = editText.text.toString()

        var index = text.indexOf(query, 0, true)

        while (index >= 0) {
            searchMatches.add(index)
            index = text.indexOf(query, index + query.length, true)
        }

        highlightAllMatches(query)

        if (searchMatches.isNotEmpty()) {
            navigateSearch(0)
        }
    }

    private fun highlightAllMatches(query: String) {
        val spannable = editText.text as? Spannable ?: return
        val normalColor = noteManager.searchHighlightColor

        for (matchIndex in searchMatches) {
            spannable.setSpan(
                BackgroundColorSpan(normalColor),
                matchIndex,
                matchIndex + query.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun highlightCurrentMatch() {
        val spannable = editText.text as? Spannable ?: return

        if (searchMatches.isEmpty() || currentSearchIndex >= searchMatches.size) return

        val currentColor = noteManager.searchCurrentHighlightColor
        val normalColor = noteManager.searchHighlightColor

        for ((i, matchIndex) in searchMatches.withIndex()) {
            val color = if (i == currentSearchIndex) currentColor else normalColor

            spannable.setSpan(
                BackgroundColorSpan(color),
                matchIndex,
                matchIndex + currentSearchQuery.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun clearSearchHighlights() {
        val spannable = editText.text as? Spannable ?: return

        spannable.getSpans(
            0,
            spannable.length,
            BackgroundColorSpan::class.java
        ).forEach {
            spannable.removeSpan(it)
        }
    }

    private fun navigateSearch(direction: Int) {
        if (searchMatches.isEmpty()) return

        currentSearchIndex = when {
            direction > 0 -> (currentSearchIndex + 1) % searchMatches.size
            direction < 0 -> if (currentSearchIndex <= 0) searchMatches.size - 1 else currentSearchIndex - 1
            else -> currentSearchIndex
        }

        highlightCurrentMatch()

        val position = searchMatches[currentSearchIndex]

        editText.setSelection(position, position + currentSearchQuery.length)

        editText.post {
            editText.bringPointIntoView(position)
        }
    }

    private fun showCrossNoteSearch() {
        val input = EditText(this)
        input.hint = getString(R.string.search_all_notes)

        showBottomDialog(
            getString(R.string.search_all_notes),
            wrapInPadding(input),
            getString(R.string.ok)
        ) {
            val query = input.text.toString().trim()

            if (query.isNotEmpty()) {
                performCrossNoteSearch(query)
            }
        }

        input.post {
            input.requestFocus()
            showKeyboardFor(input)
        }
    }

    private fun performCrossNoteSearch(query: String) {
        lifecycleScope.launch {
            val results = mutableListOf<Triple<Note, String, Int>>()

            for (note in allNotes) {
                val content = noteManager.readNote(note) ?: continue

                val lowerContent = content.lowercase()
                val lowerQuery = query.lowercase()

                var index = lowerContent.indexOf(lowerQuery)

                while (index >= 0) {
                    val start = maxOf(0, index - 30)
                    val end = minOf(content.length, index + query.length + 30)

                    val snippet =
                        (if (start > 0) "…" else "") +
                                content.substring(start, end) +
                                (if (end < content.length) "…" else "")

                    results.add(Triple(note, snippet, index))

                    index = lowerContent.indexOf(lowerQuery, index + query.length)
                }
            }

            if (results.isEmpty()) {
                toast(getString(R.string.no_search_results))
                return@launch
            }

            val displayTexts = results.map {
                "${it.first.displayName}\n${it.second}"
            }.toTypedArray()

            showBottomDialogSimple(
                getString(
                    R.string.search_results,
                    results.size,
                    results.map { it.first }.distinct().size
                ),
                displayTexts
            ) { which ->
                val (note, _, matchIndex) = results[which]

                lifecycleScope.launch {
                    openNote(note)

                    delay(200)

                    showInNoteSearch()
                    searchInput.setText(query)

                    delay(200)

                    val targetIdx = searchMatches.indexOfFirst { it >= matchIndex }

                    if (targetIdx != -1) {
                        currentSearchIndex = targetIdx
                        highlightCurrentMatch()

                        val pos = searchMatches[currentSearchIndex]

                        editText.setSelection(pos, pos + query.length)

                        editText.post {
                            editText.bringPointIntoView(pos)
                        }
                    }
                }
            }
        }
    }

    // ===== SETTINGS DIALOGS =====

    private fun toggleStorageMode() {
        val newMode = if (noteManager.storageMode == StorageMode.LOCAL) {
            StorageMode.EXTERNAL
        } else {
            StorageMode.LOCAL
        }

        if (newMode == StorageMode.EXTERNAL && !noteManager.externalRepo.hasPermission()) {
            pickFolderLauncher.launch(null)
            return
        }

        noteManager.storageMode = newMode

        lifecycleScope.launch {
            refreshNotes()
            openLastNote()
        }

        updateToolbarButtons()
    }

    private fun syncNotes() {
        if (!noteManager.externalRepo.hasPermission()) {
            toast(getString(R.string.folder_needed))
            pickFolderLauncher.launch(null)
            return
        }

        lifecycleScope.launch {
            toast("Syncing…")

            val result = noteManager.syncNotes()

            toast(getString(R.string.sync_complete, result.copied, result.updated))

            refreshNotes()
        }
    }

    private fun exportBackup() {
        lifecycleScope.launch {
            val path = noteManager.exportBackup()

            if (path != null) {
                toast(getString(R.string.backup_exported, path))
            } else {
                toast(getString(R.string.backup_failed))
            }
        }
    }

    private fun showTopHeightDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_top_height, null)

        val valueText = view.findViewById<TextView>(R.id.top_height_value)
        val seekBar = view.findViewById<SeekBar>(R.id.top_height_seek)

        seekBar.max = MAX_TOP_INSET_PERCENT
        seekBar.progress = noteManager.topInsetPercent.coerceIn(0, MAX_TOP_INSET_PERCENT)

        valueText.text = getString(R.string.top_height_value, seekBar.progress)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                valueText.text = getString(R.string.top_height_value, p)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        showBottomDialog(
            getString(R.string.top_height),
            view,
            getString(R.string.save)
        ) {
            noteManager.topInsetPercent = seekBar.progress
            applyTopInset()
        }
    }

    private fun showScrollerSizeDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_top_height, null)

        val valueText = view.findViewById<TextView>(R.id.top_height_value)
        val seekBar = view.findViewById<SeekBar>(R.id.top_height_seek)

        seekBar.max = 150
        seekBar.progress = noteManager.scrollerSizePercent - 50

        valueText.text = getString(R.string.scroller_size_value, noteManager.scrollerSizePercent)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                valueText.text = getString(R.string.scroller_size_value, p + 50)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        showBottomDialog(
            getString(R.string.scroller_size),
            view,
            getString(R.string.save)
        ) {
            noteManager.scrollerSizePercent = seekBar.progress + 50
            applyScrollerSize()
        }
    }

    private fun showSlotCountDialog() {
        val counts = arrayOf("1", "2", "3", "4", "5", "6", "7", "8")

        showBottomDialogSimple(getString(R.string.max_slots), counts) { which ->
            noteManager.metadata = noteManager.metadata.copy(slotCount = which + 1)
            noteManager.saveMetadata()
            updateSlotBar()
        }
    }

    private fun showSlotLengthDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_top_height, null)

        val valueText = view.findViewById<TextView>(R.id.top_height_value)
        val seekBar = view.findViewById<SeekBar>(R.id.top_height_seek)

        seekBar.max = 10

        val current = noteManager.slotMaxChars

        seekBar.progress = if (current <= 0) {
            0
        } else {
            (current - 2).coerceIn(1, 10)
        }

        valueText.text = if (seekBar.progress == 0) {
            getString(R.string.slot_length_off)
        } else {
            getString(R.string.slot_length_chars, seekBar.progress + 2)
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                valueText.text = if (progress == 0) {
                    getString(R.string.slot_length_off)
                } else {
                    getString(R.string.slot_length_chars, progress + 2)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        showBottomDialog(
            getString(R.string.slot_length),
            view,
            getString(R.string.save)
        ) {
            noteManager.slotMaxChars = if (seekBar.progress == 0) {
                0
            } else {
                seekBar.progress + 2
            }

            updateSlotBar()
        }
    }

    private fun showColorPicker() {
        val input = EditText(this)

        input.hint = "#RRGGBB"
        input.setText(String.format("#%06X", 0xFFFFFF and noteManager.appColor))
        input.setSelection(input.text.length)

        showBottomDialog(
            getString(R.string.app_color),
            wrapInPadding(input),
            getString(R.string.save)
        ) {
            try {
                noteManager.appColor = Color.parseColor(input.text.toString().trim())
                applyAppColor()
                updateSlotBar()
            } catch (e: Exception) {
                toast("Invalid color format")
            }
        }
    }

    private fun showSearchColorPicker(isCurrent: Boolean) {
        val input = EditText(this)

        input.hint = "#AARRGGBB or #RRGGBB"

        val currentColor = if (isCurrent) {
            noteManager.searchCurrentHighlightColor
        } else {
            noteManager.searchHighlightColor
        }

        input.setText(String.format("#%08X", currentColor))
        input.setSelection(input.text.length)

        showBottomDialog(
            getString(
                if (isCurrent) R.string.current_search_color
                else R.string.search_highlight_color
            ),
            wrapInPadding(input),
            getString(R.string.save)
        ) {
            try {
                val color = Color.parseColor(input.text.toString().trim())

                if (isCurrent) {
                    noteManager.searchCurrentHighlightColor = color
                } else {
                    noteManager.searchHighlightColor = color
                }

                if (searchBar.visibility == View.VISIBLE && currentSearchQuery.isNotEmpty()) {
                    highlightAllMatches(currentSearchQuery)
                    highlightCurrentMatch()
                }
            } catch (e: Exception) {
                toast("Invalid color format")
            }
        }
    }

    // ===== APPLY SETTINGS =====

    private fun applyTopInset() {
        val basePadding = dp(16)
        val percent = noteManager.topInsetPercent.coerceIn(0, MAX_TOP_INSET_PERCENT)

        val height = if (rootLayout.height > 0) {
            rootLayout.height
        } else {
            resources.displayMetrics.heightPixels
        }

        val topInset = (height * percent / 100f).toInt()

        editText.setPadding(basePadding, topInset, basePadding, basePadding)
    }

    private fun applyScrollerSize() {
        val percent = noteManager.scrollerSizePercent
        val density = resources.displayMetrics.density

        val scrollerWidth = (24 * density * percent / 100f).toInt()
        val thumbWidth = (8 * density * percent / 100f).toInt()
        val thumbHeight = (56 * density * percent / 100f).toInt()

        fastScroller.layoutParams = fastScroller.layoutParams.apply {
            width = scrollerWidth
        }

        findViewById<View>(R.id.scroll_thumb).layoutParams =
            findViewById<View>(R.id.scroll_thumb).layoutParams.apply {
                width = thumbWidth
                height = thumbHeight
            }

        fastScroller.requestLayout()
    }

    private fun applyScrollerVisibility() {
        fastScroller.visibility = if (noteManager.showScroller) View.INVISIBLE else View.GONE
    }

    private fun applyAppColor() {
        findViewById<View>(R.id.scroll_thumb)?.background?.setTint(noteManager.appColor)
    }

    // ===== CORE LOGIC =====

    private fun scheduleSave() {
        saveJob?.cancel()

        saveJob = lifecycleScope.launch {
            delay(AUTOSAVE_DELAY_MS)
            saveCurrentNoteNow()
        }
    }

    private suspend fun saveCurrentNoteNow() {
        val note = currentNote ?: return

        val text = withContext(Dispatchers.Main) { editText.text.toString() }

        if (text == lastSavedText) return

        if (withContext(Dispatchers.IO) { noteManager.writeNote(note, text) }) {
            lastSavedText = text
        } else {
            withContext(Dispatchers.Main) {
                toast(getString(R.string.save_failed_choose_folder))
            }
        }
    }

    private fun saveCurrentNoteBlocking() {
        val note = currentNote ?: return
        val text = editText.text.toString()

        if (text == lastSavedText) return

        if (runBlocking(Dispatchers.IO) { noteManager.writeNote(note, text) }) {
            lastSavedText = text
        }
    }

    private fun saveLastUiState() {
        if (currentNote != null) {
            noteManager.lastScrollY = noteScroll.scrollY
            noteManager.lastSelectionStart = editText.selectionStart.coerceAtLeast(0)
            noteManager.lastSelectionEnd = editText.selectionEnd.coerceAtLeast(0)
            noteManager.slotScrollX = noteSlotBar.scrollX
        }
    }

    private fun undo() {
        val scrollY = noteScroll.scrollY
        val selectionStart = editText.selectionStart
        val selectionEnd = editText.selectionEnd

        val previous = undoRedo.undo(editText.text.toString()) ?: return

        setTextWithoutWatcher(previous)

        try {
            val newLen = editText.text.length

            editText.setSelection(
                selectionStart.coerceAtMost(newLen),
                selectionEnd.coerceAtMost(newLen)
            )
        } catch (_: Exception) {
        }

        noteScroll.post {
            noteScroll.scrollTo(0, scrollY.coerceAtMost(noteScroll.getMaxScroll()))
        }

        scheduleSave()
        updateToolbarButtons()
    }

    private fun redo() {
        val scrollY = noteScroll.scrollY
        val selectionStart = editText.selectionStart
        val selectionEnd = editText.selectionEnd

        val next = undoRedo.redo(editText.text.toString()) ?: return

        setTextWithoutWatcher(next)

        try {
            val newLen = editText.text.length

            editText.setSelection(
                selectionStart.coerceAtMost(newLen),
                selectionEnd.coerceAtMost(newLen)
            )
        } catch (_: Exception) {
        }

        noteScroll.post {
            noteScroll.scrollTo(0, scrollY.coerceAtMost(noteScroll.getMaxScroll()))
        }

        scheduleSave()
        updateToolbarButtons()
    }

    private fun requestScrollToEnd() {
        scrollToEndUntil = System.currentTimeMillis() + SCROLL_TO_END_TIMEOUT_MS
        scrollToEndWhenKeyboardVisible = true

        scrollToEndIfRequested()

        for (d in listOf(50L, 150L, 300L, 600L, 900L)) {
            noteScroll.postDelayed({ scrollToEndIfRequested() }, d)
        }
    }

    private fun scrollToEndIfRequested() {
        if (isFinishing || isDestroyed) return

        if (System.currentTimeMillis() < scrollToEndUntil) {
            scrollToEnd()
        }
    }

    private fun scrollToEnd() {
        val maxScroll = noteScroll.getMaxScroll()

        if (maxScroll > 0) {
            noteScroll.scrollTo(0, maxScroll)
        }
    }

    private fun setupFastScroller() {
        val scrollThumb = findViewById<View>(R.id.scroll_thumb)

        val controller = FastScrollController(noteScroll, fastScroller, scrollThumb)

        controller.setup()
        controller.setTopMargin(
            (resources.displayMetrics.heightPixels * 45 / 100f).toInt()
        )
    }

    private fun updateFastScroller() {
        if (!noteManager.showScroller) return

        val scrollThumb = findViewById<View>(R.id.scroll_thumb)

        FastScrollController(noteScroll, fastScroller, scrollThumb)
            .update(noteScroll.scrollY, noteScroll.getMaxScroll())
    }

    // ===== CLICK TO FOCUS =====

    private fun setupClickToFocus() {
        findViewById<View>(R.id.note_content).setOnClickListener {
            if (!isTextSelectionActionMode) {
                editText.requestFocus()
                editText.setSelection(editText.text.length)
                showKeyboard()
            }
        }

        editText.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                showKeyboardRunnable?.let { editText.removeCallbacks(it) }

                val action = Runnable {
                    val isSelecting = isTextSelectionActionMode ||
                            editText.selectionStart != editText.selectionEnd

                    if (!isSelecting) {
                        if (!editText.hasFocus()) editText.requestFocus()
                        showKeyboard()
                    }
                }

                showKeyboardRunnable = action
                editText.postDelayed(action, 150)
            }

            false
        }
    }

    private fun setTextWithoutWatcher(text: String) {
        isLoading = true
        isProgrammaticTextChange = true

        editText.removeTextChangedListener(textWatcher)
        editText.setText(text)
        editText.addTextChangedListener(textWatcher)

        isLoading = false
        isProgrammaticTextChange = false
    }

    private fun setCursorEndAndShowKeyboard() {
        editText.post {
            try {
                editText.setSelection(editText.text.length)
            } catch (_: Exception) {
            }

            if (editText.text.length == 0) {
                scrollToEndUntil = 0L
                scrollToEndWhenKeyboardVisible = false
                noteScroll.scrollTo(0, 0)
            } else {
                requestScrollToEnd()
            }

            requestNoteKeyboard()
        }
    }

    // ===== KEYBOARD UTILITIES =====

    private fun isKeyboardVisible(): Boolean =
        ViewCompat.getRootWindowInsets(rootLayout)?.isVisible(WindowInsetsCompat.Type.ime()) == true

    private fun requestNoteKeyboard() {
        if (!noteManager.showKeyboardOnOpenNote) return
        if (searchBar.visibility == View.VISIBLE) return
        if (isTextSelectionActionMode && !noteManager.keyboardOnSelect) return

        if (!hasWindowFocus()) {
            pendingKeyboardOnResume = true
        }

        showKeyboardReliably()
    }

    private fun showKeyboard() {
        if (isTextSelectionActionMode && !noteManager.keyboardOnSelect) return

        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
    }
    
    private fun bringCursorIntoView() {
        if (isFinishing || isDestroyed) return
        if (searchBar.visibility == View.VISIBLE) return
        if (isTextSelectionActionMode && !noteManager.keyboardOnSelect) return
    
        val position = maxOf(editText.selectionStart, editText.selectionEnd).coerceAtLeast(0)
    
        editText.post {
            if (!isFinishing && !isDestroyed) {
                editText.bringPointIntoView(position)
            }
        }
    }

    private fun showKeyboardForced() {
        if (isTextSelectionActionMode && !noteManager.keyboardOnSelect) return

        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(editText, InputMethodManager.SHOW_FORCED)
    }

    private fun showKeyboardFor(view: EditText) {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        cancelKeyboardRetries()

        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(editText.windowToken, 0)
    }

    private fun showKeyboardReliably() {
        if (isTextSelectionActionMode && !noteManager.keyboardOnSelect) return
        if (searchBar.visibility == View.VISIBLE) return
    
        editText.requestFocus()
        showKeyboard()
    
        editText.postDelayed({
            if (!isFinishing && !isDestroyed && isKeyboardVisible()) {
                bringCursorIntoView()
            }
        }, 250)
    
        cancelKeyboardRetries()
    
        val delays = listOf(120L, 320L, 650L, 1100L)
        var attempt = 0
    
        val runnable = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                if (currentNote == null) return
                if (searchBar.visibility == View.VISIBLE) return
    
                if (!isKeyboardVisible()) {
                    editText.requestFocus()
                    showKeyboardForced()
                }
    
                editText.postDelayed({
                    if (!isFinishing && !isDestroyed && isKeyboardVisible()) {
                        bringCursorIntoView()
                    }
                }, 150)
    
                attempt += 1
    
                if (attempt < delays.size && !isKeyboardVisible()) {
                    editText.postDelayed(this, delays[attempt])
                }
            }
        }
    
        keyboardRetryRunnable = runnable
        editText.postDelayed(runnable, delays[0])
    }

    private fun cancelKeyboardRetries() {
        keyboardRetryRunnable?.let { editText.removeCallbacks(it) }
        keyboardRetryRunnable = null
    }

    private fun toast(message: String) {
        if (!isFinishing && !isDestroyed) {
            runOnUiThread {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
