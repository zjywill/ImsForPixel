package com.svenuks.imsforpixel

import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.PhoneForwarded
import androidx.compose.material.icons.filled.Adb
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiCalling3
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.flyfishxu.kadb.Kadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

/**
 * Snapshot of one logical SIM slot, refreshed periodically by [MainScreen].
 * [carrier] and [embedded] are only known when a SIM is active and READ_PHONE_STATE is granted.
 */
data class SlotStatus(
    val slot: Int,
    val config: CarrierOverrides.State,
    val ims: ImsStatus,
    val carrier: String? = null,
    val embedded: Boolean = false
) {
    val hasSim: Boolean get() = config != CarrierOverrides.State.NO_SIM

    /** Carrier name when known (e.g. "中国移动"), otherwise "SIM n". */
    val title: String get() = carrier ?: "SIM ${slot + 1}"

    /** What kind of slot this is, e.g. "eSIM" vs a physical tray. */
    val kind: String get() = if (embedded) "eSIM" else "SIM 卡"
}

/**
 * Number of logical SIM slots the modem supports. Phones with one physical tray plus eSIM still
 * report 2 in dual-SIM (DSDS) mode, so empty slots are hidden in the UI rather than listed.
 */
private fun modemSlotCount(context: Context): Int {
    val tm = context.getSystemService(android.telephony.TelephonyManager::class.java) ?: return 1
    val count = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        tm.activeModemCount
    } else {
        @Suppress("DEPRECATION")
        tm.phoneCount
    }
    return count.coerceIn(1, 2)
}

private fun readSlotStatuses(context: Context): List<SlotStatus> {
    val subManager = context.getSystemService(android.telephony.SubscriptionManager::class.java)
    return (0 until modemSlotCount(context)).map { slot ->
        var config = CarrierOverrides.queryState(context, slot)
        val ims = readImsStatus(context, slot)
        // Without READ_PHONE_STATE we can't see the SIM list; fall back to what the shell query saw.
        if (ims == ImsStatus.NO_SIM && config != CarrierOverrides.State.APPLIED && config != CarrierOverrides.State.LOST) {
            config = CarrierOverrides.State.NO_SIM
        }
        val sub = if (CarrierOverrides.hasPhonePermission(context)) {
            try { subManager?.getActiveSubscriptionInfoForSimSlotIndex(slot) } catch (e: Exception) { null }
        } else {
            null
        }
        SlotStatus(
            slot = slot,
            config = config,
            ims = if (config == CarrierOverrides.State.NO_SIM) ImsStatus.NO_SIM else ims,
            carrier = sub?.displayName?.toString()?.takeIf { it.isNotBlank() },
            embedded = sub?.isEmbedded == true
        )
    }
}

/** Slots worth showing: those with a SIM, or just the first slot when none has one. */
private fun visibleSlots(statuses: List<SlotStatus>): List<SlotStatus> =
    statuses.filter { it.hasSim }.ifEmpty { statuses.take(1) }

private const val INSTRUMENT_CMD =
    "am instrument -w -e clear %s com.svenuks.imsforpixel/com.svenuks.imsforpixel.BrokerInstrumentation"

private fun isValidPort(port: Int?) = port != null && port in 1..65535

/** True when adbd rejected our key, i.e. this install hasn't been paired via Wireless Debugging. */
private fun isNotPairedError(e: Throwable): Boolean {
    val text = generateSequence(e) { it.cause }.joinToString(" ") { it.message.orEmpty() }
    return "CERTIFICATE_UNKNOWN" in text || "CERTIFICATE_REQUIRED" in text
}

