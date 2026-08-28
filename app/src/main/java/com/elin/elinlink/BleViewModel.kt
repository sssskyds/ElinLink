package com.elin.elinlink

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class ConnState { IDLE, SCANNING, CONNECTING, CONNECTED, DISCONNECTED }

class BleViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        // Nordic UART Service (NUS)
        val NUS_SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // write (phone -> device)
        val NUS_TX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // notify (device -> phone)
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val SCAN_PERIOD_MS = 12_000L
    }

    private val appCtx get() = getApplication<Application>()
    private val handler = Handler(Looper.getMainLooper())

    private val btManager by lazy {
        appCtx.getSystemService(BluetoothManager::class.java)
    }
    private val btAdapter: BluetoothAdapter? get() = btManager?.adapter

    // ---- Observable state ----
    private val _state = MutableStateFlow(ConnState.IDLE)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    private val _devices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val devices: StateFlow<List<ScannedDevice>> = _devices.asStateFlow()

    private val _log = MutableStateFlow("")
    val log: StateFlow<String> = _log.asStateFlow()

    private val _connectedName = MutableStateFlow<String?>(null)
    val connectedName: StateFlow<String?> = _connectedName.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var scanning = false

    fun isBluetoothOn(): Boolean = btAdapter?.isEnabled == true

    private fun hasScanPerm(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            ContextCompat.checkSelfPermission(appCtx, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        else
            ContextCompat.checkSelfPermission(appCtx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private fun hasConnectPerm(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            ContextCompat.checkSelfPermission(appCtx, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        else true

    // ---------- Scanning ----------
    @SuppressLint("MissingPermission")
    fun startScan() {
        if (scanning) return
        if (!hasScanPerm()) { _toast.value = "Scan permission not granted"; return }
        val scanner = btAdapter?.bluetoothLeScanner
        if (scanner == null) { _toast.value = "Bluetooth unavailable"; return }

        _devices.value = emptyList()
        scanning = true
        _state.value = ConnState.SCANNING

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, scanCallback)
        handler.postDelayed({ stopScan() }, SCAN_PERIOD_MS)
        appendLog(">> Scanning...")
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!scanning) return
        scanning = false
        try { btAdapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
        if (_state.value == ConnState.SCANNING) _state.value = ConnState.IDLE
        appendLog(">> Scan stopped")
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device ?: return
            val name = if (hasConnectPerm()) (dev.name ?: "Unknown device") else "Unknown device"
            val existing = _devices.value.toMutableList()
            val idx = existing.indexOfFirst { it.address == dev.address }
            if (idx >= 0) {
                existing[idx] = existing[idx].copy(rssi = result.rssi, name = name)
            } else {
                existing.add(ScannedDevice(dev, name, dev.address, result.rssi))
            }
            _devices.value = existing.sortedByDescending { it.rssi }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            _state.value = ConnState.IDLE
            _toast.value = "Scan failed (code $errorCode)"
        }
    }

    // ---------- Connecting ----------
    @SuppressLint("MissingPermission")
    fun connect(target: ScannedDevice) {
        if (!hasConnectPerm()) { _toast.value = "Connect permission not granted"; return }
        stopScan()
        disconnect()
        _state.value = ConnState.CONNECTING
        appendLog(">> Connecting to ${target.name} (${target.address})")
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            target.device.connectGatt(appCtx, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            target.device.connectGatt(appCtx, false, gattCallback)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.let {
            try { it.disconnect(); it.close() } catch (_: Exception) {}
        }
        gatt = null
        writeChar = null
        if (_state.value == ConnState.CONNECTED || _state.value == ConnState.CONNECTING) {
            _state.value = ConnState.DISCONNECTED
        }
        _connectedName.value = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    appendLog(">> Connected. Discovering services...")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    appendLog(">> Disconnected (status $status)")
                    _state.value = ConnState.DISCONNECTED
                    _connectedName.value = null
                    writeChar = null
                    try { g.close() } catch (_: Exception) {}
                    if (gatt == g) gatt = null
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                appendLog(">> Service discovery failed ($status)")
                return
            }
            val service = g.getService(NUS_SERVICE)
            if (service == null) {
                appendLog(">> Nordic UART Service not found on this device")
                _toast.value = "NUS not found"
                return
            }
            writeChar = service.getCharacteristic(NUS_RX)
            val notifyChar = service.getCharacteristic(NUS_TX)

            if (notifyChar != null) {
                g.setCharacteristicNotification(notifyChar, true)
                val cccd = notifyChar.getDescriptor(CCCD)
                if (cccd != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(cccd)
                    }
                }
            }
            _state.value = ConnState.CONNECTED
            _connectedName.value = _devices.value.firstOrNull { it.address == g.device.address }?.name
                ?: (if (hasConnectPerm()) g.device.name else null) ?: g.device.address
            appendLog(">> Ready. Notifications enabled.")
        }

        // Android 13+
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == NUS_TX) appendLog("RX: " + value.toString(Charsets.UTF_8))
        }

        // Android <= 12
        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
                characteristic.uuid == NUS_TX
            ) {
                val data = characteristic.value ?: return
                appendLog("RX: " + data.toString(Charsets.UTF_8))
            }
        }
    }

    // ---------- Writing ----------
    @SuppressLint("MissingPermission")
    fun send(text: String) {
        val g = gatt
        val ch = writeChar
        if (g == null || ch == null || _state.value != ConnState.CONNECTED) {
            _toast.value = "Not connected"; return
        }
        val payload = (text + "\n").toByteArray(Charsets.UTF_8)
        val writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, payload, writeType) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = writeType
                ch.value = payload
                g.writeCharacteristic(ch)
            }
        }
        if (ok) appendLog("TX: $text") else appendLog(">> Write failed")
    }

    fun clearLog() { _log.value = "" }
    fun consumeToast() { _toast.value = null }

    private fun appendLog(line: String) {
        val prefix = if (_log.value.isEmpty()) "" else "\n"
        _log.value = _log.value + prefix + line
    }

    override fun onCleared() {
        super.onCleared()
        stopScan()
        disconnect()
    }
}
