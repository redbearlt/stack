package com.stock.ticker

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
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
import kotlin.math.abs

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
                stopTickerService()
            } else {
                requestPermissionAndStart()
            }
        }

        binding.manageBtn.setOnClickListener { showManageDialog() }
        binding.settingsBtn.setOnClickListener { showSettingsDialog() }

        if (!StockTickerService.isRunning) {
            requestPermissionAndStart()
        } else {
            updateUiState(true)
            startUiUpdates()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        if (!isChangingConfigurations) {
            stopTickerService()
        }
        super.onDestroy()
    }

    private fun requestPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), PERMISSION_CODE)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, getString(R.string.overlay_permission_message), Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, OVERLAY_PERMISSION_CODE)
            return
        }

        doStartService()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_CODE) {
            doStartService()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_CODE) {
            requestPermissionAndStart()
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

    private fun stopTickerService() {
        uiUpdateJob?.cancel()
        Intent(this, StockTickerService::class.java).also {
            it.action = StockTickerService.ACTION_STOP
            startService(it)
        }
        updateUiState(false)
        clearStockList()
    }

    private fun startUiUpdates() {
        uiUpdateJob?.cancel()
        uiUpdateJob = scope.launch {
            while (isActive) {
                StockTickerService.currentPrices?.let { renderStockList(it) }
                binding.statusText.text = if (StockTickerService.isRunning) {
                    getString(R.string.running_status)
                } else {
                    getString(R.string.stopped_status)
                }
                delay(1000L)
            }
        }
    }

    private fun updateUiState(running: Boolean) {
        binding.toggleButton.text = getString(
            if (running) R.string.action_stop else R.string.action_start
        )
        binding.statusText.text = getString(
            if (running) R.string.running_status else R.string.status_tap_to_start
        )
    }

    private fun renderStockList(prices: List<StockPrice>) {
        if (binding.stockList.childCount == prices.size) {
            prices.forEachIndexed { index, price ->
                val row = binding.stockList.getChildAt(index) ?: return@forEachIndexed
                updateRow(row, price)
            }
            return
        }

        binding.stockList.removeAllViews()
        prices.forEach { binding.stockList.addView(createStockRow(it)) }
    }

    private fun createStockRow(price: StockPrice): View {
        val density = resources.displayMetrics.density

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

        val accent = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                (2.5f * density).toInt(),
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFFC41E1E.toInt())
        }

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
        val row = card.getChildAt(1) as? LinearLayout ?: return
        val priceLayout = row.getChildAt(1) as? LinearLayout ?: return
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
        changeTv.text = "$arrow${"%.2f".format(abs(price.changePercent))}%"
        changeTv.setTextColor(color)
    }

    private fun clearStockList() {
        binding.stockList.removeAllViews()
    }

    private fun showManageDialog() {
        val stocks = StockConfig.getStocks(this).toMutableList()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        val listHint = TextView(this).apply {
            text = getString(R.string.manage_current_list, stocks.size)
            textSize = 14f
            setTextColor(0xFF666666.toInt())
        }
        root.addView(listHint)

        val listLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun refreshList() {
            listHint.text = getString(R.string.manage_current_list, stocks.size)
            listLayout.removeAllViews()
            if (stocks.isEmpty()) {
                val emptyHint = TextView(this).apply {
                    text = getString(R.string.manage_empty)
                    textSize = 14f
                    setPadding(0, 16, 0, 16)
                    setTextColor(0xFF999999.toInt())
                }
                listLayout.addView(emptyHint)
                return
            }

            stocks.forEachIndexed { index, code ->
                val row = createManageRow(code) {
                    stocks.removeAt(index)
                    refreshList()
                }
                listLayout.addView(row)
            }
        }

        refreshList()
        root.addView(listLayout)

        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1
            ).also { it.setMargins(0, 16, 0, 16) }
            setBackgroundColor(0xFFE0E0E0.toInt())
        }
        root.addView(divider)

        val addHint = TextView(this).apply {
            text = getString(R.string.manage_add_stock)
            textSize = 14f
            setTextColor(0xFF666666.toInt())
        }
        root.addView(addHint)

        val addRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 0)
        }

        val inputView = EditText(this).apply {
            hint = getString(R.string.manage_input_hint)
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        addRow.addView(inputView)

        val addBtn = Button(this).apply {
            text = getString(R.string.manage_add)
            setOnClickListener {
                val normalized = StockConfig.normalizeCode(inputView.text.toString())
                if (normalized == null) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.invalid_stock_code),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                if (stocks.contains(normalized)) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.duplicate_stock_code),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                stocks.add(normalized)
                inputView.text.clear()
                refreshList()
            }
        }
        addRow.addView(addBtn)
        root.addView(addRow)

        AlertDialog.Builder(this)
            .setTitle(R.string.manage_dialog_title)
            .setView(root)
            .setPositiveButton(R.string.action_save_apply) { _, _ ->
                StockConfig.saveStocks(this, stocks)
                if (StockTickerService.isRunning) {
                    Intent(this, StockTickerService::class.java).also {
                        it.action = StockTickerService.ACTION_RELOAD
                        startService(it)
                    }
                }
                Toast.makeText(this, getString(R.string.stock_list_updated), Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.action_reset_default) { _, _ ->
                StockConfig.resetToDefault(this)
                if (StockTickerService.isRunning) {
                    Intent(this, StockTickerService::class.java).also {
                        it.action = StockTickerService.ACTION_RELOAD
                        startService(it)
                    }
                }
                Toast.makeText(this, getString(R.string.stock_list_reset), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun createManageRow(code: String, onDelete: () -> Unit): View {
        val market = StockConfig.getMarketDisplay(code)
        val pureCode = StockConfig.getPureCode(code)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 10, 0, 10)
        }

        TextView(this).apply {
            text = getString(R.string.manage_row_format, pureCode, market)
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
            row.addView(this)
        }

        Button(this).apply {
            text = getString(R.string.action_delete)
            textSize = 13f
            setOnClickListener { onDelete() }
            row.addView(this)
        }
        return row
    }

    private fun showSettingsDialog() {
        val density = resources.displayMetrics.density
        val currentColor = StockSettings.getColor(this)
        val currentInterval = StockSettings.getInterval(this)
        val currentOffset = StockSettings.getOffset(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (16 * density).toInt(),
                (16 * density).toInt(),
                (16 * density).toInt(),
                (16 * density).toInt()
            )
        }

        val colorLabel = TextView(this).apply {
            text = getString(R.string.settings_color_label)
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
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    if (color == 0xFFFFFFFF.toInt() || color == 0xFFFFFF00.toInt()) {
                        setStroke((2 * density).toInt(), 0xFFCCCCCC.toInt())
                    }
                    if (color == currentColor) {
                        setStroke((4 * density).toInt(), 0xFF333333.toInt())
                    }
                }
                setOnClickListener {
                    StockSettings.setColor(this@MainActivity, color)
                    notifyServiceReload()
                    showSettingsDialog()
                }
            }
            colorRow.addView(circle)
        }
        root.addView(colorRow)

        val intervalLabel = TextView(this).apply {
            text = getString(R.string.settings_interval_label)
            textSize = 15f
            setTextColor(0xFF333333.toInt())
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        root.addView(intervalLabel)

        val intervalRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, (12 * density).toInt())
        }
        StockSettings.presetIntervals.forEach { seconds ->
            val btn = Button(this).apply {
                text = getString(R.string.settings_interval_option, seconds)
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (6 * density).toInt()
                }
                setBackgroundColor(
                    if (seconds == currentInterval) 0xFFC41E1E.toInt() else 0xFF444444.toInt()
                )
                setTextColor(
                    if (seconds == currentInterval) 0xFFFFFFFF.toInt() else 0xFFCCCCCC.toInt()
                )
                gravity = Gravity.CENTER
                setPadding(0, (10 * density).toInt(), 0, (10 * density).toInt())
                setOnClickListener {
                    StockSettings.setInterval(this@MainActivity, seconds)
                    notifyServiceReload()
                    showSettingsDialog()
                }
            }
            intervalRow.addView(btn)
        }
        root.addView(intervalRow)

        val offsetLabel = TextView(this).apply {
            text = getString(R.string.settings_offset_label, currentOffset)
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
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    offsetLabel.text = getString(R.string.settings_offset_label, progress)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    StockSettings.setOffset(this@MainActivity, seekBar?.progress ?: 32)
                    notifyServiceReload()
                }
            })
        }
        root.addView(seekBar)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_dialog_title)
            .setView(root)
            .setPositiveButton(R.string.settings_close, null)
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
