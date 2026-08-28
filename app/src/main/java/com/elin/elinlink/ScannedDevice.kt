package com.elin.elinlink

import android.bluetooth.BluetoothDevice

data class ScannedDevice(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    var rssi: Int
)
