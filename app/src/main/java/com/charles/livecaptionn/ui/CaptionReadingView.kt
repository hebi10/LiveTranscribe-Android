package com.charles.livecaptionn.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.charles.livecaptionn.overlay.OverlayCaptionMessage
import com.charles.livecaptionn.overlay.OverlayLineBounds
import com.charles.livecaptionn.overlay.OverlayRowBounds

/** Native text layouts expose character offsets, preserving the reading line across reflow. */
internal class CaptionReadingView(context: Context, private val reader: CaptionReadingState) : ScrollView(context) {
    private data class Row(val id: Long, val container: LinearLayout, val translation: TextView, val source: TextView)
    private data class Content(val messages: List<OverlayCaptionMessage>, val showOriginal: Boolean,
        val sizeSp: Float, val foreground: Int, val secondary: Int)

    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(24))
    }
    private val rows = mutableListOf<Row>()
    private var content: Content? = null
    private var previousWidthSpec = -1
    private var previousHeightSpec = -1
    private var bounds = emptyList<OverlayRowBounds>()
    private var pendingRestore = reader.position != null
    private var layingOut = false
    var onFollowingChanged: (Boolean) -> Unit = {}

    init {
        isFillViewport = false
        clipToPadding = true
        isSaveEnabled = false // Compose saves the character anchor, not ScrollView's pixel offset.
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        contentDescription = "번역 자막. 스크롤하여 이전 자막 읽기"
    }

    fun render(messages: List<OverlayCaptionMessage>, showOriginal: Boolean, sizeSp: Float, foreground: Int, secondary: Int) {
        val next = Content(messages, showOriginal, sizeSp, foreground, secondary)
        if (next == content) return // Recognition/status-only emissions never move the reader.
        preserve()
        // The first render after recreation must restore its saved anchor, including while following.
        reader.contentChanged(content?.let { it.messages.lastOrNull() != messages.lastOrNull() }
            ?: (reader.position == null))
        content = next
        val retained = rows.associateBy { it.id }
        column.removeAllViews()
        rows.clear()
        messages.forEach { message ->
            val row = retained[message.id] ?: newRow(message.id)
            row.translation.text = message.translatedText
            row.translation.textSize = sizeSp
            row.translation.setTextColor(foreground)
            row.source.text = message.originalText
            row.source.textSize = (sizeSp * 0.8f).coerceAtLeast(12f)
            row.source.setTextColor(secondary)
            row.source.visibility = if (showOriginal && message.originalText.isNotBlank() &&
                !message.originalText.trim().equals(message.translatedText.trim(), ignoreCase = true)) View.VISIBLE else View.GONE
            rows += row
            column.addView(row.container)
        }
        pendingRestore = true
        requestLayout()
    }

    fun showLatest() {
        reader.requestLatest()
        pendingRestore = true
        requestLayout()
    }

    private fun newRow(id: Long): Row {
        fun text() = TextView(context).apply {
            includeFontPadding = false
            setLineSpacing(dp(5).toFloat(), 1f)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val translation = text()
        val source = text().apply { setPadding(0, dp(8), 0, 0) }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(22))
            addView(translation, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(source, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        return Row(id, container, translation, source)
    }

    private fun preserve() {
        if (!pendingRestore && bounds.isNotEmpty()) reader.capture(scrollY, bounds)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (previousWidthSpec != widthMeasureSpec || previousHeightSpec != heightMeasureSpec) {
            preserve()
            pendingRestore = true
            previousWidthSpec = widthMeasureSpec
            previousHeightSpec = heightMeasureSpec
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        layingOut = true
        super.onLayout(changed, l, t, r, b)
        bounds = rows.map { row ->
            val top = column.top + row.container.top
            val lines = lines(row.translation, top + row.translation.top, false) +
                if (row.source.visibility == View.VISIBLE) lines(row.source, top + row.source.top, true) else emptyList()
            OverlayRowBounds(row.id, lines.firstOrNull()?.top ?: top, top + row.container.height, lines)
        }
        if (pendingRestore && height > 0) {
            scrollTo(0, reader.restore(bounds, maximumY(), height - paddingTop - paddingBottom))
            pendingRestore = false
        }
        layingOut = false
        onFollowingChanged(reader.followingLatest)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (!layingOut && !pendingRestore) {
            reader.userScrolled(t, bounds, maximumY())
            onFollowingChanged(reader.followingLatest)
        }
    }

    private fun lines(view: TextView, top: Int, source: Boolean): List<OverlayLineBounds> {
        val layout = view.layout ?: return emptyList()
        return (0 until layout.lineCount).map { index ->
            OverlayLineBounds(layout.getLineStart(index), top + view.totalPaddingTop + layout.getLineTop(index),
                top + view.totalPaddingTop + layout.getLineBottom(index), source)
        }
    }

    private fun maximumY() = (column.height - (height - paddingTop - paddingBottom)).coerceAtLeast(0)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
