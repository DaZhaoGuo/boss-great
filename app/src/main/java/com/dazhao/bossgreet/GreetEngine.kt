package com.dazhao.bossgreet

sealed class EngineAction {
    data class Tap(val x: Int, val y: Int, val reason: String) : EngineAction()
    data object Back : EngineAction()
    data class Scroll(
        val method: ScrollMethod,
        val distancePx: Int,
        val anchorTitle: String,
    ) : EngineAction()
    data class MarkSeen(val fingerprint: String) : EngineAction()
    data class Log(val message: String) : EngineAction()
    data class Stop(val reason: String) : EngineAction()
    data class ListExhausted(val message: String) : EngineAction()
}

enum class EngineStep {
    WAIT_LIST,
    WAIT_DETAIL,
    WAIT_AFTER_GREET,
    WAIT_AFTER_SCROLL,
    BACK_TO_LIST,
}

class GreetEngine(
    private val filter: GreetFilter,
    private val maxSuccess: Int,
    private val seen: MutableSet<String>,
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
    private var failedRounds: Int = 0
    private var scrollMethod: ScrollMethod = ScrollMethod.CARD_GESTURE
    private var otherSince: Long = -1L
    private var baselineMarks: List<CardMark> = emptyList()
    private var scrollJudged: Boolean = false
    private var lastIdleLogAt: Long = Long.MIN_VALUE

    fun onTick(
        root: UiNode,
        screenWidth: Int,
        screenHeight: Int,
        nowMs: Long,
        canAct: Boolean,
        scrollAccepted: Boolean? = null,
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
            EngineStep.WAIT_AFTER_SCROLL ->
                onWaitAfterScroll(root, page, screenWidth, screenHeight, nowMs, canAct, scrollAccepted)
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
        return when (page) {
            PageKind.DETAIL -> onWaitDetail(root, page, screenWidth, screenHeight, nowMs, canAct)
            PageKind.CHAT -> leaveChat(nowMs, canAct, "当前在会话页，返回列表")
            PageKind.OTHER -> onOther(root, nowMs, canAct)
            PageKind.LIST -> {
                otherSince = -1L
                onList(root, screenWidth, screenHeight, nowMs, canAct)
            }
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
            failedRounds = 0
            scrollJudged = false
            enter(EngineStep.WAIT_DETAIL, nowMs)
            return listOf(
                EngineAction.Log("打开职位 ${picked.title} ${picked.salary}"),
                EngineAction.Tap(picked.clickX, picked.clickY, picked.title),
            )
        }
        scrollMethod = ScrollMethod.CARD_GESTURE
        return emitScroll(cards, screenHeight, nowMs, canAct, "当前屏幕职位已处理，上滑列表")
    }

    private fun onWaitAfterScroll(
        root: UiNode,
        page: PageKind,
        screenWidth: Int,
        screenHeight: Int,
        nowMs: Long,
        canAct: Boolean,
        scrollAccepted: Boolean?,
    ): List<EngineAction> {
        if (page != PageKind.LIST) {
            if (nowMs - stepEnteredAt < MAX_PAGE_WAIT_MS) return emptyList()
            if (!canAct) return listOf(EngineAction.Log("上滑后离开了职位列表，准备返回"))
            enter(EngineStep.BACK_TO_LIST, nowMs)
            return listOf(EngineAction.Log("上滑后离开了职位列表，返回"), EngineAction.Back)
        }
        val elapsed = nowMs - stepEnteredAt
        val loading = BossPages.isLoading(root)
        val cards = BossPages.findCards(root, screenWidth, screenHeight)
        val moved = BossPages.listMoved(baselineMarks, BossPages.cardMarks(cards))
        val ready = when {
            loading && elapsed < MAX_PAGE_WAIT_MS -> false
            moved && !loading && elapsed >= 400 -> true
            scrollAccepted == false && elapsed >= 400 -> true
            !loading && elapsed >= MIN_SETTLE_MS -> true
            elapsed >= MAX_PAGE_WAIT_MS -> true
            else -> false
        }
        if (!ready) return if (loading) idle(nowMs, "等待列表分页加载") else emptyList()
        val picked = BossPages.pick(cards, filter, seen)
        val trace = scrollTrace(elapsed, loading, scrollAccepted, moved, cards)
        if (!scrollJudged) {
            scrollJudged = true
            val endLabel = BossPages.listEndLabel(root)
            if (!loading && !moved && picked == null && endLabel != null) {
                return listOf(EngineAction.Log("$trace；屏幕内结束文案「$endLabel」")) +
                    listExhausted("列表已加载完，没有更多职位")
            }
            if (moved) {
                failedRounds = 0
                scrollMethod = ScrollMethod.CARD_GESTURE
                if (picked != null) {
                    enter(EngineStep.WAIT_LIST, nowMs)
                    return listOf(EngineAction.Log("$trace；继续打招呼 ${picked.title}"))
                }
            } else {
                when (scrollMethod) {
                    ScrollMethod.CARD_GESTURE -> scrollMethod = ScrollMethod.PARENT_SCROLL
                    ScrollMethod.PARENT_SCROLL -> scrollMethod = ScrollMethod.LONG_GESTURE
                    ScrollMethod.LONG_GESTURE -> {
                        if (!loading) failedRounds += 1
                        if (failedRounds >= FAILED_ROUNDS_TO_END) {
                            return listOf(EngineAction.Log(trace)) +
                                listExhausted("连续上滑后列表没有位移，停止")
                        }
                        scrollMethod = ScrollMethod.CARD_GESTURE
                    }
                }
            }
        }
        val message = when (scrollMethod) {
            ScrollMethod.CARD_GESTURE -> "列表已位移，继续上滑"
            ScrollMethod.PARENT_SCROLL -> "列表未位移，改滚动父节点"
            ScrollMethod.LONG_GESTURE -> "父节点未位移，改用长距离快滑"
        }
        if (!canAct) return listOf(EngineAction.Log(trace))
        return listOf(EngineAction.Log(trace)) + emitScroll(cards, screenHeight, nowMs, canAct = true, message)
    }

    private fun scrollTrace(
        elapsed: Long,
        loading: Boolean,
        scrollAccepted: Boolean?,
        moved: Boolean,
        cards: List<JobCard>,
    ): String {
        val callback = when (scrollAccepted) {
            true -> "完成"
            false -> "取消"
            null -> "未返回"
        }
        val beforeY = baselineMarks.associate { it.fingerprint to it.y }
        val positions = if (cards.isEmpty()) {
            "职位=无"
        } else {
            cards.joinToString("；") { card ->
                val old = beforeY[card.fingerprint]
                val shift = if (old == null) "新${card.clickY}" else "$old→${card.clickY}"
                "${card.title}@$shift"
            }
        }
        return "滚动核对 方式=${methodLabel(scrollMethod)} 位移=$moved 加载=$loading 回调=$callback 等待=${elapsed}ms $positions"
    }

    private fun methodLabel(method: ScrollMethod): String = when (method) {
        ScrollMethod.CARD_GESTURE -> "上滑"
        ScrollMethod.PARENT_SCROLL -> "父节点"
        ScrollMethod.LONG_GESTURE -> "长滑"
    }

    private fun emitScroll(
        cards: List<JobCard>,
        screenHeight: Int,
        nowMs: Long,
        canAct: Boolean,
        message: String,
    ): List<EngineAction> {
        if (!canAct) return emptyList()
        scrollJudged = false
        baselineMarks = BossPages.cardMarks(cards)
        enter(EngineStep.WAIT_AFTER_SCROLL, nowMs)
        return listOf(
            EngineAction.Log(message),
            EngineAction.Scroll(
                method = scrollMethod,
                distancePx = BossPages.swipeDistance(cards, screenHeight),
                anchorTitle = cards.firstOrNull()?.title.orEmpty(),
            ),
        )
    }

    private fun listExhausted(message: String): List<EngineAction> =
        listOf(EngineAction.ListExhausted(message))

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
            if (greet == null) {
                if (nowMs - stepEnteredAt < 6_000) return idle(nowMs, "详情页未找到立即沟通，继续等待")
                if (!canAct) return emptyList()
                return skipPending(root, screenWidth, screenHeight, nowMs, "详情页没有立即沟通，返回继续", back = true)
            }
            if (!canAct) return emptyList()
            greetRetries = 0
            successCount += 1
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
        return skipPending(root, screenWidth, screenHeight, nowMs, "未能进入详情，返回继续", back = true)
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
            stepEnteredAt = nowMs
            return listOf(EngineAction.Log("仍未回到职位列表，继续返回"), EngineAction.Back)
        }
        return listOf(EngineAction.Back)
    }

    private fun onOther(root: UiNode, nowMs: Long, canAct: Boolean): List<EngineAction> {
        val dismiss = BossPages.findDismiss(root)
        if (dismiss != null) {
            otherSince = -1L
            if (!canAct) return emptyList()
            return listOf(
                EngineAction.Log("关闭弹窗 ${dismiss.label}"),
                EngineAction.Tap(dismiss.x, dismiss.y, dismiss.label),
            )
        }
        if (otherSince < 0) otherSince = nowMs
        if (nowMs - otherSince < 5_000) return idle(nowMs, "当前不在职位列表，等待后返回")
        if (!canAct) return emptyList()
        otherSince = nowMs
        return listOf(EngineAction.Log("当前页无法关闭，返回一步"), EngineAction.Back)
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

    companion object {
        const val MIN_SETTLE_MS = 1_000L
        const val MAX_PAGE_WAIT_MS = 8_000L
        const val FAILED_ROUNDS_TO_END = 2
    }
}
