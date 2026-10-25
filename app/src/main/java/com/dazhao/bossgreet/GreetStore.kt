package com.dazhao.bossgreet

import android.content.Context
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class GreetConfig(
    val maxCount: Int,
    val intervalMs: Int,
    val includeRaw: String,
    val excludeRaw: String,
) {
    val filter: GreetFilter
        get() = GreetFilter(
            includes = GreetFilter.keywords(includeRaw),
            excludes = GreetFilter.keywords(excludeRaw),
        )

    companion object {
        val DEFAULT = GreetConfig(maxCount = 200, intervalMs = 2000, includeRaw = "", excludeRaw = "")

        fun sanitize(
            maxCount: Int,
            intervalMs: Int,
            includeRaw: String,
            excludeRaw: String,
        ): GreetConfig = GreetConfig(
            maxCount = maxCount.coerceIn(1, 500),
            intervalMs = intervalMs.coerceIn(800, 10_000),
            includeRaw = includeRaw.trim(),
            excludeRaw = excludeRaw.trim(),
        )
    }
}

class GreetStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val seen: MutableSet<String> = loadSeen()

    fun load(): GreetConfig = GreetConfig.sanitize(
        maxCount = loadMax(),
        intervalMs = prefs.getInt(KEY_INTERVAL, GreetConfig.DEFAULT.intervalMs),
        includeRaw = prefs.getString(KEY_INCLUDE, "").orEmpty(),
        excludeRaw = prefs.getString(KEY_EXCLUDE, "").orEmpty(),
    )

    fun save(config: GreetConfig) {
        val safe = GreetConfig.sanitize(
            config.maxCount,
            config.intervalMs,
            config.includeRaw,
            config.excludeRaw,
        )
        prefs.edit()
            .putBoolean(KEY_LIMIT_MIGRATED, true)
            .putInt(KEY_MAX, safe.maxCount)
            .putInt(KEY_INTERVAL, safe.intervalMs)
            .putString(KEY_INCLUDE, safe.includeRaw)
            .putString(KEY_EXCLUDE, safe.excludeRaw)
            .apply()
    }

    fun markSeen(fingerprint: String) {
        seen += fingerprint
        prefs.edit()
            .putString(KEY_DAY, today())
            .putStringSet(KEY_SEEN, HashSet(seen))
            .apply()
    }

    fun clearSeen() {
        seen.clear()
        prefs.edit()
            .putString(KEY_DAY, today())
            .putStringSet(KEY_SEEN, HashSet())
            .apply()
    }

    private fun loadMax(): Int {
        if (prefs.getBoolean(KEY_LIMIT_MIGRATED, false)) {
            return prefs.getInt(KEY_MAX, GreetConfig.DEFAULT.maxCount)
        }
        prefs.edit().putBoolean(KEY_LIMIT_MIGRATED, true).putInt(KEY_MAX, 200).apply()
        return 200
    }

    private fun loadSeen(): MutableSet<String> {
        if (prefs.getString(KEY_DAY, "") != today()) return mutableSetOf()
        return prefs.getStringSet(KEY_SEEN, emptySet()).orEmpty().toMutableSet()
    }

    private fun today(): String = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)

    private companion object {
        const val PREFS = "boss_greet"
        const val KEY_MAX = "max_count"
        const val KEY_LIMIT_MIGRATED = "limit_migrated_200"
        const val KEY_INTERVAL = "interval_ms"
        const val KEY_INCLUDE = "include"
        const val KEY_EXCLUDE = "exclude"
        const val KEY_DAY = "seen_day"
        const val KEY_SEEN = "seen_ids"
    }
}
