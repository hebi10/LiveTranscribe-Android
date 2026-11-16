package com.charles.livecaptionn.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.charles.livecaptionn.R
import com.charles.livecaptionn.data.SettingsRepository
import com.charles.livecaptionn.settings.BubbleSide
import com.charles.livecaptionn.settings.BubbleCaptionOptions
import com.charles.livecaptionn.settings.CaptionSettings
import com.charles.livecaptionn.settings.CorrectionHighlightPalette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A separate WindowManager surface, rather than a minimized full-size popup.
 * Its window occupies only the visible button, translation and open controls.
 */
class BubbleOverlayController(
    sourceContext: Context,
    private val scope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val onPauseResume: () -> Unit,
    private val onStop: () -> Unit
) {
    private val context = bubbleWindowContext(sourceContext)
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density
    private var side = BubbleSide.RIGHT
    private var preferredY = 260
    private var storedSide = BubbleSide.RIGHT
    private var storedY = 260
    private var settingsOpen = false
    private var attached = false
    private var windowParams: WindowManager.LayoutParams? = null
    private var latestUi: OverlayUiState? = null
    private var latestSettings = CaptionSettings()
    private val presentation = BubbleCaptionPresentationState()
    private var captionVisible = false
    private var timer: Job? = null
    private var scheduledFor: Long? = null

    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.TRANSPARENT)
    }
    private val captionRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val translatedView = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 16f
        setPadding(0, 0, 0, 0)
        setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE)
    }
    private val originalView = TextView(context).apply {
        setTextColor(Color.LTGRAY)
        textSize = 13f
        visibility = View.GONE
    }
    private val captionContent = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setBackgroundColor(Color.rgb(32, 32, 32))
        addView(translatedView)
        addView(originalView)
    }
    private val captionScroll = object : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val heightLimit = View.MeasureSpec.makeMeasureSpec(
                (displayHeight() / 3).coerceAtLeast(dp(80)), View.MeasureSpec.AT_MOST
            )
            super.onMeasure(widthMeasureSpec, heightLimit)
        }
    }.apply {
        isVerticalScrollBarEnabled = true
        isFillViewport = false
        visibility = View.GONE
        addView(captionContent)
        setPadding(dp(4), 0, dp(4), 0)
    }
    private val bubbleButton = ImageButton(context).apply {
        setImageResource(R.drawable.ic_overlay_captions)
        imageTintList = ColorStateList.valueOf(Color.WHITE)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.rgb(35, 35, 35))
            setStroke(dp(1), Color.GRAY)
        }
        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(10), dp(10), dp(10), dp(10))
        contentDescription = "번역 버블 설정 열기"
        minimumHeight = dp(48)
        minimumWidth = dp(48)
        setOnClickListener {
            settingsOpen = !settingsOpen
            refreshView()
        }
        setOnTouchListener(DragListener())
    }
    private val menu = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setBackgroundColor(Color.rgb(36, 36, 36))
    }
    private val durationAction = menuAction("자막 유지 5초") {
        val values = BubbleCaptionOptions.durationsMs
        val current = latestSettings.bubbleHoldMs
        val next = values[(values.indexOf(current).coerceAtLeast(0) + 1) % values.size]
        latestSettings = latestSettings.copy(bubbleHoldMs = next)
        refreshView()
        scope.launch { settingsRepository.update { it.copy(bubbleHoldMs = next) } }
    }
    private val textSizeAction = menuAction("글자 크기 16") {
        val values = BubbleCaptionOptions.textSizesSp
        val current = latestSettings.bubbleTextSizeSp
        val next = values[(values.indexOf(current).coerceAtLeast(0) + 1) % values.size]
        latestSettings = latestSettings.copy(bubbleTextSizeSp = next)
        translatedView.textSize = next
        originalView.textSize = (next - 2f).coerceAtLeast(12f)
        refreshView()
        scope.launch { settingsRepository.update { it.copy(bubbleTextSizeSp = next) } }
    }
    private val originalAction = menuAction("원문 숨김") {
        val next = !latestSettings.bubbleShowOriginal
        latestSettings = latestSettings.copy(bubbleShowOriginal = next)
        latestUi?.let { update(it, latestSettings) }
        scope.launch { settingsRepository.update { it.copy(bubbleShowOriginal = next) } }
    }
    private val focusAction = menuAction("시청 집중 켜짐") {
        val next = !latestSettings.readingFocusMode
        latestSettings = latestSettings.copy(readingFocusMode = next)
        latestUi?.let { update(it.copy(readingFocusMode = next), latestSettings) }
        scope.launch { settingsRepository.update { it.copy(readingFocusMode = next) } }
    }
    private val pauseAction = menuAction("일시정지") { onPauseResume() }
    private val stopAction = menuAction("번역 종료") { onStop() }
    private val resetAction = menuAction("버블 위치 초기화") {
        side = BubbleSide.RIGHT
        preferredY = 260
        storedSide = side
        storedY = preferredY
        arrangeForSide()
        refreshView()
        scope.launch {
            settingsRepository.update { it.copy(bubbleSide = BubbleSide.RIGHT, bubbleY = 260) }
        }
    }
    private val statusText = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 14f
        setPadding(dp(6), dp(4), dp(6), dp(4))
        text = "번역 대기 중"
    }

    init {
        captionRow.addView(captionScroll)
        captionRow.addView(bubbleButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        menu.addView(statusText)
        menu.addView(durationAction)
        menu.addView(textSizeAction)
        menu.addView(originalAction)
        menu.addView(focusAction)
        menu.addView(resetAction)
        menu.addView(pauseAction)
        menu.addView(stopAction)
        root.addView(captionRow)
        root.addView(menu)
    }

    fun show(settings: CaptionSettings) {
        if (attached) return
        latestSettings = settings
        side = settings.bubbleSide
        preferredY = settings.bubbleY.coerceAtLeast(0)
        storedSide = side
        storedY = preferredY
        arrangeForSide()
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or if (side == BubbleSide.RIGHT) Gravity.END else Gravity.START
        lp.x = dp(8)
        lp.y = fittedY(preferredY)
        windowManager.addView(root, lp)
        windowParams = lp
        attached = true
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitAfterLayout() }
        refreshView()
    }

    fun update(ui: OverlayUiState, settings: CaptionSettings) {
        latestUi = ui
        latestSettings = settings
        if (settings.bubbleSide != storedSide || settings.bubbleY != storedY) {
            storedSide = settings.bubbleSide
            storedY = settings.bubbleY
            side = storedSide
            preferredY = storedY.coerceAtLeast(0)
            arrangeForSide()
        }
        translatedView.textSize = settings.bubbleTextSizeSp
        originalView.textSize = (settings.bubbleTextSizeSp - 2f).coerceAtLeast(12f)
        val now = SystemClock.elapsedRealtime()
        val state = presentation.reduce(ui, settings.bubbleHoldMs, now)
        captionVisible = state.visible
        val last = state.message
        translatedView.text = if (last != null) correctionStyledCaption(
            last.translatedText,
            if (ui.correctionHighlightEnabled) last.correctionRanges else emptyList(),
            last.correctionHighlightUntilMs,
            CorrectionHighlightPalette.colorInt(ui.correctionColor, false),
            now
        ) else ""
        originalView.text = last?.originalText.orEmpty()
        originalView.visibility = if (state.visible && settings.bubbleShowOriginal &&
            !ui.readingFocusMode && last != null && last.originalText.isNotBlank())
            View.VISIBLE else View.GONE
        statusText.text = when {
            ui.stopping -> "번역 종료 중"
            ui.status == com.charles.livecaptionn.speech.RecognitionStatus.PAUSED -> "일시정지"
            ui.status == com.charles.livecaptionn.speech.RecognitionStatus.ERROR -> "번역 오류"
            ui.recognizing -> "음성 인식 중"
            ui.translating -> "번역 중"
            else -> ui.phase.label
        }
        refreshView()
        val highlightExpiry = last?.correctionHighlightUntilMs?.takeIf {
            ui.correctionHighlightEnabled && it > now && last.correctionRanges.isNotEmpty()
        }
        scheduleRefresh(listOfNotNull(state.expiresAtMs, highlightExpiry).minOrNull(), now)
    }

    fun hide() {
        timer?.cancel()
        timer = null
        scheduledFor = null
        presentation.clear()
        if (attached) {
            attached = false
            try {
                windowManager.removeViewImmediate(root)
            } catch (_: IllegalArgumentException) { }
        }
        windowParams = null
        latestUi = null
        settingsOpen = false
    }

    private fun refreshView() {
        if (!attached) return
        val hasCaption = captionVisible &&
            latestUi?.stopping != true && !settingsOpen
        captionScroll.visibility = if (hasCaption) View.VISIBLE else View.GONE
        captionScroll.layoutParams = LinearLayout.LayoutParams(
            bubbleCaptionWidth(displayWidth(), density),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        menu.visibility = if (settingsOpen) View.VISIBLE else View.GONE
        durationAction.text = "자막 유지 " + (latestSettings.bubbleHoldMs / 1000) + "초"
        textSizeAction.text = "글자 크기 " + latestSettings.bubbleTextSizeSp.toInt()
        originalAction.text = if (latestSettings.bubbleShowOriginal) "원문 켜짐" else "원문 숨김"
        focusAction.text = if (latestSettings.readingFocusMode) "시청 집중 켜짐" else "시청 집중 꺼짐"
        pauseAction.text = if (latestUi?.status ==
            com.charles.livecaptionn.speech.RecognitionStatus.PAUSED) "번역 재개" else "일시정지"
        pauseAction.isEnabled = latestUi?.stopping != true
        stopAction.isEnabled = latestUi?.stopping != true
        menu.layoutParams = LinearLayout.LayoutParams(
            bubbleCaptionWidth(displayWidth(), density),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        bubbleButton.contentDescription = if (settingsOpen) "번역 버블 설정 닫기" else "번역 버블 설정 열기"
        fitAfterLayout()
    }

    private fun scheduleRefresh(deadline: Long?, now: Long) {
        if (scheduledFor == deadline) return
        timer?.cancel()
        timer = null
        scheduledFor = deadline
        if (deadline == null) return
        timer = scope.launch {
            delay((deadline - now).coerceAtLeast(1L))
            scheduledFor = null
            latestUi?.let { update(it, latestSettings) }
        }
    }

    private fun arrangeForSide() {
        captionRow.removeAllViews()
        captionRow.gravity = if (side == BubbleSide.RIGHT) Gravity.END else Gravity.START
        val buttonParams = LinearLayout.LayoutParams(dp(48), dp(48))
        if (side == BubbleSide.RIGHT) {
            captionRow.addView(captionScroll)
            captionRow.addView(bubbleButton, buttonParams)
        } else {
            captionRow.addView(bubbleButton, buttonParams)
            captionRow.addView(captionScroll)
        }
        root.gravity = if (side == BubbleSide.RIGHT) Gravity.END else Gravity.START
    }

    private fun fitAfterLayout() {
        if (!attached) return
        val lp = windowParams ?: return
        val fitted = fittedY(preferredY)
        val gravity = Gravity.TOP or if (side == BubbleSide.RIGHT) Gravity.END else Gravity.START
        if (lp.y != fitted || lp.gravity != gravity) {
            lp.y = fitted
            lp.gravity = gravity
            windowManager.updateViewLayout(root, lp)
        }
    }

    private fun fittedY(y: Int): Int {
        val area = displayBounds()
        val (top, bottom) = insets()
        return fittedBubbleY(y, area.height(), top, bottom,
            root.height.coerceAtLeast(dp(48)))
    }

    private fun displayBounds(): Rect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            windowManager.currentWindowMetrics.bounds
        else Rect(0, 0, context.resources.displayMetrics.widthPixels,
            context.resources.displayMetrics.heightPixels)

    private fun insets(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val system = windowManager.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
            system.top to system.bottom
        } else dp(24) to dp(24)

    private fun displayWidth(): Int = displayBounds().width()
    private fun displayHeight(): Int = displayBounds().height()
    private fun dp(value: Int): Int = (value * density).roundToInt()

    private fun menuAction(label: String, action: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(48)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { action() }
        }

    private inner class DragListener : View.OnTouchListener {
        private var startRawX = 0f
        private var startRawY = 0f
        private var startY = 0
        private var startSide = BubbleSide.RIGHT
        private var dragging = false
        private val slop = ViewConfiguration.get(context).scaledTouchSlop

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startY = preferredY
                    startSide = side
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    if (!dragging && abs(dx) < slop && abs(dy) < slop) return true
                    dragging = true
                    settingsOpen = false
                    preferredY = fittedY(startY + dy.roundToInt())
                    val next = bubbleSideAt(event.rawX, displayWidth())
                    if (next != side) {
                        side = next
                        arrangeForSide()
                    }
                    refreshView()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        scope.launch {
                            settingsRepository.update {
                                it.copy(bubbleSide = side, bubbleY = preferredY)
                            }
                        }
                    } else view.performClick()
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        side = startSide
                        preferredY = startY
                        arrangeForSide()
                        refreshView()
                    }
                    dragging = false
                    return true
                }
            }
            return false
        }
    }
}

private fun bubbleWindowContext(source: Context): Context {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return source
    val display = (source.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
        .getDisplay(Display.DEFAULT_DISPLAY) ?: return source
    return source.createDisplayContext(display)
        .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
}
