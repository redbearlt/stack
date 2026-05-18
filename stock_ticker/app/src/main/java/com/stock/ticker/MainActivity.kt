package com.stock.ticker

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.stock.ticker.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 股票行情 App 主界面
 *
 * 打开后自动启动后台监控，关闭时自动停止。
 * 行情数据在界面列表和系统通知栏同时显示。
 */
class MainActivity : Activity() {

    private lateinit var binding: ActivityMainBinding
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
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

        binding.settingsBtn.setOnClickListener {
            showSettingsDialog()
        }

        // 自动启动服务，确保状态栏立即显示行情
        if (!StockTickerService.isRunning) {
            requestPermissionAndStart()
        } else {
            updateUiState(true)
            startUiUpdates()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        // 只在非配置变更（如旋转屏幕）时停止服务
        if (!isChangingConfigurations) {
            stopService()
        }
        super.onDestroy()
    }

    // ─── 启动 / 停止 ───────────────────────────────────────

    private fun requestPermissionAndStart() {
        // 先检查通知权限
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
        // 再检查悬浮窗权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请开启悬浮窗权限以在状态栏显示价格", Toast.LENGTH_LONG).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, OVERLAY_PERMISSION_CODE)
                return
            }
        }
        doStartService()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_CODE) {
            if (Settings.canDrawOverlays(this)) {
                doStartService()
            } else {
                // 用户没开权限，仍然启动服务（只是没有悬浮窗）
                doStartService()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "请开启悬浮窗权限以在状态栏显示价格", Toast.LENGTH_LONG).show()
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivityForResult(intent, OVERLAY_PERMISSION_CODE)
                    return
                }
            }
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
        uiUpdateJob = scope.launch {
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
                val row = binding.stockList.getChildAt(i) ?: return@forEachIndexed
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
        val radius = (6 * density).toInt()

        // 外层卡片：白底极细影，左侧红条点睛
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            elevation = 1.5f * density
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * density).toInt()
            }
        }

        // 左侧朱砂红细条
        val accent = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                (2.5f * density).toInt(),
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFFC41E1E.toInt())
        }

        // 内容区
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                (14 * density).toInt(),
                (13 * density).toInt(),
                (14 * density).toInt(),
                (13 * density).toInt()
            )
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        // 股票名
        val nameTv = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            text = price.name
            textSize = 15f
            setTextColor(0xFF222222.toInt())
        }

        // 价格 + 涨幅
        val priceLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val priceTv = TextView(this).apply {
            text = "%.2f".format(price.price)
            textSize = 18f
            typeface = Typeface.MONOSPACE
        }

        val changeTv = TextView(this).apply {
            textSize = 12f
        }

        applyPriceColor(priceTv, changeTv, price)
        priceLayout.addView(priceTv)
        priceLayout.addView(changeTv)

        row.addView(nameTv)
        row.addView(priceLayout)
        card.addView(accent)
        card.addView(row)

        return card
    }

    private fun updateRow(view: View, price: StockPrice) {
        val card = view as? LinearLayout ?: return
        // card: [accent, row]
        if (card.childCount < 2) return
        val row = card.getChildAt(1) as? LinearLayout ?: return
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
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
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
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
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

    // ─── 设置对话框 ──────────────────────────────────

    private fun showSettingsDialog() {
        val density = resources.displayMetrics.density
        val currentColor = StockSettings.getColor(this)
        val currentInterval = StockSettings.getInterval(this)
        val currentOffset = StockSettings.getOffset(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
        }

        // ─ 1. 字体颜色 ─
        val colorLabel = TextView(this).apply {
            text = "顶部字体颜色"
            textSize = 15f
            setTextColor(0xFF333333.toInt())
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        root.addView(colorLabel)

        val colorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, (12 * density).toInt())
        }
        StockSettings.presetColors.forEach { (_, color) ->
            val circle = View(this).apply {
                val size = (32 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    rightMargin = (10 * density).toInt()
                }
                val gd = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    if (color == 0xFFFFFFFF.toInt() || color == 0xFFFFFF00.toInt()) {
                        setStroke((2 * density).toInt(), 0xFFCCCCCC.toInt())
                    }
                    if (color == currentColor) {
                        setStroke((4 * density).toInt(), 0xFF333333.toInt())
                    }
                }
                background = gd
                setOnClickListener {
                    StockSettings.setColor(this@MainActivity, color)
                    notifyServiceReload()
                    // 刷新对话框
                    (it.parent as? ViewGroup)?.let { parent ->
                        // 移除旧的 root，重建对话框
                        (parent.parent as? ViewGroup)?.removeAllViews()
                    }
                    showSettingsDialog()
                }
            }
            colorRow.addView(circle)
        }
        root.addView(colorRow)

        // ─ 2. 刷新间隔 ─
        val intervalLabel = TextView(this).apply {
            text = "刷新间隔（秒）"
            textSize = 15f
            setTextColor(0xFF333333.toInt())
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        root.addView(intervalLabel)

        val intervalRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, (12 * density).toInt())
        }
        StockSettings.presetIntervals.forEach { sec ->
            val btn = Button(this).apply {
                text = "${sec} 秒"
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (6 * density).toInt() }
                setBackgroundColor(if (sec == currentInterval) 0xFFC41E1E.toInt() else 0xFF444444.toInt())
                setTextColor(if (sec == currentInterval) 0xFFFFFFFF.toInt() else 0xFFCCCCCC.toInt())
                gravity = Gravity.CENTER
                setPadding(0, (10 * density).toInt(), 0, (10 * density).toInt())
                setOnClickListener {
                    StockSettings.setInterval(this@MainActivity, sec)
                    notifyServiceReload()
                    (it.parent as? ViewGroup)?.let { p ->
                        (p.parent as? ViewGroup)?.removeAllViews()
                    }
                    showSettingsDialog()
                }
            }
            intervalRow.addView(btn)
        }
        root.addView(intervalRow)

        // ─ 3. 偏移量 ─
        val offsetLabel = TextView(this).apply {
            text = "水平偏移：${currentOffset}%"
            textSize = 15f
            setTextColor(0xFF333333.toInt())
            setPadding(0, 0, 0, (4 * density).toInt())
        }
        root.addView(offsetLabel)

        val seekBar = SeekBar(this).apply {
            max = 50
            progress = currentOffset
            setPadding(0, 0, 0, (12 * density).toInt())
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    offsetLabel.text = "水平偏移：${p}%"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    StockSettings.setOffset(this@MainActivity, sb?.progress ?: 32)
                    notifyServiceReload()
                }
            })
        }
        root.addView(seekBar)

        // ─ 对话框 ─
        AlertDialog.Builder(this)
            .setTitle("显示设置")
            .setView(root)
            .setPositiveButton("关闭") { _, _ -> }
            .show()
    }

    private fun notifyServiceReload() {
        if (StockTickerService.isRunning) {
            Intent(this, StockTickerService::class.java).also {
                it.action = StockTickerService.ACTION_RELOAD
                startService(it)
            }
        }
    }

    companion object {
        private const val PERMISSION_CODE = 200
        private const val OVERLAY_PERMISSION_CODE = 201
    }
}
