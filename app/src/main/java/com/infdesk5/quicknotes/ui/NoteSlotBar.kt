package com.infdesk5.quicknotes.ui

import android.content.ClipData
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.infdesk5.quicknotes.model.Note
import kotlin.math.abs

class NoteSlotBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(4), dp(8), dp(4))
        clipChildren = false
    }

    private var notes: List<Note> = emptyList()
    private var slotCount: Int = 5
    private var appColor: Int = 0xFF1E8E3E.toInt()
    private var onSlotClick: ((Note) -> Unit)? = null
    private var onSlotReorder: ((Int, Int) -> Unit)? = null
    private var currentNoteId: String? = null
    private var slotMaxChars: Int = 0

    var onScrollChangedListener: ((Int) -> Unit)? = null

    private var populatedCount = 0

    // Drag state
    private var draggedIndex = -1
    private var draggedTag = -1
    private var isDragging = false
    private var dropHandled = false

    // Stable reorder state
    private var currentPlaceholder = -1
    private var baseOrderWithoutDragged = listOf<Int>()

    private val slotViews = mutableMapOf<Int, View>()
    private val visualOrder = mutableListOf<Int>()
    private val targetTranslationByTag = mutableMapOf<Int, Float>()

    // Edge auto-scroll state
    private var lastDragViewportX = 0f
    private var edgeScrollDirection = 0
    private var edgeScrollSpeed = 0
    private var edgeScrollRunnable: Runnable? = null

    private val edgeSize = dp(48)
    private val maxEdgeScrollSpeed = dp(12)
    private val spacerWidth = dp(6)
    private val animationDuration = 150L
    private val dragGhostAlpha = 0.35f

    // Material-style motion curve.
    private val materialInterpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    init {
        addView(container, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))

        isHorizontalScrollBarEnabled = false
        clipChildren = false
        clipToPadding = false

        setupDragListener()
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        onScrollChangedListener?.invoke(l)
    }

    fun setNotes(
        notes: List<Note>,
        slotCount: Int,
        appColor: Int,
        currentNoteId: String?,
        slotMaxChars: Int = 0
    ) {
        stopEdgeScroll()
        resetDragStateInternal()

        this.notes = notes
        this.slotCount = slotCount
        this.appColor = appColor
        this.currentNoteId = currentNoteId
        this.slotMaxChars = slotMaxChars

        populatedCount = notes.take(slotCount).size

        rebuildSlots()
    }

    fun setOnSlotClickListener(listener: (Note) -> Unit) {
        onSlotClick = listener
    }

    fun setOnSlotReorderListener(listener: (Int, Int) -> Unit) {
        onSlotReorder = listener
    }

    private fun rebuildSlots() {
        container.removeAllViews()

        slotViews.clear()
        visualOrder.clear()
        targetTranslationByTag.clear()

        baseOrderWithoutDragged = emptyList()
        currentPlaceholder = -1

        val slotNotes = notes.take(slotCount)
        populatedCount = slotNotes.size

        for (i in 0 until slotCount) {
            val view = if (i < populatedCount) {
                createSlotView(slotNotes[i], i)
            } else {
                createEmptySlot(i)
            }

            slotViews[i] = view
            visualOrder.add(i)

            container.addView(view)

            if (i < slotCount - 1) {
                container.addView(createSpacer())
            }
        }

        post {
            applyVisualOrder(false)
        }
    }

    private fun createSpacer(): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(spacerWidth, 0)
    }

    private fun createSlotView(note: Note, index: Int): TextView {
        val textView = TextView(context).apply {
            text = formatSlotName(note.displayName)

            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ContextCompat.getColor(context, android.R.color.white))
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))

            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = dp(140)

            alpha = 1f
            translationX = 0f

            val bgColor = when {
                note.id == currentNoteId -> appColor
                note.slotColor != 0 -> note.slotColor
                else -> 0xFF333333.toInt()
            }

            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(bgColor)
            }

            elevation = 2f
        }

        textView.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(36)
        )

        textView.tag = index

        textView.setOnClickListener {
            onSlotClick?.invoke(note)
        }

        textView.setOnLongClickListener {
            if (isDragging || populatedCount <= 1) {
                return@setOnLongClickListener false
            }

            draggedIndex = index
            draggedTag = index
            isDragging = true
            dropHandled = false

            val data = ClipData.newPlainText("index", index.toString())
            val shadow = DragShadowBuilder(textView)

            val started = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                textView.startDragAndDrop(data, shadow, textView, 0)
            } else {
                textView.startDrag(data, shadow, textView, 0)
            }

            if (started) {
                initializeDragBase()
                textView.alpha = dragGhostAlpha
            } else {
                resetDragStateInternal()
            }

            true
        }

        return textView
    }

    private fun createEmptySlot(index: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(60), dp(36))

        alpha = 1f
        translationX = 0f

        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(0xFF222222.toInt())
            setStroke(dp(1), 0xFF444444.toInt())
        }

        tag = index
    }

    private fun setupDragListener() {
        setOnDragListener { _, event ->
            handleDragEvent(event)
        }
    }

    private fun handleDragEvent(event: DragEvent): Boolean {
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> {
                if (isDragging && baseOrderWithoutDragged.isEmpty() && populatedCount > 1) {
                    initializeDragBase()
                }

                return isDragging || draggedIndex != -1
            }

            DragEvent.ACTION_DRAG_LOCATION -> {
                if (!isDragging) return false

                handleDragLocation(event.x)
                return true
            }

            DragEvent.ACTION_DROP -> {
                if (!isDragging) return false

                stopEdgeScroll()

                dropHandled = true

                val finalTarget = currentPlaceholder

                if (
                    draggedIndex != -1 &&
                    finalTarget != -1 &&
                    finalTarget != draggedIndex
                ) {
                    onSlotReorder?.invoke(draggedIndex, finalTarget)
                } else {
                    resetVisualState()
                    dropHandled = true
                }

                isDragging = false

                return true
            }

            DragEvent.ACTION_DRAG_ENDED -> {
                stopEdgeScroll()

                if (!dropHandled) {
                    resetVisualState()
                } else {
                    slotViews[draggedTag]?.alpha = 1f

                    draggedIndex = -1
                    draggedTag = -1
                    isDragging = false
                    dropHandled = false
                }

                return true
            }

            else -> return true
        }
    }

    private fun initializeDragBase() {
        baseOrderWithoutDragged = (0 until populatedCount).filter { it != draggedTag }

        currentPlaceholder = draggedIndex.coerceIn(0, baseOrderWithoutDragged.size)
    }

    private fun handleDragLocation(viewportX: Float) {
        if (!isDragging || draggedTag == -1 || width <= 0 || populatedCount <= 1) return

        if (baseOrderWithoutDragged.isEmpty()) {
            initializeDragBase()
        }

        lastDragViewportX = viewportX.coerceIn(0f, width.toFloat())

        val contentX = lastDragViewportX + scrollX

        val target = getTargetPlaceholder(contentX)

        if (target != currentPlaceholder) {
            currentPlaceholder = target
            updateVisualOrderFromPlaceholder()
        }

        updateEdgeScroll(lastDragViewportX)
    }

    private fun getTargetPlaceholder(contentX: Float): Int {
        if (baseOrderWithoutDragged.isEmpty()) return 0

        if (slotViews.values.any { it.width == 0 }) {
            return currentPlaceholder.coerceAtLeast(0)
        }

        for ((i, tag) in baseOrderWithoutDragged.withIndex()) {
            val view = slotViews[tag] ?: continue

            // Stable boundary: the original center of this slot.
            val centerX = view.left + view.width / 2f

            if (contentX < centerX) {
                return i
            }
        }

        return baseOrderWithoutDragged.size
    }

    private fun updateVisualOrderFromPlaceholder() {
        val safePlaceholder = currentPlaceholder.coerceIn(0, baseOrderWithoutDragged.size)

        visualOrder.clear()

        visualOrder.addAll(baseOrderWithoutDragged.take(safePlaceholder))
        visualOrder.add(draggedTag)
        visualOrder.addAll(baseOrderWithoutDragged.drop(safePlaceholder))

        // Empty slots remain after populated slots.
        for (i in populatedCount until slotCount) {
            visualOrder.add(i)
        }

        applyVisualOrder(true)
    }

    private fun applyVisualOrder(animate: Boolean) {
        if (visualOrder.isEmpty()) return

        // If views are not laid out yet, do not calculate translations.
        if (slotViews.values.any { it.width == 0 }) return

        var cursor = container.paddingLeft

        for ((position, tag) in visualOrder.withIndex()) {
            val view = slotViews[tag] ?: continue

            val translation = (cursor - view.left).toFloat()
            val previousTarget = targetTranslationByTag[tag]

            // Avoid restarting the same animation. This greatly reduces shaking.
            if (previousTarget == null || abs(previousTarget - translation) > 0.5f) {
                targetTranslationByTag[tag] = translation

                if (animate) {
                    view.animate().cancel()
                    view.animate()
                        .translationX(translation)
                        .setDuration(animationDuration)
                        .setInterpolator(materialInterpolator)
                        .withLayer()
                        .start()
                } else {
                    view.translationX = translation
                }
            }

            cursor += view.width

            if (position < visualOrder.size - 1) {
                cursor += spacerWidth
            }
        }
    }

    private fun updateEdgeScroll(viewportX: Float) {
        if (!isDragging || width <= 0 || populatedCount <= 1) {
            stopEdgeScroll()
            return
        }

        val maxScrollX = maxOf(0, container.width - width)
        val edge = edgeSize

        if (viewportX < edge && scrollX > 0) {
            val distance = viewportX.coerceIn(0f, edge.toFloat())
            val fraction = 1f - (distance / edge)

            edgeScrollDirection = -1
            edgeScrollSpeed = (maxEdgeScrollSpeed * fraction).toInt().coerceAtLeast(2)
        } else if (viewportX > width - edge && scrollX < maxScrollX) {
            val distance = (width - viewportX).coerceIn(0f, edge.toFloat())
            val fraction = 1f - (distance / edge)

            edgeScrollDirection = 1
            edgeScrollSpeed = (maxEdgeScrollSpeed * fraction).toInt().coerceAtLeast(2)
        } else {
            stopEdgeScroll()
            return
        }

        startEdgeScrollIfNeeded()
    }

    private fun startEdgeScrollIfNeeded() {
        if (edgeScrollRunnable != null) return

        val runnable = object : Runnable {
            override fun run() {
                if (!isDragging || edgeScrollDirection == 0) {
                    edgeScrollRunnable = null
                    return
                }

                scrollBy(edgeScrollDirection * edgeScrollSpeed, 0)

                handleDragLocation(lastDragViewportX)

                if (isDragging && edgeScrollDirection != 0) {
                    postDelayed(this, 16)
                } else {
                    edgeScrollRunnable = null
                }
            }
        }

        edgeScrollRunnable = runnable
        post(runnable)
    }

    private fun stopEdgeScroll() {
        edgeScrollDirection = 0
        edgeScrollSpeed = 0

        edgeScrollRunnable?.let {
            removeCallbacks(it)
        }

        edgeScrollRunnable = null
    }

    private fun resetVisualState() {
        stopEdgeScroll()

        slotViews[draggedTag]?.alpha = 1f

        visualOrder.clear()
        visualOrder.addAll(0 until slotCount)

        targetTranslationByTag.clear()

        applyVisualOrder(true)

        baseOrderWithoutDragged = emptyList()
        currentPlaceholder = -1

        draggedIndex = -1
        draggedTag = -1
        isDragging = false
        dropHandled = false
    }

    private fun resetDragStateInternal() {
        stopEdgeScroll()

        draggedIndex = -1
        draggedTag = -1
        isDragging = false
        dropHandled = false

        baseOrderWithoutDragged = emptyList()
        currentPlaceholder = -1

        targetTranslationByTag.clear()
    }

    private fun formatSlotName(name: String): String {
        if (slotMaxChars <= 0) return name

        // Short names are not affected.
        if (name.length <= 4) return name

        // No need to truncate if it already fits.
        if (name.length <= slotMaxChars) return name

        return name.take(slotMaxChars) + "…"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
