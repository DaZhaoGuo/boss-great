package com.dazhao.bossgreet

data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

data class UiNode(
    val text: String,
    val className: String,
    val clickable: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
    val bounds: Bounds,
    val children: List<UiNode> = emptyList(),
)

enum class PageKind {
    LIST,
    DETAIL,
    CHAT,
    LIMIT,
    OTHER,
}

data class JobCard(
    val title: String,
    val salary: String,
    val company: String,
    val fingerprint: String,
    val clickX: Int,
    val clickY: Int,
)

data class ClickTarget(
    val x: Int,
    val y: Int,
    val label: String,
)

data class CardMark(
    val fingerprint: String,
    val y: Int,
)

enum class ScrollMethod {
    CARD_GESTURE,
    PARENT_SCROLL,
    LONG_GESTURE,
}

object ListSwipe {
    const val START_FRACTION = 0.72f
    const val END_FRACTION = 0.32f
    const val DURATION_MS = 420L
}

data class GreetFilter(
    val includes: List<String> = emptyList(),
    val excludes: List<String> = emptyList(),
) {
    companion object {
        fun keywords(raw: String): List<String> =
            raw.split(',', '，', '、', ';', '；')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
    }
}

object BossPages {
    const val PACKAGE_NAME = "com.hpbr.bosszhipin"

    private val salaryRegex = Regex("""\d+(?:\.\d+)?\s*[-~～]\s*\d+(?:\.\d+)?\s*[kK]""")
    private val metaRegex = Regex(
        """^(\d+\s*[-~～]\s*\d+年|\d+年以上|\d+年以内|\d+年以下|在校/应届)$""",
    )
    private val metaExact = setOf(
        "本科", "大专", "硕士", "博士", "不限", "学历不限", "经验不限", "在校生", "应届生", "实习",
    )
    private val badges = setOf("外地", "急聘", "外包", "热门")
    private val chrome = setOf(
        "推荐", "附近", "最新", "职位", "有了", "消息", "我的", "筛选",
        "立即沟通", "继续沟通", "职位详情", "岗位职责", "发送",
    )
    private val limitHints = listOf("已达上限", "次数用完", "次数已用完", "开聊次数")
    private val listEndHints = listOf("没有更多", "暂无更多", "已经到底", "暂无相关职位")
    private val dismissPriority = listOf("取消", "暂不", "以后再说", "我知道了", "知道了", "关闭")

    fun flatten(node: UiNode): List<UiNode> {
        val out = ArrayList<UiNode>()
        fun walk(current: UiNode) {
            out += current
            current.children.forEach { walk(it) }
        }
        walk(node)
        return out
    }

    fun visibleTexts(node: UiNode): List<String> =
        flatten(node).map { it.text.trim() }.filter { it.isNotEmpty() }

    fun isSalary(text: String): Boolean {
        val value = text.trim()
        return value == "面议" || salaryRegex.containsMatchIn(value)
    }

    fun isGreetLabel(text: String): Boolean = matchesAction(text, "立即沟通")

    fun isContinueLabel(text: String): Boolean = matchesAction(text, "继续沟通")

    fun isLoading(root: UiNode): Boolean {
        return flatten(root).any { node ->
            val text = node.text.trim()
            node.className.endsWith("ProgressBar") ||
                text.contains("加载中") ||
                text.contains("正在加载") ||
                text.contains("加载更多") ||
                text.contains("努力加载")
        }
    }

    fun listEndLabel(root: UiNode): String? {
        val screen = root.bounds
        return flatten(root).firstOrNull { node ->
            val text = node.text.trim()
            text.length <= 16 &&
                listEndHints.any { text.startsWith(it) } &&
                node.bounds.height > 0 &&
                node.bounds.top < screen.bottom &&
                node.bounds.bottom > screen.top
        }?.text?.trim()
    }

    fun isListEnded(root: UiNode): Boolean = listEndLabel(root) != null

