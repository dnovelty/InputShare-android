package com.bhznjns.inputsharereporter

import android.annotation.SuppressLint
import android.content.Context
import android.content.Context.WINDOW_SERVICE
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.bhznjns.inputsharereporter.utils.Direction

typealias TriggeredCallback = () -> Unit

class SideLineOverlay : View {
    companion object {
        // A 1px strip is nearly impossible to hit: the injected mouse cursor
        // is clamped to the physical edge (e.g. y=0), but on many devices the
        // overlay is laid out below the status bar / cutout, missing that
        // pixel entirely. A 16px strip (about 4dp on xxhdpi) is still
        // invisible in normal use but reliably catchable by hover events.
        private const val EDGE_TRIGGER_THICKNESS_PX = 16
    }

    private lateinit var triggerCallback: TriggeredCallback
    private lateinit var params: WindowManager.LayoutParams

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    fun setIsDebug(isDebug: Boolean): SideLineOverlay {
        if (isDebug) this.setBackgroundColor(Color.RED)
        return this
    }

    fun setDirection(direction: String?): SideLineOverlay {
        val direction = parseDirection(direction)
        setParamWithDirection(direction)
        return this
    }

    fun setTriggeredCallback(callback: TriggeredCallback): SideLineOverlay {
        triggerCallback = callback
        return this
    }

    fun launch() {
        val windowManager = context.getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager.addView(this, this.params)
    }

    fun close() {
        if (!isAttachedToWindow) return
        val windowManager = context.getSystemService(WINDOW_SERVICE) as WindowManager
        try {
            windowManager.removeView(this)
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
        }
    }

    @SuppressLint("RtlHardcoded")
    private fun setParamWithDirection(direction: Direction) {
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                // Allow the strip to extend into the status bar / cutout area
                // so it really sits on the physical screen edge (y=0 / x=0),
                // where the injected mouse cursor gets clamped by the system.
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        params = when (direction) {
            Direction.UP, Direction.DOWN -> WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                EDGE_TRIGGER_THICKNESS_PX,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT
            )
            Direction.LEFT, Direction.RIGHT -> WindowManager.LayoutParams(
                EDGE_TRIGGER_THICKNESS_PX,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT
            )
        }
        params.gravity = when (direction) {
            Direction.LEFT  -> Gravity.LEFT
            Direction.RIGHT -> Gravity.RIGHT
            Direction.UP    -> Gravity.TOP
            Direction.DOWN  -> Gravity.BOTTOM
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun parseDirection(direction: String?): Direction {
        Log.d("SideLineOverlay", "Received direction: $direction")
        return when (direction) {
            "up"    -> Direction.UP
            "right" -> Direction.RIGHT
            "left"  -> Direction.LEFT
            "down"  -> Direction.DOWN
            else    -> Direction.LEFT
        }
    }

    private var triggered = false
    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        if (event?.action == MotionEvent.ACTION_HOVER_ENTER) {
            if (!triggered) {
                triggerCallback()
            }
            triggered = true
        } else if (event?.action == MotionEvent.ACTION_HOVER_EXIT) {
            triggered = false
        }
        return super.onGenericMotionEvent(event)
    }
}