/** Turns low-level ADB/TLS exceptions into a message a user can act on. */
private fun friendlyAdbError(e: Throwable): String {
    val text = generateSequence(e) { it.cause }.joinToString(" ") { it.message.orEmpty() }
    return when {
        isNotPairedError(e) -> "尚未配对：请点按「无线调试」，选择「使用配对码配对设备」，然后在通知栏输入配对码"
        "ECONNREFUSED" in text || "Connection refused" in text ->
            "无法连接无线调试，请确认「无线调试」已开启"
        "timed out" in text || "timeout" in text.lowercase() ->
            "连接无线调试超时，请确认手机仍连接着 Wi‑Fi"
        else -> e.message ?: e.javaClass.simpleName
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MainScreen() {
    var selectedSimSlot by rememberSaveable { mutableIntStateOf(0) }
    var portInput by rememberSaveable { mutableStateOf("") }
    var isApplying by remember { mutableStateOf(false) }
    // Bumped after pairing so the ADB authorization check re-runs.
    var authEpoch by remember { mutableIntStateOf(0) }
    // Bumped after restore so the toggles reload their values from prefs.
    var configEpoch by remember { mutableIntStateOf(0) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    // True while am instrument is running — pauses background ImsQueryTool polling
    val isInstrumenting = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var slotStatuses by remember {
        mutableStateOf(listOf(SlotStatus(0, CarrierOverrides.State.UNKNOWN, ImsStatus.UNREGISTERED)))
    }
    var isAuthorized by remember { mutableStateOf(false) }
    // Re-read permission state whenever a permission request completes.
    val permissionEpoch = (context as? MainActivity)?.permissionEpoch?.intValue ?: 0
    var hasPhonePermission by remember { mutableStateOf(CarrierOverrides.hasPhonePermission(context)) }
    var hasLocalNetwork by remember { mutableStateOf(Permissions.hasLocalNetwork(context)) }
    LaunchedEffect(permissionEpoch) {
        hasPhonePermission = CarrierOverrides.hasPhonePermission(context)
        hasLocalNetwork = Permissions.hasLocalNetwork(context)
    }

    fun showMessage(text: String) {
        scope.launch { snackbarHostState.showSnackbar(text) }
    }

    LaunchedEffect(Unit) {
        MainActivity.onAuthStatusChanged = { authEpoch++ }
        while (true) {
            slotStatuses = withContext(Dispatchers.IO) { readSlotStatuses(context) }
            hasPhonePermission = CarrierOverrides.hasPhonePermission(context)
            hasLocalNetwork = Permissions.hasLocalNetwork(context)
            delay(2000)
        }
    }

    // Auth check: re-run when the port changes or after pairing, and keep retrying every 5s
    // until authorized. Never runs per 1s tick, to avoid spamming new Kadb connections that
    // compete with the background IMS polling loop and any active BrokerInstrumentation command.
    LaunchedEffect(portInput, authEpoch) {
        val port = portInput.toIntOrNull()
        if (!isValidPort(port)) {
            isAuthorized = false
            return@LaunchedEffect
        }
        while (true) {
            isAuthorized = withContext(Dispatchers.IO) {
                try {
                    AdbKeys.await()
                    Kadb.create("127.0.0.1", port!!, 3000, 3000).use { kadb ->
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

    // Background IMS status polling through the shell (ImsQueryTool via app_process).
    // Only once ADB is authorized; before pairing every attempt would just fail the TLS handshake.
    LaunchedEffect(portInput, isAuthorized) {
        val port = portInput.toIntOrNull()
        if (!isValidPort(port) || !isAuthorized) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            var activeKadb: Kadb? = null
            try {
                while (true) {
                    // Pause while BrokerInstrumentation is running to avoid file conflicts
                    if (isInstrumenting.get()) {
                        delay(1000)
                        continue
                    }
                    try {
                        AdbKeys.await()
                        val kadb = activeKadb ?: Kadb.create("127.0.0.1", port!!, 5000, 5000).also { activeKadb = it }
                        val pathRes = kadb.shell("pm path com.svenuks.imsforpixel")
                        val path = pathRes.output.trim().substringAfter("package:")
                        if (pathRes.exitCode == 0 && path.isNotEmpty()) {
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

    /**
     * Launches BrokerInstrumentation in the background via ADB and waits for it to finish.
     * `am instrument` force-stops this package before starting the instrumentation, so this
     * process (and the UI) may be killed; in that case the broker's own notification reports
     * the result. If we survive, we wait for its done marker before re-enabling the buttons.
     */
    fun runBroker(clear: Boolean) {
        val port = portInput.toIntOrNull()
        if (!isValidPort(port)) {
            showMessage("请先开启无线调试")
            return
        }

        isApplying = true
        isInstrumenting.set(true)
        scope.launch {
            val doneFile = java.io.File(context.filesDir, "broker_done.txt")
            val startedAt = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) {
                try {
                    AdbKeys.await()
                    Kadb.create("127.0.0.1", port!!, 10000, 10000).use { kadb ->
                        // Detached on purpose: the shell session must outlive this process.
                        val response = kadb.shell("nohup ${INSTRUMENT_CMD.format(clear)} > /dev/null 2>&1 &")
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
                // A rejected key means pairing was lost; re-run the authorization check.
                if (isNotPairedError(error)) {
                    isAuthorized = false
                    authEpoch++
                }
                showMessage("${if (clear) "恢复" else "激活"}失败：${friendlyAdbError(error)}")
                return@launch
            }

            showMessage("已提交，约 30 秒内通知栏会显示结果")
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

    fun triggerApply() {
        // Reset clear flags to false to ensure the configuration overrides are applied
        CarrierOverrides.prefs(context).edit()
            .putBoolean("clear_slot_0", false)
            .putBoolean("clear_slot_1", false)
            .commit()
        runBroker(clear = false)
    }

    fun triggerRestore() {
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

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val background = MaterialTheme.colorScheme.surfaceContainer

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = background,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("IMS for Pixel") },
                subtitle = { Text("免 Root 开启 VoLTE · VoNR · Wi‑Fi 通话") },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = background,
                    scrolledContainerColor = background
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Warn when previously applied overrides have been wiped (e.g. after a system update)
            val lostSlots = slotStatuses.indices.filter { slotStatuses[it].config == CarrierOverrides.State.LOST }
            if (lostSlots.isNotEmpty()) {
                item(key = "lost") {
                    Banner(
                        icon = Icons.Filled.Warning,
                        text = "SIM ${lostSlots.joinToString("、") { (it + 1).toString() }} 的配置已失效，" +
                            "通常是系统更新后被清除。",
                        actionLabel = "重新激活",
                        onAction = { triggerApply() },
                        actionEnabled = !isApplying,
                        error = true
                    )
                }
            }
            if (!hasLocalNetwork) {
                item(key = "local-network") {
                    Banner(
                        icon = Icons.Filled.Info,
                        text = "需要「附近设备」权限，才能自动发现无线调试端口。",
                        actionLabel = "去授权",
                        onAction = {
                            (context as? MainActivity)?.requestPermissionOrOpenSettings(android.Manifest.permission.ACCESS_LOCAL_NETWORK)
                        }
                    )
                }
            }
            if (!hasPhonePermission) {
                item(key = "permission") {
                    Banner(
                        icon = Icons.Filled.Info,
                        text = "授予「电话」权限后，才能检测配置是否仍然生效。",
                        actionLabel = "去授权",
                        onAction = {
                            (context as? MainActivity)?.requestPermissionOrOpenSettings(android.Manifest.permission.READ_PHONE_STATE)
                        }
                    )
                }
            }

            val shownSlots = visibleSlots(slotStatuses)
            // Keep the selection on a slot that is actually shown (e.g. after a SIM is removed).
            val activeSlot = shownSlots.firstOrNull { it.slot == selectedSimSlot } ?: shownSlots.first()

            item(key = "status") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (status in shownSlots) {
                        SimStatusCard(status, Modifier.weight(1f))
                    }
                }
            }

            item(key = "features-header") { SectionHeader("通话功能") }
            item(key = "features") {
                FeatureSettings(
                    selected = activeSlot,
                    slots = shownSlots,
                    onSlotSelected = { selectedSimSlot = it },
                    configEpoch = configEpoch
                )
            }

            item(key = "connection-header") { SectionHeader("无线调试连接") }
            item(key = "connection") {
                ConnectionSection(
                    isAuthorized = isAuthorized,
                    hasLocalNetwork = hasLocalNetwork,
                    portInput = portInput,
                    onPortInputChange = { portInput = it }
                )
            }

            item(key = "actions") {
                ActionSection(
                    hasPort = portInput.isNotEmpty(),
                    isAuthorized = isAuthorized,
                    isApplying = isApplying,
                    onApply = { triggerApply() },
                    onRestore = { showRestoreDialog = true }
                )
            }
        }
    }

    if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            icon = { Icon(Icons.Filled.Restore, contentDescription = null) },
            title = { Text("恢复运营商默认配置？") },
            text = { Text("将清除本应用写入的所有运营商覆盖配置，并把各项开关恢复为默认值。") },
            confirmButton = {
                TextButton(onClick = {
                    showRestoreDialog = false
                    triggerRestore()
                }) { Text("恢复") }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 0.dp)
    )
}

@Composable
private fun Banner(
    icon: ImageVector,
    text: String,
    actionLabel: String,
    onAction: () -> Unit,
    actionEnabled: Boolean = true,
    error: Boolean = false
) {
    val container = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val content = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content)
    ) {
        Row(
            modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(16.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onAction, enabled = actionEnabled) { Text(actionLabel, color = content) }
        }
    }
}

private data class Badge(val text: String, val color: Color, val icon: ImageVector)

@Composable
private fun configBadge(state: CarrierOverrides.State): Badge {
    val scheme = MaterialTheme.colorScheme
    return when (state) {
        CarrierOverrides.State.APPLIED -> Badge("配置已生效", scheme.primary, Icons.Filled.CheckCircle)
        CarrierOverrides.State.LOST -> Badge("配置已失效", scheme.error, Icons.Filled.Warning)
        CarrierOverrides.State.DEFAULT -> Badge("运营商默认", scheme.onSurfaceVariant, Icons.Filled.Info)
        CarrierOverrides.State.NO_SIM -> Badge("未插卡", scheme.onSurfaceVariant, Icons.Filled.Info)
        CarrierOverrides.State.UNKNOWN -> Badge("配置状态未知", scheme.onSurfaceVariant, Icons.Filled.Info)
    }
}

@Composable
private fun imsBadge(status: ImsStatus): Badge {
    val scheme = MaterialTheme.colorScheme
    return when (status) {
        ImsStatus.REGISTERED -> Badge("IMS 已注册", scheme.primary, Icons.Filled.CheckCircle)
        ImsStatus.UNREGISTERED -> Badge("IMS 未注册", scheme.error, Icons.Filled.Error)
        ImsStatus.NO_SIM -> Badge("IMS —", scheme.onSurfaceVariant, Icons.Filled.Info)
    }
}

@Composable
private fun SimStatusCard(status: SlotStatus, modifier: Modifier = Modifier) {
    val noSim = !status.hasSim
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = if (noSim) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.primaryContainer,
                    contentColor = if (noSim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Icon(Icons.Filled.SimCard, contentDescription = null, modifier = Modifier.padding(8.dp).size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        if (noSim) "未检测到 SIM 卡" else status.title,
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (!noSim) {
                        Text(
                            status.kind,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (noSim) {
                Text(
                    "插入 SIM 卡或启用 eSIM 后即可激活",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                StatusLine(configBadge(status.config))
                Spacer(Modifier.height(4.dp))
                StatusLine(imsBadge(status.ims))
            }
        }
    }
}

@Composable
private fun StatusLine(badge: Badge) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(badge.icon, contentDescription = null, tint = badge.color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(badge.text, style = MaterialTheme.typography.labelLarge, color = badge.color)
    }
}

/**
 * One segment of an Android-Settings-style grouped list (Material 3 Expressive
 * [SegmentedListItem]). Clickable when [onClick] is set.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SegmentedItem(
    index: Int,
    count: Int,
    headline: String,
    supporting: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val shapes = ListItemDefaults.segmentedShapes(index = index, count = count)
    val colors = ListItemDefaults.segmentedColors()
    val leading: (@Composable () -> Unit)? = icon?.let { { Icon(it, contentDescription = null) } }
    val supportingContent: (@Composable () -> Unit)? = supporting?.let { { Text(it) } }
    if (onClick != null) {
        SegmentedListItem(
            onClick = onClick,
            shapes = shapes,
            leadingContent = leading,
            trailingContent = trailing,
            supportingContent = supportingContent,
            colors = colors
        ) { Text(headline) }
    } else {
        SegmentedListItem(
            shapes = shapes,
            leadingContent = leading,
            trailingContent = trailing,
            supportingContent = supportingContent,
            colors = colors
        ) { Text(headline) }
    }
}

/**
 * A segmented list item whose whole row toggles a switch, like Android Settings. Uses the
 * click overload (the toggleable one tints the row as "selected" and announces a checkbox)
 * and adds switch semantics itself.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SegmentedSwitchItem(
    index: Int,
    count: Int,
    headline: String,
    supporting: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    SegmentedListItem(
        onClick = { onCheckedChange(!checked) },
        modifier = Modifier.semantics {
            role = Role.Switch
            toggleableState = ToggleableState(checked)
        },
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { CheckSwitch(checked, onCheckedChange = null) },
        supportingContent = { Text(supporting) },
        colors = ListItemDefaults.segmentedColors()
    ) { Text(headline) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SegmentedColumn(content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) { content() }
}

@Composable
private fun CheckSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        thumbContent = if (checked) {
            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
        } else {
            null
        }
    )
}

private data class Feature(val key: String, val title: String, val description: String, val icon: ImageVector)

private val FEATURES = listOf(
    Feature("volte", "VoLTE 通话", "通过 4G/LTE 网络进行高清语音通话", Icons.Filled.Call),
    Feature("vonr", "5G 通话 (VoNR)", "在 5G 独立组网下进行语音通话", Icons.Filled.SignalCellularAlt),
    Feature("vowifi", "Wi‑Fi 通话", "信号不佳时通过 Wi‑Fi 拨打电话", Icons.Filled.WifiCalling3),
    Feature("wfc_roaming", "Wi‑Fi 通话漫游", "漫游时保持 Wi‑Fi 通话可用", Icons.Filled.TravelExplore),
    Feature("ss_ut", "补充业务 (UT)", "呼叫转移、呼叫等待等网络设置", Icons.AutoMirrored.Filled.PhoneForwarded),
)

@Composable
private fun FeatureSettings(
    selected: SlotStatus,
    slots: List<SlotStatus>,
    onSlotSelected: (Int) -> Unit,
    configEpoch: Int
) {
    val selectedSlot = selected.slot
    val context = LocalContext.current
    val prefs = remember { CarrierOverrides.prefs(context) }
    // Bumped on every toggle so dependent values (pending-change hint) recompute.
    var editEpoch by remember { mutableIntStateOf(0) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Only offer a SIM switcher when more than one SIM is actually in use.
        if (slots.size > 1) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                slots.forEachIndexed { index, slot ->
                    SegmentedButton(
                        selected = selectedSlot == slot.slot,
                        onClick = { onSlotSelected(slot.slot) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = slots.size),
                        icon = { SegmentedButtonDefaults.Icon(active = selectedSlot == slot.slot) {
                            Icon(Icons.Filled.SimCard, contentDescription = null, modifier = Modifier.size(SegmentedButtonDefaults.IconSize))
                        } }
                    ) {
                        Text(slot.title)
                    }
                }
            }
        }

        SegmentedColumn {
            FEATURES.forEachIndexed { index, feature ->
                val prefKey = "${feature.key}_slot_$selectedSlot"
                var checked by remember(selectedSlot, configEpoch, feature.key) {
                    mutableStateOf(prefs.getBoolean(prefKey, true))
                }
                fun toggle(value: Boolean) {
                    checked = value
                    prefs.edit().putBoolean(prefKey, value).putBoolean("clear_slot_$selectedSlot", false).commit()
                    editEpoch++
                }
                SegmentedSwitchItem(
                    index = index,
                    count = FEATURES.size,
                    headline = feature.title,
                    supporting = feature.description,
                    icon = feature.icon,
                    checked = checked,
                    onCheckedChange = ::toggle
                )
            }
        }

        // Hint when the toggles no longer match what was applied to this SIM.
        val pending = remember(selectedSlot, configEpoch, editEpoch, selected.config) {
            CarrierOverrides.isActivated(prefs, selectedSlot) &&
                prefs.getString("applied_sig_slot_$selectedSlot", null) !=
                CarrierOverrides.signature(CarrierOverrides.buildBundle(prefs, selectedSlot))
        }
        AnimatedVisibility(pending) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "设置已更改，点按「一键激活」后生效",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}

@Composable
private fun StatusIcon(ok: Boolean, pendingColor: Color = MaterialTheme.colorScheme.error) {
    Icon(
        imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.Error,
        contentDescription = if (ok) "正常" else "未完成",
        tint = if (ok) MaterialTheme.colorScheme.primary else pendingColor
    )
}

@Composable
private fun ConnectionSection(
    isAuthorized: Boolean,
    hasLocalNetwork: Boolean,
    portInput: String,
    onPortInputChange: (String) -> Unit
) {
    val context = LocalContext.current
    var isWifiConnected by remember { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }

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

        val caps = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        isWifiConnected = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true

        onDispose {
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (e: Exception) {}
        }
    }

    // Auto-discover the connect and pairing ports via mDNS (Network Service Discovery).
    // On Android 17+ this needs ACCESS_LOCAL_NETWORK; restart discovery once it's granted.
    DisposableEffect(hasLocalNetwork) {
        if (!hasLocalNetwork) return@DisposableEffect onDispose {}
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

        fun listener(serviceTypeFragment: String, onPort: (Int) -> Unit) = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.e("LocalAdb", "Start $serviceTypeFragment discovery failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.e("LocalAdb", "Stop $serviceTypeFragment discovery failed: $errorCode")
            }
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.contains(serviceTypeFragment)) return
                @Suppress("DEPRECATION")
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                        Log.e("LocalAdb", "Resolve $serviceTypeFragment failed: $errorCode")
                    }
                    override fun onServiceResolved(resolvedServiceInfo: NsdServiceInfo) {
                        Log.d("LocalAdb", "Resolved $serviceTypeFragment port: ${resolvedServiceInfo.port}")
                        onPort(resolvedServiceInfo.port)
                    }
                })
            }
        }

        val connectListener = listener("adb-tls-connect") { onPortInputChange(it.toString()) }
        val pairingListener = listener("adb-tls-pairing") { MainActivity.pairingPort = it }
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
            try { nsdManager.stopServiceDiscovery(connectListener) } catch (e: Exception) {}
            try { nsdManager.stopServiceDiscovery(pairingListener) } catch (e: Exception) {}
        }
    }

    val debugSupporting = when {
        isAuthorized -> "已配对并授权"
        !hasLocalNetwork && portInput.isEmpty() -> "需要「附近设备」权限才能自动发现端口"
        portInput.isNotEmpty() -> "尚未配对 · 点按后选择「使用配对码配对设备」，在通知栏输入配对码"
        else -> "点按前往开启无线调试并配对"
    }

    SegmentedColumn {
        SegmentedItem(
            index = 0,
            count = 3,
            headline = "Wi‑Fi",
            supporting = if (isWifiConnected) "已连接" else "无线调试需要先连接 Wi‑Fi 网络",
            icon = Icons.Filled.Wifi,
            trailing = { StatusIcon(isWifiConnected) }
        )
        SegmentedItem(
            index = 1,
            count = 3,
            headline = "无线调试",
            supporting = debugSupporting,
            icon = Icons.Filled.Adb,
            onClick = {
                (context as? MainActivity)?.requestNotificationPermissionAndShow()
                try {
                    context.startActivity(Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"))
                } catch (e: Exception) {
                    try {
                        context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                    } catch (e2: Exception) {
                        Log.w("LocalAdb", "Developer options not found", e2)
                    }
                }
            },
            trailing = {
                if (isAuthorized) {
                    StatusIcon(true)
                } else {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }
        )
        val rotation by animateFloatAsState(if (showAdvanced) 180f else 0f, label = "expand")
        SegmentedItem(
            index = 2,
            count = 3,
            headline = "高级设置",
            supporting = if (portInput.isNotEmpty()) "连接端口 $portInput" else "手动指定连接端口",
            icon = Icons.Filled.Tune,
            onClick = { showAdvanced = !showAdvanced },
            trailing = { Icon(Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.rotate(rotation)) }
        )
        AnimatedVisibility(showAdvanced) {
            Box(Modifier.padding(top = 8.dp)) {
                OutlinedTextField(
                    value = portInput,
                    onValueChange = { value -> onPortInputChange(value.filter(Char::isDigit).take(5)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("连接端口 (ADB Port)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ActionSection(
    hasPort: Boolean,
    isAuthorized: Boolean,
    isApplying: Boolean,
    onApply: () -> Unit,
    onRestore: () -> Unit
) {
    Column(
        modifier = Modifier.padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onApply,
            enabled = isAuthorized && !isApplying,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            if (isApplying) {
                LoadingIndicator(
                    modifier = Modifier.size(28.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(12.dp))
                Text("正在应用…", style = MaterialTheme.typography.titleMedium)
            } else {
                Icon(Icons.Filled.Bolt, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        isAuthorized -> "一键激活"
                        hasPort -> "请先完成配对"
                        else -> "等待开启无线调试…"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
        OutlinedButton(
            onClick = onRestore,
            enabled = isAuthorized && !isApplying,
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Icon(Icons.Filled.Restore, contentDescription = null, modifier = Modifier.size(ButtonIconSize))
            Spacer(Modifier.width(8.dp))
            Text("恢复运营商默认")
        }
        Text(
            "激活时应用可能会自动关闭，这是正常现象，结果会通过通知显示。系统更新后需要重新激活。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
    }
}

private val ButtonIconSize = 18.dp
