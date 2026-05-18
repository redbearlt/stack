package com.stock.ticker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
class StockTickerService : Service() {

    private val stockCodes: List<String>
        get() = StockConfig.getStocks(this)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastPriceText = "0.00"
    private var fetchErrorCount = 0
    private var overlayView: TextView? = null
    private var windowManager: WindowManager? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                removeOverlay()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_RELOAD -> {
                scope.coroutineContext[Job]?.cancelChildren()
                currentPrices = null
                lastPriceText = "0.00"
                removeOverlay()
                showOverlay()
                updateNotification(buildNotification(lastPriceText))
                startPolling()
            }

            else -> {
                showOverlay()
                startForeground(NOTIFICATION_ID, buildNotification("0.00"))
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
        removeOverlay()
        scope.cancel()
        super.onDestroy()
    }

    private fun startPolling() {
        val ctx = this
        scope.launch {
            while (isActive && isRunning) {
                try {
                    val prices = fetchPrices()
                    if (prices.isNotEmpty()) {
                        currentPrices = prices
                        lastPriceText = "%.2f".format(prices.first().price)
                        fetchErrorCount = 0
                    } else {
                        fetchErrorCount++
                        if (fetchErrorCount > 3) lastPriceText = "--"
                    }
                } catch (_: Exception) {
                    fetchErrorCount++
                    if (fetchErrorCount > 3) lastPriceText = "--"
                }
                updateOverlay(lastPriceText)
                updateNotification(buildNotification(lastPriceText))
                delay(StockSettings.getInterval(ctx) * 1000L)
            }
        }
    }

    private fun showOverlay() {
        if (overlayView != null) return
        val wm = windowManager ?: return

        val overlayColor = StockSettings.getColor(this)
        val offsetPercent = StockSettings.getOffset(this)

        val tv = TextView(this).apply {
            text = "0.00"
            textSize = 13f
            setTextColor(overlayColor)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(8, 2, 8, 2)
            alpha = 0.92f
        }

        val statusBarHeight = resources.getIdentifier("status_bar_height", "dimen", "android")
            .takeIf { it > 0 }
            ?.let { resources.getDimensionPixelSize(it) }
            ?: 72
        val screenWidth = resources.displayMetrics.widthPixels
        val offsetX = (screenWidth * offsetPercent / 100.0).toInt()

        val params = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            format = PixelFormat.TRANSLUCENT
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = statusBarHeight
            gravity = Gravity.TOP or Gravity.START
            x = offsetX
            y = 0
        }

        wm.addView(tv, params)
        overlayView = tv
    }

    private fun updateOverlay(text: String) {
        overlayView?.post { overlayView?.text = text }
    }

    private fun removeOverlay() {
        val wm = windowManager ?: return
        overlayView?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        overlayView = null
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

            val text = BufferedReader(InputStreamReader(conn.inputStream, "GBK")).use { it.readText() }
            parseSinaResponse(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseSinaResponse(text: String): List<StockPrice> {
        val prices = mutableListOf<StockPrice>()
        for (line in text.lines()) {
            for (code in stockCodes) {
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
                prices.add(StockPrice(code, name, current, change, changePercent))
            }
        }
        return prices
    }

    private fun buildNotification(fallbackPrice: String): Notification {
        val price = currentPrices?.firstOrNull()
        val title = if (price != null) {
            "${price.name} ${"%.2f".format(price.price)}"
        } else {
            fallbackPrice
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(getString(R.string.notification_content))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSilent(true)
            .setShowWhen(false)
            .build()
    }

    private fun updateNotification(notification: Notification) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                description = getString(R.string.notification_channel_description)
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
    val code: String,
    val name: String,
    val price: Double,
    val change: Double,
    val changePercent: Double
)
