package com.dazhao.bossgreet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BossPagesTest {
    @Test
    fun salaryRecognizesKRangeAndRejectsExperienceOrHeadcount() {
        assertTrue(BossPages.isSalary("25-35K"))
        assertTrue(BossPages.isSalary("20-30K·13薪"))
        assertTrue(BossPages.isSalary("面议"))
        assertFalse(BossPages.isSalary("5-10年"))
        assertFalse(BossPages.isSalary("1000-9999人"))
    }

    @Test
    fun keywordsSplitOnCommaEnumerationAndSemicolon() {
        assertEquals(listOf("Android", "前端", "开发"), GreetFilter.keywords("Android，前端,  开发； "))
    }

    @Test
    fun classifiesListDetailChatAndLimit() {
        assertEquals(PageKind.LIST, BossPages.classify(listPage()))
        assertEquals(PageKind.DETAIL, BossPages.classify(detail("立即沟通")))
        assertEquals(PageKind.DETAIL, BossPages.classify(detail("继续沟通")))
        assertEquals(
            PageKind.CHAT,
            BossPages.classify(
                page(
                    tv("", b(40, 2000, 800, 2100), editable = true),
                    tv("发送", b(820, 2000, 1040, 2100)),
                ),
            ),
        )
        assertEquals(
            PageKind.LIMIT,
            BossPages.classify(page(tv("今日主动沟通次数已达上限", b(80, 800, 980, 900)))),
        )
    }

    @Test
    fun cardUsesTitleOnTheSameRowAsSalary() {
        val card = BossPages.findCards(listPage(), 1080, 2400).single()
        assertEquals("高级安卓前端开发", card.title)
        assertEquals("25-35K", card.salary)
        assertEquals("TEKsystems 不需要融资 1000-9999人", card.company)
        assertEquals(370, card.clickX)
        assertEquals(435, card.clickY)
        assertEquals(
            "高级安卓前端开发|25-35k|teksystems不需要融资1000-9999人",
            card.fingerprint,
        )
    }

    @Test
    fun cardUsesTitleAboveSalaryWhenTheyAreNotOnTheSameRow() {
        val root = page(
            tv("推荐", b(40, 180, 160, 240)),
            tv("附近", b(180, 180, 300, 240)),
            tv("后端开发工程师", b(40, 480, 700, 540)),
            tv("30-45K", b(760, 560, 1040, 630)),
            tv("知象光电科技", b(40, 650, 700, 710)),
        )
        val card = BossPages.findCards(root, 1080, 2400).single()
        assertEquals("后端开发工程师", card.title)
        assertEquals("知象光电科技", card.company)
    }

    @Test
    fun filterSkipsSeenIncludedAndExcludedCards() {
        val cards = BossPages.findCards(twoCards(), 1080, 2400)
        assertEquals("产品经理", BossPages.pick(cards, GreetFilter(includes = listOf("产品")), emptySet())?.title)
        assertEquals(
            "高级安卓前端开发",
            BossPages.pick(cards, GreetFilter(excludes = listOf("科创")), emptySet())?.title,
        )
        assertNull(BossPages.pick(cards, GreetFilter(excludes = listOf("TEK", "科创")), emptySet()))
        val seen = setOf(cards.first().fingerprint)
        assertEquals("产品经理", BossPages.pick(cards, GreetFilter(), seen)?.title)
    }

    @Test
    fun dismissPrefersCancel() {
        val root = page(
            tv("开通会员后可继续沟通", b(80, 700, 900, 780)),
            tv("我知道了", b(600, 1100, 900, 1180)),
            tv("取消", b(120, 1100, 420, 1180)),
        )
        assertEquals(PageKind.OTHER, BossPages.classify(root))
        assertEquals("取消", BossPages.findDismiss(root)?.label)
    }

    private fun twoCards(): UiNode = page(
        tv("推荐", b(40, 180, 160, 240)),
        tv("附近", b(180, 180, 300, 240)),
        tv("高级安卓前端开发", b(40, 400, 700, 470)),
        tv("25-35K", b(760, 400, 1040, 470)),
        tv("TEKsystems 不需要融资 1000-9999人", b(40, 490, 900, 550)),
        tv("产品经理", b(40, 900, 500, 970)),
        tv("18-23K", b(760, 900, 1040, 970)),
        tv("深圳科创学院", b(40, 990, 700, 1050)),
    )
}

class GreetEngineTest {
    @Test
    fun greetsThenReturnsAndStopsAtLimit() {
        val engine = GreetEngine(GreetFilter(), maxSuccess = 1, seen = mutableSetOf())
        val opened = engine.onTick(listPage(), 1080, 2400, 10_000, true)
        assertTrue(opened.any { it is EngineAction.Tap && it.reason == "高级安卓前端开发" })
        assertEquals(EngineStep.WAIT_DETAIL, engine.step)

        val greet = engine.onTick(detail("立即沟通"), 1080, 2400, 12_000, true)
        assertTrue(greet.any { it is EngineAction.Tap && it.reason == "立即沟通" })

        assertTrue(engine.onTick(detail("继续沟通"), 1080, 2400, 13_000, true).isEmpty())
        val done = engine.onTick(detail("继续沟通"), 1080, 2400, 14_000, true)
        assertEquals(1, engine.successCount)
        assertTrue(done.any { it is EngineAction.Back })
        assertTrue(done.any { it is EngineAction.Log && it.message == "已沟通 高级安卓前端开发" })

        assertTrue(engine.onTick(listPage(), 1080, 2400, 16_000, true).isEmpty())
        val stopped = engine.onTick(listPage(), 1080, 2400, 18_000, true)
        assertTrue(stopped.any { it is EngineAction.Stop })
        assertEquals(1, engine.attemptCount)
    }

