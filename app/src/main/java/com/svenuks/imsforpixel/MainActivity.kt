package com.svenuks.imsforpixel

import android.Manifest
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import org.lsposed.hiddenapibypass.HiddenApiBypass
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.content.Intent
import okio.Path.Companion.toPath
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbCertPolicy
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore

import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import android.os.Build

class MainActivity : ComponentActivity() {

    companion object {
        @JvmStatic
        var pairingPort: Int? = null
        var onAuthStatusChanged: (() -> Unit)? = null
    }

    private var pairingReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            HiddenApiBypass.addHiddenApiExemptions("L")
            Log.d("IMSForSven", "Successfully applied HiddenApiBypass exemptions")
        } catch (e: Exception) {
            Log.e("IMSForSven", "Failed to apply HiddenApiBypass exemptions", e)
        }

        // Initialize KadbCert key store
        try {
            val privateKeyFile = java.io.File(filesDir, "kadb_private_key.pem")
            val store = OkioFilePrivateKeyStore(
                privateKeyFile.absolutePath.toPath()
            )
            KadbCert.configure(
                store = store,
                policy = KadbCertPolicy(),
                additionalPrivateKeysPem = emptyList()
            )
            KadbCert.ensureReady()
            Log.d("IMSForSven", "KadbCert configured successfully")
        } catch (e: Exception) {
            Log.e("IMSForSven", "Failed to configure KadbCert", e)
        }

        // Initialize defaults: enable everything except APN editing and Cross-SIM Calling, both of which are hidden and disabled by default.
        val prefs = getSharedPreferences("volte_settings", Context.MODE_PRIVATE)
        val initialized = prefs.getBoolean("initialized_defaults_v3", false)
        if (!initialized) {
            val editor = prefs.edit()
            for (slot in 0..1) {
                editor.putBoolean("volte_slot_$slot", true)
                editor.putBoolean("vonr_slot_$slot", true)
                editor.putBoolean("vowifi_slot_$slot", true)
                editor.putBoolean("cross_sim_slot_$slot", false) // Hidden and default false
                editor.putBoolean("wfc_roaming_slot_$slot", true)
                editor.putBoolean("ss_ut_slot_$slot", true)
                editor.putBoolean("show_ims_slot_$slot", true)
                editor.putBoolean("allow_apn_slot_$slot", false) // Hidden and default false
            }
            editor.putBoolean("initialized_defaults_v3", true)
            editor.apply()
        }

        // Setup dynamic broadcast receiver for notification pairing code input
        val filter = IntentFilter("com.svenuks.imsforpixel.ACTION_PAIR")
        pairingReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == "com.svenuks.imsforpixel.ACTION_PAIR") {
                    val remoteInput = androidx.core.app.RemoteInput.getResultsFromIntent(intent)
                    if (remoteInput != null) {
                        val code = remoteInput.getCharSequence("extra_pairing_code")?.toString()?.trim()
                        if (!code.isNullOrEmpty()) {
                            handleNotificationPairing(code)
                        }
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pairingReceiver, filter, 2) // RECEIVER_NOTEXPORTED is 2
        } else {
            registerReceiver(pairingReceiver, filter)
        }

        // READ_PHONE_STATE lets us read the live carrier config to verify the overrides are in effect.
        val missing = mutableListOf<String>()
        if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            missing += android.Manifest.permission.READ_PHONE_STATE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing += android.Manifest.permission.POST_NOTIFICATIONS
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 102)
        }

        setContent {
            MainScreen()
        }
    }

    fun requestNotificationPermissionAndShow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            } else {
                showPairingNotification()
            }
        } else {
            showPairingNotification()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showPairingNotification()
            } else {
                Toast.makeText(this, "需要通知权限来在通知栏输入配对码", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "pairing_channel",
                "无线调试配对",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "用于在系统设置页下拉通知栏快速输入无线调试配对码"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun showPairingNotification() {
        createNotificationChannel()
        
        val replyLabel = "请输入6位配对码"
        val remoteInput = androidx.core.app.RemoteInput.Builder("extra_pairing_code")
            .setLabel(replyLabel)
            .build()
            
        val intent = Intent("com.svenuks.imsforpixel.ACTION_PAIR").apply {
            `package` = packageName
        }
        
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        
        val replyPendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            intent,
            flags
        )
        
        val action = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "发送配对码 (Send)",
            replyPendingIntent
        )
            .addRemoteInput(remoteInput)
            .build()
            
        val notification = NotificationCompat.Builder(this, "pairing_channel")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("无线调试配对")
            .setContentText("💡请点击下方的【发送配对码】按钮输入6位数")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(action)
            .build()
            
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(202, notification)
    }

    private fun showPairingStatusNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, "pairing_channel")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("无线调试配对")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        manager.notify(202, notification)
    }

    private fun handleNotificationPairing(code: String) {
        val port = pairingPort
        if (port == null) {
            Toast.makeText(this, "未检测到系统配对端口，请确保系统配对弹窗处于打开状态！", Toast.LENGTH_LONG).show()
            showPairingStatusNotification("配对失败：未检测到系统配对窗口端口")
            return
        }
        
        Toast.makeText(this, "正在后台配对设备...", Toast.LENGTH_SHORT).show()
        showPairingStatusNotification("正在配对端口 $port...")

        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
        scope.launch {
            val result = try {
                Kadb.pair("127.0.0.1", port, code, filesDir.absolutePath)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
            
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = {
                        Toast.makeText(this@MainActivity, "通知配对成功！", Toast.LENGTH_LONG).show()
                        showPairingStatusNotification("配对成功！请返回应用激活配置。")
                        onAuthStatusChanged?.invoke()
                    },
                    onFailure = { error ->
                        Toast.makeText(this@MainActivity, "配对失败: ${error.message}", Toast.LENGTH_LONG).show()
                        showPairingStatusNotification("配对失败: ${error.message}")
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pairingReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {}
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(202)
    }
}

// Sleek Color Palette
val PrimaryGradStart = Color(0xFF1E3C72)
val PrimaryGradEnd = Color(0xFF2A5298)
val BackgroundDark = Color(0xFF0F172A)
val CardBackground = Color(0xFF1E293B)
val BorderColor = Color(0xFF334155)
val AccentGreen = Color(0xFF10B981)
val AccentOrange = Color(0xFFF59E0B)
val AccentRed = Color(0xFFEF4444)
val TextLight = Color(0xFFF8FAFC)
val TextMuted = Color(0xFF94A3B8)

/** IMS registration as last reported by ImsQueryTool / BrokerInstrumentation. */
enum class ImsStatus { REGISTERED, UNREGISTERED, NO_SIM }

fun readImsStatus(context: Context, slot: Int): ImsStatus {
    val text = try {
        java.io.File(context.filesDir, "ims_status_$slot.txt").readText().trim()
    } catch (e: Exception) {
        return ImsStatus.UNREGISTERED
    }
    return when (text) {
        "true" -> ImsStatus.REGISTERED
        "nosim" -> ImsStatus.NO_SIM
        else -> ImsStatus.UNREGISTERED
    }
}

/** Snapshot of both slots, refreshed periodically by [MainScreen]. */
data class SlotStatus(val config: CarrierOverrides.State, val ims: ImsStatus)

private fun readSlotStatuses(context: Context): List<SlotStatus> = (0..1).map { slot ->
    var config = CarrierOverrides.queryState(context, slot)
    val ims = readImsStatus(context, slot)
    // Without READ_PHONE_STATE we can't see the SIM list; fall back to what the shell query saw.
    if (ims == ImsStatus.NO_SIM && config != CarrierOverrides.State.APPLIED && config != CarrierOverrides.State.LOST) {
        config = CarrierOverrides.State.NO_SIM
    }
    SlotStatus(config, if (config == CarrierOverrides.State.NO_SIM) ImsStatus.NO_SIM else ims)
}

private const val INSTRUMENT_CMD =
    "am instrument -w -e clear %s com.svenuks.imsforpixel/com.svenuks.imsforpixel.BrokerInstrumentation"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    var selectedSimSlot by remember { mutableStateOf(0) }
    var portInput by remember { mutableStateOf("") }
    var isApplying by remember { mutableStateOf(false) }
    // Bumped after pairing so the ADB authorization check re-runs.
    var authEpoch by remember { mutableStateOf(0) }
    // Bumped after restore so the toggles reload their values from prefs.
    var configEpoch by remember { mutableStateOf(0) }
    // True while am instrument is running — pauses background ImsQueryTool polling
    val isInstrumenting = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    val context = LocalContext.current
    var slotStatuses by remember { mutableStateOf(readSlotStatuses(context)) }

    LaunchedEffect(Unit) {
        MainActivity.onAuthStatusChanged = {
            authEpoch++
        }
        while (true) {
            slotStatuses = withContext(Dispatchers.IO) { readSlotStatuses(context) }
            delay(1000)
        }
    }

    LaunchedEffect(portInput) {
        val port = portInput.toIntOrNull()
        if (port != null && port > 0 && port <= 65535) {
            withContext(Dispatchers.IO) {
                var activeKadb: com.flyfishxu.kadb.Kadb? = null
                try {
                    while (true) {
                        // Pause while BrokerInstrumentation is running to avoid file conflicts
                        if (isInstrumenting.get()) {
                            delay(1000)
                            continue
                        }
                        try {
                            val kadb = activeKadb ?: com.flyfishxu.kadb.Kadb.create("127.0.0.1", port, 5000, 5000).also { activeKadb = it }
                            val pathRes = kadb.shell("pm path com.svenuks.imsforpixel")
                            if (pathRes.exitCode == 0) {
                                val path = pathRes.output.trim().substringAfter("package:")
                                if (path.isNotEmpty()) {
                                    val queryCmd = "export CLASSPATH=$path; app_process /system/bin com.svenuks.imsforpixel.ImsQueryTool"
                                    val queryRes = kadb.shell(queryCmd)
                                    if (queryRes.exitCode == 0) {
                                        for (line in queryRes.output.lines()) {
                                            val parts = line.trim().split(":")
                                            val slot = parts.getOrNull(1)?.toIntOrNull() ?: continue
                                            val value = when {
                                                parts[0] == "RESULT" && parts.size == 3 -> parts[2].toBoolean().toString()
                                                parts[0] == "NOSIM" -> "nosim"
                                                else -> continue
                                            }
                                            java.io.File(context.filesDir, "ims_status_$slot.txt").writeText(value)
                                            Log.d("LocalAdb", "Updated slot $slot IMS status: $value")
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.d("LocalAdb", "Error in background IMS check: ${e.message}")
                            try { activeKadb?.close() } catch (ignored: Exception) {}
                            activeKadb = null
                        }
                        delay(3000)
                    }
                } finally {
                    try { activeKadb?.close() } catch (ignored: Exception) {}
                }
            }
        }
    }

    val scope = rememberCoroutineScope()

    /**
     * Launches BrokerInstrumentation in the background via ADB and waits for it to finish.
     * `am instrument` force-stops this package before starting the instrumentation, so this
     * process (and the UI) may be killed; in that case the broker's own notification reports
     * the result. If we survive, we wait for its done marker before re-enabling the buttons.
     */
    fun runBroker(clear: Boolean) {
        val port = portInput.toIntOrNull()
        if (port == null || port <= 0 || port > 65535) {
            Toast.makeText(context, "请先开启无线调试服务", Toast.LENGTH_SHORT).show()
            return
        }

        isApplying = true
        isInstrumenting.set(true)
        scope.launch {
            val doneFile = java.io.File(context.filesDir, "broker_done.txt")
            val startedAt = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) {
                try {
                    Kadb.create("127.0.0.1", port, 10000, 10000).use { kadb ->
                        // Detached on purpose: the shell session must outlive this process.
                        val cmd = "nohup ${INSTRUMENT_CMD.format(clear)} > /dev/null 2>&1 &"
                        val response = kadb.shell(cmd)
                        if (response.exitCode == 0) {
                            Result.success(Unit)
                        } else {
                            Result.failure(Exception("Exit code ${response.exitCode}: ${response.output}"))
                        }
                    }
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            result.onFailure { error ->
                isApplying = false
                isInstrumenting.set(false)
                val action = if (clear) "恢复" else "激活"
                Toast.makeText(context, "${action}失败: ${error.message}", Toast.LENGTH_LONG).show()
                return@launch
            }

            Toast.makeText(context, "已提交，约 30 秒内通知栏会显示结果", Toast.LENGTH_LONG).show()
            // Broker polls IMS for up to 30s; allow some slack for process startup.
            withContext(Dispatchers.IO) {
                val deadline = startedAt + 60_000
                while (System.currentTimeMillis() < deadline) {
                    val doneAt = try { doneFile.readText().trim().toLong() } catch (e: Exception) { 0L }
                    if (doneAt >= startedAt) break
                    delay(1000)
                }
            }
            isApplying = false
            isInstrumenting.set(false)
            if (clear) configEpoch++
            slotStatuses = withContext(Dispatchers.IO) { readSlotStatuses(context) }
        }
    }

    fun triggerManualApply() {
        // Reset clear flags to false to ensure the configuration overrides are applied
        CarrierOverrides.prefs(context).edit()
            .putBoolean("clear_slot_0", false)
            .putBoolean("clear_slot_1", false)
            .commit()
        runBroker(clear = false)
    }

    fun triggerManualRestore() {
        // Reset toggles for both slots back to the app defaults
        val editor = CarrierOverrides.prefs(context).edit()
        for (slot in 0..1) {
            editor.putBoolean("clear_slot_$slot", true)
                .putBoolean("volte_slot_$slot", true)
                .putBoolean("vonr_slot_$slot", true)
                .putBoolean("vowifi_slot_$slot", true)
                .putBoolean("wfc_roaming_slot_$slot", true)
                .putBoolean("ss_ut_slot_$slot", true)
                .putBoolean("show_ims_slot_$slot", true)
                .putBoolean("allow_apn_slot_$slot", false)
                .putBoolean("cross_sim_slot_$slot", false)
        }
        editor.commit()
        configEpoch++
        runBroker(clear = true)
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = PrimaryGradEnd,
            background = BackgroundDark,
            surface = CardBackground,
            onPrimary = TextLight,
            onBackground = TextLight,
            onSurface = TextLight
        )
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            "IMS for Pixel",
                            fontWeight = FontWeight.Bold,
                            color = TextLight
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = CardBackground
                    )
                )
            },
            containerColor = BackgroundDark
        ) { paddingValues ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Description Subtitle
                item {
                    Text(
                        text = "免 Root 开启 VoLTE/VoNR 通话配置",
                        color = TextMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }

                // Warn when previously applied overrides have been wiped (e.g. after a system update)
                val lostSlots = slotStatuses.indices.filter { slotStatuses[it].config == CarrierOverrides.State.LOST }
                if (lostSlots.isNotEmpty()) {
                    item {
                        WarningBanner(
                            "SIM ${lostSlots.joinToString("、") { (it + 1).toString() }} 的配置已失效" +
                                "（通常是系统更新后被清除），请重新一键激活。"
                        )
                    }
                }
                if (!CarrierOverrides.hasPhonePermission(context)) {
                    item {
                        WarningBanner("未授予「电话」权限，无法检测配置是否仍然生效。")
                    }
                }

                // SIM selector tabs
                item {
                    SimSelectorTabs(
                        selectedSlot = selectedSimSlot,
                        slotStatuses = slotStatuses,
                        onSlotSelected = { selectedSimSlot = it }
                    )
                }

                // Config Panel for Selected Slot
                item {
                    ConfigPanel(
                        slotIndex = selectedSimSlot,
                        status = slotStatuses[selectedSimSlot],
                        configEpoch = configEpoch
                    )
                }

                // Local Apply Card (Wireless Debugging self-connect)
                item {
                    LocalAdbCard(
                        authEpoch = authEpoch,
                        slotStatuses = slotStatuses,
                        portInput = portInput,
                        onPortInputChange = { portInput = it },
                        isApplying = isApplying,
                        onApplyClick = { triggerManualApply() },
                        onRestoreClick = { triggerManualRestore() }
                    )
                }
            }
        }
    }
}

@Composable
fun WarningBanner(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AccentOrange.copy(alpha = 0.15f))
            .border(1.dp, AccentOrange.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text("⚠️ $text", color = AccentOrange, fontSize = 12.sp)
    }
}

