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
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.widget.*
import java.util.UUID

data class Dev(val name: String, val level: Int, val icon: String)
data class Pods(val l: Int, val r: Int, val c: Int, val cl: Boolean, val cr: Boolean, val cc: Boolean)

@SuppressLint("MissingPermission")
class MainActivity : Activity() {

    private val battSvc = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    private val battChr = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    private val actionBatt = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
    private val extraBatt = "android.bluetooth.device.extra.BATTERY_LEVEL"

    private val ui = Handler(Looper.getMainLooper())
    private var adapter: BluetoothAdapter? = null
    private lateinit var list: LinearLayout

    private val levels = linkedMapOf<String, Dev>()
    private val seen = mutableSetOf<String>()
    private val gattBusy = mutableSetOf<String>()
    private var pods: Pods? = null
    private var podsRssi = -127
    private var podsFound = false
    private var scanCb: ScanCallback? = null

    // ---------- палитра (светлая / тёмная тема по системе) ----------
    private val dark get() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val cBg get() = if (dark) 0xFF0B0B0F.toInt() else 0xFFF3F3F7.toInt()
    private val cCard get() = if (dark) 0xFF1A1A21.toInt() else 0xFFFFFFFF.toInt()
    private val cText get() = if (dark) 0xFFF2F2F7.toInt() else 0xFF111114.toInt()
    private val cSub get() = if (dark) 0xFF8E8E99.toInt() else 0xFF7A7A85.toInt()
    private val cTrack get() = if (dark) 0xFF2C2C35.toInt() else 0xFFE8E8EE.toInt()

