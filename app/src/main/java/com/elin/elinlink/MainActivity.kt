package com.elin.elinlink

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
        binding.etCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendCurrentText(); true } else false
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
                            binding.logScroll.fullScroll(android.view.View.FOCUS_DOWN)
                        }
                    }
                }
                launch { vm.connectedName.collect { renderConnected(it) } }
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
                        binding.btnDisconnect.isEnabled =
                            connected || st == ConnState.CONNECTING
                        binding.progress.visibility =
                            if (st == ConnState.SCANNING || st == ConnState.CONNECTING)
                                android.view.View.VISIBLE else android.view.View.GONE
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

    private fun startScanFlow() = vm.startScan()

    override fun onStop() {
        super.onStop()
        vm.stopScan()
    }
}
