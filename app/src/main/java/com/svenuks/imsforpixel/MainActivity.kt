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
import androidx.compose.ui.res.stringResource
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
        // Callback: notified when BrokerInstrumentation finishes (activate or restore)
        var onInstrumentDone: ((isActivate: Boolean, success: Boolean) -> Unit)? = null
    }

    fun showImsStatusNotification(isActivate: Boolean) {
        val channelId = "ims_status_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, getString(R.string.notification_channel_ims_status), NotificationManager.IMPORTANCE_HIGH)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
        // Read actual IMS status from files
        val slot0 = try { java.io.File(filesDir, "ims_status_0.txt").readText().trim().toBoolean() } catch (e: Exception) { false }
        val slot1 = try { java.io.File(filesDir, "ims_status_1.txt").readText().trim().toBoolean() } catch (e: Exception) { false }
        val anyRegistered = slot0 || slot1
        val title = if (isActivate) {
            if (anyRegistered) getString(R.string.notification_title_volte_success) else getString(R.string.notification_title_volte_done)
        } else {
            getString(R.string.notification_title_config_restored)
        }
        val body = if (isActivate) {
            buildString {
                if (slot0) append(getString(R.string.notification_sim1_registered)) else append(getString(R.string.notification_sim1_not_registered))
                if (slot1) append(getString(R.string.notification_sim2_registered)) else append(getString(R.string.notification_sim2_not_registered))
            }
        } else {
            getString(R.string.notification_config_cleared)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(203, notification)
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 102)
            }
        }

        setContent {
            val recheckSignal = remember { mutableStateOf(System.currentTimeMillis()) }
            MainScreen(recheckSignal = recheckSignal)
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
                Toast.makeText(this, getString(R.string.notification_permission_required), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "pairing_channel",
                getString(R.string.notification_channel_pairing),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notification_channel_pairing_desc)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun showPairingNotification() {
        createNotificationChannel()
        
        val replyLabel = getString(R.string.notification_pairing_reply_label)
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
            getString(R.string.notification_pairing_send_action),
            replyPendingIntent
        )
            .addRemoteInput(remoteInput)
            .build()
            
        val notification = NotificationCompat.Builder(this, "pairing_channel")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.notification_channel_pairing))
            .setContentText(getString(R.string.notification_pairing_prompt))
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
            .setContentTitle(getString(R.string.notification_channel_pairing))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        manager.notify(202, notification)
    }

    private fun handleNotificationPairing(code: String) {
        val port = pairingPort
        if (port == null) {
            Toast.makeText(this, getString(R.string.toast_pairing_port_missing), Toast.LENGTH_LONG).show()
            showPairingStatusNotification(getString(R.string.notification_pairing_failed_port))
            return
        }
        
        Toast.makeText(this, getString(R.string.toast_pairing_in_progress), Toast.LENGTH_SHORT).show()
        showPairingStatusNotification(getString(R.string.notification_pairing_port_progress, port))

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
                        Toast.makeText(this@MainActivity, getString(R.string.toast_pairing_success), Toast.LENGTH_LONG).show()
                        showPairingStatusNotification(getString(R.string.notification_pairing_success))
                        onAuthStatusChanged?.invoke()
                    },
                    onFailure = { error ->
                        Toast.makeText(this@MainActivity, getString(R.string.toast_pairing_failed, error.message ?: ""), Toast.LENGTH_LONG).show()
                        showPairingStatusNotification(getString(R.string.notification_pairing_failed, error.message ?: ""))
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(recheckSignal: MutableState<Long> = remember { mutableStateOf(System.currentTimeMillis()) }) {
    var selectedSimSlot by remember { mutableStateOf(0) }
    var portInput by remember { mutableStateOf("") }
    var isApplying by remember { mutableStateOf(false) }
    // True while am instrument is running — pauses background ImsQueryTool polling
    val isInstrumenting = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    val context = LocalContext.current
    
    LaunchedEffect(Unit) {
        MainActivity.onAuthStatusChanged = {
            recheckSignal.value = System.currentTimeMillis()
        }
        MainActivity.onInstrumentDone = { isActivate, _ ->
            (context as? MainActivity)?.showImsStatusNotification(isActivate)
            recheckSignal.value = System.currentTimeMillis()
        }
        while (true) {
            delay(1000)
            recheckSignal.value = System.currentTimeMillis()
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
                                        val lines = queryRes.output.lines()
                                        for (line in lines) {
                                            if (line.startsWith("RESULT:")) {
                                                val parts = line.split(":")
                                                if (parts.size == 3) {
                                                    val slot = parts[1].toIntOrNull() ?: continue
                                                    val isImsRegistered = parts[2].trim().toBoolean()
                                                    val statusFile = java.io.File(context.filesDir, "ims_status_$slot.txt")
                                                    statusFile.writeText(isImsRegistered.toString())
                                                    Log.d("LocalAdb", "Updated slot $slot IMS status: $isImsRegistered")
                                                }
                                            }
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

    fun triggerManualApply() {
        val port = portInput.toIntOrNull()
        if (port == null || port <= 0 || port > 65535) {
            Toast.makeText(context, context.getString(R.string.toast_enable_wireless_debugging), Toast.LENGTH_SHORT).show()
            return
        }

        // Reset clear flags to false to ensure the configuration overrides are applied
        val prefs = context.getSharedPreferences("volte_settings", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("clear_slot_0", false)
            .putBoolean("clear_slot_1", false)
            .commit()

        isApplying = true
        isInstrumenting.set(true)
        scope.launch {
            // Run am instrument synchronously (no nohup/&) so we wait for the real result.
            // BrokerInstrumentation polls IMS for up to 30s internally; timeout set to 90s.
            val result = withContext(Dispatchers.IO) {
                try {
                    Kadb.create("127.0.0.1", port, 90000, 90000).use { kadb ->
                        val cmd = "nohup am instrument -w -e clear false com.svenuks.imsforpixel/com.svenuks.imsforpixel.BrokerInstrumentation > /dev/null 2>&1 &"
                        val response = kadb.shell(cmd)
                        if (response.exitCode == 0) {
                            Result.success(response.output)
                        } else {
                            Result.failure(Exception("Exit code ${response.exitCode}: ${response.output}"))
                        }
                    }
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            isApplying = false
            isInstrumenting.set(false)
            result.fold(
                onSuccess = {
                    MainActivity.onInstrumentDone?.invoke(true, true)
                },
                onFailure = { error ->
                    isInstrumenting.set(false)
                    Toast.makeText(context, context.getString(R.string.toast_activation_failed, error.message ?: ""), Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    fun triggerManualRestore() {
        val port = portInput.toIntOrNull()
        if (port == null || port <= 0 || port > 65535) {
            Toast.makeText(context, context.getString(R.string.toast_enable_wireless_debugging), Toast.LENGTH_SHORT).show()
            return
        }

        isApplying = true
        isInstrumenting.set(true)
        scope.launch {
            
            // Reset prefs for both slots
            val prefs = context.getSharedPreferences("volte_settings", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("clear_slot_0", true)
                .putBoolean("clear_slot_1", true)
                .putBoolean("volte_slot_0", true)
                .putBoolean("volte_slot_1", true)
                .putBoolean("vonr_slot_0", true)
                .putBoolean("vonr_slot_1", true)
                .putBoolean("vowifi_slot_0", true)
                .putBoolean("vowifi_slot_1", true)
                .putBoolean("wfc_roaming_slot_0", true)
                .putBoolean("wfc_roaming_slot_1", true)
                .putBoolean("ss_ut_slot_0", true)
                .putBoolean("ss_ut_slot_1", true)
                .putBoolean("show_ims_slot_0", true)
                .putBoolean("show_ims_slot_1", true)
                .putBoolean("allow_apn_slot_0", false)
                .putBoolean("allow_apn_slot_1", false)
                .putBoolean("cross_sim_slot_0", false)
                .putBoolean("cross_sim_slot_1", false)
                .commit()

            val result = withContext(Dispatchers.IO) {
                try {
                    Kadb.create("127.0.0.1", port, 90000, 90000).use { kadb ->
                        val cmd = "nohup am instrument -w -e clear true com.svenuks.imsforpixel/com.svenuks.imsforpixel.BrokerInstrumentation > /dev/null 2>&1 &"
                        val response = kadb.shell(cmd)
                        if (response.exitCode == 0) {
                            Result.success(response.output)
                        } else {
                            Result.failure(Exception("Exit code ${response.exitCode}: ${response.output}"))
                        }
                    }
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            isApplying = false
            isInstrumenting.set(false)
            result.fold(
                onSuccess = {
                    MainActivity.onInstrumentDone?.invoke(false, true)
                },
                onFailure = { error ->
                    isInstrumenting.set(false)
                    Toast.makeText(context, context.getString(R.string.toast_restore_failed, error.message ?: ""), Toast.LENGTH_LONG).show()
                }
            )
        }
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
                        text = stringResource(R.string.app_subtitle),
                        color = TextMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }

                // SIM selector tabs
                item {
                    SimSelectorTabs(
                        selectedSlot = selectedSimSlot,
                        onSlotSelected = { selectedSimSlot = it }
                    )
                }

                // Config Panel for Selected Slot
                item {
                    ConfigPanel(
                        slotIndex = selectedSimSlot,
                        recheckSignal = recheckSignal,
                        onConfigChanged = {}
                    )
                }

                // Local Apply Card (Wireless Debugging self-connect)
                item {
                    LocalAdbCard(
                        recheckSignal = recheckSignal,
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
fun SimSelectorTabs(
    selectedSlot: Int,
    onSlotSelected: (Int) -> Unit
) {
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
            text = { Text(stringResource(R.string.sim_slot_1), fontWeight = FontWeight.Bold) }
        )
        Tab(
            selected = selectedSlot == 1,
            onClick = { onSlotSelected(1) },
            text = { Text(stringResource(R.string.sim_slot_2), fontWeight = FontWeight.Bold) }
        )
    }
}

@Composable
fun ConfigPanel(
    slotIndex: Int,
    recheckSignal: MutableState<Long>,
    onConfigChanged: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember(slotIndex) {
        context.getSharedPreferences("volte_settings", Context.MODE_PRIVATE)
    }

    var voLteEnabled by remember(slotIndex) { mutableStateOf(prefs.getBoolean("volte_slot_$slotIndex", true)) }
    var voNrEnabled by remember(slotIndex) { mutableStateOf(prefs.getBoolean("vonr_slot_$slotIndex", true)) }
    var voWifiEnabled by remember(slotIndex) { mutableStateOf(prefs.getBoolean("vowifi_slot_$slotIndex", true)) }
    var crossSimEnabled by remember(slotIndex) { mutableStateOf(prefs.getBoolean("cross_sim_slot_$slotIndex", false)) } // Default false
    var wfcRoamingEnabled by remember(slotIndex) { mutableStateOf(prefs.getBoolean("wfc_roaming_slot_$slotIndex", true)) }
    var ssUtEnabled by remember(slotIndex) { mutableStateOf(prefs.getBoolean("ss_ut_slot_$slotIndex", true)) }
    var allowApnEdit by remember(slotIndex) { mutableStateOf(prefs.getBoolean("allow_apn_slot_$slotIndex", false)) } // Default false
    
    // Load cached IMS registration status from status file updated by PC run
    var imsRegistered by remember(slotIndex) { mutableStateOf(false) }
    var configApplied by remember(slotIndex) { mutableStateOf(false) }
    
    val signalValue = recheckSignal.value
    LaunchedEffect(slotIndex, signalValue) {
        val statusFile = java.io.File(context.filesDir, "ims_status_$slotIndex.txt")
        imsRegistered = if (statusFile.exists()) {
            try {
                statusFile.readText().trim().toBoolean()
            } catch (e: Exception) {
                prefs.getBoolean("ims_registered_slot_$slotIndex", false)
            }
        } else {
            prefs.getBoolean("ims_registered_slot_$slotIndex", false)
        }

        val appliedFile = java.io.File(context.filesDir, "config_applied_$slotIndex.txt")
        configApplied = if (appliedFile.exists()) {
            try {
                appliedFile.readText().trim().toBoolean()
            } catch (e: Exception) {
                false
            }
        } else {
            false
        }
    }

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
                    text = stringResource(R.string.config_panel_title, slotIndex + 1),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = TextLight
                )
                
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (configApplied) AccentGreen.copy(alpha = 0.15f) else BorderColor.copy(alpha = 0.3f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (configApplied) stringResource(R.string.badge_config_applied) else stringResource(R.string.badge_config_default),
                            color = if (configApplied) AccentGreen else TextMuted,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 10.sp
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (imsRegistered) AccentGreen.copy(alpha = 0.15f) else AccentRed.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (imsRegistered) stringResource(R.string.badge_ims_registered) else stringResource(R.string.badge_ims_not_registered),
                            color = if (imsRegistered) AccentGreen else AccentRed,
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
            ToggleRow(stringResource(R.string.toggle_volte_title), stringResource(R.string.toggle_volte_desc), voLteEnabled) { 
                voLteEnabled = it
                prefs.edit().putBoolean("volte_slot_$slotIndex", it).putBoolean("clear_slot_$slotIndex", false).commit()
                onConfigChanged()
            }
            ToggleRow(stringResource(R.string.toggle_vonr_title), stringResource(R.string.toggle_vonr_desc), voNrEnabled) { 
                voNrEnabled = it
                prefs.edit().putBoolean("vonr_slot_$slotIndex", it).putBoolean("clear_slot_$slotIndex", false).commit()
                onConfigChanged()
            }
            ToggleRow(stringResource(R.string.toggle_vowifi_title), stringResource(R.string.toggle_vowifi_desc), voWifiEnabled) { 
                voWifiEnabled = it
                prefs.edit().putBoolean("vowifi_slot_$slotIndex", it).putBoolean("clear_slot_$slotIndex", false).commit()
                onConfigChanged()
            }
            ToggleRow(stringResource(R.string.toggle_wfc_roaming_title), stringResource(R.string.toggle_wfc_roaming_desc), wfcRoamingEnabled) { 
                wfcRoamingEnabled = it
                prefs.edit().putBoolean("wfc_roaming_slot_$slotIndex", it).putBoolean("clear_slot_$slotIndex", false).commit()
                onConfigChanged()
            }
            ToggleRow(stringResource(R.string.toggle_ss_ut_title), stringResource(R.string.toggle_ss_ut_desc), ssUtEnabled) { 
                ssUtEnabled = it
                prefs.edit().putBoolean("ss_ut_slot_$slotIndex", it).putBoolean("clear_slot_$slotIndex", false).commit()
                onConfigChanged()
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
    recheckSignal: MutableState<Long>,
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
    var slot0Active by remember { mutableStateOf(false) }
    var slot1Active by remember { mutableStateOf(false) }
    var slot0Applied by remember { mutableStateOf(false) }
    var slot1Applied by remember { mutableStateOf(false) }
    var isWifiConnected by remember { mutableStateOf(false) }

    val signalValue = recheckSignal.value
    // Auth check: only re-run when portInput changes, NOT on every 1s signal tick.
    // Running every second would spam new Kadb connections and compete with the
    // background IMS polling loop and any active BrokerInstrumentation command.
    LaunchedEffect(portInput) {
        val port = portInput.toIntOrNull()
        if (port != null && port > 0 && port <= 65535) {
            withContext(Dispatchers.IO) {
                try {
                    Kadb.create("127.0.0.1", port, 3000, 3000).use { kadb ->
                        val response = kadb.shell("echo 1")
                        isAuthorized = (response.exitCode == 0)
                    }
                } catch (e: Exception) {
                    isAuthorized = false
                }
            }
        } else {
            isAuthorized = false
        }
    }

    LaunchedEffect(signalValue) {
        slot0Active = try {
            java.io.File(context.filesDir, "ims_status_0.txt").readText().trim().toBoolean()
        } catch (e: Exception) {
            false
        }
        slot1Active = try {
            java.io.File(context.filesDir, "ims_status_1.txt").readText().trim().toBoolean()
        } catch (e: Exception) {
            false
        }
        slot0Applied = try {
            java.io.File(context.filesDir, "config_applied_0.txt").readText().trim().toBoolean()
        } catch (e: Exception) {
            false
        }
        slot1Applied = try {
            java.io.File(context.filesDir, "config_applied_1.txt").readText().trim().toBoolean()
        } catch (e: Exception) {
            false
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
                    stringResource(R.string.card_activation_title),
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
                    Text(stringResource(R.string.step1_title), fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = TextLight)
                    Text(stringResource(R.string.step1_desc), fontSize = 10.sp, color = TextMuted)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isWifiConnected) AccentGreen.copy(alpha = 0.15f) else AccentRed.copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (isWifiConnected) stringResource(R.string.status_connected) else stringResource(R.string.status_not_connected),
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
                    Text(stringResource(R.string.step2_title), fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = TextLight)
                    Text(
                        text = if (isAuthorized) stringResource(R.string.step2_status_authorized) else if (portInput.isNotEmpty()) stringResource(R.string.step2_status_needs_pairing) else stringResource(R.string.step2_status_prompt),
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
                        text = if (isAuthorized) stringResource(R.string.badge_authorized) else if (portInput.isNotEmpty()) stringResource(R.string.badge_unpaired) else stringResource(R.string.status_not_connected),
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
                            Toast.makeText(context, context.getString(R.string.toast_developer_options_not_found), Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextLight),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor)
            ) {
                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.btn_open_wireless_debugging), fontSize = 11.sp)
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = BorderColor)
            Spacer(modifier = Modifier.height(12.dp))

            // Step 3: Activation & Status check
            Text(stringResource(R.string.step3_title), fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = TextLight)
            Spacer(modifier = Modifier.height(4.dp))
            Text(stringResource(R.string.step3_desc), fontSize = 10.sp, color = TextMuted)
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
                        Text(stringResource(R.string.sim1_config_title), fontSize = 10.sp, color = TextMuted)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (slot0Applied) stringResource(R.string.status_app_optimized) else stringResource(R.string.status_system_default),
                            color = if (slot0Applied) AccentGreen else TextMuted,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(1.dp))
                        Text(
                            text = if (slot0Active) stringResource(R.string.badge_ims_registered) else stringResource(R.string.badge_ims_not_registered),
                            color = if (slot0Active) AccentGreen else AccentRed,
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
                        Text(stringResource(R.string.sim2_config_title), fontSize = 10.sp, color = TextMuted)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (slot1Applied) stringResource(R.string.status_app_optimized) else stringResource(R.string.status_system_default),
                            color = if (slot1Applied) AccentGreen else TextMuted,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(1.dp))
                        Text(
                            text = if (slot1Active) stringResource(R.string.badge_ims_registered) else stringResource(R.string.badge_ims_not_registered),
                            color = if (slot1Active) AccentGreen else AccentRed,
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
                            if (hasResolvedPort) stringResource(R.string.btn_activate) else stringResource(R.string.btn_waiting),
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
                        stringResource(R.string.btn_restore),
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.hint_ims_update),
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
                    text = if (showManualPorts) stringResource(R.string.toggle_hide_advanced) else stringResource(R.string.toggle_show_advanced),
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
                    label = { Text(stringResource(R.string.label_adb_port), fontSize = 9.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = LocalTextStyle.current.copy(fontSize = 11.sp)
                )
            }
        }
    }
}
