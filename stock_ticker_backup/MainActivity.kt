package com.stock.ticker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.stock.ticker.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 股票行情 App 主界面
 *
 * 打开后自动启动后台监控，关闭时自动停止。
 * 行情数据在界面列表和系统通知栏同时显示。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var uiUpdateJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toggleButton.setOnClickListener {
            if (StockTickerService.isRunning) {
                stopService()
            } else {
                requestPermissionAndStart()
            }
        }

        binding.manageBtn.setOnClickListener {
            showManageDialog()
        }

        // 如果服务已在运行（如从后台恢复），直接同步UI
        if (StockTickerService.isRunning) {
            updateUiState(true)
            startUiUpdates()
        }
    }

    override fun onDestroy() {
        // 只在非配置变更（如旋转屏幕）时停止服务
        if (!isChangingConfigurations) {
            stopService()
        }
        super.onDestroy()
    }

    // ─── 启动 / 停止 ───────────────────────────────────────

    private fun requestPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    PERMISSION_CODE
                )
                return
            }
        }
        doStartService()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_CODE) {
            doStartService()
        }
    }

    private fun doStartService() {
        Intent(this, StockTickerService::class.java).also {
            it.action = StockTickerService.ACTION_START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(it)
            } else {
                startService(it)
            }
        }
        updateUiState(true)
        startUiUpdates()
    }

    private fun stopService() {
        uiUpdateJob?.cancel()
        Intent(this, StockTickerService::class.java).also {
            it.action = StockTickerService.ACTION_STOP
            startService(it)
        }
        updateUiState(false)
        clearStockList()
    }

    // ─── UI 更新 ─────────────────────────────────────────

    private fun startUiUpdates() {
        uiUpdateJob?.cancel()
        uiUpdateJob = lifecycleScope.launch {
            while (isActive) {
                val prices = StockTickerService.currentPrices
                if (prices != null) {
                    renderStockList(prices)
                }
                binding.statusText.text = if (StockTickerService.isRunning) {
                    "监控运行中"
                } else {
                    "已停止"
                }
                delay(1000L)
            }
        }
    }

    private fun updateUiState(running: Boolean) {
        binding.toggleButton.text = if (running) "停止" else "启动"
    }

    // ─── 股票列表渲染 ─────────────────────────────────────

    private fun renderStockList(prices: List<StockPrice>) {
        if (binding.stockList.childCount == prices.size) {
            // 只更新已有条目
            prices.forEachIndexed { i, p ->
                val row = binding.stockList.getChildAt(i) as? View ?: return@forEachIndexed
                updateRow(row, p)
            }
        } else {
            // 重建列表（首次或数量变化时）
            binding.stockList.removeAllViews()
            prices.forEach { p ->
                binding.stockList.addView(createStockRow(p))
            }
        }
    }

    private fun createStockRow(price: StockPrice): View {
        val density = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                (12 * density).toInt(),
                (14 * density).toInt(),
                (12 * density).toInt(),
                (14 * density).toInt()
            )
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, android.R.color.white))
        }

        val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val paramsEnd = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        // 股票名
        val nameTv = TextView(this).apply {
            layoutParams = params
            text = price.name
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.black))
        }

        // 价格 + 涨幅（竖直排列）
        val priceLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            layoutParams = paramsEnd
        }

        val priceTv = TextView(this).apply {
            text = "%.2f".format(price.price)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        }

        val changeTv = TextView(this).apply {
            textSize = 13f
        }

        applyPriceColor(priceTv, changeTv, price)
        priceLayout.addView(priceTv)
        priceLayout.addView(changeTv)

        // 分割线
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).also {
                it.setMargins(0, 0, 0, 0)
            }
            setBackgroundColor(0xFFE0E0E0.toInt())
        }

        row.addView(nameTv)
        row.addView(priceLayout)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        container.addView(row)
        container.addView(divider)

        return container
    }

    private fun updateRow(view: View, price: StockPrice) {
        val container = view as? LinearLayout ?: return
        if (container.childCount < 1) return
        val row = container.getChildAt(0) as? LinearLayout ?: return
        if (row.childCount < 2) return

        val priceLayout = row.getChildAt(1) as? LinearLayout ?: return
        if (priceLayout.childCount < 2) return

        val priceTv = priceLayout.getChildAt(0) as? TextView ?: return
        val changeTv = priceLayout.getChildAt(1) as? TextView ?: return

        priceTv.text = "%.2f".format(price.price)
        applyPriceColor(priceTv, changeTv, price)
    }

    private fun applyPriceColor(priceTv: TextView, changeTv: TextView, price: StockPrice) {
        val color = when {
            price.changePercent > 0.01 -> ContextCompat.getColor(this, R.color.stock_up)
            price.changePercent < -0.01 -> ContextCompat.getColor(this, R.color.stock_down)
            else -> ContextCompat.getColor(this, R.color.stock_unchanged)
        }
        val arrow = when {
            price.changePercent > 0.01 -> "↑"
            price.changePercent < -0.01 -> "↓"
            else -> "—"
        }
        priceTv.setTextColor(color)
        changeTv.text = "$arrow${"%.2f".format(kotlin.math.abs(price.changePercent))}%"
        changeTv.setTextColor(color)
    }

    private fun clearStockList() {
        binding.stockList.removeAllViews()
    }

    // ─── 股票管理对话框 ──────────────────────────────────

    private fun showManageDialog() {
        val stocks = StockConfig.getStocks(this).toMutableList()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        // ─ 当前列表 ─
        val listHint = TextView(this).apply {
            text = "当前监控的股票（${stocks.size} 只）"
            textSize = 14f
            setTextColor(0xFF666666.toInt())
        }
        root.addView(listHint)

        val listLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun refreshList() {
            listLayout.removeAllViews()
            if (stocks.isEmpty()) {
                val emptyHint = TextView(this).apply {
                    text = "暂无股票，请在下方添加"
                    textSize = 14f
                    setPadding(0, 16, 0, 16)
                    setTextColor(0xFF999999.toInt())
                }
                listLayout.addView(emptyHint)
            } else {
                stocks.forEachIndexed { index, code ->
                    val row = createManageRow(code) {
                        stocks.removeAt(index)
                        refreshList()
                    }
                    listLayout.addView(row)
                }
            }
        }
        refreshList()
        root.addView(listLayout)

        // ─ 分割线 ─
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).also { it.setMargins(0, 16, 0, 16) }
            setBackgroundColor(0xFFE0E0E0.toInt())
        }
        root.addView(divider)

        // ─ 添加新股票 ─
        val addHint = TextView(this).apply {
            text = "添加股票"
            textSize = 14f
            setTextColor(0xFF666666.toInt())
        }
        root.addView(addHint)

        val addRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 0)
        }

        val inputView = EditText(this).apply {
            hint = "输入 6 位代码，如 600519"
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        addRow.addView(inputView)

        val addBtn = Button(this).apply {
            text = "添加"
            setOnClickListener {
                val raw = inputView.text.toString().trim()
                val normalized = StockConfig.normalizeCode(raw)
                if (normalized == null) {
                    Toast.makeText(this@MainActivity, "无效代码，请输入 6 位数字", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (stocks.contains(normalized)) {
                    Toast.makeText(this@MainActivity, "该股票已在列表中", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                stocks.add(normalized)
                inputView.text.clear()
                refreshList()
            }
        }
        addRow.addView(addBtn)
        root.addView(addRow)

        // ─ 对话框 ─
        AlertDialog.Builder(this)
            .setTitle("管理股票")
            .setView(root)
            .setPositiveButton("保存并应用") { _, _ ->
                StockConfig.saveStocks(this, stocks)
                // 如果服务正在运行，通知它重新加载
                if (StockTickerService.isRunning) {
                    Intent(this, StockTickerService::class.java).also {
                        it.action = StockTickerService.ACTION_RELOAD
                        startService(it)
                    }
                }
                Toast.makeText(this, "股票列表已更新", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("恢复默认") { _, _ ->
                StockConfig.resetToDefault(this)
                if (StockTickerService.isRunning) {
                    Intent(this, StockTickerService::class.java).also {
                        it.action = StockTickerService.ACTION_RELOAD
                        startService(it)
                    }
                }
                Toast.makeText(this, "已恢复默认股票列表", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 管理对话框中的单行：代码 + 删除按钮 */
    private fun createManageRow(code: String, onDelete: () -> Unit): View {
        val market = StockConfig.getMarketDisplay(code)
        val pureCode = StockConfig.getPureCode(code)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 10, 0, 10)
        }
        TextView(this).apply {
            text = "$pureCode（${market}）"
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            row.addView(this)
        }
        Button(this).apply {
            text = "删除"
            textSize = 13f
            setOnClickListener { onDelete() }
            row.addView(this)
        }
        return row
    }

    companion object {
        private const val PERMISSION_CODE = 200
    }
}
