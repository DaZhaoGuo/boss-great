package com.dazhao.bossgreet

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.random.Random

class BossGreetService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val tick = Runnable { onTick() }
    private lateinit var store: GreetStore
    private lateinit var overlay: OverlayWindow
    private var nextActAt = 0L
    private var acting = false
    private var overlayWarned = false
    private var lastForeignLogAt = 0L

    override fun onServiceConnected() {
        connected = true
        store = GreetStore(this)
        overlay = OverlayWindow(this)
        serviceInfo = serviceInfo.apply {
            flags = flags or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        handler.post(tick)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        connected = false
        handler.removeCallbacks(tick)
        if (::overlay.isInitialized) overlay.hide()
    }

    private fun onTick() {
        try {
            scan()
        } catch (error: Throwable) {
            GreetRuntime.log("扫描异常：${error.message ?: error.javaClass.simpleName}")
        }
        handler.postDelayed(tick, TICK_MS)
    }

    private fun scan() {
        val now = SystemClock.uptimeMillis()
        if (!GreetRuntime.running && !GreetRuntime.dumpRequested) {
            if (::overlay.isInitialized) overlay.hide()
            return
        }
        val root = rootInActiveWindow ?: return
        val live = capture(root)
        try {
            val packageName = live.info.packageName?.toString().orEmpty()
            if (packageName != BossPages.PACKAGE_NAME) {
                if (GreetRuntime.dumpRequested) {
                    GreetRuntime.dumpRequested = false
                    GreetRuntime.log("当前前台为 $packageName")
                } else if (GreetRuntime.running && now - lastForeignLogAt > 4_000) {
                    lastForeignLogAt = now
                    GreetRuntime.log("前台不是 Boss直聘")
                }
                return
            }
            if (GreetRuntime.dumpRequested) {
                GreetRuntime.dumpRequested = false
                val lines = BossPages.visibleTexts(live.ui).take(40)
                GreetRuntime.log(
                    if (lines.isEmpty()) "当前页面没有可读文案" else "页面文案\n${lines.joinToString("\n")}",
                )
                return
            }
            val engine = GreetRuntime.engine ?: return
            val metrics = resources.displayMetrics
            val actions = engine.onTick(
                root = live.ui,
                screenWidth = metrics.widthPixels,
                screenHeight = metrics.heightPixels,
                nowMs = now,
                canAct = canAct(now),
            )
            var stopped = false
            for (action in actions) {
                when (action) {
                    is EngineAction.Log -> GreetRuntime.log(action.message)
                    is EngineAction.MarkSeen -> store.markSeen(action.fingerprint)
                    is EngineAction.Stop -> {
                        GreetRuntime.running = false
                        GreetRuntime.log(action.reason)
                        stopped = true
                    }
                    is EngineAction.Tap -> performTap(live, action.x, action.y, now)
                    EngineAction.Back -> performBack(now)
                    EngineAction.Scroll -> performScroll(live, now)
                }
            }
            if (stopped || !GreetRuntime.running) {
                overlay.hide()
            } else {
                val shown = overlay.show("已沟通 ${engine.successCount}/${GreetRuntime.maxCount}  ·  停止") {
                    GreetRuntime.requestStop("已手动停止")
                    overlay.hide()
                }
                if (!shown && !overlayWarned) {
                    overlayWarned = true
                    GreetRuntime.log("未授予悬浮窗权限，可在主界面停止")
                }
            }
        } finally {
            recycle(live)
        }
    }

    private fun canAct(now: Long): Boolean = !acting && now >= nextActAt

    private fun armCooldown(now: Long) {
        nextActAt = now + GreetRuntime.baseIntervalMs + Random.nextInt(0, 401)
    }

    private fun performTap(live: LiveNode, x: Int, y: Int, now: Long) {
        acting = true
        armCooldown(now)
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            lineTo(x + 1f, y + 1f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    acting = false
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    acting = false
                }
            },
            null,
        )
        if (!accepted) {
            acting = false
            if (!clickNearest(live, x, y)) GreetRuntime.log("点击未送达")
        }
        handler.postDelayed({ acting = false }, 1_200)
    }

    private fun performBack(now: Long) {
        performGlobalAction(GLOBAL_ACTION_BACK)
        armCooldown(now)
    }

    private fun performScroll(live: LiveNode, now: Long) {
        val target = largestScrollable(live)
        val scrolled = target?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
        armCooldown(now)
        if (!scrolled) GreetRuntime.log("未找到可滚动列表")
    }

    private fun clickNearest(live: LiveNode, x: Int, y: Int): Boolean {
        val hit = deepest(live, x, y) ?: return false
        val parents = ArrayList<AccessibilityNodeInfo>()
        var current: AccessibilityNodeInfo? = hit.info
        var guard = 0
        var clicked = false
        while (current != null && guard < 12) {
            guard += 1
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                clicked = true
                break
            }
            val parent = current.parent ?: break
            parents += parent
            current = parent
        }
        parents.forEach { it.release() }
        return clicked
    }

    private fun deepest(node: LiveNode, x: Int, y: Int): LiveNode? {
        for (child in node.children.asReversed()) {
            val found = deepest(child, x, y)
            if (found != null) return found
        }
        val bounds = node.ui.bounds
        return if (x in bounds.left..bounds.right && y in bounds.top..bounds.bottom) node else null
    }

    private fun largestScrollable(node: LiveNode): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        fun walk(current: LiveNode) {
            if (current.info.isScrollable) {
                val area = current.ui.bounds.width * current.ui.bounds.height
                if (area > bestArea) {
                    best = current.info
                    bestArea = area
                }
            }
            current.children.forEach { walk(it) }
        }
        walk(node)
        return best
    }

    private fun capture(info: AccessibilityNodeInfo): LiveNode {
        val children = buildList {
            for (index in 0 until info.childCount) {
                val child = info.getChild(index) ?: continue
                add(capture(child))
            }
        }
        val rect = Rect()
        info.getBoundsInScreen(rect)
        val text = listOf(info.text?.toString().orEmpty(), info.contentDescription?.toString().orEmpty())
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(" ")
        val ui = UiNode(
            text = text,
            className = info.className?.toString().orEmpty(),
            clickable = info.isClickable,
            scrollable = info.isScrollable,
            editable = info.isEditable,
            bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom),
            children = children.map { it.ui },
        )
        return LiveNode(info, ui, children)
    }

    private fun recycle(node: LiveNode) {
        node.children.forEach { recycle(it) }
        node.info.release()
    }

    @Suppress("DEPRECATION")
    private fun AccessibilityNodeInfo.release() {
        recycle()
    }

    private class LiveNode(
        val info: AccessibilityNodeInfo,
        val ui: UiNode,
        val children: List<LiveNode>,
    )

    companion object {
        const val TICK_MS = 800L

        @Volatile
        var connected: Boolean = false

        fun isEnabled(context: Context): Boolean {
            val accessibilityOn = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0,
            ) == 1
            if (!accessibilityOn) return false
            val expected = ComponentName(context, BossGreetService::class.java).flattenToString()
            val setting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            return setting.split(':').any { it.equals(expected, ignoreCase = true) }
        }
    }
}
