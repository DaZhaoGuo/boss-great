package com.dazhao.bossgreet

sealed class EngineAction {
    data class Tap(val x: Int, val y: Int, val reason: String) : EngineAction()
    data object Back : EngineAction()
    data object Scroll : EngineAction()
    data class MarkSeen(val fingerprint: String) : EngineAction()
    data class Log(val message: String) : EngineAction()
    data class Stop(val reason: String) : EngineAction()
}

enum class EngineStep {
    WAIT_LIST,
    WAIT_DETAIL,
    WAIT_AFTER_GREET,
    BACK_TO_LIST,
}

class GreetEngine(
    private val filter: GreetFilter,
    private val maxSuccess: Int,
    private val seen: MutableSet<String>,
    private val maxScrolls: Int = 40,
) {
    var step: EngineStep = EngineStep.WAIT_LIST
        private set
    var successCount: Int = 0
        private set
    var attemptCount: Int = 0
        private set

    private var stepEnteredAt: Long = -1L
    private var pending: JobCard? = null
    private var greetRetries: Int = 0
    private var backCount: Int = 0
    private var unchangedScrolls: Int = 0
    private var scrollCount: Int = 0
    private var lastFingerprints: Set<String>? = null
    private var lastIdleLogAt: Long = Long.MIN_VALUE

    fun onTick(
        root: UiNode,
        screenWidth: Int,
        screenHeight: Int,
        nowMs: Long,
        canAct: Boolean,
    ): List<EngineAction> {
        if (stepEnteredAt < 0) stepEnteredAt = nowMs
        val page = BossPages.classify(root)
        if (page == PageKind.LIMIT) {
            return listOf(EngineAction.Stop("沟通次数已达上限，本轮停止"))
        }
        return when (step) {
            EngineStep.WAIT_LIST -> onWaitList(root, page, screenWidth, screenHeight, nowMs, canAct)
            EngineStep.WAIT_DETAIL -> onWaitDetail(root, page, screenWidth, screenHeight, nowMs, canAct)
            EngineStep.WAIT_AFTER_GREET -> onAfterGreet(root, page, screenWidth, screenHeight, nowMs, canAct)
            EngineStep.BACK_TO_LIST -> onBackToList(page, nowMs, canAct)
        }
    }

    private fun onWaitList(
        root: UiNode,
        page: PageKind,
        screenWidth: Int,
        screenHeight: Int,
        nowMs: Long,
        canAct: Boolean,
    ): List<EngineAction> {
        if (successCount >= maxSuccess) {
            return listOf(EngineAction.Stop("已达单次上限 $successCount"))
        }
        if (attemptCount >= maxSuccess * 3) {
            return listOf(EngineAction.Stop("尝试次数达到上限的 3 倍"))
        }
        return when (page) {
            PageKind.DETAIL -> onWaitDetail(root, page, screenWidth, screenHeight, nowMs, canAct)
            PageKind.CHAT -> leaveChat(nowMs, canAct, "当前在会话页，返回列表")
            PageKind.OTHER -> onOther(root, nowMs, canAct)
            PageKind.LIST -> onList(root, screenWidth, screenHeight, nowMs, canAct)
            PageKind.LIMIT -> listOf(EngineAction.Stop("沟通次数已达上限，本轮停止"))
        }
    }

    private fun onList(
        root: UiNode,
        screenWidth: Int,
        screenHeight: Int,
        nowMs: Long,
        canAct: Boolean,
    ): List<EngineAction> {
        val cards = BossPages.findCards(root, screenWidth, screenHeight)
        val picked = BossPages.pick(cards, filter, seen)
        if (picked != null) {
            if (!canAct) return emptyList()
            attemptCount += 1
            pending = picked
            unchangedScrolls = 0
            lastFingerprints = null
            enter(EngineStep.WAIT_DETAIL, nowMs)
            return listOf(
                EngineAction.Log("打开职位 ${picked.title} ${picked.salary}"),
                EngineAction.Tap(picked.clickX, picked.clickY, picked.title),
            )
        }
        if (!canAct) return emptyList()
        val fingerprints = cards.map { it.fingerprint }.toSet()
        unchangedScrolls = if (lastFingerprints != null && fingerprints == lastFingerprints) {
            unchangedScrolls + 1
        } else {
            0
        }
        lastFingerprints = fingerprints
        if (unchangedScrolls >= 3) {
            return listOf(EngineAction.Stop("连续 3 次滚动没有新职位"))
        }
        if (scrollCount >= maxScrolls) {
            return listOf(EngineAction.Stop("滚动次数达到 $maxScrolls，本轮停止"))
        }
        scrollCount += 1
        return listOf(EngineAction.Log("向下滚动列表"), EngineAction.Scroll)
    }

    private fun onWaitDetail(
        root: UiNode,
        page: PageKind,
        screenWidth: Int,
        screenHeight: Int,
        nowMs: Long,
        canAct: Boolean,
    ): List<EngineAction> {
        if (page == PageKind.DETAIL) {
            if (BossPages.findContinue(root) != null) {
                if (!canAct) return emptyList()
                return skipPending(
                    root,
                    screenWidth,
                    screenHeight,
                    nowMs,
                    "该职位已是继续沟通，跳过",
                    back = true,
                )
            }
            val greet = BossPages.findGreet(root)
            if (greet == null) return idle(nowMs, "详情页未找到立即沟通")
            if (!canAct) return emptyList()
            greetRetries = 0
            enter(EngineStep.WAIT_AFTER_GREET, nowMs)
            val name = pending?.title ?: "当前职位"
            return listOf(
                EngineAction.Log("点击立即沟通 $name"),
                EngineAction.Tap(greet.x, greet.y, "立即沟通"),
            )
        }
        if (page == PageKind.CHAT) return leaveChat(nowMs, canAct, "进入会话页，返回")
        if (nowMs - stepEnteredAt < 4_000) return emptyList()
        if (!canAct) return emptyList()
        return skipPending(root, screenWidth, screenHeight, nowMs, "未能进入详情，跳过", back = false)
    }

    private fun onAfterGreet(
        root: UiNode,
        page: PageKind,
        screenWidth: Int,
        screenHeight: Int,
        nowMs: Long,
        canAct: Boolean,
    ): List<EngineAction> {
        if (nowMs - stepEnteredAt < 1_500) return emptyList()
        if (!canAct) return emptyList()
        val name = pending?.title ?: "当前职位"
        if (page == PageKind.CHAT || BossPages.findContinue(root) != null) {
            successCount += 1
            val fingerprint = markPending(root, screenWidth, screenHeight)
            pending = null
            enter(EngineStep.BACK_TO_LIST, nowMs)
            return listOf(
                EngineAction.MarkSeen(fingerprint),
                EngineAction.Log("已沟通 $name"),
                EngineAction.Back,
            )
        }
        if (page == PageKind.LIST) {
            val fingerprint = markPending(root, screenWidth, screenHeight)
            pending = null
            enter(EngineStep.WAIT_LIST, nowMs)
            return listOf(
                EngineAction.MarkSeen(fingerprint),
                EngineAction.Log("已离开详情，未确认是否沟通，跳过 $name"),
            )
        }
        val greet = BossPages.findGreet(root)
        if (page == PageKind.DETAIL && greet != null && greetRetries < 1) {
            greetRetries += 1
            stepEnteredAt = nowMs
            return listOf(
                EngineAction.Log("再次点击立即沟通 $name"),
                EngineAction.Tap(greet.x, greet.y, "立即沟通"),
            )
        }
        if (page == PageKind.DETAIL && greet != null) {
            return skipPending(root, screenWidth, screenHeight, nowMs, "点击后仍未进入沟通，跳过 $name", back = true)
        }
        if (nowMs - stepEnteredAt < 4_000) return emptyList()
        return skipPending(root, screenWidth, screenHeight, nowMs, "点击后结果未确认，跳过 $name", back = true)
    }

    private fun onBackToList(page: PageKind, nowMs: Long, canAct: Boolean): List<EngineAction> {
        if (page == PageKind.LIST) {
            enter(EngineStep.WAIT_LIST, nowMs)
            return emptyList()
        }
        if (nowMs - stepEnteredAt < 600) return emptyList()
        if (!canAct) return emptyList()
        backCount += 1
        if (backCount > 4) {
            enter(EngineStep.WAIT_LIST, nowMs)
            return listOf(EngineAction.Log("多次返回未回到职位列表"))
        }
        return listOf(EngineAction.Back)
    }

    private fun onOther(root: UiNode, nowMs: Long, canAct: Boolean): List<EngineAction> {
        val dismiss = BossPages.findDismiss(root)
        if (dismiss != null && canAct) {
            return listOf(
                EngineAction.Log("关闭弹窗 ${dismiss.label}"),
                EngineAction.Tap(dismiss.x, dismiss.y, dismiss.label),
            )
        }
        return idle(nowMs, "当前不在职位列表")
    }

    private fun leaveChat(nowMs: Long, canAct: Boolean, message: String): List<EngineAction> {
        if (!canAct) return emptyList()
        enter(EngineStep.BACK_TO_LIST, nowMs)
        return listOf(EngineAction.Log(message), EngineAction.Back)
    }

    private fun skipPending(
        root: UiNode,
        screenWidth: Int,
        screenHeight: Int,
        nowMs: Long,
        message: String,
        back: Boolean,
    ): List<EngineAction> {
        val fingerprint = markPending(root, screenWidth, screenHeight)
        pending = null
        enter(if (back) EngineStep.BACK_TO_LIST else EngineStep.WAIT_LIST, nowMs)
        val actions = mutableListOf<EngineAction>(
            EngineAction.MarkSeen(fingerprint),
            EngineAction.Log(message),
        )
        if (back) actions += EngineAction.Back
        return actions
    }

    private fun markPending(root: UiNode, screenWidth: Int, screenHeight: Int): String {
        val fingerprint = pending?.fingerprint
            ?: BossPages.detailFingerprint(root, screenWidth, screenHeight)
        seen += fingerprint
        return fingerprint
    }

    private fun idle(nowMs: Long, message: String): List<EngineAction> {
        if (nowMs - lastIdleLogAt < 4_000) return emptyList()
        lastIdleLogAt = nowMs
        return listOf(EngineAction.Log(message))
    }

    private fun enter(next: EngineStep, nowMs: Long) {
        step = next
        stepEnteredAt = nowMs
        if (next == EngineStep.BACK_TO_LIST) backCount = 0
    }
}
