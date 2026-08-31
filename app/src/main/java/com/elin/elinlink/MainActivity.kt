package com.elin.elinlink

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.RadioButton
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
        binding.tvEmptyDash.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        for (cfg in list) {
            val card = layoutInflater.inflate(R.layout.item_gauge_card, binding.dashboardContainer, false)
            card.findViewById<TextView>(R.id.tvGaugeTitle).text =
                if (cfg.type == GaugeType.BAR) "Bar \u2022 ${cfg.title}" else "Meter \u2022 ${cfg.title}"
            card.findViewById<Button>(R.id.btnEditGauge).setOnClickListener { showGaugeConfig(cfg.type, cfg) }
            card.findViewById<Button>(R.id.btnDeleteGauge).setOnClickListener { vm.removeGauge(cfg.id) }
            val holder = card.findViewById<FrameLayout>(R.id.gaugeHolder)
            val gv: View = if (cfg.type == GaugeType.BAR)
                BarGaugeView(this).apply { configure(cfg) }
            else
                MeterGaugeView(this).apply { configure(cfg) }
            val hPx = (cfg.heightDp * resources.displayMetrics.density).toInt().coerceAtLeast(1)
            holder.addView(gv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, hPx))
            gaugeViews[cfg.id] = gv
            binding.dashboardContainer.addView(card)
        }
        updateGaugeValues(vm.frame.value)
    }

    private fun updateGaugeValues(frame: ByteArray?) {
        if (frame == null) return
        for (cfg in vm.gauges.value) {
            val gv = gaugeViews[cfg.id] ?: continue
            val v = GaugeParser.valueFor(frame, cfg)
            when (gv) {
                is BarGaugeView -> gv.setValue(v)
                is MeterGaugeView -> gv.setValue(v)
            }
        }
    }

    /** Show the add/edit dialog. When [existing] is non-null the dialog edits it in place. */
    private fun showGaugeConfig(type: GaugeType, existing: GaugeConfig?) {
        val view = layoutInflater.inflate(R.layout.dialog_gauge_config, null)
        val etTitle = view.findViewById<EditText>(R.id.etTitle)
        val etUnit = view.findViewById<EditText>(R.id.etUnit)
        val etMultiplier = view.findViewById<EditText>(R.id.etMultiplier)
        val etBitStart = view.findViewById<EditText>(R.id.etBitStart)
        val etBitEnd = view.findViewById<EditText>(R.id.etBitEnd)
        val rbHorizontal = view.findViewById<RadioButton>(R.id.rbHorizontal)
        val rbVertical = view.findViewById<RadioButton>(R.id.rbVertical)
        val etHeight = view.findViewById<EditText>(R.id.etHeight)
        val etSteps = view.findViewById<EditText>(R.id.etSteps)

        if (existing != null) {
            etTitle.setText(existing.title)
            etUnit.setText(existing.unit)
            etMultiplier.setText(existing.multiplier.toString())
            etBitStart.setText(existing.bitStart.toString())
            etBitEnd.setText(existing.bitEnd.toString())
            if (existing.orientation == GaugeOrientation.VERTICAL) rbVertical.isChecked = true
            else rbHorizontal.isChecked = true
            etHeight.setText(existing.heightDp.toString())
            etSteps.setText(existing.steps.toString())
        } else {
            etMultiplier.setText("1")
            etBitStart.setText("0")
            etBitEnd.setText("7")
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
                            steps = steps
                        )
                    )
                } else {
                    vm.addGauge(
                        GaugeConfig.new(
                            type, title, unit, multiplier,
                            bitStart, bitEnd, orientation, heightDp, steps
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
