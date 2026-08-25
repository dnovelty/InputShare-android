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

/** Jump-zone strip width in dp. Replaces the old 1px hairline so the return
 *  edge is easier to hit (deskflow's jump-zone behavior). */
private const val STRIP_WIDTH_DP = 8f

class SideLineOverlay : View {
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
        val stripWidth = (STRIP_WIDTH_DP * context.resources.displayMetrics.density).toInt()
        params = when (direction) {
            Direction.UP, Direction.DOWN -> WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                stripWidth,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            Direction.LEFT, Direction.RIGHT -> WindowManager.LayoutParams(
                stripWidth,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
        }
        params.gravity = when (direction) {
            Direction.LEFT  -> Gravity.LEFT
            Direction.RIGHT -> Gravity.RIGHT
            Direction.UP    -> Gravity.TOP
            Direction.DOWN  -> Gravity.BOTTOM
        }

        // 挖孔/刘海屏：让悬浮条铺到物理屏幕边缘，否则系统会把窗口压低到挖孔栏以下，
        // 导致光标还没贴到屏幕边缘（挖孔摄像头稍下方）就触发切回电脑。
        // 与 deskflow-droid 的 CursorView 处理一致。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
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