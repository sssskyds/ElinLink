package com.elin.elinlink

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Space
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.elin.elinlink.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val vm: BleViewModel by viewModels()
    private lateinit var adapter: DeviceAdapter

    private val gaugeViews = LinkedHashMap<String, View>()
    private val gaugeValueLabels = LinkedHashMap<String, TextView>()

    private companion object {
        const val SCREEN_CONNECT = 0
        const val SCREEN_DASHBOARD = 1
        const val SCREEN_TERMINAL = 2
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) startScanFlow()
        else Toast.makeText(this, "Permissions required for BLE", Toast.LENGTH_LONG).show()
    }

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { if (vm.isBluetoothOn()) ensurePermissionsAndScan() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = DeviceAdapter { device -> vm.connect(device) }
        binding.rvDevices.layoutManager = LinearLayoutManager(this)
        binding.rvDevices.adapter = adapter

        binding.btnScan.setOnClickListener { ensureBluetoothThenScan() }
        binding.btnDisconnect.setOnClickListener { vm.disconnect() }
        binding.btnSend.setOnClickListener { sendCurrentText() }
        binding.btnClear.setOnClickListener { vm.clearLog() }
        binding.btnTerminal.setOnClickListener {
            binding.screenFlipper.displayedChild = SCREEN_TERMINAL
        }
        binding.btnBackToDash.setOnClickListener {
            binding.screenFlipper.displayedChild = SCREEN_DASHBOARD
        }
        binding.btnAddBar.setOnClickListener { showGaugeConfig(GaugeType.BAR, null) }
        binding.btnAddMeter.setOnClickListener { showGaugeConfig(GaugeType.METER, null) }
        binding.btnToggleControls.setOnClickListener {
            val visible = binding.controlsBox.visibility == View.VISIBLE
            binding.controlsBox.visibility = if (visible) View.GONE else View.VISIBLE
            binding.btnToggleControls.text = if (visible) "+" else "\u2212"
        }
        setThemeIcon()
        binding.btnTheme.setOnClickListener {
            val dark = !ThemeManager.isDark(this)
            ThemeManager.setDark(this, dark)
            // Applying the night mode recreates this activity so the new theme takes effect.
            ThemeManager.apply(dark)
        }
        binding.btnHelp.setOnClickListener { showHelpDialog() }
        binding.etCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendCurrentText(); true } else false
        }

        onBackPressedDispatcher.addCallback(this) {
            when (binding.screenFlipper.displayedChild) {
                SCREEN_TERMINAL -> binding.screenFlipper.displayedChild = SCREEN_DASHBOARD
                SCREEN_DASHBOARD -> vm.disconnect()
                else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        }

        observeState()
    }

    /** Show a moon in dark mode and a sun in light mode. */
    private fun setThemeIcon() {
        binding.btnTheme.setImageResource(
            if (ThemeManager.isDark(this)) R.drawable.ic_theme_moon else R.drawable.ic_theme_sun
        )
    }

    private fun showHelpDialog() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) { "" }

        val guide = """
            Elin-Link is a Bluetooth LE serial terminal and live dashboard for devices that use the Nordic UART Service (NUS).

            CONNECT
            \u2022 Tap Scan, then pick your device from the list.
            \u2022 The status line shows Idle / Scanning / Connecting / Connected.

            DASHBOARD
            \u2022 Tap \u201c+ Bar\u201d or \u201c+ Meter\u201d to add a gauge.
            \u2022 Each gauge has a title, unit, multiplier, bit range, colour palette, orientation, height and steps.
            \u2022 Use the pencil to edit a gauge or the \u2715 to remove it.
            \u2022 Incoming hex frames (comma or space separated) drive the gauges live.
            \u2022 Use (\u2212) / (+) in the header to hide or show the controls.

            TERMINAL
            \u2022 Tap Terminal to see the raw serial log and send commands.

            THEME
            \u2022 Tap the sun / moon icon to switch between light and dark (default dark).
        """.trimIndent()

        val message = guide + "\n\nVersion " + version + "\nDeveloped by Ajeet"

        AlertDialog.Builder(this)
            .setTitle("Usage guide")
            .setMessage(message)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun sendCurrentText() {
        val text = binding.etCommand.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        vm.send(text)
        binding.etCommand.setText("")
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.devices.collect { adapter.submitList(it) } }
                launch {
                    vm.log.collect {
                        binding.tvLog.text = it
                        binding.logScroll.post {
                            binding.logScroll.fullScroll(View.FOCUS_DOWN)
                        }
                    }
                }
                launch { vm.connectedName.collect { renderConnected(it) } }
                launch { vm.gauges.collect { renderGauges(it) } }
                launch { vm.frame.collect { updateGaugeValues(it) } }
                launch {
                    vm.state.collect { st ->
                        binding.tvStatus.text = when (st) {
                            ConnState.IDLE -> getString(R.string.status_idle)
                            ConnState.SCANNING -> getString(R.string.status_scanning)
                            ConnState.CONNECTING -> getString(R.string.status_connecting)
                            ConnState.CONNECTED -> getString(R.string.status_connected)
                            ConnState.DISCONNECTED -> getString(R.string.status_disconnected)
                        }
                        val connected = st == ConnState.CONNECTED
                        binding.btnSend.isEnabled = connected
                        binding.etCommand.isEnabled = connected
                        binding.btnDisconnect.isEnabled = connected
                        binding.progress.visibility =
                            if (st == ConnState.SCANNING || st == ConnState.CONNECTING)
                                View.VISIBLE else View.GONE

                        if (connected) {
                            if (binding.screenFlipper.displayedChild == SCREEN_CONNECT) {
                                binding.screenFlipper.displayedChild = SCREEN_DASHBOARD
                            }
                        } else {
                            binding.screenFlipper.displayedChild = SCREEN_CONNECT
                        }
                    }
                }
                launch {
                    vm.toast.collect { msg ->
                        if (msg != null) {
                            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                            vm.consumeToast()
                        }
                    }
                }
            }
        }
    }

    private fun renderConnected(name: String?) {
        binding.tvConnected.text =
            if (name != null) getString(R.string.connected_to, name) else ""
    }

    // ---------- Dashboard / gauges ----------
    private fun renderGauges(list: List<GaugeConfig>) {
        binding.dashboardContainer.removeAllViews()
        gaugeViews.clear()
        gaugeValueLabels.clear()
        binding.tvEmptyDash.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE

        val cols = (resources.configuration.screenWidthDp / 200).coerceIn(1, 4)
        val margin = (4 * resources.displayMetrics.density).toInt()
        var row: LinearLayout? = null

        list.forEachIndexed { index, cfg ->
            if (index % cols == 0) {
                val newRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                binding.dashboardContainer.addView(
                    newRow,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
                row = newRow
            }
            val currentRow = row ?: return@forEachIndexed
            val card = layoutInflater.inflate(R.layout.item_gauge_card, currentRow, false)
            card.findViewById<TextView>(R.id.tvGaugeTitle).text = cfg.title
            card.findViewById<View>(R.id.btnEditGauge).setOnClickListener { showGaugeConfig(cfg.type, cfg) }
            card.findViewById<View>(R.id.btnDeleteGauge).setOnClickListener { vm.removeGauge(cfg.id) }
            val label = card.findViewById<TextView>(R.id.tvGaugeValue)
            label.text = if (cfg.unit.isNotEmpty()) "-- ${cfg.unit}" else "--"
            val holder = card.findViewById<FrameLayout>(R.id.gaugeHolder)
            val gv: View = if (cfg.type == GaugeType.BAR)
                BarGaugeView(this).apply { configure(cfg) }
            else
                MeterGaugeView(this).apply { configure(cfg) }
            val hPx = (cfg.heightDp * resources.displayMetrics.density).toInt().coerceAtLeast(1)
            holder.addView(gv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, hPx))
            gaugeViews[cfg.id] = gv
            gaugeValueLabels[cfg.id] = label

            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(margin, margin, margin, margin)
            currentRow.addView(card, lp)
        }

        // Pad the final row so cards keep an even width.
        val remainder = list.size % cols
        val lastRow = row
        if (remainder != 0 && lastRow != null) {
            repeat(cols - remainder) {
                lastRow.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
            }
        }

        updateGaugeValues(vm.frame.value)
    }

    private fun updateGaugeValues(frame: ByteArray?) {
        if (frame == null) return
        for (cfg in vm.gauges.value) {
            val v = GaugeParser.valueFor(frame, cfg)
            when (val gv = gaugeViews[cfg.id]) {
                is BarGaugeView -> gv.setValue(v)
                is MeterGaugeView -> gv.setValue(v)
                else -> {}
            }
            gaugeValueLabels[cfg.id]?.text =
                if (cfg.unit.isNotEmpty()) "${formatValue(v)} ${cfg.unit}" else formatValue(v)
        }
    }

    private fun formatValue(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString()
        else String.format(java.util.Locale.US, "%.2f", v)

    /** Show the add/edit dialog. When [existing] is non-null the dialog edits it in place. */
    private fun showGaugeConfig(type: GaugeType, existing: GaugeConfig?) {
        val view = layoutInflater.inflate(R.layout.dialog_gauge_config, null)
        val etTitle = view.findViewById<EditText>(R.id.etTitle)
        val etUnit = view.findViewById<EditText>(R.id.etUnit)
        val etMultiplier = view.findViewById<EditText>(R.id.etMultiplier)
        val etBitStart = view.findViewById<EditText>(R.id.etBitStart)
        val etBitEnd = view.findViewById<EditText>(R.id.etBitEnd)
        val spPalette = view.findViewById<Spinner>(R.id.spPalette)
        val rbHorizontal = view.findViewById<RadioButton>(R.id.rbHorizontal)
        val rbVertical = view.findViewById<RadioButton>(R.id.rbVertical)
        val etHeight = view.findViewById<EditText>(R.id.etHeight)
        val etSteps = view.findViewById<EditText>(R.id.etSteps)

        val palettes = GaugePalette.values()
        spPalette.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            palettes.map { it.display }
        )

        if (existing != null) {
            etTitle.setText(existing.title)
            etUnit.setText(existing.unit)
            etMultiplier.setText(existing.multiplier.toString())
            etBitStart.setText(existing.bitStart.toString())
            etBitEnd.setText(existing.bitEnd.toString())
            spPalette.setSelection(palettes.indexOf(existing.palette).coerceAtLeast(0))
            if (existing.orientation == GaugeOrientation.VERTICAL) rbVertical.isChecked = true
            else rbHorizontal.isChecked = true
            etHeight.setText(existing.heightDp.toString())
            etSteps.setText(existing.steps.toString())
        } else {
            etMultiplier.setText("1")
            etBitStart.setText("0")
            etBitEnd.setText("7")
            spPalette.setSelection(0)
            etHeight.setText(if (type == GaugeType.BAR) "90" else "170")
            etSteps.setText("10")
        }

        val editing = existing != null
        val dialogTitle = when {
            editing && type == GaugeType.BAR -> "Edit Bar"
            editing -> "Edit Meter"
            type == GaugeType.BAR -> "Add Bar"
            else -> "Add Meter"
        }

        AlertDialog.Builder(this)
            .setTitle(dialogTitle)
            .setView(view)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(if (editing) "Save" else "Add") { _, _ ->
                val orientation =
                    if (rbVertical.isChecked) GaugeOrientation.VERTICAL else GaugeOrientation.HORIZONTAL
                val title = etTitle.text?.toString()?.trim().orEmpty()
                    .ifEmpty { if (type == GaugeType.BAR) "Bar" else "Meter" }
                val unit = etUnit.text?.toString()?.trim().orEmpty()
                val multiplier = etMultiplier.text?.toString()?.toDoubleOrNull() ?: 1.0
                val bitStart = etBitStart.text?.toString()?.toIntOrNull() ?: 0
                val bitEnd = etBitEnd.text?.toString()?.toIntOrNull() ?: 7
                val heightDp = etHeight.text?.toString()?.toIntOrNull() ?: 120
                val steps = etSteps.text?.toString()?.toIntOrNull() ?: 10
                val palette = palettes.getOrElse(spPalette.selectedItemPosition) { GaugePalette.BLUE_RED }

                if (existing != null) {
                    vm.updateGauge(
                        existing.copy(
                            title = title,
                            unit = unit,
                            multiplier = multiplier,
                            bitStart = bitStart,
                            bitEnd = bitEnd,
                            orientation = orientation,
                            heightDp = heightDp,
                            steps = steps,
                            palette = palette
                        )
                    )
                } else {
                    vm.addGauge(
                        GaugeConfig.new(
                            type, title, unit, multiplier,
                            bitStart, bitEnd, orientation, heightDp, steps, palette
                        )
                    )
                }
            }
            .show()
    }

    // ---------- BLE flow ----------
    private fun ensureBluetoothThenScan() {
        if (!vm.isBluetoothOn()) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else ensurePermissionsAndScan()
    }

    private fun ensurePermissionsAndScan() {
        val needed = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) startScanFlow() else permLauncher.launch(needed.toTypedArray())
    }

    private fun requiredPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun startScanFlow() {
        binding.screenFlipper.displayedChild = SCREEN_CONNECT
        vm.startScan()
    }

    override fun onStop() {
        super.onStop()
        vm.stopScan()
    }
}
