package com.shahbaz.flightblackbox

import android.content.Context

/** Persistent access to recorder configuration, with diagnostics fixed to deep and strict modes. */
class FlightBlackBoxConfiguration internal constructor(context: Context) {
    /**
     * Exposes the preferences value.
     */
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /**
     * Runs the read operation.
     */
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
            ).withRequiredDiagnosticsMode()
        }.getOrElse {
            preferences.edit().clear().apply()
            defaults
        }
    }

    /**
     * Runs the save operation.
     */
    fun save(config: FbbConfig): FbbConfig {
        val fixedConfig = config.withRequiredDiagnosticsMode()
        preferences.edit()
            .putString(KEY_TRACE_LEVEL, fixedConfig.traceLevel.name)
            .putString(KEY_DURABILITY_MODE, fixedConfig.durabilityMode.name)
            .putInt(KEY_QUEUE_CAPACITY, fixedConfig.queueCapacity)
            .putLong(
                KEY_QUEUE_BACKPRESSURE_WARNING_THRESHOLD_MILLIS,
                fixedConfig.queueBackpressureWarningThresholdMillis,
            )
            .putLong(KEY_NORMAL_FLUSH_INTERVAL_MILLIS, fixedConfig.normalFlushIntervalMillis)
            .putLong(KEY_FORCE_INTERVAL_MILLIS, fixedConfig.forceIntervalMillis)
            .putInt(KEY_MAX_INLINE_VALUE_LENGTH, fixedConfig.maxInlineValueLength)
            .putInt(KEY_MAX_DETAIL_LENGTH, fixedConfig.maxDetailLength)
            .putBoolean(KEY_INCLUDE_THREAD_NAME, fixedConfig.includeThreadName)
            .apply()
        return fixedConfig
    }

    /**
     * Runs the update operation.
     */
    fun update(transform: FbbConfig.() -> FbbConfig): FbbConfig =
        save(read().transform())

    /**
     * Runs the reset operation.
     */
    fun reset(): FbbConfig {
        preferences.edit().clear().apply()
        return FbbConfig()
    }

    /**
     * Runs the fun operation.
     */
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
