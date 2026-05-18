package com.stock.ticker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 前台服务：每 5 秒获取股票实时价格，更新通知栏。
 * Activity 通过 [currentPrices] 和 [isRunning] 获取状态。
 */
class StockTickerService : Service() {

    // 从 SharedPreferences 动态读取股票列表，修改后通过 RELOAD 刷新
    private val stockCodes: List<String>
        get() = StockConfig.getStocks(this)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastDisplayText = "等待数据..."

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                // 重新加载股票列表后重启轮询
                scope.coroutineContext.cancelChildren()
                currentPrices = null
                lastDisplayText = "重新加载..."
                updateNotification(buildNotification(lastDisplayText))
                startPolling()
            }
            else -> {
                // ACTION_START 或首次启动
                startForeground(NOTIFICATION_ID, buildNotification("启动中..."))
                if (!isRunning) {
                    isRunning = true
                    startPolling()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        currentPrices = null
        scope.cancel()
        super.onDestroy()
    }

    private fun startPolling() {
        scope.launch {
            while (isActive && isRunning) {
                try {
                    val prices = fetchPrices()
                    if (prices.isNotEmpty()) {
                        currentPrices = prices
                        lastDisplayText = formatPrices(prices)
                    }
                } catch (_: Exception) {
                    // 网络异常时保持上次的数据
                }
                updateNotification(buildNotification(lastDisplayText))
                delay(5000L)
            }
        }
    }

    private fun fetchPrices(): List<StockPrice> {
        val codes = stockCodes
        if (codes.isEmpty()) return emptyList()
        val query = codes.joinToString(",")
        val url = URL("http://hq.sinajs.cn/list=$query")
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.setRequestProperty("Referer", "http://finance.sina.com.cn")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.connect()
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return emptyList()
            val text = BufferedReader(
                InputStreamReader(conn.inputStream, "GBK")
            ).use { it.readText() }
            parseSinaResponse(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseSinaResponse(text: String): List<StockPrice> {
        val codes = stockCodes
        val prices = mutableListOf<StockPrice>()
        for (line in text.lines()) {
            for (code in codes) {
                val prefix = "var hq_str_$code=\""
                if (!line.startsWith(prefix)) continue
                val data = line.removePrefix(prefix).trimEnd('"', ';', '\n', '\r')
                val fields = data.split(",")
                if (fields.size < 32) continue
                val name = fields[0]
                val current = fields[3].toDoubleOrNull() ?: 0.0
                val yesterdayClose = fields[2].toDoubleOrNull() ?: 0.0
                val change = current - yesterdayClose
                val changePercent = if (yesterdayClose > 0) change / yesterdayClose * 100 else 0.0
                prices.add(StockPrice(name, current, change, changePercent))
            }
        }
        return prices
    }

    private fun formatPrices(prices: List<StockPrice>): String {
        return prices.joinToString(" | ") { p ->
            val arrow = if (p.changePercent >= 0) "↑" else "↓"
            "${p.name} ${"%.2f".format(p.price)}$arrow${"%.2f".format(kotlin.math.abs(p.changePercent))}%"
        }
    }

    private fun buildNotification(content: String): Notification {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("行情 $time")
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setShowBadge(false)
            .build()
    }

    private fun updateNotification(n: Notification) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, n)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "行情数据",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "stock_ticker_01"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.stock.ticker.START"
        const val ACTION_STOP = "com.stock.ticker.STOP"
        const val ACTION_RELOAD = "com.stock.ticker.RELOAD"

        @JvmStatic
        @Volatile
        var currentPrices: List<StockPrice>? = null

        @JvmStatic
        @Volatile
        var isRunning = false
    }
}

data class StockPrice(
    val name: String,
    val price: Double,
    val change: Double,
    val changePercent: Double
)
