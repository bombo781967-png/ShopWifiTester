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
import android.net.Uri
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
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
import androidx.compose.ui.geometry.Size
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
import java.net.InetSocketAddress
import java.net.Socket
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

data class EndpointProbeState(
    val title: String,
    val subtitle: String,
    val hosts: List<String>,
    val activeHost: String,
    val latencyMs: Long? = null,
    val jitterMs: Long? = null,
    val isReachable: Boolean = false
)

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
                Text("Android richiede l'accesso alla posizione per leggere SSID, canali Wi-Fi e Access Point.", color = TextSecondary, fontSize = 14.sp)
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

    // Stati diagnostica Wi-Fi
    var ssid by remember { mutableStateOf("Rilevamento SSID...") }
    var bssid by remember { mutableStateOf("00:00:00:00:00:00") }
    var rssi by remember { mutableIntStateOf(-90) }
    var freq by remember { mutableIntStateOf(0) }
    var linkSpeed by remember { mutableIntStateOf(0) }
    var gatewayIp by remember { mutableStateOf("192.168.1.1") }
    var pingMs by remember { mutableStateOf("--") }
    var packetLossCount by remember { mutableIntStateOf(0) }
    var roamingCount by remember { mutableIntStateOf(0) }
    var isScanning by remember { mutableStateOf(false) }

    // Dialog per invio email
    var showEmailDialog by remember { mutableStateOf(false) }
    var recipientEmail by remember { mutableStateOf("") }

    // Stati probe domini aziendali (Checkpoint & ItemOptix)
    var checkpointState by remember {
        mutableStateOf(
            EndpointProbeState(
                title = "*.checkpointsystems.com",
                subtitle = "IO Portal — Portale Checkpoint",
                hosts = listOf("portal.checkpointsystems.com", "www.checkpointsystems.com", "checkpointsystems.com"),
                activeHost = "portal.checkpointsystems.com"
            )
        )
    }
    var itemOptixState by remember {
        mutableStateOf(
            EndpointProbeState(
                title = "*.itemoptix.com",
                subtitle = "IO Portal, IO Mobile — Servizi ItemOptix",
                hosts = listOf("api.itemoptix.com", "portal.itemoptix.com", "www.itemoptix.com", "itemoptix.com"),
                activeHost = "api.itemoptix.com"
            )
        )
    }

    val rssiHistory = remember { mutableStateListOf<Int>() }
    val eventLogs = remember { mutableStateListOf<String>() }
    var channelWarnings by remember { mutableStateOf<List<ChannelOverlapWarning>>(emptyList()) }
    var scannedNetworks by remember { mutableStateOf<List<ScannedNetwork>>(emptyList()) }

    fun addLog(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        eventLogs.add(0, "$time  |  $msg")
        if (eventLogs.size > 100) eventLogs.removeAt(eventLogs.lastIndex)
    }

    fun buildReportBody(): String {
        val timestamp = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
        val bandLabel = if (freq == 0) "N/D" else if (freq < 3000) "2.4 GHz ($freq MHz)" else "5 GHz ($freq MHz)"
        val cpLat = if (checkpointState.isReachable && checkpointState.latencyMs != null) "${checkpointState.latencyMs} ms" else "OFFLINE"
        val cpJit = if (checkpointState.jitterMs != null) "${checkpointState.jitterMs} ms" else "--"
        val ioLat = if (itemOptixState.isReachable && itemOptixState.latencyMs != null) "${itemOptixState.latencyMs} ms" else "OFFLINE"
        val ioJit = if (itemOptixState.jitterMs != null) "${itemOptixState.jitterMs} ms" else "--"

        val sb = StringBuilder()
        sb.appendLine("REPORT DIAGNOSTICA WI-FI PUNTO VENDITA")
        sb.appendLine("Data e ora rilievo: $timestamp")
        sb.appendLine()
        sb.appendLine("1. CONNESSIONE E SEGNALE ATTIVO")
        sb.appendLine("- SSID utilizzato: $ssid")
        sb.appendLine("- Access Point (BSSID): $bssid")
        sb.appendLine("- Intensità segnale (RSSI): $rssi dBm")
        sb.appendLine("- Canale / Banda: Ch ${freqToChannel(freq)} - $bandLabel")
        sb.appendLine("- Velocità Link: $linkSpeed Mbps")
        sb.appendLine()
        sb.appendLine("2. ROAMING E STABILITÀ RETE LOCALE")
        sb.appendLine("- Roaming effettuati (cambi AP): $roamingCount")
        sb.appendLine("- Ping Gateway ($gatewayIp): $pingMs")
        sb.appendLine("- Pacchetti persi: $packetLossCount")
        sb.appendLine()
        sb.appendLine("3. PRESTAZIONI SERVIZI CLOUD (LATENZA E JITTER)")
        sb.appendLine("- *.checkpointsystems.com (IO Portal - ${checkpointState.activeHost}): Latenza $cpLat | Jitter $cpJit")
        sb.appendLine("- *.itemoptix.com (IO Portal / IO Mobile - ${itemOptixState.activeHost}): Latenza $ioLat | Jitter $ioJit")
        sb.appendLine()
        sb.appendLine("4. ANALISI SOVRAPPOSIZIONE CANALI")
        if (channelWarnings.isEmpty()) {
            sb.appendLine("Scansione canali non ancora eseguita")
        } else {
            channelWarnings.forEach { sb.appendLine("- ${it.message}") }
        }
        sb.appendLine()
        sb.appendLine("5. LOG EVENTI E ROAMING (ULTIMI 25)")
        if (eventLogs.isEmpty()) {
            sb.appendLine("Nessun evento di roaming o disconnessione registrato")
        } else {
            eventLogs.take(25).forEach { sb.appendLine(it) }
        }
        return sb.toString()
    }

    fun sendEmailReport(targetEmail: String) {
        val subject = "Report Diagnostica Wi-Fi Negozio - SSID: $ssid"
        val body = buildReportBody()
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            if (targetEmail.isNotBlank()) {
                putExtra(Intent.EXTRA_EMAIL, arrayOf(targetEmail.trim()))
            }
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        try {
            context.startActivity(Intent.createChooser(intent, "Invia Report Wi-Fi con..."))
        } catch (e: Exception) {
            Toast.makeText(context, "Nessuna app email configurata sul dispositivo", Toast.LENGTH_LONG).show()
        }
    }

    // 1. Monitoraggio Roaming e Cadute (con conteggio Roaming)
    DisposableEffect(Unit) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        var lastBssid: String? = null
        var disconnectStart = 0L

        val callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val info = caps.transportInfo as? WifiInfo ?: return
                    val currentBssid = info.bssid ?: return
                    val currentSsid = info.ssid?.removePrefix("\"")?.removeSuffix("\"")
                    if (!currentSsid.isNullOrBlank() && currentSsid != "<unknown ssid>") {
                        ssid = currentSsid
                    }
                    if (lastBssid != null && lastBssid != currentBssid) {
                        roamingCount++
                        val elapsed = if (disconnectStart > 0) "${System.currentTimeMillis() - disconnectStart} ms" else "Fast Roaming (<200ms)"
                        addLog("ROAMING #$roamingCount ($ssid): $lastBssid -> $currentBssid ($elapsed)")
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

    // 2. Polling Live Segnale + Ping Gateway + Latenza/Jitter Checkpoint & ItemOptix
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

        val cpSamples = ArrayDeque<Long>()
        val ioSamples = ArrayDeque<Long>()
        var cycle = 0

        while (isActive) {
            val activeNet = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(activeNet)
            val info: WifiInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                caps?.transportInfo as? WifiInfo
            } else {
                @Suppress("DEPRECATION") wifiManager.connectionInfo
            }

            if (info != null) {
                val rawSsid = info.ssid?.removePrefix("\"")?.removeSuffix("\"") ?: ""
                ssid = if (rawSsid.isBlank() || rawSsid == "<unknown ssid>") "Wi-Fi Connesso (SSID Nascosto)" else rawSsid
                bssid = info.bssid ?: "N/D"
                rssi = info.rssi
                freq = info.frequency
                linkSpeed = info.linkSpeed

                rssiHistory.add(rssi)
                if (rssiHistory.size > 40) rssiHistory.removeAt(0)
            } else {
                ssid = "Non connesso al Wi-Fi"
            }

            // Ping Gateway locale ogni 500ms
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

            // Probe Cloud Checkpoint & ItemOptix ogni 1000ms
            if (cycle % 2 == 0) {
                val cpResult = withContext(Dispatchers.IO) { probeDomainCandidates(checkpointState.hosts) }
                if (cpResult != null) {
                    val (resolvedHost, rtt) = cpResult
                    cpSamples.addLast(rtt)
                    if (cpSamples.size > 15) cpSamples.removeFirst()
                    checkpointState = checkpointState.copy(
                        activeHost = resolvedHost,
                        latencyMs = rtt,
                        jitterMs = calculateJitter(cpSamples),
                        isReachable = true
                    )
                } else {
                    checkpointState = checkpointState.copy(latencyMs = null, isReachable = false)
                }

                val ioResult = withContext(Dispatchers.IO) { probeDomainCandidates(itemOptixState.hosts) }
                if (ioResult != null) {
                    val (resolvedHost, rtt) = ioResult
                    ioSamples.addLast(rtt)
                    if (ioSamples.size > 15) ioSamples.removeFirst()
                    itemOptixState = itemOptixState.copy(
                        activeHost = resolvedHost,
                        latencyMs = rtt,
                        jitterMs = calculateJitter(ioSamples),
                        isReachable = true
                    )
                } else {
                    itemOptixState = itemOptixState.copy(latencyMs = null, isReachable = false)
                }
            }

            cycle++
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
                                    "Conflitto 2.4GHz: '${a.ssid}' (Ch ${a.channel}) - '${b.ssid}' (Ch ${b.channel})",
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

    // Finestra di dialogo per inserire l'email destinatario
    if (showEmailDialog) {
        AlertDialog(
            onDismissRequest = { showEmailDialog = false },
            containerColor = CardDark,
            title = {
                Text("Invia Report Test Wi-Fi", color = TextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Inserisci l'indirizzo email a cui inviare tutti i dati raccolti nel negozio:",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                    OutlinedTextField(
                        value = recipientEmail,
                        onValueChange = { recipientEmail = it },
                        placeholder = { Text("esempio@azienda.it", color = TextSecondary) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showEmailDialog = false
                        sendEmailReport(recipientEmail)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusGreen, contentColor = BgDark)
                ) {
                    Text("APRI EMAIL E INVIA", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmailDialog = false }) {
                    Text("ANNULLA", color = TextSecondary)
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 18.dp, bottom = 28.dp)
    ) {
        // Header Top con SSID in evidenza
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("SSID CONNESSO IN USO", color = AccentBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Text(ssid, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                }
                Spacer(Modifier.width(8.dp))
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
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "SSID: $ssid  •  AP: $bssid",
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

        // Griglia KPI (Roaming + Rete Locale)
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    KpiTile(
                        title = "ROAMING EFFETTUATI",
                        value = "$roamingCount",
                        subtitle = "Cambi AP rilevati",
                        valueColor = AccentBlue,
                        modifier = Modifier.weight(1f)
                    )
                    KpiTile(
                        title = "CANALE / BANDA",
                        value = "Ch ${freqToChannel(freq)}",
                        subtitle = if (freq < 3000) "2.4 GHz ($freq MHz)" else "5 GHz ($freq MHz)",
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    KpiTile(
                        title = "PING GATEWAY",
                        value = pingMs,
                        subtitle = gatewayIp,
                        valueColor = if (pingMs == "TIMEOUT") StatusRed else StatusGreen,
                        modifier = Modifier.weight(1f)
                    )
                    KpiTile(
                        title = "LINK / PERSI",
                        value = "$linkSpeed Mbps",
                        subtitle = "Pacchetti persi: $packetLossCount",
                        valueColor = if (packetLossCount > 0) StatusRed else TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Sezione Ping Domini Cloud (Checkpoint & ItemOptix)
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "LATENZA & JITTER SERVIZI CLOUD",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                EndpointProbeCard(state = checkpointState)
                EndpointProbeCard(state = itemOptixState)
            }
        }

        // Pulsanti Azione: Scansione Spettro + Invio Report Email
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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

                Button(
                    onClick = { showEmailDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusGreen, contentColor = BgDark),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) {
                    Text(
                        "INVIA REPORT TEST VIA EMAIL",
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.8.sp
                    )
                }
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
                Text("LOG ROAMING ($roamingCount) & EVENTI", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                TextButton(onClick = { eventLogs.clear(); packetLossCount = 0; roamingCount = 0 }) {
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
fun EndpointProbeCard(state: EndpointProbeState) {
    val latencyColor = when {
        !state.isReachable || state.latencyMs == null -> StatusRed
        state.latencyMs <= 60 -> StatusGreen
        state.latencyMs <= 150 -> StatusAmber
        else -> StatusRed
    }
    val jitterColor = when {
        state.jitterMs == null -> TextSecondary
        state.jitterMs <= 15 -> StatusGreen
        state.jitterMs <= 40 -> StatusAmber
        else -> StatusRed
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = CardDark),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, CardBorder, RoundedCornerShape(14.dp))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(state.title, color = AccentBlue, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text(state.subtitle, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text("Host: ${state.activeHost}", color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("LATENZA", color = TextSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (state.isReachable && state.latencyMs != null) "${state.latencyMs} ms" else "OFFLINE",
                            color = latencyColor,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("JITTER", color = TextSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (state.jitterMs != null) "${state.jitterMs} ms" else "--",
                            color = jitterColor,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

fun probeDomainCandidates(candidates: List<String>): Pair<String, Long>? {
    for (host in candidates) {
        val rtt = measureTcpRtt(host, 443, 1200)
        if (rtt != null) return Pair(host, rtt)
    }
    return null
}

fun measureTcpRtt(host: String, port: Int, timeoutMs: Int): Long? {
    return try {
        Socket().use { socket ->
            val start = System.nanoTime()
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            (System.nanoTime() - start) / 1_000_000L
        }
    } catch (e: Exception) {
        null
    }
}

fun calculateJitter(samples: ArrayDeque<Long>): Long {
    if (samples.size < 2) return 0L
    val list = samples.toList()
    var diffSum = 0L
    for (i in 1 until list.size) {
        diffSum += abs(list[i] - list[i - 1])
    }
    return diffSum / (list.size - 1)
}

@Composable
fun SignalGauge(rssi: Int, color: Color, label: String) {
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