    fun swipeDistance(cards: List<JobCard>, screenHeight: Int): Int {
        val gap = cards.sortedBy { it.clickY }
            .zipWithNext { current, next -> next.clickY - current.clickY }
            .firstOrNull { it > 80 }
        val raw = gap ?: (screenHeight * 0.30f).toInt()
        val minDistance = (screenHeight * 0.22f).toInt().coerceAtLeast(1)
        val maxDistance = (screenHeight * 0.55f).toInt().coerceAtLeast(minDistance)
        return raw.coerceIn(minDistance, maxDistance)
    }

    fun cardMarks(cards: List<JobCard>): List<CardMark> =
        cards.map { CardMark(it.fingerprint, it.clickY) }

    fun listMoved(before: List<CardMark>, after: List<CardMark>, thresholdPx: Int = 40): Boolean {
        val beforeY = before.associate { it.fingerprint to it.y }
        val afterY = after.associate { it.fingerprint to it.y }
        if (beforeY.keys != afterY.keys) return true
        return afterY.any { (fingerprint, y) -> (beforeY[fingerprint] ?: y) - y >= thresholdPx }
    }

    fun classify(root: UiNode): PageKind {
        val nodes = flatten(root)
        val texts = nodes.map { it.text.trim() }.filter { it.isNotEmpty() }
        val joined = texts.joinToString("\n")
        if (limitHints.any { joined.contains(it) }) return PageKind.LIMIT
        val hasGreet = texts.any { isGreetLabel(it) }
        val hasContinue = texts.any { isContinueLabel(it) }
        val hasDetailMark = texts.any { it == "职位详情" || it == "岗位职责" || it.startsWith("岗位职责") }
        val hasTabs = texts.any { it == "推荐" } && texts.any { it == "附近" }
        val salaryCount = texts.count { isSalary(it) }
        if ((hasGreet || hasContinue) && (hasDetailMark || !hasTabs)) return PageKind.DETAIL
        if (hasTabs && salaryCount >= 1) return PageKind.LIST
        if (nodes.any { it.editable } && texts.any { it == "发送" }) return PageKind.CHAT
        return PageKind.OTHER
    }

    fun findCards(
        root: UiNode,
        screenWidth: Int,
        screenHeight: Int,
        safeZone: Boolean = true,
    ): List<JobCard> {
        val nodes = flatten(root).filter { it.text.isNotBlank() }
        val cards = ArrayList<JobCard>()
        for (salaryNode in nodes.filter { isSalary(it.text) }) {
            val titleNode = pickTitle(nodes, salaryNode)
            val embedded = embeddedTitle(salaryNode.text)
            val title = titleNode?.text?.trim().orEmpty().ifEmpty { embedded.orEmpty() }
            if (title.length < 2) continue
            val company = pickCompany(nodes, salaryNode, titleNode)
            val clickX: Int
            val clickY: Int
            if (titleNode != null) {
                clickX = titleNode.bounds.centerX
                clickY = titleNode.bounds.centerY
            } else {
                clickX = salaryNode.bounds.left + salaryNode.bounds.width / 4
                clickY = salaryNode.bounds.centerY
            }
            if (safeZone && (clickY < screenHeight * 0.10 || clickY > screenHeight * 0.92)) continue
            val safeX = clickX.coerceIn(1, (screenWidth - 1).coerceAtLeast(1))
            val safeY = clickY.coerceIn(1, (screenHeight - 1).coerceAtLeast(1))
            cards += JobCard(
                title = title,
                salary = salaryNode.text.trim(),
                company = company,
                fingerprint = fingerprint(title, salaryNode.text, company),
                clickX = safeX,
                clickY = safeY,
            )
        }
        return cards.distinctBy { it.fingerprint }.sortedBy { it.clickY }
    }

    fun pick(cards: List<JobCard>, filter: GreetFilter, seen: Set<String>): JobCard? =
        cards.firstOrNull { accept(it, filter, seen) }

    fun accept(card: JobCard, filter: GreetFilter, seen: Set<String>): Boolean {
        if (card.fingerprint in seen) return false
        if (filter.includes.isNotEmpty() &&
            filter.includes.none { card.title.contains(it, ignoreCase = true) }
        ) {
            return false
        }
        if (filter.excludes.any { key ->
                card.title.contains(key, ignoreCase = true) ||
                    card.company.contains(key, ignoreCase = true)
            }
        ) {
            return false
        }
        return true
    }

