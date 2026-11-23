package com.example.shopwifitester

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

data class ChannelOverlapWarning(val message: String, val isCritical: Boolean)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF4F6F8)) {
                    WifiTesterScreen()
                }
            }
        }
    }
}

@Composable
fun WifiTesterScreen() {
    val context = LocalContext.current
    var hasPermissions by remember {
        mutableStateOf(
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        hasPermissions = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true
    }

    LaunchedEffect(Unit) {
        if (!hasPermissions) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    if (!hasPermissions) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Button(onClick = {
                permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
            }) {
                Text("Concedi Permessi Posizione per Analisi Wi-Fi")
            }
        }
        return
    }

    var ssid by remember { mutableStateOf("Rilevamento...") }
    var bssid by remember { mutableStateOf("-") }
    var rssi by remember { mutableIntStateOf(-100) }
    var freq by remember { mutableIntStateOf(0) }
    var linkSpeed by remember { mutableIntStateOf(0) }
    var gatewayIp by remember { mutableStateOf("192.168.1.1") }
    var pingMs by remember { mutableStateOf("-") }
    var packetLossCount by remember { mutableIntStateOf(0) }

    val eventLogs = remember { mutableStateListOf<String>() }
    var channelWarnings by remember { mutableStateOf<List<ChannelOverlapWarning>>(emptyList()) }
    var scannedChannels by remember { mutableStateOf<List<String>>(emptyList()) }

    fun addLog(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        eventLogs.add(0, "[$time] $msg")
        if (eventLogs.size > 100) eventLogs.removeAt(eventLogs.lastIndex)
    }

    DisposableEffect(Unit) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        var lastBssid: String? = null
        var disconnectStart = 0L

        val callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val info = caps.transportInfo as? WifiInfo ?: return
                    val currentBssid = info.bssid ?: return
                    if (lastBssid != null && lastBssid != currentBssid) {
                        val elapsed = if (disconnectStart > 0) "${System.currentTimeMillis() - disconnectStart} ms" else "Fast Roaming (<200ms)"
                        addLog("🔄 ROAMING: $lastBssid -> $currentBssid ($elapsed)")
                        disconnectStart = 0L
                    } else if (disconnectStart > 0) {
                        val elapsed = System.currentTimeMillis() - disconnectStart
                        addLog("✅ RICONNESSO allo stesso AP dopo $elapsed ms")
                        disconnectStart = 0L
                    }
                    lastBssid = currentBssid
                }

                override fun onLost(network: Network) {
                    disconnectStart = System.currentTimeMillis()
                    addLog("⚠️ DISCONNESSIONE WI-FI!")
                }
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onLost(network: Network) {
                    disconnectStart = System.currentTimeMillis()
                    addLog("⚠️ DISCONNESSIONE WI-FI!")
                }
            }
        }

        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        cm.registerNetworkCallback(req, callback)
        onDispose { cm.unregisterNetworkCallback(callback) }
    }

    LaunchedEffect(gatewayIp) {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        @Suppress("DEPRECATION")
        val dhcpGateway = wifiManager.dhcpInfo?.gateway ?: 0
        if (dhcpGateway != 0) {
            gatewayIp = String.format(
                Locale.US, "%d.%d.%d.%d",
                dhcpGateway and 0xff, dhcpGateway shr 8 and 0xff,
                dhcpGateway shr 16 and 0xff, dhcpGateway shr 24 and 0xff
            )
        }

        while (isActive) {
            val activeNet = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(activeNet)
            val info: WifiInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                caps?.transportInfo as? WifiInfo
            } else {
                @Suppress("DEPRECATION") wifiManager.connectionInfo
            }

            if (info != null) {
                ssid = info.ssid.removePrefix("\"").removeSuffix("\"")
                bssid = info.bssid ?: "N/D"
                rssi = info.rssi
                freq = info.frequency
                linkSpeed = info.linkSpeed
            }

            withContext(Dispatchers.IO) {
                try {
                    val start = System.currentTimeMillis()
                    val reachable = InetAddress.getByName(gatewayIp).isReachable(400)
                    val elapsed = System.currentTimeMillis() - start
                    withContext(Dispatchers.Main) {
                        if (reachable) {
                            pingMs = "$elapsed ms"
                        } else {
                            pingMs = "PERSO!"
                            packetLossCount++
                            addLog("❌ Pacchetto perso verso $gatewayIp (Tot: $packetLossCount)")
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { pingMs = "Errore" }
                }
            }
            delay(500)
        }
    }

    fun runChannelScan() {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                context.unregisterReceiver(this)
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
                val results = wifiManager.scanResults
                val parsed = results.map { res ->
                    val ch = freqToChannel(res.frequency)
                    val band = if (res.frequency < 3000) "2.4G" else "5G"
                    Triple(res.SSID.ifEmpty { "[Nascosta]" }, ch, Pair(band, res.level))
                }.sortedByDescending { it.third.second }

                scannedChannels = parsed.map { "${it.first} | Canale ${it.second} (${it.third.first}) | ${it.third.second} dBm" }

                val warnings = mutableListOf<ChannelOverlapWarning>()
                val strong24 = parsed.filter { it.third.first == "2.4G" && it.third.second > -78 }
                for (i in strong24.indices) {
                    for (j in i + 1 until strong24.size) {
                        val a = strong24[i]
                        val b = strong24[j]
                        if (abs(a.second - b.second) < 5) {
                            warnings.add(
                                ChannelOverlapWarning(
                                    "Sovrapposizione 2.4GHz: '${a.first}' (Ch ${a.second}) e '${b.first}' (Ch ${b.second})",
                                    a.second == b.second
                                )
                            )
                        }
                    }
                }
                channelWarnings = warnings.ifEmpty {
                    listOf(ChannelOverlapWarning("✅ Nessuna sovrapposizione critica rilevata!", false))
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        @Suppress("DEPRECATION")
        wifiManager.startScan()
        addLog("📡 Scansione canali avviata...")
    }

    val signalColor = when {
        rssi >= -65 -> Color(0xFF2E7D32)
        rssi >= -75 -> Color(0xFFEF6C00)
        else -> Color(0xFFC62828)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Diagnostica Wi-Fi Negozio", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = signalColor),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(ssid, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("$rssi dBm  •  Canale ${freqToChannel(freq)} ($freq MHz)", color = Color.White, fontSize = 16.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("AP (BSSID): $bssid", color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp)
                    Text("Link: $linkSpeed Mbps  •  Ping ($gatewayIp): $pingMs", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        item {
            Button(
                onClick = { runChannelScan() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Analizza Sovrapposizione Canali")
            }
        }

        if (channelWarnings.isNotEmpty()) {
            items(channelWarnings) { warn ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (warn.isCritical) Color(0xFFFFEBEE) else Color(0xFFE8F5E9)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        warn.message,
                        modifier = Modifier.padding(12.dp),
                        color = if (warn.isCritical) Color(0xFFB71C1C) else Color(0xFF1B5E20),
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp
                    )
                }
            }
        }

        if (scannedChannels.isNotEmpty()) {
            item {
                Text("Reti e Canali Rilevati:", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            items(scannedChannels.take(8)) { net ->
                Text("• $net", fontSize = 12.sp, color = Color.DarkGray)
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Log Roaming e Cadute (${eventLogs.size})", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                TextButton(onClick = { eventLogs.clear(); packetLossCount = 0 }) {
                    Text("Pulisci")
                }
            }
        }

        items(eventLogs) { log ->
            Text(log, fontSize = 12.sp, modifier = Modifier.background(Color.White).padding(6.dp).fillMaxWidth())
        }
    }
}

fun freqToChannel(freq: Int): Int = when (freq) {
    in 2412..2484 -> (freq - 2407) / 5
    in 5170..5825 -> (freq - 5000) / 5
    in 5955..7115 -> (freq - 5950) / 5
    else -> 0
}
