package com.stock.ticker

import android.content.Context

/**
 * 股票配置管理：读取和保存监控股票列表。
 * 数据存储在 SharedPreferences 中，修改后无需重新编译。
 */
object StockConfig {

    private const val PREFS_NAME = "stock_ticker_config"
    private const val KEY_STOCKS = "watch_list"

    private val defaultStocks = listOf(
        "sh600519", // 贵州茅台
    )

    fun getStocks(context: Context): List<String> {
        val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_STOCKS, null) ?: return defaultStocks
        return saved.split(",").filter { it.isNotBlank() }
    }

    fun saveStocks(context: Context, stocks: List<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STOCKS, stocks.joinToString(","))
            .apply()
    }

    fun resetToDefault(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_STOCKS)
            .apply()
    }

    /**
     * 支持完整格式（sh600519）或纯数字代码（600519）。
     * - 6、9 开头：沪市
     * - 0、2、3 开头：深市
     * - 4、8 开头：北交所
     */
    fun normalizeCode(input: String): String? {
        val trimmed = input.trim().lowercase()
        if (trimmed.isEmpty()) return null

        if (trimmed.length > 6) {
            val prefix = trimmed.substring(0, 2)
            if (prefix in listOf("sh", "sz", "bj")) {
                val code = trimmed.substring(2)
                if (code.length == 6 && code.all { it.isDigit() }) {
                    return "$prefix$code"
                }
            }
            return null
        }

        if (trimmed.length == 6 && trimmed.all { it.isDigit() }) {
            val market = when (trimmed.first()) {
                '6', '9' -> "sh"
                '0', '2', '3' -> "sz"
                '4', '8' -> "bj"
                else -> return null
            }
            return "$market$trimmed"
        }

        return null
    }

    fun getMarketDisplay(code: String): String {
        return when (code.substring(0, 2)) {
            "sh" -> "沪市"
            "sz" -> "深市"
            "bj" -> "北交所"
            else -> code.substring(0, 2)
        }
    }

    fun getPureCode(code: String): String = code.substring(2)
}
