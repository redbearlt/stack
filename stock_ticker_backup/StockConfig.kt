package com.stock.ticker

import android.content.Context

/**
 * 股票配置管理：读取/保存监控的股票列表。
 * 数据存储在 SharedPreferences 中，修改后无需重新编译。
 */
object StockConfig {

    private const val PREFS_NAME = "stock_ticker_config"
    private const val KEY_STOCKS = "watch_list"

    /** 默认股票列表（首次安装时使用） */
    private val defaultStocks = listOf(
        "sh600519",  // 贵州茅台
        "sz300750",  // 宁德时代
        "sh600036",  // 招商银行
        "sh601318",  // 中国平安
        "sz002594",  // 比亚迪
    )

    /** 读取已保存的股票列表 */
    fun getStocks(context: Context): List<String> {
        val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_STOCKS, null) ?: return defaultStocks
        return saved.split(",").filter { it.isNotBlank() }
    }

    /** 保存股票列表 */
    fun saveStocks(context: Context, stocks: List<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STOCKS, stocks.joinToString(","))
            .apply()
    }

    /** 重置为默认列表 */
    fun resetToDefault(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_STOCKS)
            .apply()
    }

    /**
     * 智能识别股票代码所属市场。
     * 支持完整格式（sh600519）或纯代码（600519）：
     * - 6、9 开头 → 上海（sh）
     * - 0、2、3 开头 → 深圳（sz）
     * - 4、8 开头 → 北京（bj）
     */
    fun normalizeCode(input: String): String? {
        val trimmed = input.trim().lowercase()
        if (trimmed.isEmpty()) return null

        // 已有市场前缀
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

        // 纯数字代码，自动判断市场
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

    /** 获取市场的中文显示名 */
    fun getMarketDisplay(code: String): String {
        return when (code.substring(0, 2)) {
            "sh" -> "沪"
            "sz" -> "深"
            "bj" -> "京"
            else -> code.substring(0, 2)
        }
    }

    /** 获取纯股票代码（去掉前缀） */
    fun getPureCode(code: String): String = code.substring(2)
}
