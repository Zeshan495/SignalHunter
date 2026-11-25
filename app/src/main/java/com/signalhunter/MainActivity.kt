package com.signalhunter

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.telephony.CellInfo
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.telephony.CellSignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.SignalStrength
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class SignalSpot(
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val simLabel: String,
    val carrier: String,
    val network: String,
    val dbm: Int?
)

data class SimSignal(
    val label: String,
    val carrier: String,
    val network: String,
    val dbm: Int?,
    val level: Int,
    val cells: List<String>
)

class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshSignals() }

    private var signals by mutableStateOf<List<SimSignal>>(emptyList())
    private var status by mutableStateOf("Grant Phone and Location permissions to read cellular details.")
    private var testing by mutableStateOf(false)
    private var testResult by mutableStateOf("Not tested yet")
    private val history = mutableStateListOf<String>()
    private val spots = mutableStateListOf<SignalSpot>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoreSavedSpots()
        setContent {
            MaterialTheme {
                SignalHunterScreen(
                    signals = signals,
                    status = status,
                    history = history,
                    spots = spots,
                    testing = testing,
                    testResult = testResult,
                    onPermission = { requestPermissions() },
                    onRefresh = { refreshSignals() },
                    onSettings = { openNetworkSettings() },
                    onTest = { runNetworkTest() },
                    onClearHistory = { history.clear() },
                    onSaveSpot = { saveSignalSpot() },
                    onOpenBestSpot = { openBestSpot() },
                    onExportSpots = { exportSpots() }
                )
            }
        }
        requestPermissions()
    }

    private fun requestPermissions() {
        val needed = arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
        else refreshSignals()
    }

    @Suppress("MissingPermission")
    private fun refreshSignals() {
        val hasPhone = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasPhone || !hasLocation) {
            status = "Permissions are needed. Tap Grant permissions."
            return
        }
        try {
            val subManager = getSystemService(TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val active = subManager.activeSubscriptionInfoList.orEmpty()
            val found = mutableListOf<SimSignal>()
            if (active.isEmpty()) {
                val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
                found += readSim(tm, "Default SIM")
            } else {
                active.forEachIndexed { index, info ->
                    val tm = (getSystemService(TELEPHONY_SERVICE) as TelephonyManager)
                        .createForSubscriptionId(info.subscriptionId)
                    val name = info.displayName?.toString()?.takeIf { it.isNotBlank() } ?: "SIM ${index + 1}"
                    found += readSim(tm, name)
                }
            }
            signals = found
            status = if (found.isEmpty()) "No active SIM detected." else "Updated. Walk a few metres and refresh to compare reception."
            found.forEach { s ->
                val line = "${System.currentTimeMillis()}|${s.label}|${s.carrier}|${s.network}|${s.dbm ?: "—"} dBm"
                if (history.firstOrNull()?.substringAfter("|") != line.substringAfter("|")) history.add(0, line)
            }
            while (history.size > 20) history.removeAt(history.lastIndex)
        } catch (e: SecurityException) {
            status = "Android restricted cellular information. Check permissions and try again."
        } catch (e: Exception) {
            status = "Could not read SIM information: ${e.localizedMessage ?: "unknown error"}"
        }
    }

    @Suppress("DEPRECATION")
    private fun readSim(tm: TelephonyManager, label: String): SimSignal {
        val networkType = networkName(tm.dataNetworkType)
        val cells: List<CellInfo> = try { tm.allCellInfo.orEmpty() } catch (_: Exception) { emptyList() }
        val registered = cells.firstOrNull { it.isRegistered }
        val strength = registered?.cellSignalStrength
        val dbm = strength?.dbm?.takeIf { it in -150..-1 }
        val level = strength?.level ?: 0
        val cellLines = cells.take(6).map {
            val kind = when (it) {
                is android.telephony.CellInfoLte -> "LTE"
                is android.telephony.CellInfoNr -> "NR/5G"
                is android.telephony.CellInfoWcdma -> "WCDMA"
                is android.telephony.CellInfoGsm -> "GSM"
                is android.telephony.CellInfoCdma -> "CDMA"
                else -> "Cell"
            }
            val db = it.cellSignalStrength.dbm
            "$kind • ${if (db in -150..-1) "$db dBm" else "signal unavailable"}${if (it.isRegistered) " • serving" else ""}"
        }
        return SimSignal(
            label = label,
            carrier = tm.networkOperatorName.ifBlank { "Carrier unavailable" },
            network = networkType,
            dbm = dbm,
            level = level,
            cells = cellLines
        )
    }

    private fun networkName(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_HSPAP,
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSDPA -> "3G HSPA"
        TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
        TelephonyManager.NETWORK_TYPE_EDGE,
        TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "Unknown"
        else -> "Other"
    }



    private fun persistSavedSpots() {
        val array = JSONArray()
        spots.forEach { spot ->
            array.put(JSONObject().apply {
                put("timestamp", spot.timestamp)
                put("latitude", spot.latitude)
                put("longitude", spot.longitude)
                put("simLabel", spot.simLabel)
                put("carrier", spot.carrier)
                put("network", spot.network)
                if (spot.dbm != null) put("dbm", spot.dbm)
            })
        }
        getSharedPreferences("signal_hunter", MODE_PRIVATE)
            .edit().putString("saved_spots", array.toString()).apply()
    }

    private fun restoreSavedSpots() {
        try {
            val raw = getSharedPreferences("signal_hunter", MODE_PRIVATE)
                .getString("saved_spots", null) ?: return
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                spots.add(
                    SignalSpot(
                        timestamp = item.optLong("timestamp"),
                        latitude = item.optDouble("latitude"),
                        longitude = item.optDouble("longitude"),
                        simLabel = item.optString("simLabel"),
                        carrier = item.optString("carrier"),
                        network = item.optString("network"),
                        dbm = if (item.has("dbm")) item.optInt("dbm") else null
                    )
                )
            }
        } catch (_: Exception) {
            // Ignore malformed local data and allow fresh readings.
        }
    }

    @Suppress("MissingPermission")
    private fun saveSignalSpot() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine) {
            status = "Precise location permission is required to save a mapped reading."
            requestPermissions()
            return
        }
        try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            val location = providers.mapNotNull { provider ->
                try { if (lm.isProviderEnabled(provider)) lm.getLastKnownLocation(provider) else null }
                catch (_: Exception) { null }
            }.maxByOrNull { it.time }
            if (location == null) {
                status = "No recent GPS fix. Turn on Location, go outdoors, wait for GPS, then try again."
                return
            }
            val best = signals.filter { it.dbm != null }.maxByOrNull { it.dbm!! }
            if (best == null) {
                status = "No valid signal reading to save. Refresh signal first."
                return
            }
            val spot = SignalSpot(
                System.currentTimeMillis(), location.latitude, location.longitude,
                best.label, best.carrier, best.network, best.dbm
            )
            spots.add(0, spot)
            persistSavedSpots()
            status = "Saved ${best.carrier} at ${"%.5f".format(location.latitude)}, ${"%.5f".format(location.longitude)}"
        } catch (_: SecurityException) {
            status = "Location permission was denied. Allow precise location and try again."
        } catch (e: Exception) {
            status = "Could not save location: ${e.localizedMessage ?: "unknown error"}"
        }
    }

    private fun openBestSpot() {
        val best = spots.filter { it.dbm != null }.maxByOrNull { it.dbm!! }
        if (best == null) {
            status = "Save at least one GPS signal reading first."
            return
        }
        val label = "${best.carrier} ${best.dbm} dBm"
        val uri = Uri.parse("geo:${best.latitude},${best.longitude}?q=${best.latitude},${best.longitude}(${Uri.encode(label)})")
        startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    private fun exportSpots() {
        if (spots.isEmpty()) {
            status = "No mapped readings to export yet."
            return
        }
        val csv = buildString {
            append("timestamp,latitude,longitude,sim,carrier,network,dbm\n")
            spots.forEach {
                append("${it.timestamp},${it.latitude},${it.longitude},\"${it.simLabel}\",\"${it.carrier}\",\"${it.network}\",${it.dbm ?: ""}\n")
            }
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_SUBJECT, "Signal Hunter village signal map")
            putExtra(Intent.EXTRA_TEXT, csv)
        }
        startActivity(Intent.createChooser(intent, "Export signal map CSV"))
    }

    private fun openNetworkSettings() {
        try {
            startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun runNetworkTest() {
        if (testing) return
        testing = true
        testResult = "Testing HTTPS latency and download speed…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val start = System.nanoTime()
                    val conn = (URL("https://speed.cloudflare.com/__down?bytes=1000000").openConnection() as HttpURLConnection)
                    conn.connectTimeout = 8000
                    conn.readTimeout = 12000
                    conn.requestMethod = "GET"
                    conn.connect()
                    val latency = (System.nanoTime() - start) / 1_000_000
                    var bytes = 0L
                    conn.inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            bytes += n
                        }
                    }
                    conn.disconnect()
                    val seconds = ((System.nanoTime() - start) / 1_000_000_000.0).coerceAtLeast(0.001)
                    val mbps = bytes * 8.0 / seconds / 1_000_000.0
                    "HTTPS latency: ${latency} ms • Download: ${String.format(Locale.US, "%.2f", mbps)} Mbps"
                } catch (e: Exception) {
                    "Test failed: ${e.localizedMessage ?: "network unavailable"}"
                }
            }
            testResult = result
            testing = false
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignalHunterScreen(
    signals: List<SimSignal>,
    status: String,
    history: List<String>,
    spots: List<SignalSpot>,
    testing: Boolean,
    testResult: String,
    onPermission: () -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onTest: () -> Unit,
    onClearHistory: () -> Unit,
    onSaveSpot: () -> Unit,
    onOpenBestSpot: () -> Unit,
    onExportSpots: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val best = signals.filter { it.dbm != null }.maxByOrNull { it.dbm!! }
    val bestDbm = best?.dbm
    val quality = when {
        bestDbm == null -> "Waiting for signal"
        bestDbm >= -85 -> "Excellent"
        bestDbm >= -100 -> "Good"
        bestDbm >= -110 -> "Fair"
        else -> "Weak"
    }
    val qualityColor = when {
        bestDbm == null -> MaterialTheme.colorScheme.onSurfaceVariant
        bestDbm >= -100 -> androidx.compose.ui.graphics.Color(0xFF42D392)
        bestDbm >= -110 -> androidx.compose.ui.graphics.Color(0xFFFFC15A)
        else -> androidx.compose.ui.graphics.Color(0xFFFF7777)
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFF70E1C1),
            onPrimary = androidx.compose.ui.graphics.Color(0xFF06251E),
            secondary = androidx.compose.ui.graphics.Color(0xFF8AB4FF),
            background = androidx.compose.ui.graphics.Color(0xFF0B1118),
            surface = androidx.compose.ui.graphics.Color(0xFF141E29),
            surfaceVariant = androidx.compose.ui.graphics.Color(0xFF1C2936),
            onSurface = androidx.compose.ui.graphics.Color(0xFFEAF2F8),
            onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF9AAAB8)
        )
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text("SIGNAL HUNTER", style = MaterialTheme.typography.titleMedium)
                            Text("NETWORK INTELLIGENCE", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    actions = {
                        IconButton(onClick = onRefresh) {
                            androidx.compose.material3.Icon(
                                imageVector = androidx.compose.material.icons.Icons.Default.Refresh,
                                contentDescription = "Refresh signal"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    val destinations = listOf("Overview", "Signal map", "Tools")
                    val icons = listOf(
                        androidx.compose.material.icons.Icons.Default.Dashboard,
                        androidx.compose.material.icons.Icons.Default.Map,
                        androidx.compose.material.icons.Icons.Default.Tune
                    )
                    destinations.forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            icon = { androidx.compose.material3.Icon(icons[index], contentDescription = label) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                when (selectedTab) {
                                    1 -> "Coverage explorer"
                                    2 -> "Network toolkit"
                                    else -> "Your connection"
                                },
                                style = MaterialTheme.typography.headlineSmall
                            )
                            Text(
                                when (selectedTab) {
                                    1 -> "Save and compare reception around your area"
                                    2 -> "Run diagnostics and manage Android network settings"
                                    else -> "Understand your mobile signal in real time"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                androidx.compose.material3.Icon(
                                    androidx.compose.material.icons.Icons.Default.SignalCellularAlt,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text("${signals.size} SIM", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = MaterialTheme.shapes.extraLarge
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("BEST AVAILABLE SIGNAL", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f))
                                Surface(color = qualityColor.copy(alpha = 0.14f), shape = MaterialTheme.shapes.large) {
                                    Text(quality.uppercase(), modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        color = qualityColor, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(bestDbm?.let { "$it" } ?: "—",
                                    style = MaterialTheme.typography.displayLarge,
                                    color = qualityColor)
                                Spacer(Modifier.width(8.dp))
                                Text("dBm", modifier = Modifier.padding(bottom = 12.dp),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                repeat(5) { index ->
                                    val active = best != null && index < ((best.level.coerceIn(0, 4) + 1))
                                    Surface(
                                        modifier = Modifier.weight(1f).height(if (index == 4) 30.dp else 22.dp + index * 2.dp),
                                        shape = MaterialTheme.shapes.small,
                                        color = if (active) qualityColor else MaterialTheme.colorScheme.surfaceVariant
                                    ) {}
                                }
                            }
                            Text(
                                best?.let { "${it.carrier}  •  ${it.network}  •  ${it.label}" }
                                    ?: "Grant permissions and refresh to read your SIM signal",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = onRefresh, modifier = Modifier.weight(1f)) {
                                    androidx.compose.material3.Icon(
                                        androidx.compose.material.icons.Icons.Default.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(17.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("Refresh")
                                }
                                OutlinedButton(onClick = onPermission, modifier = Modifier.weight(1f)) {
                                    Text("Permissions")
                                }
                            }
                        }
                    }
                }

                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            androidx.compose.material3.Icon(
                                androidx.compose.material.icons.Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(status, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                if (selectedTab == 0 || selectedTab == 1) {
                    item {
                        SectionHeading("SIM comparison", "Compare the signal reported for each active SIM")
                    }
                    if (signals.isEmpty()) {
                        item {
                            EmptyCard("No cellular readings yet",
                                "Allow Phone and precise Location access, then refresh the signal.")
                        }
                    }
                    items(signals) { sim ->
                        val color = when {
                            sim.dbm == null -> MaterialTheme.colorScheme.onSurfaceVariant
                            sim.dbm >= -100 -> androidx.compose.ui.graphics.Color(0xFF42D392)
                            sim.dbm >= -110 -> androidx.compose.ui.graphics.Color(0xFFFFC15A)
                            else -> androidx.compose.ui.graphics.Color(0xFFFF7777)
                        }
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = MaterialTheme.shapes.large
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(sim.carrier, style = MaterialTheme.typography.titleMedium)
                                        Text("${sim.label}  •  ${sim.network}", style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(sim.dbm?.let { "$it dBm" } ?: "N/A",
                                        style = MaterialTheme.typography.titleLarge, color = color)
                                }
                                LinearProgressIndicator(
                                    progress = { sim.level.coerceIn(0, 4) / 4f },
                                    modifier = Modifier.fillMaxWidth().height(7.dp),
                                    color = color,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                                Text("Signal level ${sim.level}/4", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (sim.cells.isNotEmpty()) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                                    Text("CELL INFORMATION", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    sim.cells.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                    }
                }

                if (selectedTab == 1) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = MaterialTheme.shapes.large
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Village signal mapping", style = MaterialTheme.typography.titleMedium)
                                Text("Record your current best SIM reading with the phone's last known GPS position.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Button(onClick = onSaveSpot, modifier = Modifier.fillMaxWidth()) {
                                    androidx.compose.material3.Icon(
                                        androidx.compose.material.icons.Icons.Default.AddLocationAlt,
                                        contentDescription = null
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("Save current location")
                                }
                                OutlinedButton(onClick = onOpenBestSpot, modifier = Modifier.fillMaxWidth()) {
                                    Text("Navigate to strongest saved spot")
                                }
                                OutlinedButton(onClick = onExportSpots, modifier = Modifier.fillMaxWidth()) {
                                    Text("Export readings as CSV")
                                }
                                Text("${spots.size} saved locations", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary)
                                spots.take(12).forEach { spot ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text("${spot.carrier} • ${spot.network}", style = MaterialTheme.typography.bodyMedium)
                                            Text("${"%.5f".format(spot.latitude)}, ${"%.5f".format(spot.longitude)}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Text(spot.dbm?.let { "$it dBm" } ?: "—",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.primary)
                                    }
                                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                                }
                                if (spots.isEmpty()) Text("No saved locations. Take readings at different places to start comparing.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                if (selectedTab == 2 || selectedTab == 0) {
                    item { SectionHeading("Network diagnostics", "Check real-world data performance") }
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = MaterialTheme.shapes.large
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    androidx.compose.material3.Icon(
                                        androidx.compose.material.icons.Icons.Default.Speed,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(26.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text("Internet speed check", style = MaterialTheme.typography.titleMedium)
                                }
                                Text(testResult, style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Button(onClick = onTest, enabled = !testing, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (testing) "Testing connection…" else "Run download test")
                                }
                                Text("Downloads approximately 1 MB from Cloudflare. Results are approximate and may vary.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = MaterialTheme.shapes.large
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Android network controls", style = MaterialTheme.typography.titleMedium)
                                Text("Manage SIMs and preferred network modes in Android settings. Changes remain under your control.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) {
                                    Text("Open network settings")
                                }
                            }
                        }
                    }
                }

                if (selectedTab == 0 || selectedTab == 2) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionHeading("Recent readings", "Latest signal samples", Modifier.weight(1f))
                            TextButton(onClick = onClearHistory) { Text("Clear") }
                        }
                    }
                    if (history.isEmpty()) {
                        item { EmptyCard("No history yet", "Refresh signal to start collecting recent readings.") }
                    }
                    items(history.take(10)) { entry ->
                        Text(entry.substringAfter("|"), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                item {
                    Text(
                        "Signal Hunter measures reception; it cannot amplify radio power or guarantee better tower coverage. Android and carrier policies may limit cell details.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeading(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(subtitle, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyCard(title: String, message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(message, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
