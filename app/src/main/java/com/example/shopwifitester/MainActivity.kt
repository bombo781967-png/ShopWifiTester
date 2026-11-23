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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
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

// Palette Colori Dark Pro
val BgDark = Color(0xFF0B1120)
val CardDark = Color(0xFF151F32)
val CardBorder = Color(0xFF26334D)
val TextPrimary = Color(0xFFF8FAFC)
val TextSecondary = Color(0xFF94A3B8)
val AccentBlue = Color(0xFF38BDF8)
val StatusGreen = Color(0xFF10B981)
val StatusAmber = Color(0xFFF59E0B)
val StatusRed = Color(0xFFEF4444)

data class ChannelOverlapWarning(val message: String, val isCritical: Boolean)
data class ScannedNetwork(val ssid: String, val channel: Int, val band: String, val rssi: Int)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = BgDark, surface = CardDark)) {
                Surface(modifier = Modifier.fillMaxSize(), color = BgDark) {
                    ProfessionalWifiScreen()
                }
            }
        }
    }
}

@Composable
fun ProfessionalWifiScreen() {
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
        Box(modifier = Modifier.fillMaxSize().background(BgDark), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Text("Permessi Richiesti", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("Android richiede l'accesso alla posizione per analizzare canali Wi-Fi e Access Point.", color = TextSecondary, fontSize = 14.sp)
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)) },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue, contentColor = BgDark)
                ) {
                    Text("Attiva Analisi Wi-Fi", fontWeight = FontWeight.Bold)
                }
            }
        }
        return
    }

    // Stati diagnostica
    var ssid by remember { mutableStateOf("Scansione...") }
    var bssid by remember { mutableStateOf("00:00:00:00:00:00") }
    var rssi by remember { mutableIntStateOf(-90) }
    var freq by remember { mutableIntStateOf(0) }
    var linkSpeed by remember { mutableIntStateOf(0) }
    var gatewayIp by remember { mutableStateOf("192.168.1.1") }
    var pingMs by remember { mutableStateOf("--") }
    var packetLossCount by remember { mutableIntStateOf(0) }
    var isScanning by remember { mutableStateOf(false) }

    val rssiHistory = remember { mutableStateListOf<Int>() }
    val eventLogs = remember { mutableStateListOf<String>() }
    var channelWarnings by remember { mutableStateOf<List<ChannelOverlapWarning>>(emptyList()) }
    var scannedNetworks by remember { mutableStateOf<List<ScannedNetwork>>(emptyList()) }

    fun addLog(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        eventLogs.add(0, "$time  |  $msg")
        if (eventLogs.size > 100) eventLogs.removeAt(eventLogs.lastIndex)
    }

    // 1. Monitoraggio Roaming e Cadute
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
                        addLog("ROAMING: $lastBssid → $currentBssid ($elapsed)")
                        disconnectStart = 0L
                    } else if (disconnectStart > 0) {
                        val elapsed = System.currentTimeMillis() - disconnectStart
                        addLog("RICONNESSO allo stesso AP (${elapsed} ms)")
                        disconnectStart = 0L
                    }
                    lastBssid = currentBssid
                }

                override fun onLost(network: Network) {
                    disconnectStart = System.currentTimeMillis()
                    addLog("DISCONNESSIONE WI-FI RILEVATA")
                }
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onLost(network: Network) {
                    disconnectStart = System.currentTimeMillis()
                    addLog("DISCONNESSIONE WI-FI RILEVATA")
                }
            }
        }

        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        cm.registerNetworkCallback(req, callback)
        onDispose { cm.unregisterNetworkCallback(callback) }
    }

    // 2. Polling Live Segnale + Ping Gateway
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

                rssiHistory.add(rssi)
                if (rssiHistory.size > 40) rssiHistory.removeAt(0)
            }

            withContext(Dispatchers.IO) {
                try {
                    val start = System.currentTimeMillis()
                    val reachable = InetAddress.getByName(gatewayIp).isReachable(400)
                    val elapsed = System.currentTimeMillis() - start
                    withContext(Dispatchers.Main) {
                        if (reachable) {
                            pingMs = "${elapsed} ms"
                        } else {
                            pingMs = "TIMEOUT"
                            packetLossCount++
                            addLog("PACKET LOSS verso $gatewayIp (Tot: $packetLossCount)")
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { pingMs = "ERR" }
                }
            }
            delay(500)
        }
    }

    // 3. Scansione Canali
    fun runChannelScan() {
        isScanning = true
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                context.unregisterReceiver(this)
                isScanning = false
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
                val results = wifiManager.scanResults
                val parsed = results.map { res ->
                    ScannedNetwork(
                        ssid = res.SSID.ifEmpty { "[Rete Nascosta]" },
                        channel = freqToChannel(res.frequency),
                        band = if (res.frequency < 3000) "2.4 GHz" else "5 GHz",
                        rssi = res.level
                    )
                }.sortedByDescending { it.rssi }

                scannedNetworks = parsed

                val warnings = mutableListOf<ChannelOverlapWarning>()
                val strong24 = parsed.filter { it.band == "2.4 GHz" && it.rssi > -78 }
                for (i in strong24.indices) {
                    for (j in i + 1 until strong24.size) {
                        val a = strong24[i]
                        val b = strong24[j]
                        if (abs(a.channel - b.channel) < 5) {
                            warnings.add(
                                ChannelOverlapWarning(
                                    "Conflitto 2.4GHz: '${a.ssid}' (Ch ${a.channel}) ↔ '${b.ssid}' (Ch ${b.channel})",
                                    a.channel == b.channel
                                )
                            )
                        }
                    }
                }
                channelWarnings = warnings.ifEmpty {
                    listOf(ChannelOverlapWarning("Spettro pulito: nessuna sovrapposizione critica rilevata", false))
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        @Suppress("DEPRECATION")
        wifiManager.startScan()
        addLog("Scansione spettro Wi-Fi avviata")
    }

    val statusColor by animateColorAsState(
        targetValue = when {
            rssi >= -65 -> StatusGreen
            rssi >= -75 -> StatusAmber
            else -> StatusRed
        },
        label = "statusColor"
    )

    val qualityLabel = when {
        rssi >= -55 -> "SEGNALE ECCELLENTE"
        rssi >= -65 -> "BUONA COPERTURA POS"
        rssi >= -75 -> "SEGNALE DEBOLE"
        else -> "ZONA D'OMBRA CRITICA"
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 18.dp, bottom = 28.dp)
    ) {
        // Header Top
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("SHOP WI-FI ANALYZER", color = AccentBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Text(ssid, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(statusColor.copy(alpha = 0.15f))
                        .border(1.dp, statusColor.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(statusColor))
                        Spacer(Modifier.width(6.dp))
                        Text("LIVE", color = statusColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Tachimetro RSSI + Grafico Storico
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardDark),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    SignalGauge(rssi = rssi, color = statusColor, label = qualityLabel)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Access Point (BSSID): $bssid",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    if (rssiHistory.size > 2) {
                        Spacer(Modifier.height(14.dp))
                        SignalSparkline(history = rssiHistory, lineColor = statusColor)
                    }
                }
            }
        }

        // Griglia 4 KPI
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    KpiTile(
                        title = "CANALE / BANDA",
                        value = "Ch ${freqToChannel(freq)}",
                        subtitle = if (freq < 3000) "2.4 GHz ($freq MHz)" else "5 GHz ($freq MHz)",
                        modifier = Modifier.weight(1f)
                    )
                    KpiTile(
                        title = "PING GATEWAY",
                        value = pingMs,
                        subtitle = gatewayIp,
                        valueColor = if (pingMs == "TIMEOUT") StatusRed else AccentBlue,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    KpiTile(
                        title = "VELOCITÀ LINK",
                        value = "$linkSpeed Mbps",
                        subtitle = "Tx/Rx negoziata",
                        modifier = Modifier.weight(1f)
                    )
                    KpiTile(
                        title = "PACCHETTI PERSI",
                        value = "$packetLossCount",
                        subtitle = "Cadute ping",
                        valueColor = if (packetLossCount > 0) StatusRed else StatusGreen,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Pulsante Scansione Spettro
        item {
            Button(
                onClick = { runChannelScan() },
                enabled = !isScanning,
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue, contentColor = BgDark),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text(
                    if (isScanning) "SCANSIONE SPETTRO IN CORSO..." else "ANALIZZA SOVRAPPOSIZIONE CANALI",
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.8.sp
                )
            }
        }

        // Avvisi Sovrapposizioni
        if (channelWarnings.isNotEmpty()) {
            items(channelWarnings) { warn ->
                val bg = if (warn.isCritical) StatusRed.copy(alpha = 0.15f) else StatusGreen.copy(alpha = 0.15f)
                val border = if (warn.isCritical) StatusRed else StatusGreen
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(bg)
                        .border(1.dp, border.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                        .padding(14.dp)
                ) {
                    Text(warn.message, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        // Lista Reti Rilevate
        if (scannedNetworks.isNotEmpty()) {
            item {
                Text("RETI VICINE RILEVATE (${scannedNetworks.size})", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
            items(scannedNetworks.take(8)) { net ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(CardDark)
                        .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(net.ssid, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("Canale ${net.channel} • ${net.band}", color = TextSecondary, fontSize = 12.sp)
                    }
                    Text(
                        "${net.rssi} dBm",
                        color = if (net.rssi >= -65) StatusGreen else if (net.rssi >= -78) StatusAmber else TextSecondary,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp
                    )
                }
            }
        }

        // Log Roaming
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("LOG ROAMING & DISCONNESSIONI (${eventLogs.size})", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                TextButton(onClick = { eventLogs.clear(); packetLossCount = 0 }) {
                    Text("AZZERA", color = AccentBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (eventLogs.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(CardDark)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Cammina per il negozio per rilevare cambi AP e cadute", color = TextSecondary, fontSize = 12.sp)
                }
            }
        } else {
            items(eventLogs) { log ->
                val isError = log.contains("DISCONNESSIONE") || log.contains("LOSS")
                Text(
                    log,
                    color = if (isError) StatusRed else TextPrimary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(CardDark)
                        .padding(10.dp)
                )
            }
        }
    }
}

@Composable
fun SignalGauge(rssi: Int, color: Color, label: String) {
    // Mappa -95 dBm -> 0f, -35 dBm -> 1f
    val normalized = ((rssi.coerceIn(-95, -35) + 95) / 60f)
    val animatedProgress by animateFloatAsState(targetValue = normalized, animationSpec = tween(400), label = "gauge")

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(180.dp)) {
        Canvas(modifier = Modifier.fillMaxSize().padding(10.dp)) {
            val stroke = 14.dp.toPx()
            drawArc(
                color = CardBorder,
                startAngle = 140f,
                sweepAngle = 260f,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
                size = Size(size.width, size.height)
            )
            drawArc(
                color = color,
                startAngle = 140f,
                sweepAngle = 260f * animatedProgress,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
                size = Size(size.width, size.height)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$rssi", color = TextPrimary, fontSize = 42.sp, fontWeight = FontWeight.ExtraBold)
            Text("dBm", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
        }
    }
}

@Composable
fun SignalSparkline(history: List<Int>, lineColor: Color) {
    Canvas(modifier = Modifier.fillMaxWidth().height(48.dp)) {
        if (history.size < 2) return@Canvas
        val stepX = size.width / (history.size - 1)
        val path = Path()
        history.forEachIndexed { index, value ->
            val normY = ((value.coerceIn(-95, -35) + 95) / 60f)
            val x = index * stepX
            val y = size.height - (normY * size.height)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

@Composable
fun KpiTile(title: String, value: String, subtitle: String, modifier: Modifier = Modifier, valueColor: Color = TextPrimary) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardDark),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.border(1.dp, CardBorder, RoundedCornerShape(14.dp))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            Spacer(Modifier.height(4.dp))
            Text(value, color = valueColor, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            Text(subtitle, color = TextSecondary, fontSize = 11.sp, maxLines = 1)
        }
    }
}

fun freqToChannel(freq: Int): Int = when (freq) {
    in 2412..2484 -> (freq - 2407) / 5
    in 5170..5825 -> (freq - 5000) / 5
    in 5955..7115 -> (freq - 5950) / 5
    else -> 0
}
