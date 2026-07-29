package com.vitals.optimizer

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Bundle
import android.os.StatFs
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                VitalsApp(this)
            }
        }
    }
}

data class StorageStats(val totalGB: Double, val freeGB: Double, val ownCacheMB: Double)

fun readStorage(context: Context): StorageStats {
    val stat = StatFs(context.filesDir.path)
    val total = stat.blockCountLong * stat.blockSizeLong
    val free = stat.availableBlocksLong * stat.blockSizeLong
    val cacheBytes = dirSize(context.cacheDir)
    return StorageStats(
        totalGB = total / 1_073_741_824.0,
        freeGB = free / 1_073_741_824.0,
        ownCacheMB = cacheBytes / 1_048_576.0
    )
}

fun dirSize(dir: File): Long {
    if (!dir.exists()) return 0
    var size = 0L
    dir.listFiles()?.forEach {
        size += if (it.isDirectory) dirSize(it) else it.length()
    }
    return size
}

data class RamStats(val totalGB: Double, val availGB: Double, val lowMemory: Boolean)

fun readRam(context: Context): RamStats {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val info = ActivityManager.MemoryInfo()
    am.getMemoryInfo(info)
    return RamStats(
        totalGB = info.totalMem / 1_073_741_824.0,
        availGB = info.availMem / 1_073_741_824.0,
        lowMemory = info.lowMemory
    )
}

data class BatteryStats(val percent: Int, val isCharging: Boolean, val temperatureC: Float)

fun readBattery(context: Context): BatteryStats {
    val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    val percent = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    val charging = bm.isCharging
    return BatteryStats(percent = percent, isCharging = charging, temperatureC = 0f)
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VitalsApp(context: Context) {
    var tab by remember { mutableStateOf(0) }
    val tabs = listOf("Storage", "RAM", "Battery")

    var storage by remember { mutableStateOf(readStorage(context)) }
    var ram by remember { mutableStateOf(readRam(context)) }
    var battery by remember { mutableStateOf(readBattery(context)) }
    var lastFreedMB by remember { mutableStateOf<Double?>(null) }

    fun refresh() {
        storage = readStorage(context)
        ram = readRam(context)
        battery = readBattery(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Vitals", fontWeight = FontWeight.Bold) })
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, label ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
                }
            }

            Column(Modifier.padding(20.dp).fillMaxSize()) {
                when (tab) {
                    0 -> StorageTab(storage, lastFreedMB, onClean = {
                        val before = storage.ownCacheMB
                        context.cacheDir.deleteRecursively()
                        refresh()
                        lastFreedMB = before - storage.ownCacheMB
                    })
                    1 -> RamTab(ram, onRefresh = { refresh() })
                    2 -> BatteryTab(battery, onRefresh = { refresh() })
                }
            }
        }
    }
}

@Composable
fun StatCard(title: String, value: String, subtitle: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Text(value, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun StorageTab(stats: StorageStats, lastFreedMB: Double?, onClean: () -> Unit) {
    val usedGB = stats.totalGB - stats.freeGB
    val usedPct = ((usedGB / stats.totalGB) * 100).toInt()

    StatCard(
        title = "Storage used",
        value = "$usedPct%",
        subtitle = "${"%.1f".format(usedGB)} GB used of ${"%.0f".format(stats.totalGB)} GB · ${"%.1f".format(stats.freeGB)} GB free"
    )
    Spacer(Modifier.height(16.dp))
    Text(
        "This app's own cache: ${"%.1f".format(stats.ownCacheMB)} MB",
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(16.dp))
    Button(onClick = onClean, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.Delete, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Clear this app's cache")
    }
    lastFreedMB?.let {
        Spacer(Modifier.height(10.dp))
        Text("Freed ${"%.1f".format(it)} MB", color = MaterialTheme.colorScheme.primary)
    }
    Spacer(Modifier.height(20.dp))
    Text(
        "Android doesn't allow third-party apps to clear other apps' cache or storage — " +
            "that's an OS-level restriction on every device since Android 5.0. For other apps, use " +
            "Settings → Storage → [app] → Clear cache.",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
fun RamTab(stats: RamStats, onRefresh: () -> Unit) {
    val usedGB = stats.totalGB - stats.availGB
    val usedPct = ((usedGB / stats.totalGB) * 100).toInt()

    StatCard(
        title = "Memory used",
        value = "$usedPct%",
        subtitle = "${"%.1f".format(usedGB)} GB used of ${"%.1f".format(stats.totalGB)} GB · ${"%.1f".format(stats.availGB)} GB free"
    )
    Spacer(Modifier.height(16.dp))
    if (stats.lowMemory) {
        Text("System reports low memory right now.", color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
    }
    Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.Memory, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Refresh reading")
    }
    Spacer(Modifier.height(20.dp))
    Text(
        "Since Android 5.0, apps can no longer see or close other apps' background processes — " +
            "only the system's own memory manager can do that. Android already reclaims memory " +
            "automatically as needed, which is why standalone \"RAM booster\" claims from other " +
            "cleaner apps don't actually do anything useful on modern Android.",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
fun BatteryTab(stats: BatteryStats, onRefresh: () -> Unit) {
    StatCard(
        title = "Battery level",
        value = "${stats.percent}%",
        subtitle = if (stats.isCharging) "Charging" else "On battery"
    )
    Spacer(Modifier.height(16.dp))
    Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.BatteryChargingFull, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Refresh reading")
    }
    Spacer(Modifier.height(20.dp))
    Text(
        "For real battery savings, Android's built-in Settings → Battery → Battery Saver / " +
            "Adaptive Battery already restricts background drain per app better than a third-party " +
            "app is permitted to — apps can no longer force-restrict each other directly.",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
