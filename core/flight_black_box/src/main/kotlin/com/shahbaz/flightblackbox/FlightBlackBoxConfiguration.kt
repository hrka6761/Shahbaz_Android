package com.shahbaz.flightblackbox

import android.content.Context

/** Persistent access to recorder configuration chosen by Settings. */
class FlightBlackBoxConfiguration internal constructor(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): FbbConfig {
        val defaults = FbbConfig()
        return runCatching {
            FbbConfig(
                traceLevel = readEnum(KEY_TRACE_LEVEL, defaults.traceLevel),
                durabilityMode = readEnum(KEY_DURABILITY_MODE, defaults.durabilityMode),
                queueCapacity = preferences.getInt(KEY_QUEUE_CAPACITY, defaults.queueCapacity),
                queueBackpressureWarningThresholdMillis = preferences.getLong(
                    KEY_QUEUE_BACKPRESSURE_WARNING_THRESHOLD_MILLIS,
                    defaults.queueBackpressureWarningThresholdMillis,
                ),
                normalFlushIntervalMillis = preferences.getLong(
                    KEY_NORMAL_FLUSH_INTERVAL_MILLIS,
                    defaults.normalFlushIntervalMillis,
                ),
                forceIntervalMillis = preferences.getLong(
                    KEY_FORCE_INTERVAL_MILLIS,
                    defaults.forceIntervalMillis,
                ),
                maxInlineValueLength = preferences.getInt(
                    KEY_MAX_INLINE_VALUE_LENGTH,
                    defaults.maxInlineValueLength,
                ),
                maxDetailLength = preferences.getInt(KEY_MAX_DETAIL_LENGTH, defaults.maxDetailLength),
                includeThreadName = preferences.getBoolean(KEY_INCLUDE_THREAD_NAME, defaults.includeThreadName),
            )
        }.getOrElse {
            preferences.edit().clear().apply()
            defaults
        }
    }

    fun save(config: FbbConfig): FbbConfig {
        preferences.edit()
            .putString(KEY_TRACE_LEVEL, config.traceLevel.name)
            .putString(KEY_DURABILITY_MODE, config.durabilityMode.name)
            .putInt(KEY_QUEUE_CAPACITY, config.queueCapacity)
            .putLong(
                KEY_QUEUE_BACKPRESSURE_WARNING_THRESHOLD_MILLIS,
                config.queueBackpressureWarningThresholdMillis,
            )
            .putLong(KEY_NORMAL_FLUSH_INTERVAL_MILLIS, config.normalFlushIntervalMillis)
            .putLong(KEY_FORCE_INTERVAL_MILLIS, config.forceIntervalMillis)
            .putInt(KEY_MAX_INLINE_VALUE_LENGTH, config.maxInlineValueLength)
            .putInt(KEY_MAX_DETAIL_LENGTH, config.maxDetailLength)
            .putBoolean(KEY_INCLUDE_THREAD_NAME, config.includeThreadName)
            .apply()
        return config
    }

    fun update(transform: FbbConfig.() -> FbbConfig): FbbConfig =
        save(read().transform())

    fun reset(): FbbConfig {
        preferences.edit().clear().apply()
        return FbbConfig()
    }

    private inline fun <reified T : Enum<T>> readEnum(key: String, defaultValue: T): T {
        val stored = preferences.getString(key, null) ?: return defaultValue
        return runCatching { enumValueOf<T>(stored) }.getOrDefault(defaultValue)
    }

    private companion object {
        const val PREFERENCES_NAME = "flight_black_box_configuration"
        const val KEY_TRACE_LEVEL = "traceLevel"
        const val KEY_DURABILITY_MODE = "durabilityMode"
        const val KEY_QUEUE_CAPACITY = "queueCapacity"
        const val KEY_QUEUE_BACKPRESSURE_WARNING_THRESHOLD_MILLIS =
            "queueBackpressureWarningThresholdMillis"
        const val KEY_NORMAL_FLUSH_INTERVAL_MILLIS = "normalFlushIntervalMillis"
        const val KEY_FORCE_INTERVAL_MILLIS = "forceIntervalMillis"
        const val KEY_MAX_INLINE_VALUE_LENGTH = "maxInlineValueLength"
        const val KEY_MAX_DETAIL_LENGTH = "maxDetailLength"
        const val KEY_INCLUDE_THREAD_NAME = "includeThreadName"
    }
}
