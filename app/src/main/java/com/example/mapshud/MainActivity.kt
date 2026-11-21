package com.example.mapshud

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.math.*

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity(), LocationListener {

    private val SVC = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    private val CHR = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")

    private var gatt: BluetoothGatt? = null
    private var chr: BluetoothGattCharacteristic? = null

    private var route: List<DoubleArray> = emptyList()   // [lat, lon]
    private var dest: DoubleArray? = null
    private var fetching = false
    private var lastFetch = 0L

    private lateinit var status: TextView
    private lateinit var destInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 80, 40, 40)
        }
        status = TextView(this).apply { text = "Belum terhubung"; textSize = 16f }
        destInput = EditText(this).apply { hint = "Tujuan: lat,lon  (contoh -6.2088,106.8456)" }
        val btnConnect = Button(this).apply { text = "1. Hubungkan ke MapsHUD" }
        val btnStart = Button(this).apply { text = "2. Mulai navigasi" }
        val btnStop = Button(this).apply { text = "Stop" }
        listOf(status, destInput, btnConnect, btnStart, btnStop).forEach { root.addView(it) }
        setContentView(root)

        requestPerms()

        btnConnect.setOnClickListener { scanAndConnect() }
        btnStart.setOnClickListener { startNavigation() }
        handleIntent(intent)   // kalau app dibuka lewat Share
        btnStop.setOnClickListener {
            (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(this)
            dest = null; route = emptyList()
            setStatus("Dihentikan")
        }
    }

    // ---------- permission ----------
    private fun requestPerms() {
        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            p += Manifest.permission.BLUETOOTH_SCAN
            p += Manifest.permission.BLUETOOTH_CONNECT
        }
        val need = p.filter { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) ActivityCompat.requestPermissions(this, need.toTypedArray(), 1)
    }

    // ---------- BLE ----------
    private fun scanAndConnect() {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val scanner = adapter.bluetoothLeScanner
        setStatus("Mencari MapsHUD...")
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SVC)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) {
                scanner.stopScan(this)
                gatt = result.device.connectGatt(this@MainActivity, false, gattCb, BluetoothDevice.TRANSPORT_LE)
            }
        })
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) g.requestMtu(247)
            else { chr = null; setStatus("BLE terputus") }
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, st: Int) { g.discoverServices() }
        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            chr = g.getService(SVC)?.getCharacteristic(CHR)
            setStatus(if (chr != null) "Terhubung ke MapsHUD" else "Service tidak ditemukan")
        }
    }

    @Suppress("DEPRECATION")
    private fun bleWrite(s: String) {
        val c = chr ?: return
        val g = gatt ?: return
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        c.value = s.toByteArray()
        g.writeCharacteristic(c)
    }

    // ---------- lokasi + rute ----------
    override fun onLocationChanged(loc: Location) {
        val d = dest ?: return
        if (route.size < 2) {
            fetchRoute(loc, d)
            return
        }
        pushToEsp(loc, d)
    }

    private fun dist(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dx = (lon2 - lon1) * 111320.0 * cos(Math.toRadians(lat1))
        val dy = (lat2 - lat1) * 111320.0
        return hypot(dx, dy)
    }

    private fun fetchRoute(loc: Location, d: DoubleArray) {
        val now = System.currentTimeMillis()
        if (fetching || now - lastFetch < 15000) return
        fetching = true; lastFetch = now
        Thread {
            try {
                val url = "https://router.project-osrm.org/route/v1/driving/" +
                        "${loc.longitude},${loc.latitude};${d[1]},${d[0]}?overview=full&geometries=geojson"
                val json = JSONObject(URL(url).readText())
                val coords = json.getJSONArray("routes").getJSONObject(0)
                    .getJSONObject("geometry").getJSONArray("coordinates")
                val list = ArrayList<DoubleArray>(coords.length())
                for (i in 0 until coords.length()) {
                    val c = coords.getJSONArray(i)
                    list.add(doubleArrayOf(c.getDouble(1), c.getDouble(0)))  // [lat, lon]
                }
                route = list
                setStatus("Rute dimuat: ${list.size} titik")
            } catch (e: Exception) {
                setStatus("Gagal ambil rute: ${e.message}")
            } finally { fetching = false }
        }.start()
    }

    private fun pushToEsp(loc: Location, d: DoubleArray) {
        // titik rute terdekat
        var bi = 0; var bd = Double.MAX_VALUE
        for (i in route.indices) {
            val dd = dist(loc.latitude, loc.longitude, route[i][0], route[i][1])
            if (dd < bd) { bd = dd; bi = i }
        }
        if (bd > 80) { route = emptyList(); return }   // keluar rute -> hitung ulang

        var remain = 0.0
        for (i in bi until route.size - 1)
            remain += dist(route[i][0], route[i][1], route[i + 1][0], route[i + 1][1])

        val brg = if (loc.hasBearing()) loc.bearing.toInt() else 0
        val sb = StringBuilder("$brg,${(loc.speed * 3.6).toInt()},${remain.toInt()}|")

        val cosLat = cos(Math.toRadians(loc.latitude))
        var lastLat = loc.latitude; var lastLon = loc.longitude
        var cum = 0.0; var cnt = 0
        for (i in bi until route.size) {
            val p = route[i]
            val seg = dist(lastLat, lastLon, p[0], p[1])
            if (seg < 15 && i != route.size - 1) continue   // jarangkan titik
            cum += seg
            val dx = (p[1] - loc.longitude) * 111320.0 * cosLat
            val dy = (p[0] - loc.latitude) * 111320.0
            sb.append("${dx.roundToInt()},${dy.roundToInt()};")
            lastLat = p[0]; lastLon = p[1]
            if (++cnt >= 24 || cum > 500) break
        }
        bleWrite(sb.toString())
        setStatus("Mengirim... sisa ${remain.toInt()} m")
    }

    // ---------- terima Share dari Google Maps / link lokasi ----------
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        i ?: return
        val text = when (i.action) {
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> i.dataString
            else -> null
        } ?: return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            toast("Izinkan permission dulu, lalu share ulang lokasinya"); return
        }
        setStatus("Membaca lokasi dari share...")
        Thread {
            // 1) koordinat langsung di teks, 2) kalau link pendek: ikuti redirect lalu baca
            val ll = extractLatLon(text) ?: Regex("https?://\\S+").find(text)?.value
                ?.let { resolveUrl(it) }?.let { extractLatLon(it) }
            if (ll == null) {
                setStatus("Koordinat tidak ditemukan di link"); return@Thread
            }
            runOnUiThread {
                destInput.setText("${ll[0]},${ll[1]}")
                if (chr == null) scanAndConnect()
                startNavigation()
            }
        }.start()
    }

    private fun extractLatLon(s: String): DoubleArray? {
        val patterns = listOf(
            "!3d(-?\\d+\\.\\d+)!4d(-?\\d+\\.\\d+)",                              // titik tempat di Google Maps
            "@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)",                                  // .../@lat,lon,zoom
            "(?:q|query|ll|destination)=(-?\\d+\\.\\d+)(?:,|%2C)\\s*(-?\\d+\\.\\d+)", // ?q=lat,lon
            "(-?\\d{1,3}\\.\\d+),\\s*(-?\\d{1,3}\\.\\d+)"                         // koordinat polos / geo:
        )
        for (p in patterns) {
            val m = Regex(p).find(s) ?: continue
            return doubleArrayOf(m.groupValues[1].toDouble(), m.groupValues[2].toDouble())
        }
        return null
    }

    private fun resolveUrl(start: String): String? = try {
        var u = start
        var result: String? = null
        for (n in 0 until 6) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            val code = c.responseCode
            val loc = c.getHeaderField("Location")
            c.disconnect()
            if (code in 300..399 && loc != null)
                u = if (loc.startsWith("http")) loc else URL(URL(u), loc).toString()
            else { result = u; break }
        }
        result ?: u
    } catch (e: Exception) { null }

    private fun startNavigation() {
        val parts = destInput.text.toString().split(",").map { it.trim().toDoubleOrNull() }
        if (parts.size != 2 || parts.any { it == null }) {
            toast("Format tujuan salah. Pakai: lat,lon"); return
        }
        dest = doubleArrayOf(parts[0]!!, parts[1]!!)
        route = emptyList()
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
            setStatus("Menunggu GPS...")
        } catch (e: SecurityException) {
            toast("Izin lokasi belum diberikan")
        }
    }

    private fun setStatus(s: String) = runOnUiThread { status.text = s }
    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
