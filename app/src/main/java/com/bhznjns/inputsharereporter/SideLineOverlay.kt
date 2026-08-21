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
        // 触发深度：光标深入边缘条超过此比例才切换（灵敏档）
        private const val TRIGGER_DEPTH = 0.5f
        // 复位深度：光标退回浅于此才允许再次触发，与触发线之间形成死区防抖
        private const val RESET_DEPTH = 0.2f
        // 严格档触发深度：需贴近屏幕极值才触发
        private const val CONFIRM_DEPTH = 0.85f
        // 切线轴位移阈值：超过视为"有活动"，用于严格档确认真顶住边缘
        private const val TANGENTIAL_EPS_PX = 2f
    }

    private lateinit var triggerCallback: TriggeredCallback
    private lateinit var params: WindowManager.LayoutParams
    // 当前贴边方向，供深度/切线坐标计算使用
    private var direction: Direction = Direction.LEFT
    // 严格档开关：开启后需贴近极值且切线轴有活动才触发
    private var requireTangentialConfirm = false

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    fun setIsDebug(isDebug: Boolean): SideLineOverlay {
        if (isDebug) this.setBackgroundColor(Color.RED)
        return this
    }

    fun setDirection(direction: String?): SideLineOverlay {
        this.direction = parseDirection(direction)
        setParamWithDirection(this.direction)
        return this
    }

    fun setTriggeredCallback(callback: TriggeredCallback): SideLineOverlay {
        triggerCallback = callback
        return this
    }

    // 开启严格档：需要贴近屏幕极值且切线轴持续活动才触发，更不易误触
    fun setRequireTangentialConfirm(enabled: Boolean): SideLineOverlay {
        requireTangentialConfirm = enabled
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
    // 上一次事件的切线轴坐标，用于检测切线活动
    private var lastTangential = 0f
    // 是否已有切线基线：首个事件无基线，不判"有活动"
    private var hasTangentialBaseline = false

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        if (event == null) return super.onGenericMotionEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_HOVER_MOVE -> {
                val depth = computeDepth(event)
                val tangential = tangentialCoord(event)
                // 切线轴相对上次有位移，视为光标正在活动（顶住边缘左右滑动时持续为真）
                val tangentialActive = hasTangentialBaseline &&
                        Math.abs(tangential - lastTangential) >= TANGENTIAL_EPS_PX
                lastTangential = tangential
                hasTangentialBaseline = true

                if (!triggered) {
                    // 严格档：贴近极值 + 有活动；灵敏档：仅看深度跨过触发线
                    val reached = if (requireTangentialConfirm) {
                        depth >= CONFIRM_DEPTH && tangentialActive
                    } else {
                        depth >= TRIGGER_DEPTH
                    }
                    if (reached) {
                        triggerCallback()
                        triggered = true
                    }
                } else if (depth <= RESET_DEPTH) {
                    // 退回浅于复位线才允许下次触发（死区防抖）
                    triggered = false
                }
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                // 完全甩出边缘条：复位触发态，并清空切线基线供下次进入重新建立
                triggered = false
                hasTangentialBaseline = false
            }
        }
        return super.onGenericMotionEvent(event)
    }

    // 归一化深度：0=边缘条内缘，1=最贴屏幕极值
    private fun computeDepth(event: MotionEvent): Float {
        val t = EDGE_TRIGGER_THICKNESS_PX.toFloat()
        val ratio = when (direction) {
            Direction.UP    -> (t - event.y) / t
            Direction.DOWN  -> event.y / t
            Direction.LEFT  -> (t - event.x) / t
            Direction.RIGHT -> event.x / t
        }
        return ratio.coerceIn(0f, 1f) // 防 hover 坐标越界导致深度超出 [0,1]
    }

    // 切线轴坐标：沿边缘滑动的那一维
    private fun tangentialCoord(event: MotionEvent): Float =
        when (direction) {
            Direction.UP, Direction.DOWN -> event.x
            Direction.LEFT, Direction.RIGHT -> event.y
        }
}