/** Badge label + color for a slot's config state. */
fun configBadge(state: CarrierOverrides.State): Pair<String, Color> = when (state) {
    CarrierOverrides.State.APPLIED -> "已生效" to AccentGreen
    CarrierOverrides.State.LOST -> "已失效" to AccentOrange
    CarrierOverrides.State.DEFAULT -> "系统默认" to TextMuted
    CarrierOverrides.State.NO_SIM -> "未插卡" to TextMuted
    CarrierOverrides.State.UNKNOWN -> "未知" to TextMuted
}

fun imsBadge(status: ImsStatus): Pair<String, Color> = when (status) {
    ImsStatus.REGISTERED -> "IMS已注册" to AccentGreen
    ImsStatus.UNREGISTERED -> "IMS未注册" to AccentRed
    ImsStatus.NO_SIM -> "无 SIM" to TextMuted
}


@Composable
fun SimSelectorTabs(
    selectedSlot: Int,
    slotStatuses: List<SlotStatus>,
    onSlotSelected: (Int) -> Unit
) {
    fun label(slot: Int) = if (slotStatuses[slot].config == CarrierOverrides.State.NO_SIM) {
        "SIM 卡 ${slot + 1}（未插卡）"
    } else {
        "SIM 卡 ${slot + 1}"
    }
    TabRow(
        selectedTabIndex = selectedSlot,
        containerColor = CardBackground,
        contentColor = TextLight,
        indicator = { tabPositions ->
            TabRowDefaults.SecondaryIndicator(
                Modifier.tabIndicatorOffset(tabPositions[selectedSlot]),
                color = PrimaryGradEnd
            )
        },
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, BorderColor, RoundedCornerShape(12.dp))
    ) {
        Tab(
            selected = selectedSlot == 0,
            onClick = { onSlotSelected(0) },
            text = { Text(label(0), fontWeight = FontWeight.Bold) }
        )
        Tab(
            selected = selectedSlot == 1,
            onClick = { onSlotSelected(1) },
            text = { Text(label(1), fontWeight = FontWeight.Bold) }
        )
    }
}

