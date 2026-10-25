package com.dazhao.bossgreet

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
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
        if (!GreetRuntime.running && !GreetRuntime.scrollProbe && !GreetRuntime.dumpRequested) {
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
                } else if ((GreetRuntime.running || GreetRuntime.scrollProbe) && now - lastForeignLogAt > 4_000) {
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
            if (GreetRuntime.scrollProbe) {
                runScrollProbe(live, now)
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
                scrollAccepted = GreetRuntime.scrollAccepted,
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
                    is EngineAction.ListExhausted -> {
                        GreetRuntime.running = false
                        GreetRuntime.log(action.message)
                        stopped = true
                        alertListEnded()
                    }
                    is EngineAction.Tap -> performTap(live, action.x, action.y, now)
                    EngineAction.Back -> performBack(now)
                    is EngineAction.Scroll -> performScroll(live, action, now)
                }
            }
            if (stopped || !GreetRuntime.running) {
                overlay.hide()
            } else {
                val shown = overlay.show("已沟通 ${engine.successCount}/${GreetRuntime.maxCount}  ·  长按停止") {
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

    private fun runScrollProbe(live: LiveNode, now: Long) {
        overlay.hide()
        val metrics = resources.displayMetrics
        val actions = GreetRuntime.listScrollProbe.onTick(
            root = live.ui,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            nowMs = now,
            canAct = canAct(now),
        )
        for (action in actions) {
            when (action) {
                is ProbeAction.Log -> GreetRuntime.log(action.message)
                is ProbeAction.Stop -> {
                    GreetRuntime.scrollProbe = false
                    GreetRuntime.log(action.reason)
                }
                is ProbeAction.Gesture -> dispatchSwipe(action.startY, action.endY, action.durationMs, now)
                is ProbeAction.NodeScroll -> scrollEveryNode(live, action.forward, now)
            }
        }
    }

    private fun canAct(now: Long): Boolean = !acting && now >= nextActAt

    private fun armCooldown(now: Long) {
        nextActAt = now + GreetRuntime.baseIntervalMs + Random.nextInt(0, 401)
    }

    private fun performTap(live: LiveNode, x: Int, y: Int, now: Long) {
        GreetRuntime.log("点击坐标 ($x,$y)")
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
        GreetRuntime.log("全局返回 GLOBAL_ACTION_BACK")
        performGlobalAction(GLOBAL_ACTION_BACK)
        armCooldown(now)
    }

    private fun performScroll(live: LiveNode, action: EngineAction.Scroll, now: Long) {
        GreetRuntime.scrollAccepted = null
        when (action.method) {
            ScrollMethod.PARENT_SCROLL -> {
                scrollEveryNode(live, forward = true, now = now)
            }
            ScrollMethod.LONG_GESTURE -> {
                val height = resources.displayMetrics.heightPixels.toFloat()
                dispatchSwipe(
                    height * ListSwipe.START_FRACTION,
                    height * ListSwipe.END_FRACTION,
                    durationMs = ListSwipe.DURATION_MS,
                    now = now,
                )
            }
            ScrollMethod.CARD_GESTURE -> {
                val height = resources.displayMetrics.heightPixels.toFloat()
                dispatchSwipe(
                    height * ListSwipe.START_FRACTION,
                    height * ListSwipe.END_FRACTION,
                    durationMs = ListSwipe.DURATION_MS,
                    now = now,
                )
            }
        }
    }

    private fun dispatchSwipe(startY: Float, endY: Float, durationMs: Long, now: Long) {
        val x = resources.displayMetrics.widthPixels * 0.5f
        acting = true
        armCooldown(now)
        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        GreetRuntime.log(
            "提交手势 dispatchGesture (${x.toInt()},${startY.toInt()})→(${x.toInt()},${endY.toInt()}) ${durationMs}ms",
        )
        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    GreetRuntime.scrollAccepted = true
                    acting = false
                    GreetRuntime.log("手势回调完成 onCompleted")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    GreetRuntime.scrollAccepted = false
                    acting = false
                    GreetRuntime.log("手势回调取消 onCancelled")
                }
            },
            null,
        )
        if (!accepted) {
            GreetRuntime.scrollAccepted = false
            acting = false
            GreetRuntime.log("手势提交被拒绝 dispatchGesture=false")
        } else {
            GreetRuntime.log("手势提交已接受 dispatchGesture=true")
        }
        handler.postDelayed({ acting = false }, 1_200)
    }

    private fun scrollEveryNode(live: LiveNode, forward: Boolean, now: Long) {
        val actionId = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        val direction = if (forward) "FORWARD" else "BACKWARD"
        val nodes = flattenLive(live).filter { it.ui.scrollable }
        if (nodes.isEmpty()) {
            GreetRuntime.log("父节点滚动 无 scrollable 节点")
            GreetRuntime.scrollAccepted = false
            armCooldown(now)
            return
        }
        var any = false
        for (node in nodes) {
            val bounds = node.ui.bounds
            val ok = node.info.performAction(actionId)
            any = any || ok
            GreetRuntime.log(
                "父节点 ACTION_SCROLL_$direction 类名=${node.ui.className} 高=${bounds.height} 结果=$ok",
            )
        }
        GreetRuntime.scrollAccepted = any
        armCooldown(now)
    }

    private fun flattenLive(node: LiveNode): List<LiveNode> {
        val out = ArrayList<LiveNode>()
        fun walk(current: LiveNode) {
            out += current
            current.children.forEach { walk(it) }
        }
        walk(node)
        return out
    }

    private fun alertListEnded() {
        val vibrator = getSystemService(Vibrator::class.java)
        vibrator?.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 200, 140, 200, 140, 200, 140, 320), -1),
        )
        val intent = Intent(this, ListEndActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (error: RuntimeException) {
            GreetRuntime.log("列表结束弹窗未打开：${error.message ?: error.javaClass.simpleName}")
        }
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

    private fun capture(info: AccessibilityNodeInfo): LiveNode {
        val children = buildList {
            for (index in 0 until info.childCount) {
                val child = info.getChild(index) ?: continue
                add(capture(child))
            }
        }
        val rect = Rect()
        info.getBoundsInScreen(rect)
        val visible = info.text?.toString()?.trim().orEmpty()
        val description = info.contentDescription?.toString()?.trim().orEmpty()
        val text = visible.ifEmpty { description }
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
