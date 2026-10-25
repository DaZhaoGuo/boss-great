package com.dazhao.bossgreet

import kotlin.math.abs

sealed class ProbeAction {
    data class Log(val message: String) : ProbeAction()
    data class Gesture(val startY: Float, val endY: Float, val durationMs: Long, val label: String) : ProbeAction()
    data class NodeScroll(val forward: Boolean) : ProbeAction()
    data class Stop(val reason: String) : ProbeAction()
}

class ListScrollProbe(
    private val maxCycles: Int = 4,
    private val settleMs: Long = 1_800L,
) {
    private var actionCount = 0
    private var waitUntil = 0L
    private var baseline = emptyList<CardMark>()
    private var lastAction = ""
    private var lastNonListLogAt = 0L
    private val maxActions = maxCycles * 4

    fun reset() {
        actionCount = 0
        waitUntil = 0L
        baseline = emptyList()
        lastAction = ""
        lastNonListLogAt = 0L
    }

    fun onTick(
        root: UiNode,
        screenWidth: Int,
        screenHeight: Int,
        nowMs: Long,
        canAct: Boolean,
    ): List<ProbeAction> {
        val page = BossPages.classify(root)
        if (page != PageKind.LIST) {
            if (nowMs - lastNonListLogAt < 4_000L) return emptyList()
            lastNonListLogAt = nowMs
            return listOf(ProbeAction.Log("滑动探测等待列表页，当前=${page.name}"))
        }
        val cards = BossPages.findCards(root, screenWidth, screenHeight, safeZone = false)
        val marks = BossPages.cardMarks(cards)
        if (nowMs < waitUntil) return emptyList()
        if (lastAction.isNotEmpty()) {
            val report = ProbeAction.Log(scrollReport(listShifted(baseline, marks), marks))
            lastAction = ""
            baseline = marks
            if (actionCount >= maxActions) {
                return listOf(report, ProbeAction.Stop("列表滑动探测结束，共 $maxCycles 轮"))
            }
            return listOf(report) + nextAction(marks, screenHeight, nowMs, canAct)
        }
        if (actionCount >= maxActions) {
            return listOf(ProbeAction.Stop("列表滑动探测结束，共 $maxCycles 轮"))
        }
        return nextAction(marks, screenHeight, nowMs, canAct)
    }

    private fun nextAction(
        marks: List<CardMark>,
        screenHeight: Int,
        nowMs: Long,
        canAct: Boolean,
    ): List<ProbeAction> {
        if (!canAct) return emptyList()
        val height = screenHeight.toFloat()
        val action = when (actionCount % 4) {
            0 -> ProbeAction.Gesture(
                height * ListSwipe.START_FRACTION,
                height * ListSwipe.END_FRACTION,
                ListSwipe.DURATION_MS,
                "上滑",
            )
            1 -> ProbeAction.Gesture(
                height * ListSwipe.END_FRACTION,
                height * ListSwipe.START_FRACTION,
                ListSwipe.DURATION_MS,
                "下滑",
            )
            2 -> ProbeAction.NodeScroll(forward = true)
            else -> ProbeAction.NodeScroll(forward = false)
        }
        lastAction = when (action) {
            is ProbeAction.Gesture -> action.label
            is ProbeAction.NodeScroll -> if (action.forward) "父节点向前" else "父节点向后"
            else -> ""
        }
        baseline = marks
        waitUntil = nowMs + settleMs
        actionCount += 1
        val intro = if (actionCount == 1) {
            listOf(
                ProbeAction.Log(
                    "列表滑动探测开始 职位=${marks.size} ${marks.joinToString("；") { "${it.fingerprint.substringBefore('|')}@${it.y}" }}",
                ),
            )
        } else {
            emptyList()
        }
        return intro + listOf(ProbeAction.Log("探测动作 $lastAction"), action)
    }

    private fun scrollReport(moved: Boolean, marks: List<CardMark>): String {
        val positions = if (marks.isEmpty()) {
            "无职位坐标"
        } else {
            marks.joinToString("；") { mark ->
                val old = baseline.firstOrNull { it.fingerprint == mark.fingerprint }?.y
                val shift = if (old == null) "新${mark.y}" else "$old→${mark.y}"
                "${mark.fingerprint.substringBefore('|')}@$shift"
            }
        }
        return "探测核对 动作=$lastAction 位移=$moved $positions"
    }

    companion object {
        fun listShifted(before: List<CardMark>, after: List<CardMark>, thresholdPx: Int = 40): Boolean {
            val beforeY = before.associate { it.fingerprint to it.y }
            val afterY = after.associate { it.fingerprint to it.y }
            if (beforeY.keys != afterY.keys) return true
            return afterY.any { (fingerprint, y) ->
                abs((beforeY[fingerprint] ?: y) - y) >= thresholdPx
            }
        }
    }
}