@Composable
fun ConfigPanel(
    slotIndex: Int,
    status: SlotStatus,
    configEpoch: Int
) {
    val context = LocalContext.current
    val prefs = remember(slotIndex) { CarrierOverrides.prefs(context) }

    var voLteEnabled by remember(slotIndex, configEpoch) { mutableStateOf(prefs.getBoolean("volte_slot_$slotIndex", true)) }
    var voNrEnabled by remember(slotIndex, configEpoch) { mutableStateOf(prefs.getBoolean("vonr_slot_$slotIndex", true)) }
    var voWifiEnabled by remember(slotIndex, configEpoch) { mutableStateOf(prefs.getBoolean("vowifi_slot_$slotIndex", true)) }
    var wfcRoamingEnabled by remember(slotIndex, configEpoch) { mutableStateOf(prefs.getBoolean("wfc_roaming_slot_$slotIndex", true)) }
    var ssUtEnabled by remember(slotIndex, configEpoch) { mutableStateOf(prefs.getBoolean("ss_ut_slot_$slotIndex", true)) }

    val (configText, configColor) = configBadge(status.config)
    val (imsText, imsColor) = imsBadge(status.ims)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderColor, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "运营商参数设置 (插槽 ${slotIndex + 1})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = TextLight
                )
                
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(configColor.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "配置:$configText",
                            color = configColor,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 10.sp
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(imsColor.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = imsText,
                            color = imsColor,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 10.sp
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = BorderColor)
            Spacer(modifier = Modifier.height(8.dp))

            // Toggles
            ToggleRow("启用 VoLTE 通话 (VoLTE)", "允许通过 4G/LTE 网络进行语音通话", voLteEnabled) { 
                voLteEnabled = it
                prefs.edit().putBoolean("volte_slot_$slotIndex", it).putBoolean("clear_slot_$slotIndex", false).commit()
            }
            ToggleRow("启用 5G 通话 (VoNR)", "启用 5G 独立组网语音通话支持 (VoNR)", voNrEnabled) { 
                voNrEnabled = it
                prefs.edit().putBoolean("vonr_slot_$slotIndex", it).putBoolean("clear_slot_$slotIndex", false).commit()
            }
            ToggleRow("启用 Wi-Fi 通话 (VoWiFi)", "信号不佳时允许通过 Wi-Fi 进行通话", voWifiEnabled) { 
                voWifiEnabled = it
                prefs.edit().putBoolean("vowifi_slot_$slotIndex", it).putBoolean("clear_slot_$slotIndex", false).commit()
            }
            ToggleRow("启用 Wi-Fi 通话漫游", "在国际或漫游状态下保持 Wi-Fi 通话启用", wfcRoamingEnabled) { 
                wfcRoamingEnabled = it
                prefs.edit().putBoolean("wfc_roaming_slot_$slotIndex", it).putBoolean("clear_slot_$slotIndex", false).commit()
            }
            ToggleRow("启用补充业务 (UT)", "启用运营商呼叫转移、呼叫等待等补充网络设置", ssUtEnabled) { 
                ssUtEnabled = it
                prefs.edit().putBoolean("ss_ut_slot_$slotIndex", it).putBoolean("clear_slot_$slotIndex", false).commit()
            }
        }
    }
}

