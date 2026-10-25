package com.dazhao.bossgreet

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

class OverlayWindow(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: TextView? = null
    private var onStop: (() -> Unit)? = null

    fun show(text: String, stop: () -> Unit): Boolean {
        if (!Settings.canDrawOverlays(context)) return false
        onStop = stop
        return try {
            val current = view
            if (current == null) {
                createView().also { view = it }.text = text
            } else if (current.text.toString() != text) {
                current.text = text
                windowManager.updateViewLayout(current, current.layoutParams)
            }
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    fun hide() {
        val current = view ?: return
        view = null
        try {
            windowManager.removeView(current)
        } catch (_: RuntimeException) {
        }
    }

    private fun createView(): TextView {
        val density = context.resources.displayMetrics.density
        val textView = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            minHeight = (density * 48).toInt()
            setPadding((density * 16).toInt(), (density * 8).toInt(), (density * 16).toInt(), (density * 8).toInt())
            setBackgroundColor(0xF012B7A6.toInt())
            // 单击易误触；长按才停止，避免滑动/切页时打断打招呼
            isClickable = true
            isLongClickable = true
            setOnClickListener(null)
            setOnLongClickListener {
                onStop?.invoke()
                true
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (density * 48).toInt()
        }
        windowManager.addView(textView, params)
        return textView
    }
}
