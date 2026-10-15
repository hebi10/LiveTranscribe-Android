package com.charles.livecaptionn.overlay

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.charles.livecaptionn.R
import com.charles.livecaptionn.data.SettingsRepository
import com.charles.livecaptionn.settings.CaptionDisplayMode
import com.charles.livecaptionn.settings.CaptionSettings
import com.charles.livecaptionn.speech.RecognitionStatus
import com.charles.livecaptionn.ui.l10n.UiStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

class OverlayController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val onPauseResume: () -> Unit,
    private val onClose: () -> Unit,
    private val onToggleMinimize: () -> Unit,
    private val onAcceptLanguageSuggestion: (String) -> Unit,
    private val onDismissLanguageSuggestion: (String) -> Unit,
    private val uiStrings: () -> UiStrings = { UiStrings.EMPTY }
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density
    private var root: FrameLayout? = null
    private var statusText: TextView? = null
    private var statusDetail: TextView? = null
    private var controls: LinearLayout? = null
    private var originalContainer: LinearLayout? = null
    private var originalText: TextView? = null
    private var dividerView: View? = null
    private var translatedText: TextView? = null
    private var chatContainer: LinearLayout? = null
    private var body: ScrollView? = null
    private var pauseButton: ImageButton? = null
    private var expandButton: ImageButton? = null
    private var modeButton: ImageButton? = null
    private var sourceButton: ImageButton? = null
    private var minButton: ImageButton? = null
    private var closeButton: ImageButton? = null
    private var resizeHandle: ImageButton? = null
    private var latestAction: LinearLayout? = null
    private var latestIcon: ImageView? = null
    private var latestLabel: TextView? = null
    private var suggestionBar: LinearLayout? = null
    private var suggestionLabel: TextView? = null
    private var suggestionCode: String? = null
    private val buttons = mutableListOf<ImageButton>()
    private val buttonStyles = mutableMapOf<ImageButton, Pair<Int, Boolean>>()
    private val chatRows = linkedMapOf<Long, ChatRow>()
    private var params: WindowManager.LayoutParams? = null
    // Only explicit move/resize actions change the preference; fitting is presentation-only.
    private var geometryState: OverlayGeometryState? = null
    private var lastSettingsGeometry: OverlayGeometry? = null
    private var geometryJob: Job? = null
    private var latestUi: OverlayUiState? = null
    private var lastContent: CaptionContent? = null
    private var lastAppearance: CaptionAppearance? = null
    private var followingLatest = true
    private var pendingViewport: PendingViewport? = null
    private var scrollRevision = 0
    private var scrollPreDrawListener: ViewTreeObserver.OnPreDrawListener? = null

    private data class ChatRow(val container: LinearLayout, val translated: TextView, val source: TextView)
    private data class CaptionContent(
        val mode: CaptionDisplayMode,
        val display: OverlayTextDisplay,
        val messages: List<OverlayCaptionMessage>,
        val showOriginal: Boolean
    )
    private data class CaptionAppearance(val themeId: String, val fontId: String, val sizeSp: Float)
    private data class PendingViewport(val following: Boolean, val position: OverlayScrollPosition)

    private val configurationCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            root?.post {
                preserveViewport()
                clampWindow()
                restoreViewportAfterLayout()
            }
        }
        override fun onLowMemory() = Unit
    }

    fun show(initialX: Int, initialY: Int, widthDp: Int, heightDp: Int) {
        if (root != null) return
        val preferred = OverlayGeometry(initialX, initialY, dp(widthDp), dp(heightDp))
        geometryState = OverlayGeometryState(preferred)
        lastSettingsGeometry = preferred
        val geometry = normalDisplayGeometry() ?: return
        val p = WindowManager.LayoutParams(
            geometry.width, geometry.height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = geometry.x
            y = geometry.y
        }
        params = p
        val frame = FrameLayout(context)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(Color.parseColor("#AA111111"))
            setPadding(dp(12), dp(4), dp(12), dp(8))
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, dp(44))
        }
        statusText = TextView(context).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
            tooltipText = uiStrings()["Move caption window"]
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setOnTouchListener(DragTouchListener())
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.overlay_move_left, "자막 창 왼쪽으로 이동"))
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.overlay_move_right, "자막 창 오른쪽으로 이동"))
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.overlay_move_up, "자막 창 위로 이동"))
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.overlay_move_down, "자막 창 아래로 이동"))
                }
                override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                    when (action) {
                        R.id.overlay_move_left -> moveBy(-dp(24), 0)
                        R.id.overlay_move_right -> moveBy(dp(24), 0)
                        R.id.overlay_move_up -> moveBy(0, -dp(24))
                        R.id.overlay_move_down -> moveBy(0, dp(24))
                        else -> return super.performAccessibilityAction(host, action, args)
                    }
                    return true
                }
            }
        }
        pauseButton = makeButton(R.drawable.ic_overlay_pause, "Pause captioning", onPauseResume)
        expandButton = makeButton(R.drawable.ic_overlay_expand, "Expand controls") {
            val ui = latestUi ?: return@makeButton
            if (ui.minimized) onToggleMinimize()
            else {
                val expanded = !ui.controlsExpanded
                update(ui.copy(controlsExpanded = expanded))
                scope.launch { settingsRepository.update { it.copy(overlayControlsExpanded = expanded) } }
            }
        }
        header.addView(statusText)
        header.addView(pauseButton)
        header.addView(expandButton)

        controls = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        // Scrolling keeps every control reachable on narrow displays.
        val controlStrip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        modeButton = makeButton(R.drawable.ic_overlay_chat, "Switch to chat view") {
            val ui = latestUi ?: return@makeButton
            val mode = if (ui.displayMode == CaptionDisplayMode.CHAT) CaptionDisplayMode.CAPTIONS else CaptionDisplayMode.CHAT
            update(ui.copy(displayMode = mode))
            scope.launch { settingsRepository.update { it.copy(captionDisplayMode = mode) } }
        }
        sourceButton = makeButton(R.drawable.ic_overlay_source, "Show original text") {
            val ui = latestUi ?: return@makeButton
            val showOriginal = !ui.showOriginal
            update(ui.copy(showOriginal = showOriginal))
            scope.launch { settingsRepository.update { it.copy(showOriginal = showOriginal) } }
        }
        minButton = makeButton(R.drawable.ic_overlay_minimize, "Minimize overlay", onToggleMinimize)
        closeButton = makeButton(R.drawable.ic_overlay_close, "Close overlay", onClose)
        controlStrip.addView(modeButton)
        controlStrip.addView(sourceButton)
        controlStrip.addView(minButton)
        controlStrip.addView(closeButton)
        controls?.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(controlStrip)
            layoutParams = LinearLayout.LayoutParams(-1, dp(44))
        })
        statusDetail = TextView(context).apply {
            textSize = 12f
            // Status-only updates cannot alter the caption viewport's height.
            minLines = 2
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, dp(4))
        }
        controls?.addView(statusDetail)
        controls?.addView(createLanguageSuggestionBar())

        originalText = makeCaptionText(14f)
        dividerView = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(0, dp(4), 0, dp(5)) }
        }
        originalContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(originalText)
            addView(dividerView)
        }
        translatedText = makeCaptionText(22f)
        chatContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val scrollContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(-1, -2)
            setPadding(0, dp(2), 0, dp(28))
            addView(originalContainer)
            addView(translatedText)
            addView(chatContainer)
        }
        body = ScrollView(context).apply {
            addView(scrollContent)
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    cancelPendingScroll()
                }
                false
            }
            setOnScrollChangeListener { _, _, _, _, _ ->
                if (pendingViewport == null && latestUi?.minimized != true) {
                    followingLatest = isAtBottom()
                    updateLatestAction()
                }
            }
        }
        container.addView(header)
        container.addView(controls)
        container.addView(body)
        frame.addView(container)

        latestIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_overlay_latest)
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        latestLabel = TextView(context).apply {
            textSize = 12f
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(4), 0, 0, 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        latestAction = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumHeight = dp(44)
            setPadding(dp(10), 0, dp(12), 0)
            addView(latestIcon)
            addView(latestLabel)
            visibility = View.GONE
            isFocusable = true
            layoutParams = FrameLayout.LayoutParams(-2, dp(44), Gravity.BOTTOM or Gravity.START).apply {
                bottomMargin = dp(4)
                marginStart = dp(12)
                marginEnd = dp(52)
            }
            setOnClickListener {
                followingLatest = true
                cancelPendingScroll()
                body?.let { it.scrollTo(0, maximumScrollY()) }
                updateLatestAction()
            }
        }
        frame.addView(latestAction)

        resizeHandle = makeButton(R.drawable.ic_overlay_resize, "Resize caption window") { resizeBy(dp(24)) }.apply {
            layoutParams = FrameLayout.LayoutParams(dp(44), dp(44), Gravity.BOTTOM or Gravity.END)
            setOnTouchListener(ResizeTouchListener())
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, uiStrings()["Enlarge caption window"]))
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, uiStrings()["Shrink caption window"]))
                }
                override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                    when (action) {
                        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> resizeBy(dp(24))
                        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> resizeBy(-dp(24))
                        else -> return super.performAccessibilityAction(host, action, args)
                    }
                    return true
                }
            }
        }
        frame.addView(resizeHandle)
        root = frame
        try {
            wm.addView(frame, p)
            context.registerComponentCallbacks(configurationCallbacks)
            geometryJob = scope.launch {
                settingsRepository.settingsFlow.collect { settings ->
                    val next = OverlayGeometry(settings.overlayX, settings.overlayY,
                        dp(settings.overlayWidthDp), dp(settings.overlayHeightDp))
                    if (next != lastSettingsGeometry) {
                        lastSettingsGeometry = next
                        preserveViewport()
                        geometryState?.preferred = next
                        clampWindow()
                        restoreViewportAfterLayout()
                    }
                }
            }
        } catch (t: Throwable) {
            root = null
            params = null
            throw t
        }
    }

    fun update(ui: OverlayUiState) {
        val frame = root ?: return
        val container = frame.getChildAt(0) as? LinearLayout ?: return
        val previous = latestUi
        val suggestionVisibilityChanged = previous != null && ui.controlsExpanded && !ui.minimized &&
            (previous.suggestedLanguageCode != null) != (ui.suggestedLanguageCode != null)
        val layoutChanged = previous == null || previous.minimized != ui.minimized ||
            previous.controlsExpanded != ui.controlsExpanded || suggestionVisibilityChanged
        val modeChanged = previous != null && previous.displayMode != ui.displayMode
        if (layoutChanged) preserveViewport()
        latestUi = ui
        val theme = OverlayThemeCatalog.find(ui.themeId)
        val font = OverlayFontCatalog.find(ui.fontId)
        val s = uiStrings()
        val effectiveOpacity = ui.opacity.coerceIn(0.2f, 1.0f)
        (container.background as? GradientDrawable)?.setColor(withAlpha(theme.backgroundRgb, (effectiveOpacity * 255).roundToInt()))
        statusText?.apply {
            setTextColor(theme.textRgb)
            setTypefaceIfChanged(this, font.typeface)
            val suggestion = when (ui.suggestedLanguageCode) {
                "en" -> "영어 전환 추천"
                "ja" -> "일본어 전환 추천"
                else -> null
            }
            val label = when {
                ui.skippedTranslations > 0 || ui.skippedSpeechSegments > 0 -> "${s[ui.status.displayName]} · 일부 자막 생략"
                suggestion != null -> "${s[ui.status.displayName]} · $suggestion"
                else -> s[ui.status.displayName]
            }
            setTextIfChanged(this, label)
            contentDescription = "${s["Status"]}: $label. ${s["Move caption window"]}"
        }
        val detail = ui.statusDetail?.trim().orEmpty()
        statusDetail?.apply {
            setTextColor(withAlpha(theme.textRgb, 200))
            setTypefaceIfChanged(this, font.typeface)
            setTextIfChanged(this, detail)
        }
        controls?.visibility = if (ui.controlsExpanded && !ui.minimized) View.VISIBLE else View.GONE
        body?.visibility = if (ui.minimized) View.GONE else View.VISIBLE
        resizeHandle?.visibility = if (ui.minimized) View.GONE else View.VISIBLE
        setButton(pauseButton, if (ui.status == RecognitionStatus.PAUSED) R.drawable.ic_overlay_play else R.drawable.ic_overlay_pause,
            if (ui.status == RecognitionStatus.PAUSED) "Resume captioning" else "Pause captioning")
        setButton(expandButton, when {
            ui.minimized -> R.drawable.ic_overlay_restore
            ui.controlsExpanded -> R.drawable.ic_overlay_collapse
            else -> R.drawable.ic_overlay_expand
        }, when {
            ui.minimized -> "Restore overlay"
            ui.controlsExpanded -> "Collapse controls"
            else -> "Expand controls"
        })
        setButton(modeButton, if (ui.displayMode == CaptionDisplayMode.CHAT) R.drawable.ic_overlay_captions else R.drawable.ic_overlay_chat,
            if (ui.displayMode == CaptionDisplayMode.CHAT) "Switch to caption view" else "Switch to chat view")
        modeButton?.isSelected = ui.displayMode == CaptionDisplayMode.CHAT
        sourceButton?.isSelected = ui.showOriginal
        setButton(sourceButton, R.drawable.ic_overlay_source, if (ui.showOriginal) "Hide original text" else "Show original text")
        for (button in buttons) applyButtonStyle(button, theme.textRgb, button.isSelected || button === resizeHandle)
        latestAction?.background = roundedBackground(withAlpha(theme.backgroundRgb, 245))
        latestIcon?.setColorFilter(theme.textRgb)
        latestLabel?.apply {
            setTextColor(theme.textRgb)
            setTextIfChanged(this, s["Latest captions"])
        }
        latestAction?.contentDescription = s["Latest captions"]
        suggestionCode = ui.suggestedLanguageCode
        suggestionLabel?.apply {
            setTextColor(theme.textRgb)
            setTextIfChanged(this, when (ui.suggestedLanguageCode) {
                "en" -> "영어 음성 감지 (2회)"
                "ja" -> "일본어 음성 감지 (2회)"
                else -> ""
            })
        }
        suggestionBar?.visibility = if (ui.suggestedLanguageCode != null) View.VISIBLE else View.GONE
        suggestionBar?.let { bar ->
            for (index in 0 until bar.childCount) {
                (bar.getChildAt(index) as? TextView)?.let {
                    it.setTextColor(theme.textRgb)
                    setTypefaceIfChanged(it, font.typeface)
                }
            }
        }

        val display = computeOverlayTextDisplay(ui).let {
            if (ui.stableCaptions && ui.messages.isEmpty() && it.translatedText == "…") {
                it.copy(translatedText = s["Captions appear in short speech segments"])
            } else it
        }
        val content = CaptionContent(ui.displayMode, display,
            if (ui.displayMode == CaptionDisplayMode.CHAT) visibleOverlayMessages(ui) else emptyList(), ui.showOriginal)
        val appearance = CaptionAppearance(ui.themeId, ui.fontId, ui.textSizeSp)
        // A recognizer status/detail update must not rewrite caption text or move its viewport.
        if (content != lastContent || appearance != lastAppearance) {
            preserveViewport()
            if (modeChanged) pendingViewport = PendingViewport(true, OverlayScrollPosition(null, 0, 0))
            renderCaptions(content, theme.textRgb, font.typeface)
            lastContent = content
            lastAppearance = appearance
            restoreViewportAfterLayout()
        }
        if (layoutChanged) {
            clampWindow()
            restoreViewportAfterLayout()
        }
        updateLatestAction()
    }

    private fun renderCaptions(content: CaptionContent, color: Int, typeface: Typeface) {
        val display = content.display
        val chat = content.mode == CaptionDisplayMode.CHAT
        originalContainer?.visibility = if (!chat && display.showOriginal) View.VISIBLE else View.GONE
        chatContainer?.visibility = if (chat && content.messages.isNotEmpty()) View.VISIBLE else View.GONE
        translatedText?.visibility = if (!chat || content.messages.isEmpty()) View.VISIBLE else View.GONE
        originalText?.let {
            setTextIfChanged(it, display.originalText)
            applyCaptionStyle(it, withAlpha(color, 190), typeface, display.originalTextSizeSp)
        }
        dividerView?.setBackgroundColor(withAlpha(color, 45))
        translatedText?.let {
            setTextIfChanged(it, display.translatedText)
            applyCaptionStyle(it, color, typeface, display.translatedTextSizeSp)
        }
        if (!chat) return
        val parent = chatContainer ?: return
        val retainedIds = content.messages.mapTo(mutableSetOf()) { it.id }
        val removed = chatRows.keys.filter { it !in retainedIds }
        for (id in removed) chatRows.remove(id)?.let { parent.removeView(it.container) }
        content.messages.forEachIndexed { index, message ->
            val row = chatRows.getOrPut(message.id) {
                val translated = makeCaptionText(display.translatedTextSizeSp)
                val source = makeCaptionText(display.originalTextSizeSp)
                val rowContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 0, 0, dp(10))
                    addView(translated)
                    addView(source)
                }
                ChatRow(rowContainer, translated, source)
            }
            if (parent.getChildAt(index) !== row.container) {
                parent.removeView(row.container)
                parent.addView(row.container, index)
            }
            val translated = message.translatedText.ifBlank { message.originalText }.ifBlank { "…" }
            val sourceVisible = content.showOriginal && message.originalText.isNotBlank() &&
                !message.originalText.trim().equals(translated.trim(), ignoreCase = true)
            setTextIfChanged(row.translated, translated)
            setTextIfChanged(row.source, message.originalText)
            row.source.visibility = if (sourceVisible) View.VISIBLE else View.GONE
            applyCaptionStyle(row.translated, color, typeface, display.translatedTextSizeSp)
            applyCaptionStyle(row.source, withAlpha(color, 180), typeface, display.originalTextSizeSp)
        }
    }

    private fun preserveViewport() {
        if (pendingViewport != null) return
        val scroll = body ?: return
        pendingViewport = PendingViewport(followingLatest, captureOverlayScrollPosition(scroll.scrollY, rowBounds()))
    }

    private fun restoreViewportAfterLayout() {
        val frame = root ?: return
        if (pendingViewport == null) return
        scrollPreDrawListener?.let {
            if (frame.viewTreeObserver.isAlive) frame.viewTreeObserver.removeOnPreDrawListener(it)
        }
        val revision = ++scrollRevision
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (frame.viewTreeObserver.isAlive) frame.viewTreeObserver.removeOnPreDrawListener(this)
                if (scrollPreDrawListener === this) scrollPreDrawListener = null
                if (root !== frame || scrollRevision != revision) return true
                val viewport = pendingViewport ?: return true
                val scroll = body ?: return true
                if (latestUi?.minimized == true) return true // Keep the anchor until restoration.
                val target = if (viewport.following) maximumScrollY()
                else restoreOverlayScrollY(viewport.position, rowBounds(), maximumScrollY())
                scroll.scrollTo(0, target)
                followingLatest = viewport.following || isAtBottom()
                pendingViewport = null
                updateLatestAction()
                return true
            }
        }
        scrollPreDrawListener = listener
        frame.viewTreeObserver.addOnPreDrawListener(listener)
        frame.invalidate()
    }

    private fun cancelPendingScroll() {
        scrollPreDrawListener?.let { listener ->
            root?.viewTreeObserver?.let { if (it.isAlive) it.removeOnPreDrawListener(listener) }
        }
        scrollPreDrawListener = null
        pendingViewport = null
        scrollRevision++
    }

    private fun rowBounds(): List<OverlayRowBounds> {
        if (latestUi?.displayMode != CaptionDisplayMode.CHAT) return emptyList()
        val parent = chatContainer ?: return emptyList()
        return (0 until parent.childCount).mapNotNull { index ->
            val child = parent.getChildAt(index)
            val id = chatRows.entries.firstOrNull { it.value.container === child }?.key ?: return@mapNotNull null
            OverlayRowBounds(id, parent.top + child.top, parent.top + child.bottom)
        }
    }

    private fun maximumScrollY(): Int {
        val scroll = body ?: return 0
        val contentHeight = scroll.getChildAt(0)?.height ?: 0
        return (contentHeight - (scroll.height - scroll.paddingTop - scroll.paddingBottom)).coerceAtLeast(0)
    }

    private fun isAtBottom(): Boolean {
        val scroll = body ?: return true
        return isOverlayAtBottom(scroll.scrollY, scroll.height - scroll.paddingTop - scroll.paddingBottom,
            scroll.getChildAt(0)?.height ?: 0, dp(8))
    }

    private fun updateLatestAction() {
        latestAction?.visibility = if (!followingLatest && latestUi?.minimized != true) View.VISIBLE else View.GONE
    }

    fun hide() {
        geometryJob?.cancel()
        geometryJob = null
        cancelPendingScroll()
        if (root != null) context.unregisterComponentCallbacks(configurationCallbacks)
        root?.let { try { wm.removeViewImmediate(it) } catch (_: IllegalArgumentException) { } }
        root = null
        params = null
        geometryState = null
        lastSettingsGeometry = null
        latestUi = null
        lastContent = null
        lastAppearance = null
        pendingViewport = null
        scrollRevision++
        followingLatest = true
        statusText = null
        statusDetail = null
        controls = null
        originalContainer = null
        originalText = null
        dividerView = null
        translatedText = null
        chatContainer = null
        chatRows.clear()
        body = null
        pauseButton = null
        expandButton = null
        modeButton = null
        sourceButton = null
        minButton = null
        closeButton = null
        resizeHandle = null
        latestAction = null
        latestIcon = null
        latestLabel = null
        buttons.clear()
        buttonStyles.clear()
        suggestionBar = null
        suggestionLabel = null
        suggestionCode = null
    }

    private fun makeCaptionText(sizeSp: Float) = TextView(context).apply {
        textSize = sizeSp
        setTextColor(Color.WHITE)
        setLineSpacing(dp(2).toFloat(), 1.05f)
        breakStrategy = LineBreaker.BREAK_STRATEGY_SIMPLE
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    private fun applyCaptionStyle(view: TextView, color: Int, typeface: Typeface, sizeSp: Float) {
        view.setTextColor(color)
        setTypefaceIfChanged(view, typeface)
        if (abs(view.textSize / context.resources.displayMetrics.scaledDensity - sizeSp) > 0.01f) view.textSize = sizeSp
    }

    private fun setTextIfChanged(view: TextView, value: String) {
        if (view.text.toString() != value) view.text = value
    }

    private fun setTypefaceIfChanged(view: TextView, typeface: Typeface) {
        if (view.typeface != typeface) view.typeface = typeface
    }

    private fun makeButton(resId: Int, key: String, onClick: () -> Unit) = ImageButton(context).apply {
        setImageResource(resId)
        tag = resId
        contentDescription = uiStrings()[key]
        tooltipText = contentDescription
        background = roundedBackground(Color.TRANSPARENT)
        setColorFilter(Color.WHITE)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        setOnClickListener { onClick() }
        buttons.add(this)
    }

    private fun setButton(button: ImageButton?, resId: Int, key: String) {
        button ?: return
        if (button.tag != resId) {
            button.setImageResource(resId)
            button.tag = resId
        }
        button.contentDescription = uiStrings()[key]
        button.tooltipText = button.contentDescription
    }

    private fun applyButtonStyle(button: ImageButton, color: Int, selected: Boolean) {
        val style = color to selected
        if (buttonStyles[button] == style) return
        button.setColorFilter(color)
        button.background = roundedBackground(if (selected) withAlpha(color, 28) else Color.TRANSPARENT)
        buttonStyles[button] = style
    }

    private fun createLanguageSuggestionBar(): LinearLayout {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        suggestionLabel = TextView(context).apply {
            textSize = 12f
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
        }
        fun choice(label: String, description: String, action: (String) -> Unit) = TextView(context).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
            minWidth = dp(44)
            minHeight = dp(44)
            setTextColor(Color.WHITE)
            contentDescription = description
            setOnClickListener { suggestionCode?.let(action) }
        }
        bar.addView(suggestionLabel)
        bar.addView(choice("변경", "추천 음성 언어로 변경", onAcceptLanguageSuggestion))
        bar.addView(choice("유지", "기존 음성 언어 유지", onDismissLanguageSuggestion))
        suggestionBar = bar
        return bar
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()
    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    private fun roundedBackground(color: Int) = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(color)
    }

    private fun usableBounds(): OverlayBounds {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.maximumWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val bounds = metrics.bounds
            return OverlayBounds(insets.left, insets.top, bounds.width() - insets.right, bounds.height() - insets.bottom)
        }
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        fun systemDimension(name: String): Int {
            val id = context.resources.getIdentifier(name, "dimen", "android")
            return if (id != 0) context.resources.getDimensionPixelSize(id) else 0
        }
        val navigation = systemDimension("navigation_bar_height")
        return OverlayBounds(0, systemDimension("status_bar_height"),
            metrics.widthPixels - if (metrics.widthPixels > metrics.heightPixels) navigation else 0,
            metrics.heightPixels - navigation)
    }

    private val minimumWidth get() = dp(maxOf(CaptionSettings.MIN_OVERLAY_WIDTH_DP, 220))
    private val minimumHeight get() = dp(maxOf(CaptionSettings.MIN_OVERLAY_HEIGHT_DP, 108))

    private fun normalDisplayGeometry(): OverlayGeometry? = geometryState?.display(
        usableBounds(), minimumWidth, minimumHeight)

    private fun displayGeometry(): OverlayGeometry? = geometryState?.display(
        usableBounds(), minimumWidth, minimumHeight,
        minimizedHeight = if (latestUi?.minimized == true) dp(56) else null,
        expandedMinHeight = if (latestUi?.controlsExpanded == true) dp(220) else null)

    private fun clampWindow() {
        val view = root ?: return
        val lp = params ?: return
        val display = displayGeometry() ?: return
        lp.x = display.x
        lp.y = display.y
        lp.width = display.width
        lp.height = display.height
        try { wm.updateViewLayout(view, lp) } catch (_: IllegalArgumentException) { }
    }

    private fun persistGeometry() {
        val geometry = geometryState?.preferred ?: return
        scope.launch {
            settingsRepository.update {
                it.copy(overlayX = geometry.x, overlayY = geometry.y,
                    overlayWidthDp = (geometry.width / density).roundToInt(),
                    overlayHeightDp = (geometry.height / density).roundToInt())
            }
        }
    }

    private fun resizeBy(delta: Int) {
        val normal = normalDisplayGeometry() ?: return
        preserveViewport()
        geometryState?.resizeTo(normal.width + delta, normal.height + delta,
            usableBounds(), minimumWidth, minimumHeight)
        clampWindow()
        restoreViewportAfterLayout()
        persistGeometry()
    }

    private fun moveBy(dx: Int, dy: Int) {
        geometryState?.moveBy(dx, dy, usableBounds(), minimumWidth, minimumHeight,
            minimizedHeight = if (latestUi?.minimized == true) dp(56) else null,
            expandedMinHeight = if (latestUi?.controlsExpanded == true) dp(220) else null) ?: return
        clampWindow()
        persistGeometry()
    }

    private fun moveTo(x: Int, y: Int) {
        geometryState?.moveTo(x, y, usableBounds(), minimumWidth, minimumHeight,
            minimizedHeight = if (latestUi?.minimized == true) dp(56) else null,
            expandedMinHeight = if (latestUi?.controlsExpanded == true) dp(220) else null)
        clampWindow()
    }

    private inner class DragTouchListener : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var moved = false
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val lp = params ?: return false
            if (root == null) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).roundToInt()
                    val dy = (event.rawY - touchY).roundToInt()
                    if (!moved && abs(dx) <= touchSlop && abs(dy) <= touchSlop) return true
                    moved = true
                    moveTo(startX + dx, startY + dy)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moved) persistGeometry()
                    moved = false
                    return true
                }
            }
            return false
        }
    }

    private inner class ResizeTouchListener : View.OnTouchListener {
        private var startW = 0
        private var startH = 0
        private var touchX = 0f
        private var touchY = 0f
        private var resized = false
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            if (params == null) return false
            if (root == null || latestUi?.minimized == true) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val normal = normalDisplayGeometry() ?: return false
                    startW = normal.width
                    // Expanded controls temporarily enlarge lp.height; resize from fitted normal size.
                    startH = normal.height
                    touchX = event.rawX
                    touchY = event.rawY
                    resized = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).roundToInt()
                    val dy = (event.rawY - touchY).roundToInt()
                    if (!resized && abs(dx) <= touchSlop && abs(dy) <= touchSlop) return true
                    resized = true
                    preserveViewport()
                    geometryState?.resizeTo(startW + dx, startH + dy,
                        usableBounds(), minimumWidth, minimumHeight)
                    clampWindow()
                    restoreViewportAfterLayout()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (resized) persistGeometry() else v.performClick()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (resized) persistGeometry()
                    resized = false
                    return true
                }
            }
            return false
        }
    }
}