@Composable
fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(description, color = TextMuted, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = TextLight,
                checkedTrackColor = PrimaryGradEnd
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalAdbCard(
    authEpoch: Int,
    slotStatuses: List<SlotStatus>,
    portInput: String,
    onPortInputChange: (String) -> Unit,
    isApplying: Boolean,
    onApplyClick: () -> Unit,
    onRestoreClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pairingPortInput by remember { mutableStateOf("") }
    var showManualPorts by remember { mutableStateOf(false) }
    var isAuthorized by remember { mutableStateOf(false) }
    var isWifiConnected by remember { mutableStateOf(false) }

    // Auth check: re-run when the port changes or after pairing, and keep retrying every 5s
    // until authorized. Never runs per 1s tick, to avoid spamming new Kadb connections that
    // compete with the background IMS polling loop and any active BrokerInstrumentation command.
    LaunchedEffect(portInput, authEpoch) {
        val port = portInput.toIntOrNull()
        if (port == null || port <= 0 || port > 65535) {
            isAuthorized = false
            return@LaunchedEffect
        }
        while (true) {
            isAuthorized = withContext(Dispatchers.IO) {
                try {
                    Kadb.create("127.0.0.1", port, 3000, 3000).use { kadb ->
                        kadb.shell("echo 1").exitCode == 0
                    }
                } catch (e: Exception) {
                    false
                }
            }
            if (isAuthorized) break
            delay(5000)
        }
    }

    DisposableEffect(Unit) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                val caps = connectivityManager.getNetworkCapabilities(network)
                isWifiConnected = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            override fun onLost(network: android.net.Network) {
                isWifiConnected = false
            }
            override fun onCapabilitiesChanged(network: android.net.Network, networkCapabilities: android.net.NetworkCapabilities) {
                isWifiConnected = networkCapabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            }
        }
        val request = android.net.NetworkRequest.Builder()
            .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        try {
            connectivityManager.registerNetworkCallback(request, callback)
        } catch (e: Exception) {}
        
        val activeNet = connectivityManager.activeNetwork
        val caps = connectivityManager.getNetworkCapabilities(activeNet)
        isWifiConnected = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true

        onDispose {
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (e: Exception) {}
        }
    }

    // Auto-discover the ports via mDNS (Network Service Discovery)
    val nsdManager = remember { context.getSystemService(Context.NSD_SERVICE) as NsdManager }
    DisposableEffect(Unit) {
        val connectListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.e("LocalAdb", "Start connect discovery failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.e("LocalAdb", "Stop connect discovery failed: $errorCode")
            }

            override fun onDiscoveryStarted(serviceType: String?) {
                Log.d("LocalAdb", "Connect discovery started")
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                Log.d("LocalAdb", "Connect discovery stopped")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d("LocalAdb", "Connect service found: ${serviceInfo.serviceName}")
                if (serviceInfo.serviceType.contains("adb-tls-connect")) {
                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                            Log.e("LocalAdb", "Connect resolve failed: $errorCode")
                        }

                        override fun onServiceResolved(resolvedServiceInfo: NsdServiceInfo) {
                            val port = resolvedServiceInfo.port
                            Log.d("LocalAdb", "Resolved local ADB port: $port")
                            onPortInputChange(port.toString())
                        }
                    })
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                Log.d("LocalAdb", "Connect service lost")
            }
        }

        val pairingListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.e("LocalAdb", "Start pairing discovery failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.e("LocalAdb", "Stop pairing discovery failed: $errorCode")
            }

            override fun onDiscoveryStarted(serviceType: String?) {
                Log.d("LocalAdb", "Pairing discovery started")
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                Log.d("LocalAdb", "Pairing discovery stopped")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d("LocalAdb", "Pairing service found: ${serviceInfo.serviceName}")
                if (serviceInfo.serviceType.contains("adb-tls-pairing")) {
                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                            Log.e("LocalAdb", "Pairing resolve failed: $errorCode")
                        }

                        override fun onServiceResolved(resolvedServiceInfo: NsdServiceInfo) {
                            val port = resolvedServiceInfo.port
                            Log.d("LocalAdb", "Resolved local ADB pairing port: $port")
                            pairingPortInput = port.toString()
                            MainActivity.pairingPort = port
                        }
                    })
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                Log.d("LocalAdb", "Pairing service lost")
            }
        }

        try {
            nsdManager.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, connectListener)
        } catch (e: Exception) {
            Log.e("LocalAdb", "Failed to start connect discovery", e)
        }

        try {
            nsdManager.discoverServices("_adb-tls-pairing._tcp", NsdManager.PROTOCOL_DNS_SD, pairingListener)
        } catch (e: Exception) {
            Log.e("LocalAdb", "Failed to start pairing discovery", e)
        }

        onDispose {
            try {
                nsdManager.stopServiceDiscovery(connectListener)
            } catch (e: Exception) {
                // Ignore
            }
            try {
                nsdManager.stopServiceDiscovery(pairingListener)
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "无线调试激活",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = TextLight
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            // Step 1: Wi-Fi connection status (Prerequisite)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("第一步：连接 Wi-Fi (必要前提)", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = TextLight)
                    Text("启用无线调试需先确保手机已连接到 Wi-Fi 网络", fontSize = 10.sp, color = TextMuted)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isWifiConnected) AccentGreen.copy(alpha = 0.15f) else AccentRed.copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (isWifiConnected) "已连接" else "未连接",
                        color = if (isWifiConnected) AccentGreen else AccentRed,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = BorderColor)
            Spacer(modifier = Modifier.height(12.dp))

            // Step 2: Wireless Debugging & Pairing
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("第二步：启用服务并配对", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = TextLight)
                    Text(
                        text = if (isAuthorized) "配对成功，已授权连接" else if (portInput.isNotEmpty()) "服务已开启 (需配对)" else "请在系统设置中开启无线调试",
                        fontSize = 10.sp,
                        color = if (isAuthorized) AccentGreen else if (portInput.isNotEmpty()) AccentOrange else TextMuted
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isAuthorized) AccentGreen.copy(alpha = 0.15f) else if (portInput.isNotEmpty()) AccentOrange.copy(alpha = 0.15f) else BorderColor.copy(alpha = 0.3f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (isAuthorized) "已授权" else if (portInput.isNotEmpty()) "未配对" else "未连接",
                        color = if (isAuthorized) AccentGreen else if (portInput.isNotEmpty()) AccentOrange else TextMuted,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    (context as? MainActivity)?.requestNotificationPermissionAndShow()
                    try {
                        context.startActivity(Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"))
                    } catch (e: Exception) {
                        try {
                            context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                        } catch (e2: Exception) {
                            Toast.makeText(context, "未找到开发者选项", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextLight),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor)
            ) {
                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("去开启无线调试", fontSize = 11.sp)
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = BorderColor)
            Spacer(modifier = Modifier.height(12.dp))

            // Step 3: Activation & Status check
            Text("第三步：一键激活配置", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = TextLight)
            Spacer(modifier = Modifier.height(4.dp))
            Text("将上方设置的参数应用到当前 SIM 卡中", fontSize = 10.sp, color = TextMuted)
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(BackgroundDark.copy(alpha = 0.5f))
                        .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text("SIM 卡 1 通话配置", fontSize = 10.sp, color = TextMuted)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = configBadge(slotStatuses[0].config).first,
                            color = configBadge(slotStatuses[0].config).second,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(1.dp))
                        Text(
                            text = imsBadge(slotStatuses[0].ims).first,
                            color = imsBadge(slotStatuses[0].ims).second,
                            fontSize = 9.sp
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(BackgroundDark.copy(alpha = 0.5f))
                        .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text("SIM 卡 2 通话配置", fontSize = 10.sp, color = TextMuted)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = configBadge(slotStatuses[1].config).first,
                            color = configBadge(slotStatuses[1].config).second,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(1.dp))
                        Text(
                            text = imsBadge(slotStatuses[1].ims).first,
                            color = imsBadge(slotStatuses[1].ims).second,
                            fontSize = 9.sp
                        )
                    }
                }
            }

            val hasResolvedPort = portInput.isNotEmpty()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onApplyClick,
                    enabled = !isApplying,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = if (hasResolvedPort) AccentGreen else BorderColor)
                ) {
                    if (isApplying) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            if (hasResolvedPort) "一键激活" else "等待开启...",
                            fontSize = 12.sp
                        )
                    }
                }

                OutlinedButton(
                    onClick = onRestoreClick,
                    enabled = !isApplying,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AccentRed.copy(alpha = 0.5f))
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "一键恢复",
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "💡 激活后 IMS 状态将在数秒内自动更新。",
                fontSize = 10.sp,
                color = AccentOrange,
                modifier = Modifier.padding(horizontal = 2.dp),
                maxLines = 1
            )

            // Advanced manual override
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = if (showManualPorts) "隐藏高级设置" else "显示高级设置",
                    color = TextMuted,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .clickable { showManualPorts = !showManualPorts }
                        .padding(4.dp)
                )
            }

            if (showManualPorts) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = portInput,
                    onValueChange = { onPortInputChange(it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("连接端口 (ADB Port)", fontSize = 9.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = LocalTextStyle.current.copy(fontSize = 11.sp)
                )
            }
        }
    }
}