    fun findGreet(root: UiNode): ClickTarget? = findLabel(root, ::isGreetLabel)

    fun findContinue(root: UiNode): ClickTarget? = findLabel(root, ::isContinueLabel)

    fun findDismiss(root: UiNode): ClickTarget? {
        val nodes = flatten(root)
        for (label in dismissPriority) {
            val hit = nodes.filter { it.text.trim() == label }.maxByOrNull { it.bounds.width }
            if (hit != null) return ClickTarget(hit.bounds.centerX, hit.bounds.centerY, label)
        }
        return null
    }

    fun detailFingerprint(root: UiNode, screenWidth: Int, screenHeight: Int): String =
        findCards(root, screenWidth, screenHeight, safeZone = false).firstOrNull()?.fingerprint
            ?: "unknown-detail"

    fun fingerprint(title: String, salary: String, company: String): String {
        fun normalize(value: String) = value.trim().replace(Regex("\\s+"), "").lowercase()
        return "${normalize(title)}|${normalize(salary)}|${normalize(company)}"
    }

    private fun findLabel(root: UiNode, predicate: (String) -> Boolean): ClickTarget? {
        val hit = flatten(root)
            .filter { predicate(it.text) }
            .maxWithOrNull(compareBy<UiNode> { it.bounds.width }.thenBy { it.bounds.top })
            ?: return null
        return ClickTarget(hit.bounds.centerX, hit.bounds.centerY, hit.text.trim())
    }

    private fun pickTitle(nodes: List<UiNode>, salary: UiNode): UiNode? {
        val salaryBounds = salary.bounds
        val sameRow = nodes.filter { node ->
            node !== salary &&
                !isSalary(node.text) &&
                verticalOverlap(node.bounds, salaryBounds) &&
                node.bounds.centerX < salaryBounds.centerX &&
                isTitleCandidate(node.text, minLength = 2)
        }
        if (sameRow.isNotEmpty()) return sameRow.maxByOrNull { it.bounds.width }
        val above = nodes.filter { node ->
            node !== salary &&
                !isSalary(node.text) &&
                node.bounds.bottom <= salaryBounds.top + 8 &&
                node.bounds.top >= salaryBounds.top - 160 &&
                isTitleCandidate(node.text, minLength = 4)
        }
        return above.maxByOrNull { it.bounds.width }
    }

    private fun pickCompany(nodes: List<UiNode>, salary: UiNode, title: UiNode?): String {
        val bottom = salary.bounds.bottom
        return nodes.filter { node ->
            node !== salary &&
                node !== title &&
                !isSalary(node.text) &&
                !isMetaTag(node.text) &&
                !isBadge(node.text) &&
                node.text.trim() !in chrome &&
                node.bounds.top >= bottom - 12 &&
                node.bounds.top <= bottom + 140 &&
                node.text.trim().length >= 2
        }.minByOrNull { it.bounds.top }?.text?.trim().orEmpty()
    }

    private fun isTitleCandidate(text: String, minLength: Int): Boolean {
        val value = text.trim()
        return value.length >= minLength && !isBadge(value) && value !in chrome && !isMetaTag(value)
    }

    private fun embeddedTitle(text: String): String? {
        val match = salaryRegex.find(text) ?: return null
        val title = text.substring(0, match.range.first).trim()
        return title.takeIf { it.length >= 2 && !isBadge(it) }
    }

    private fun verticalOverlap(first: Bounds, second: Bounds): Boolean {
        val overlap = minOf(first.bottom, second.bottom) - maxOf(first.top, second.top)
        if (overlap <= 0) return false
        val smaller = minOf(first.height, second.height).coerceAtLeast(1)
        return overlap * 2 >= smaller
    }

    private fun isMetaTag(text: String): Boolean {
        val value = text.trim()
        return value in metaExact || metaRegex.matches(value)
    }

    private fun isBadge(text: String): Boolean = text.trim() in badges

    private fun matchesAction(text: String, label: String): Boolean {
        val value = text.trim()
        return value == label || (value.startsWith(label) && value.length <= label.length + 2)
    }
}
