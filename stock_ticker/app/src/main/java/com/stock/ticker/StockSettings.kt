package com.stock.ticker

import android.content.Context

object StockSettings {

    private const val PREFS_NAME = "stock_ticker_settings"
    private const val KEY_COLOR = "overlay_color"
    private const val KEY_INTERVAL = "refresh_interval"
    private const val KEY_OFFSET = "overlay_offset"

    val presetColors = listOf(
        "红色" to 0xFFFF0000.toInt(),
        "橙色" to 0xFFFF6600.toInt(),
        "黄色" to 0xFFFFFF00.toInt(),
        "白色" to 0xFFFFFFFF.toInt(),
        "蓝色" to 0xFF4488FF.toInt(),
        "紫色" to 0xFFAA44FF.toInt(),
        "黑色" to 0xFF000000.toInt(),
    )

    val presetIntervals = listOf(1, 2, 3, 5, 10, 30)

    fun getColor(ctx: Context): Int {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_COLOR, 0xFFFFFFFF.toInt())
    }

    fun setColor(ctx: Context, color: Int) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_COLOR, color).apply()
    }

    fun getInterval(ctx: Context): Int {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_INTERVAL, 3)
    }

    fun setInterval(ctx: Context, seconds: Int) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_INTERVAL, seconds).apply()
    }

    fun getOffset(ctx: Context): Int {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_OFFSET, 32)
    }

    fun setOffset(ctx: Context, percent: Int) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_OFFSET, percent).apply()
    }
}
