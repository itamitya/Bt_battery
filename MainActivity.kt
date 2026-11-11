package com.example.btbattery

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import java.util.UUID

@SuppressLint("MissingPermission")
class MainActivity : Activity() {

    private val battSvc = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    private val battChr = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    private val actionBatt = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
    private val extraBatt = "android.bluetooth.device.extra.BATTERY_LEVEL"

    private val ui = Handler(Looper.getMainLooper())
    private var adapter: BluetoothAdapter? = null
    private lateinit var box: LinearLayout

    // адрес -> (имя, заряд %; -1 = неизвестно)
    private val levels = linkedMapOf<String, Pair<String, Int>>()
    private var pods = ""
    private var podsRssi = -127

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adapter = getSystemService(BluetoothManager::class.java)?.adapter

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 80, 40, 40) }
        val btn = Button(this).apply { text = "Обновить"; setOnClickListener { refresh() } }
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(btn)
        root.addView(ScrollView(this).apply { addView(box) })
        setContentView(root)

        val f = IntentFilter(actionBatt)
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val d = if (Build.VERSION.SDK_INT >= 33)
                    i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                val l = i.getIntExtra(extraBatt, -1)
                if (d != null && l >= 0) { levels[d.address] = (d.name ?: d.address) to l; render() }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(r, f)
    }

    private fun needed() =
        if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    override fun onResume() {
        super.onResume()
        if (needed().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) refresh()
        else requestPermissions(needed(), 1)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, g: IntArray) {
        if (g.all { it == PackageManager.PERMISSION_GRANTED }) refresh()
    }

    private fun refresh() {
        val a = adapter ?: return
        if (!a.isEnabled) { Toast.makeText(this, "Включите Bluetooth", Toast.LENGTH_SHORT).show(); return }
        levels.clear()
        for (profile in listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
            a.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                    proxy.connectedDevices.forEach { addDevice(it) }
                    a.closeProfileProxy(p, proxy)
                }
                override fun onServiceDisconnected(p: Int) {}
            }, profile)
        }
        getSystemService(BluetoothManager::class.java)
            .getConnectedDevices(BluetoothProfile.GATT).forEach { addDevice(it) }
        scanAirPods()
        render()
    }

    // Скрытый API: работает на многих телефонах, на некоторых вернёт -1
    private fun hiddenLevel(d: BluetoothDevice): Int = try {
        d.javaClass.getMethod("getBatteryLevel").invoke(d) as Int
    } catch (e: Throwable) { -1 }

    private fun addDevice(d: BluetoothDevice) {
        val old = levels[d.address]?.second ?: -1
        val l = if (old >= 0) old else hiddenLevel(d)
        levels[d.address] = (d.name ?: d.address) to l
        if (l < 0) readGatt(d)
        runOnUiThread { render() }
    }

    // Запасной вариант: стандартный BLE Battery Service (часы, браслеты и т.д.)
    private fun readGatt(d: BluetoothDevice) {
        d.connectGatt(this, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, s: Int, n: Int) {
                if (n == BluetoothProfile.STATE_CONNECTED) g.discoverServices() else g.close()
            }
            override fun onServicesDiscovered(g: BluetoothGatt, s: Int) {
                val c = g.getService(battSvc)?.getCharacteristic(battChr)
                if (c == null || !g.readCharacteristic(c)) g.close()
            }
            @Deprecated("old API")
            override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, s: Int) {
                gotLevel(g, d, c.value)
            }
            override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray, s: Int) {
                gotLevel(g, d, v)
            }
        }, BluetoothDevice.TRANSPORT_LE)
    }

    private fun gotLevel(g: BluetoothGatt, d: BluetoothDevice, v: ByteArray?) {
        if (v != null && v.isNotEmpty()) {
            levels[d.address] = (d.name ?: d.address) to (v[0].toInt() and 0xFF)
            runOnUiThread { render() }
        }
        g.close()
    }

    // AirPods и клоны: левый/правый/кейс из BLE-рекламы Apple (company id 0x004C)
    private fun scanAirPods() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        podsRssi = -127
        val filter = ScanFilter.Builder()
            .setManufacturerData(0x004C, byteArrayOf(0x07, 0x19), byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
            .build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val cb = object : ScanCallback() {
            override fun onScanResult(t: Int, r: ScanResult) {
                val d = r.scanRecord?.getManufacturerSpecificData(0x004C) ?: return
                if (d.size != 27 || r.rssi < -70 || r.rssi < podsRssi) return
                podsRssi = r.rssi
                fun hi(i: Int) = (d[i].toInt() shr 4) and 0xF
                fun lo(i: Int) = d[i].toInt() and 0xF
                val flip = (hi(5) and 2) == 0
                val left = if (flip) hi(6) else lo(6)
                val right = if (flip) lo(6) else hi(6)
                val case = lo(7)
                val chg = hi(7)
                val cl = (if (flip) chg and 1 else chg and 2) != 0
                val cr = (if (flip) chg and 2 else chg and 1) != 0
                val cc = (chg and 4) != 0
                fun p(n: Int, c: Boolean) = (if (n <= 10) "${n * 10}%" else "н/д") + if (c) " ⚡" else ""
                pods = "Левый: ${p(left, cl)}\nПравый: ${p(right, cr)}\nКейс: ${p(case, cc)}"
                runOnUiThread { render() }
            }
        }
        scanner.startScan(listOf(filter), settings, cb)
        ui.postDelayed({ try { scanner.stopScan(cb) } catch (_: Exception) {} }, 6000)
    }

    private fun render() {
        box.removeAllViews()
        fun add(s: String) = box.addView(TextView(this).apply { text = s; textSize = 20f; setPadding(0, 24, 0, 24) })
        if (pods.isNotEmpty()) add("🎧 AirPods (рядом)\n$pods")
        levels.values.forEach { (n, l) -> add("$n: " + if (l >= 0) "$l%" else "н/д") }
        if (pods.isEmpty() && levels.isEmpty()) add("Нет подключённых устройств")
    }
}
