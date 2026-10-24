package com.iptv.tvplayer.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView

/**
 * 修复 RecyclerView 在 scrollToPosition 后焦点可能丢失的问题。
 * 移植自 moyumv 项目的 CustomRecyclerView.java。
 * 核心逻辑：scrollToPosition 后延迟 50ms 自动把焦点 requestFocus 到目标 item。
 */
class CustomRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var x1 = 0f
    private var y1 = 0f

    init {
        overScrollMode = OVER_SCROLL_NEVER
    }

    private fun focus(position: Int) {
        val holder = findViewHolderForLayoutPosition(position)
        holder?.itemView?.requestFocus()
    }

    override fun scrollToPosition(position: Int) {
        super.scrollToPosition(position)
        postDelayed({ focus(position) }, 50)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount != 1) return false
        when (event.action) {
            MotionEvent.ACTION_UP -> { x1 = 0f; y1 = 0f }
            MotionEvent.ACTION_DOWN -> {
                x1 = event.x; y1 = event.y
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val offsetX = Math.abs(event.x - x1)
                val offsetY = Math.abs(event.y - y1)
                if (offsetX > offsetY && offsetX > touchSlop) {
                    parent.requestDisallowInterceptTouchEvent(false)
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }
}
