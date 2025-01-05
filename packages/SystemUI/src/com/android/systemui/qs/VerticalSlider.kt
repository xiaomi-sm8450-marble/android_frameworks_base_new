/*
 * Copyright (C) 2024 the risingOS Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.qs

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.animation.DecelerateInterpolator
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import androidx.cardview.widget.CardView
import com.android.systemui.res.R

import kotlin.math.abs
import kotlin.math.roundToInt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

import com.android.internal.util.android.VibrationUtils

interface UserInteractionListener {
    fun onLongPress()
    fun onUserSwipe()
}

open class VerticalSlider(context: Context, attrs: AttributeSet? = null) : CardView(context, attrs) {

    protected val scope = CoroutineScope(Dispatchers.IO)

    private val listeners: MutableList<UserInteractionListener> = mutableListOf()
    
    private val horizontalSwipeThreshold = context.resources.getDimensionPixelSize(R.dimen.qs_slider_swipe_threshold_dp)
    
    private var iconView: ImageView? = null
    private var hapticsKey: String = ""
    private var hapticDefValue: Int = 0
    private var progressAnimator: ValueAnimator? = null

    private var moved = false
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val mHandler = Handler()
    private var isLongPress = false
    private val longPressRunnable = Runnable {
            isLongPress = true
        }

    protected var isUserInteracting = false

    protected var progress: Int = 0
    protected var lastProgress: Int = 0
    private val cornerRadius = context.resources.getDimensionPixelSize(R.dimen.qs_controls_slider_corner_radius).toFloat()
    private val layoutRect: RectF = RectF()
    protected val progressRect: RectF = RectF()
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isAntiAlias = true
        isDither = true
        isFilterBitmap = true
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    }
    private val layoutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isAntiAlias = true
        isDither = true
        isFilterBitmap = true
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    }
    private val path = Path()
    private val threshold = 0.05f
    private var actionPerformed = false

    private var lastVibrateTime: Long = 0
    private val SLIDER_HAPTICS_TIMEOUT: Long = 100

    private val isNightMode: Boolean
        get() = true // on default qs we always use dark mode

    private val mTouchListener = object : View.OnTouchListener {
        private var initY = 0f
        private var initPct = 0f

        override fun onTouch(view: View, motionEvent: MotionEvent): Boolean {
            when (motionEvent.action) {
                MotionEvent.ACTION_DOWN -> {
                    progressAnimator?.cancel()
                    initY = motionEvent.y
                    initPct = 1 - (initY / view.height)
                    isUserInteracting = true
                    actionPerformed = false
                    isLongPress = false
                    moved = false
                    mHandler.postDelayed(longPressRunnable, longPressTimeout)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && isLongPress) {
                        doLongPressAction()
                        return true
                    }
                    val newPct = 1 - (motionEvent.y / view.height)
                    val deltaPct = abs(newPct - initPct)
                    if (deltaPct > 0.03f) {
                        moved = true
                        isLongPress = false
                        mHandler.removeCallbacks(longPressRunnable)
                        view.parent.requestDisallowInterceptTouchEvent(true)
                        progress = (newPct * 100).coerceIn(0f, 100f).toInt()
                        lastProgress = progress
                        notifyListenersUserSwipe()
                        performSliderHaptics()
                        updateProgressRect()
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mHandler.removeCallbacks(longPressRunnable)
                    if (isLongPress) {
                        isLongPress = false
                    } else if (moved) {
                        moved = false
                    }
                    updateProgressRect()
                    mHandler.postDelayed({ isUserInteracting = false }, 200)
                    return true
                }
                else -> return false
            }
        }
    }

    init {
        setOnTouchListener(mTouchListener)
        backgroundTintList = ColorStateList.valueOf(
            context.getResources().getColor(if (isNightMode) R.color.qs_controls_container_bg_color_dark
            else R.color.qs_controls_container_bg_color_light)
        )
        radius = cornerRadius
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        updateSliderPaint()
    }

    fun setSliderHapticKey(key: String, defValue: Int) {
        hapticsKey = key
        hapticDefValue = defValue
    }

    fun performSliderHaptics() {
        if (hapticsKey.isEmpty()) return
        val intensity = Settings.System.getIntForUser(context.getContentResolver(),
                hapticsKey, hapticDefValue, UserHandle.USER_CURRENT)
        if (intensity == 0) return
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastVibrateTime >= SLIDER_HAPTICS_TIMEOUT) {
            VibrationUtils.triggerVibration(context, intensity)
            lastVibrateTime = currentTime
        }
    }

    private fun doLongPressAction() {
        if (isLongPress && !actionPerformed) {
            listeners.forEach { it.onLongPress() }
            VibrationUtils.triggerVibration(context, 4)
            actionPerformed = true
            isLongPress = false
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return true
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_MOVE && (event.y <= 0 || event.y >= measuredHeight)) {
            return false
        }
        return true
    }

    fun addUserInteractionListener(listener: UserInteractionListener) {
        listeners.add(listener)
    }

    fun removeUserInteractionListener(listener: UserInteractionListener) {
        listeners.remove(listener)
    }

    private fun notifyListenersUserSwipe() {
        listeners.forEach { it.onUserSwipe() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutRect.set(0f, 0f, w.toFloat(), h.toFloat())
        progressRect.set(0f, (1 - progress / 100f) * h, w.toFloat(), h.toFloat())
        path.reset()
        path.addRoundRect(layoutRect, cornerRadius, cornerRadius, Path.Direction.CW)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (measuredHeight > 0 && measuredWidth > 0) {
            layoutRect.set(0f, 0f, measuredWidth.toFloat(), measuredHeight.toFloat())
            progressRect.set(
                0f,
                (1 - progress / 100f) * measuredHeight,
                measuredWidth.toFloat(),
                measuredHeight.toFloat()
            )
            path.reset()
            path.addRoundRect(layoutRect, cornerRadius, cornerRadius, Path.Direction.CW)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val roundedRectPath = Path().apply {
            addRoundRect(layoutRect, cornerRadius, cornerRadius, Path.Direction.CW)
        }
        canvas.clipPath(roundedRectPath)
        canvas.drawRoundRect(layoutRect, cornerRadius, cornerRadius, layoutPaint)
        val progressRadii = floatArrayOf(
            0f, 0f,   // Top-left radius
            0f, 0f,   // Top-right radius
            cornerRadius, cornerRadius, // Bottom-right radius
            cornerRadius, cornerRadius  // Bottom-left radius
        )
        val progressRectPath = Path().apply {
            addRoundRect(progressRect, progressRadii, Path.Direction.CW)
        }
        canvas.drawPath(progressRectPath, progressPaint)
    }
    
    fun qsPaneStyle(): Int {
        return Settings.System.getIntForUser(context.contentResolver, 
            Settings.System.QS_PANEL_STYLE, 0, UserHandle.USER_CURRENT)
    }

    fun translucentQsStyle(): Boolean {
        val translucentStyles = listOf(1, 2, 3)
        return translucentStyles.contains(qsPaneStyle())
    }

    protected open fun updateSliderPaint() {
        val progressAlpha = if (translucentQsStyle()) {
            context.resources.getFloat(R.dimen.qs_controls_translucent_alpha)
        } else 1f
        val backgroundAlpha = if (translucentQsStyle()) 0.8f else 1f
        progressPaint.color = context.getColor(
            if (isNightMode) R.color.qs_controls_active_color_dark 
            else R.color.qs_controls_active_color_light
        )
        progressPaint.alpha = (progressAlpha * 255).roundToInt()
        layoutPaint.color = context.getColor(
            if (isNightMode) R.color.qs_controls_container_bg_color_dark 
            else R.color.qs_controls_container_bg_color_light
        )
        layoutPaint.alpha = (backgroundAlpha * 255).roundToInt()
        updateIconTint()
        invalidate()
    }

    fun setIconView(iv: ImageView?) {
        iconView = iv
    }

    fun updateIconTint() {
        val emptyThreshold = 20 // 20% of 100
        val isEmpty = progress <= emptyThreshold
        val iconColorRes = when {
            isEmpty -> if (isNightMode) R.color.qs_controls_bg_color_light 
                else R.color.qs_controls_bg_color_dark
            translucentQsStyle() -> if (isNightMode) R.color.qs_controls_active_color_dark 
                else R.color.qs_controls_active_color_light
            else -> if (isNightMode) R.color.qs_controls_active_color_light 
                else R.color.qs_controls_active_color_dark
        }
        val color = context.getResources().getColor(iconColorRes)
        iconView?.setColorFilter(color, PorterDuff.Mode.SRC_IN)
    }
    
    fun setSliderProgress(sliderProgress: Int) {
        progress = sliderProgress.coerceIn(0, 100)
    }

    protected open fun updateProgressRect() {
        val calculatedProgress = progress / 100f
        val newTop = (1 - calculatedProgress) * measuredHeight
        val progressDelta = newTop - progressRect.top
        val smoothingFactor = when {
            progress < 10 || progress > 90 -> 0.05f
            else -> 0.1f
        }
        if (abs(progressDelta) > measuredHeight * threshold) {
            progressRect.top += progressDelta * smoothingFactor
            postInvalidateOnAnimation()
        } else {
            progressRect.top = newTop
            invalidate()
        }
        updateIconTint()
    }
    
    protected open fun updateProgressRectAnimate() {
        if (progress == lastProgress) return
        progressAnimator?.cancel()
        if (progressAnimator == null || !progressAnimator!!.isRunning) {
            progressAnimator = ValueAnimator.ofInt(lastProgress, progress)
            progressAnimator?.apply {
                duration = 80
                interpolator = DecelerateInterpolator()
                addUpdateListener { animator ->
                    val animatedValue = animator.animatedValue as Int
                    progress = animatedValue
                    lastProgress = animatedValue
                    val calculatedProgress = progress / 100f
                    val newTop = (1 - calculatedProgress) * measuredHeight
                    progressRect.top = newTop
                    updateIconTint()
                    postInvalidateOnAnimation()
                }
                start()
            }
        }
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        mHandler.removeCallbacksAndMessages(null)
        progressAnimator?.cancel()
        progressAnimator = null
        listeners.clear()
        setOnTouchListener(null)
    }
}