    private fun levelColor(l: Int) = when {
        l < 0 -> cSub
        l <= 20 -> 0xFFFF453A.toInt()
        l <= 50 -> 0xFFFF9F0A.toInt()
        else -> 0xFF30D158.toInt()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun lp(w: Int, h: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, h, wt)
    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    private val loop = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 30000) }
    }

    // ---------- UI ----------
    private fun tv(t: String, size: Float, color: Int, medium: Boolean = false) = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        includeFontPadding = false
    }

    private fun oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    private fun cardView() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(18))
        background = GradientDrawable().apply { setColor(cCard); cornerRadius = dp(24).toFloat() }
        layoutParams = lp(match, wrap).apply { bottomMargin = dp(12) }
    }

    private inner class Bar(ctx: Context, private val pct: Int, private val col: Int) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val h = height.toFloat()
            val w = width.toFloat()
            p.color = cTrack
            c.drawRoundRect(0f, 0f, w, h, h / 2, h / 2, p)
            if (pct > 0) {
                p.color = col
                c.drawRoundRect(0f, 0f, maxOf(h, w * pct / 100f), h, h / 2, h / 2, p)
            }
        }
    }

    private fun iconBubble(emoji: String) = TextView(this).apply {
        text = emoji
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        gravity = Gravity.CENTER
        background = oval(cTrack)
        layoutParams = lp(dp(46), dp(46))
    }

    private fun deviceCard(d: Dev): View {
        val c = cardView()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(iconBubble(d.icon))
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        names.addView(tv(d.name, 17f, cText, true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        names.addView(tv(if (d.level >= 0) "Подключено" else "Заряд не передаётся", 13f, cSub).apply {
            setPadding(0, dp(4), 0, 0)
        })
        row.addView(names, lp(0, wrap, 1f).apply { marginStart = dp(14); marginEnd = dp(8) })
        row.addView(tv(if (d.level >= 0) "${d.level}%" else "—", 28f, cText, true))
        c.addView(row)
        c.addView(Bar(this, maxOf(d.level, 0), levelColor(d.level)), lp(match, dp(6)).apply { topMargin = dp(16) })
        return c
    }

    private fun podCol(label: String, lvl: Int, charging: Boolean): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv(label.uppercase(), 11f, cSub, true))
        val v = (if (lvl >= 0) "$lvl%" else "—") + if (charging) " ⚡" else ""
        col.addView(tv(v, 22f, cText, true).apply { setPadding(0, dp(6), 0, 0) })
        col.addView(Bar(this, maxOf(lvl, 0), levelColor(lvl)), lp(match, dp(6)).apply { topMargin = dp(10) })
        return col
    }

    private fun podsCard(p: Pods): View {
        val c = cardView()
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(iconBubble("🎧"))
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        names.addView(tv("AirPods", 17f, cText, true))
        names.addView(tv("Рядом с телефоном", 13f, cSub).apply { setPadding(0, dp(4), 0, 0) })
        top.addView(names, lp(wrap, wrap).apply { marginStart = dp(14) })
        c.addView(top)
        val cols = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cols.addView(podCol("Левый", p.l, p.cl), lp(0, wrap, 1f).apply { marginEnd = dp(14) })
        cols.addView(podCol("Правый", p.r, p.cr), lp(0, wrap, 1f).apply { marginEnd = dp(14) })
        cols.addView(podCol("Кейс", p.c, p.cc), lp(0, wrap, 1f))
        c.addView(cols, lp(match, wrap).apply { topMargin = dp(20) })
        return c
    }

    private fun emptyView() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(0, dp(80), 0, 0)
        addView(tv("🎧", 48f, cText).apply { gravity = Gravity.CENTER })
        addView(tv("Нет подключённых устройств", 17f, cText, true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(16), 0, 0)
        })
        addView(tv("Подключите наушники или часы\nи нажмите ↻", 14f, cSub).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0)
        })
    }

    private fun render() {
        list.removeAllViews()
        pods?.let { list.addView(podsCard(it)) }
        levels.values.forEach { list.addView(deviceCard(it)) }
        if (pods == null && levels.isEmpty()) list.addView(emptyView())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adapter = getSystemService(BluetoothManager::class.java)?.adapter
        window.statusBarColor = cBg
        window.navigationBarColor = cBg

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(tv("Батарея", 34f, cText, true))
        titles.addView(tv("Bluetooth-устройства", 14f, cSub).apply { setPadding(0, dp(6), 0, 0) })
        head.addView(titles, lp(0, wrap, 1f))
        head.addView(TextView(this).apply {
            text = "↻"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTextColor(cText)
            gravity = Gravity.CENTER
            background = oval(cCard)
            setOnClickListener { refresh() }
        }, lp(dp(46), dp(46)))
        content.addView(head, lp(match, wrap).apply { bottomMargin = dp(24) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list)

        setContentView(ScrollView(this).apply { setBackgroundColor(cBg); isFillViewport = true; addView(content) })

        if (!dark) {
            if (Build.VERSION.SDK_INT >= 30) {
                val m = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                window.insetsController?.setSystemBarsAppearance(m, m)
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
        }
        render()

        val f = IntentFilter(actionBatt)
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val d = if (Build.VERSION.SDK_INT >= 33)
                    i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                val l = i.getIntExtra(extraBatt, -1)
                if (d != null && l >= 0) {
                    seen.add(d.address)
                    levels[d.address] = Dev(d.name ?: d.address, l, iconFor(d))
                    render()
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(r, f)
    }

    // ---------- разрешения ----------
    private fun needed() =
        if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    override fun onResume() {
        super.onResume()
        if (needed().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            ui.removeCallbacks(loop); loop.run()
        } else requestPermissions(needed(), 1)
    }

    override fun onPause() { super.onPause(); ui.removeCallbacks(loop) }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, g: IntArray) {
        if (g.isNotEmpty() && g.all { it == PackageManager.PERMISSION_GRANTED }) { ui.removeCallbacks(loop); loop.run() }
    }

    // ---------- данные ----------
    private fun iconFor(d: BluetoothDevice) = when (d.bluetoothClass?.majorDeviceClass) {
        BluetoothClass.Device.Major.AUDIO_VIDEO -> "🎧"
        BluetoothClass.Device.Major.WEARABLE -> "⌚"
        BluetoothClass.Device.Major.PHONE -> "📱"
        BluetoothClass.Device.Major.COMPUTER -> "💻"
        BluetoothClass.Device.Major.PERIPHERAL -> "⌨️"
        else -> "🔵"
    }

    private fun refresh() {
        val a = adapter ?: return
        if (!a.isEnabled) { Toast.makeText(this, "Включите Bluetooth", Toast.LENGTH_SHORT).show(); return }
        seen.clear()
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
        ui.postDelayed({ levels.keys.retainAll(seen); render() }, 1500)
    }

    // Скрытый API: работает на многих телефонах, на некоторых вернёт -1
    private fun hiddenLevel(d: BluetoothDevice): Int = try {
        d.javaClass.getMethod("getBatteryLevel").invoke(d) as Int
    } catch (e: Throwable) { -1 }

    private fun addDevice(d: BluetoothDevice) {
        runOnUiThread {
            seen.add(d.address)
            val h = hiddenLevel(d)
            val old = levels[d.address]?.level ?: -1
            val l = if (h >= 0) h else old
            levels[d.address] = Dev(d.name ?: d.address, l, iconFor(d))
            if (l < 0) readGatt(d)
            render()
        }
    }

    // Запасной вариант: стандартный BLE Battery Service
    private fun readGatt(d: BluetoothDevice) {
        if (!gattBusy.add(d.address)) return
        var gatt: BluetoothGatt? = null
        fun done() { gattBusy.remove(d.address); try { gatt?.close() } catch (_: Exception) {} }
        fun handle(v: ByteArray?) {
            if (v != null && v.isNotEmpty()) runOnUiThread {
                levels[d.address] = Dev(d.name ?: d.address, v[0].toInt() and 0xFF, iconFor(d))
                render()
            }
            done()
        }
        gatt = d.connectGatt(this, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, s: Int, n: Int) {
                if (n == BluetoothProfile.STATE_CONNECTED) g.discoverServices() else done()
            }
            override fun onServicesDiscovered(g: BluetoothGatt, s: Int) {
                val c = g.getService(battSvc)?.getCharacteristic(battChr)
                if (c == null || !g.readCharacteristic(c)) done()
            }
            @Deprecated("old API")
            override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, s: Int) {
                handle(c.value)
            }
            override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray, s: Int) {
                handle(v)
            }
        }, BluetoothDevice.TRANSPORT_LE)
        ui.postDelayed({ done() }, 8000)
    }

    // AirPods и клоны: левый/правый/кейс из BLE-рекламы Apple (company id 0x004C)
    private fun scanAirPods() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        scanCb?.let { try { scanner.stopScan(it) } catch (_: Exception) {} }
        podsRssi = -127
        podsFound = false
        val filter = ScanFilter.Builder()
            .setManufacturerData(0x004C, byteArrayOf(0x07, 0x19), byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
            .build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val cb = object : ScanCallback() {
            override fun onScanResult(t: Int, r: ScanResult) {
                val d = r.scanRecord?.getManufacturerSpecificData(0x004C) ?: return
                if (d.size != 27 || r.rssi < -70 || r.rssi < podsRssi) return
                podsRssi = r.rssi
                podsFound = true
                fun hi(i: Int) = (d[i].toInt() shr 4) and 0xF
                fun lo(i: Int) = d[i].toInt() and 0xF
                val flip = (hi(5) and 2) == 0
                val left = if (flip) hi(6) else lo(6)
                val right = if (flip) lo(6) else hi(6)
                val chg = hi(7)
                fun pct(n: Int) = if (n <= 10) n * 10 else -1
                val p = Pods(
                    pct(left), pct(right), pct(lo(7)),
                    (if (flip) chg and 1 else chg and 2) != 0,
                    (if (flip) chg and 2 else chg and 1) != 0,
                    (chg and 4) != 0
                )
                runOnUiThread { pods = p; render() }
            }
        }
        scanCb = cb
        scanner.startScan(listOf(filter), settings, cb)
        ui.postDelayed({
            try { scanner.stopScan(cb) } catch (_: Exception) {}
            if (!podsFound) { pods = null; render() }
        }, 6000)
    }
}
