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
        // Bump this on every change so you can confirm the phone runs the latest build.
        const val BUILD_TAG = "rev6 - RX fix + build stamp (2026-08-31)"

        // Nordic UART Service (NUS)
        val NUS_SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // write (phone -> device)
        val NUS_TX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // notify (device -> phone)
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        // Standard Generic Access service + Device Name characteristic
        val GAP_SERVICE: UUID = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")
        val GAP_DEVICE_NAME: UUID = UUID.fromString("00002A00-0000-1000-8000-00805f9b34fb")
        private const val UNKNOWN = "Unknown device"
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
    private var pendingNotify: BluetoothGattCharacteristic? = null
    private var scanning = false

    init {
        // Printed as the first line of the log so you can confirm which build is running.
        appendLog(">> Elin-Link $BUILD_TAG")
    }

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

    /**
     * Resolve the best available name from a scan result.
     * 1) Name advertised in the scan record (available without BLUETOOTH_CONNECT).
     * 2) BluetoothDevice.name (needs BLUETOOTH_CONNECT on Android 12+, often null while scanning).
     * Returns null when no real name is available so callers can keep a previously resolved one.
     */
    @SuppressLint("MissingPermission")
    private fun resolveName(result: ScanResult, dev: BluetoothDevice): String? {
        val advName = result.scanRecord?.deviceName?.trim()
        if (!advName.isNullOrEmpty()) return advName
        if (hasConnectPerm()) {
            val n = dev.name?.trim()
            if (!n.isNullOrEmpty()) return n
        }
        return null
    }

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

        // Active scan (LOW_LATENCY) so scan responses carrying the device name are received.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
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
            val resolvedName = resolveName(result, dev)
            val existing = _devices.value.toMutableList()
            val idx = existing.indexOfFirst { it.address == dev.address }
            if (idx >= 0) {
                // Keep a name we already resolved if this packet has none.
                val current = existing[idx]
                val keptName = resolvedName ?: current.name
                existing[idx] = current.copy(rssi = result.rssi, name = keptName)
            } else {
                existing.add(ScannedDevice(dev, resolvedName ?: UNKNOWN, dev.address, result.rssi))
            }
            _devices.value = existing.sortedByDescending { it.rssi }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
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
        pendingNotify = null
        if (_state.value == ConnState.CONNECTED || _state.value == ConnState.CONNECTING) {
            _state.value = ConnState.DISCONNECTED
        }
        _connectedName.value = null
    }

    /**
     * Enable notifications/indications on the NUS TX characteristic.
     * Returns true if a CCCD descriptor write was issued (completion arrives in
     * onDescriptorWrite); false if there was nothing to write.
     */
    @SuppressLint("MissingPermission")
    private fun enableNotifications(g: BluetoothGatt): Boolean {
        val notifyChar = pendingNotify ?: return false
        val enabled = g.setCharacteristicNotification(notifyChar, true)
        if (!enabled) appendLog(">> setCharacteristicNotification returned false")

        val props = notifyChar.properties
        val cccdValue = when {
            props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ->
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 ->
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }

        val cccd = notifyChar.getDescriptor(CCCD)
        if (cccd == null) {
            appendLog(">> TX characteristic has no CCCD (0x2902); cannot subscribe")
            return false
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, cccdValue) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                cccd.value = cccdValue
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun readGapName(g: BluetoothGatt) {
        val gapName = g.getService(GAP_SERVICE)?.getCharacteristic(GAP_DEVICE_NAME) ?: return
        g.readCharacteristic(gapName)
    }

    @SuppressLint("MissingPermission")
    private fun initialConnectedName(g: BluetoothGatt): String {
        val cached = _devices.value.firstOrNull { it.address == g.device.address }
            ?.name?.takeIf { it != UNKNOWN && it.isNotBlank() }
        val gattName = if (hasConnectPerm()) g.device.name?.trim()?.takeIf { it.isNotEmpty() } else null
        return cached ?: gattName ?: g.device.address
    }

    private fun applyResolvedName(g: BluetoothGatt, name: String) {
        _connectedName.value = name
        val addr = g.device.address
        val list = _devices.value.toMutableList()
        val idx = list.indexOfFirst { it.address == addr }
        if (idx >= 0) {
            list[idx] = list[idx].copy(name = name)
            _devices.value = list
        }
        appendLog(">> Device name: $name")
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
                    pendingNotify = null
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
            pendingNotify = service.getCharacteristic(NUS_TX)
            _state.value = ConnState.CONNECTED
            _connectedName.value = initialConnectedName(g)

            if (pendingNotify == null) appendLog(">> TX (notify) characteristic not found")

            // Critical path first: subscribe for incoming data. The device-name read
            // happens afterwards (in onDescriptorWrite) so RX never depends on it.
            val issued = enableNotifications(g)
            if (!issued) readGapName(g)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid == CCCD) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    appendLog(">> Ready. Notifications enabled.")
                } else {
                    appendLog(">> Failed to enable notifications (status $status)")
                }
                // Now that the subscribe is done, read the device name.
                readGapName(g)
            }
        }

        // Android 13+
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (characteristic.uuid == GAP_DEVICE_NAME && status == BluetoothGatt.GATT_SUCCESS) {
                val n = value.toString(Charsets.UTF_8).trim()
                if (n.isNotEmpty()) applyResolvedName(g, n)
            }
        }

        // Android <= 12
        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
                characteristic.uuid == GAP_DEVICE_NAME &&
                status == BluetoothGatt.GATT_SUCCESS
            ) {
                val n = characteristic.value?.toString(Charsets.UTF_8)?.trim()
                if (!n.isNullOrEmpty()) applyResolvedName(g, n)
            }
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
        val payload = (text + "\r\n").toByteArray(Charsets.UTF_8)
        val props = ch.properties
        val writeType = if (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
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
