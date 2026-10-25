package com.dazhao.bossgreet

import android.util.Log
import java.util.ArrayDeque
import java.util.Calendar
import java.util.concurrent.CopyOnWriteArrayList

object GreetRuntime {
    private const val LOG_TAG = "BossGreet"
    @Volatile var running: Boolean = false
    @Volatile var scrollProbe: Boolean = false
    @Volatile var dumpRequested: Boolean = false
    @Volatile var engine: GreetEngine? = null
    val listScrollProbe = ListScrollProbe()
    @Volatile var baseIntervalMs: Int = GreetConfig.DEFAULT.intervalMs
    @Volatile var maxCount: Int = GreetConfig.DEFAULT.maxCount
    @Volatile var scrollAccepted: Boolean? = null

    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val lines = ArrayDeque<String>()
    private val lock = Any()

    fun start(config: GreetConfig, seen: MutableSet<String>) {
        val safe = GreetConfig.sanitize(
            config.maxCount,
            config.intervalMs,
            config.includeRaw,
            config.excludeRaw,
        )
        engine = GreetEngine(safe.filter, safe.maxCount, seen)
        baseIntervalMs = safe.intervalMs
        maxCount = safe.maxCount
        scrollAccepted = null
        scrollProbe = false
        listScrollProbe.reset()
        running = true
        log("开始打招呼，单次上限 ${safe.maxCount}")
    }

    fun startScrollProbe() {
        running = false
        engine = null
        scrollAccepted = null
        listScrollProbe.reset()
        scrollProbe = true
        log("开始列表滑动探测，不打开职位、不打招呼")
    }

    fun requestStop(reason: String) {
        running = false
        scrollProbe = false
        log(reason)
    }

    fun log(message: String) {
        val stamped = message.lineSequence().mapIndexed { index, line ->
            if (index == 0) "${stamp()} $line" else line
        }.joinToString("\n")
        val snapshot = synchronized(lock) {
            lines.addLast(stamped)
            while (lines.size > 80) lines.removeFirst()
            lines.joinToString("\n")
        }
        Log.i(LOG_TAG, message)
        listeners.forEach { it(snapshot) }
    }

    fun snapshot(): String = synchronized(lock) { lines.joinToString("\n") }

    fun addListener(listener: (String) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners -= listener
    }

    private fun stamp(): String {
        val calendar = Calendar.getInstance()
        return "%02d:%02d:%02d".format(
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            calendar.get(Calendar.SECOND),
        )
    }
}