    @Test
    fun continueOnDetailIsSkippedWithoutSuccess() {
        val seen = mutableSetOf<String>()
        val engine = GreetEngine(GreetFilter(), maxSuccess = 5, seen = seen)
        val actions = engine.onTick(detail("继续沟通"), 1080, 2400, 10_000, true)
        assertEquals(0, engine.successCount)
        assertTrue(actions.any { it is EngineAction.Back })
        assertEquals(
            BossPages.fingerprint("高级安卓前端开发", "25-35K", ""),
            seen.single(),
        )
    }

    @Test
    fun retriesGreetOnceThenSkips() {
        val engine = GreetEngine(GreetFilter(), maxSuccess = 5, seen = mutableSetOf())
        engine.onTick(detail("立即沟通"), 1080, 2400, 10_000, true)
        val retry = engine.onTick(detail("立即沟通"), 1080, 2400, 12_000, true)
        assertTrue(retry.any { it is EngineAction.Log && it.message.startsWith("再次点击立即沟通") })
        val skipped = engine.onTick(detail("立即沟通"), 1080, 2400, 14_000, true)
        assertEquals(0, engine.successCount)
        assertTrue(skipped.any { it is EngineAction.Back })
        assertTrue(skipped.any { it is EngineAction.Log && it.message.contains("仍未进入沟通") })
    }

    @Test
    fun unchangedListStopsAfterThreeScrolls() {
        val list = listPage()
        val seen = BossPages.findCards(list, 1080, 2400).map { it.fingerprint }.toMutableSet()
        val engine = GreetEngine(GreetFilter(), maxSuccess = 20, seen = seen)
        repeat(3) { index ->
            val actions = engine.onTick(list, 1080, 2400, 10_000L + index * 2_000, true)
            assertTrue(actions.any { it is EngineAction.Scroll })
        }
        val stopped = engine.onTick(list, 1080, 2400, 16_000, true)
        assertTrue(stopped.any { it is EngineAction.Stop && it.reason.contains("没有新职位") })
    }

    @Test
    fun limitPageStopsImmediately() {
        val engine = GreetEngine(GreetFilter(), maxSuccess = 20, seen = mutableSetOf())
        val actions = engine.onTick(
            page(tv("今日主动沟通次数已达上限", b(80, 800, 980, 900))),
            1080, 2400, 10_000, true,
        )
        assertTrue(actions.single() is EngineAction.Stop)
    }

    @Test
    fun otherPageTapsCancel() {
        val engine = GreetEngine(GreetFilter(), maxSuccess = 20, seen = mutableSetOf())
        val actions = engine.onTick(
            page(
                tv("开通会员后可继续查看", b(80, 700, 900, 780)),
                tv("我知道了", b(600, 1100, 900, 1180)),
                tv("取消", b(120, 1100, 420, 1180)),
            ),
            1080, 2400, 10_000, true,
        )
        val tap = actions.filterIsInstance<EngineAction.Tap>().single()
        assertEquals("取消", tap.reason)
        assertEquals(270, tap.x)
    }
}

private fun b(left: Int, top: Int, right: Int, bottom: Int) = Bounds(left, top, right, bottom)

private fun tv(text: String, bounds: Bounds, editable: Boolean = false) = UiNode(
    text = text,
    className = "android.widget.TextView",
    clickable = false,
    scrollable = false,
    editable = editable,
    bounds = bounds,
)

private fun page(vararg nodes: UiNode) = UiNode(
    text = "",
    className = "android.widget.FrameLayout",
    clickable = false,
    scrollable = true,
    editable = false,
    bounds = Bounds(0, 0, 1080, 2400),
    children = nodes.toList(),
)

private fun listPage(): UiNode = page(
    tv("推荐", b(40, 180, 160, 240)),
    tv("附近", b(180, 180, 300, 240)),
    tv("最新", b(320, 180, 440, 240)),
    tv("高级安卓前端开发", b(40, 400, 700, 470)),
    tv("25-35K", b(760, 400, 1040, 470)),
    tv("TEKsystems 不需要融资 1000-9999人", b(40, 490, 900, 550)),
    tv("10年以上", b(40, 570, 200, 630)),
    tv("本科", b(220, 570, 320, 630)),
)

private fun detail(button: String): UiNode = page(
    tv("高级安卓前端开发", b(40, 280, 700, 360)),
    tv("25-35K", b(760, 280, 1040, 360)),
    tv("职位详情", b(40, 700, 300, 760)),
    tv("岗位职责", b(40, 900, 300, 960)),
    tv(button, b(40, 2100, 1040, 2220)),
)
