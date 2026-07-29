package com.vitals.optimizer

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Bundle
import android.os.StatFs
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sd
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlin.math.roundToInt

// ---- Colors ---------------------------------------------------------------

val BgColor = Color(0xFF12151B)
val SurfaceColor = Color(0xFF1B2029)
val SurfaceRaised = Color(0xFF232935)
val BorderColor = Color(0xFF2B3240)
val TextPrimary = Color(0xFFEDEFF3)
val TextDim = Color(0xFF8A93A3)
val AccentTeal = Color(0xFF5CE1D0)
val WarnAmber = Color(0xFFF5B942)
val CritRed = Color(0xFFF0715A)

fun statusColor(pct: Int, invert: Boolean): Color {
    val bad = if (invert) pct > 85 else pct < 25
    val warn = if (invert) pct > 65 else pct < 50
    return when {
        bad -> CritRed
        warn -> WarnAmber
        else -> AccentTeal
    }
}

private val VitalsDarkScheme = darkColorScheme(
    background = BgColor,
    surface = SurfaceColor,
    primary = AccentTeal,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = VitalsDarkScheme) {
                VitalsApp(this)
            }
        }
    }
}

// ---- Real device readings ----------------------------------------------

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

// ---- Per-app storage, with real uninstall -------------------------------

data class AppUsage(val packageName: String, val label: String, val totalBytes: Long, val isSystem: Boolean)

fun hasUsageAccess(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
    val mode = appOps.checkOpNoThrow(
        android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
        android.os.Process.myUid(),
        context.packageName
    )
    return mode == android.app.AppOpsManager.MODE_ALLOWED
}

fun openUsageAccessSettings(context: Context) {
    context.startActivity(
        android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

fun listAppStorage(context: Context): List<AppUsage> {
    val pm = context.packageManager
    val ssm = context.getSystemService(Context.STORAGE_STATS_SERVICE) as android.app.usage.StorageStatsManager
    val user = android.os.Process.myUserHandle()
    val apps = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
    val result = mutableListOf<AppUsage>()
    for (app in apps) {
        try {
            val stats = ssm.queryStatsForPackage(app.storageUuid, app.packageName, user)
            val total = stats.appBytes + stats.cacheBytes + stats.dataBytes
            val isSystem = (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            result.add(
                AppUsage(
                    packageName = app.packageName,
                    label = pm.getApplicationLabel(app).toString(),
                    totalBytes = total,
                    isSystem = isSystem
                )
            )
        } catch (e: Exception) {
            // Some packages can throw on query — skip them.
        }
    }
    return result.filter { it.totalBytes > 0 }.sortedByDescending { it.totalBytes }
}

fun requestUninstall(context: Context, packageName: String) {
    val intent = android.content.Intent(
        android.content.Intent.ACTION_DELETE,
        android.net.Uri.parse("package:$packageName")
    )
    context.startActivity(intent)
}

// ---- UI ------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VitalsApp(context: Context) {
    var tab by remember { mutableStateOf(0) }

    var storage by remember { mutableStateOf(readStorage(context)) }
    var ram by remember { mutableStateOf(readRam(context)) }
    var battery by remember { mutableStateOf(readBattery(context)) }
    var lastFreedMB by remember { mutableStateOf<Double?>(null) }

    fun refresh() {
        storage = readStorage(context)
        ram = readRam(context)
        battery = readBattery(context)
    }

    val storagePct = (((storage.totalGB - storage.freeGB) / storage.totalGB) * 100).roundToInt()
    val ramPct = (((ram.totalGB - ram.availGB) / ram.totalGB) * 100).roundToInt()
    val health = ((100 - storagePct) * 0.34 + battery.percent * 0.33 + (100 - ramPct) * 0.33).roundToInt()

    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = { Text("Vitals", fontWeight = FontWeight.Bold, color = TextPrimary) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgColor)
            )
        },
        bottomBar = {
            NavigationBar(containerColor = SurfaceColor) {
                val items = listOf(
                    Triple("Overview", Icons.Filled.Speed, 0),
                    Triple("Storage", Icons.Filled.Sd, 1),
                    Triple("Apps", Icons.Filled.Apps, 4),
                    Triple("RAM", Icons.Filled.Memory, 2),
                    Triple("Battery", Icons.Filled.BatteryChargingFull, 3),
                )
                items.forEach { (label, icon, index) ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label, fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AccentTeal,
                            selectedTextColor = AccentTeal,
                            unselectedIconColor = TextDim,
                            unselectedTextColor = TextDim,
                            indicatorColor = SurfaceRaised
                        )
                    )
                }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .fillMaxSize()
        ) {
            when (tab) {
                0 -> OverviewTab(health, storagePct, battery.percent, ramPct)
                1 -> StorageTab(storage, lastFreedMB, onClean = {
                    val before = storage.ownCacheMB
                    context.cacheDir.deleteRecursively()
                    refresh()
                    lastFreedMB = before - storage.ownCacheMB
                })
                2 -> RamTab(ram, onRefresh = { refresh() })
                3 -> BatteryTab(battery, onRefresh = { refresh() })
                4 -> AppsTab(context)
            }
        }
    }
}

@Composable
fun HealthRing(score: Int, size: androidx.compose.ui.unit.Dp = 168.dp, stroke: androidx.compose.ui.unit.Dp = 12.dp) {
    val color = statusColor(score, invert = false)
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size)) {
        Canvas(modifier = Modifier.size(size)) {
            val strokePx = stroke.toPx()
            drawArc(
                color = SurfaceRaised,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokePx, cap = StrokeCap.Round),
                size = Size(this.size.width - strokePx, this.size.height - strokePx),
                topLeft = androidx.compose.ui.geometry.Offset(strokePx / 2, strokePx / 2)
            )
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * (score / 100f),
                useCenter = false,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokePx, cap = StrokeCap.Round),
                size = Size(this.size.width - strokePx, this.size.height - strokePx),
                topLeft = androidx.compose.ui.geometry.Offset(strokePx / 2, strokePx / 2)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$score", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = TextPrimary, fontFamily = FontFamily.Monospace)
            Text("DEVICE HEALTH", fontSize = 10.sp, color = TextDim, letterSpacing = 0.5.sp)
        }
    }
}

@Composable
fun MiniStat(label: String, value: String, pct: Int, invert: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceColor)
            .padding(14.dp)
    ) {
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = statusColor(pct, invert), fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 11.sp, color = TextDim)
    }
}

@Composable
fun OverviewTab(health: Int, storagePct: Int, batteryPct: Int, ramPct: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(12.dp))
        HealthRing(health)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            MiniStat("Storage used", "$storagePct%", storagePct, invert = true, modifier = Modifier.weight(1f))
            MiniStat("Battery", "$batteryPct%", batteryPct, invert = false, modifier = Modifier.weight(1f))
            MiniStat("RAM used", "$ramPct%", ramPct, invert = true, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Live readings from your device — switch tabs below for details and cleanup actions.",
            fontSize = 12.sp,
            color = TextDim,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}

@Composable
fun StatCard(title: String, value: String, subtitle: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceColor)
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(title, fontSize = 12.sp, color = TextDim)
            Spacer(Modifier.height(6.dp))
            Text(value, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = TextPrimary, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, fontSize = 12.sp, color = TextDim)
        }
    }
}

@Composable
fun ActionButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AccentTeal, contentColor = Color(0xFF0B1512))
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(text, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun FootNote(text: String) {
    Text(text, fontSize = 11.5.sp, color = TextDim, lineHeight = 16.sp, modifier = Modifier.padding(top = 16.dp))
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
    Spacer(Modifier.height(14.dp))
    StatCard(
        title = "This app's cache",
        value = "${"%.1f".format(stats.ownCacheMB)} MB",
        subtitle = "Only Vitals' own cache — see note below"
    )
    Spacer(Modifier.height(18.dp))
    ActionButton("Clear this app's cache", Icons.Filled.Delete, onClean)
    lastFreedMB?.let {
        Spacer(Modifier.height(10.dp))
        Text("Freed ${"%.1f".format(it)} MB", color = AccentTeal, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
    FootNote(
        "Android doesn't allow third-party apps to clear other apps' cache or storage — " +
            "that's an OS-level restriction on every device since Android 5.0. For other apps, use " +
            "Settings → Storage → [app] → Clear cache."
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
    if (stats.lowMemory) {
        Spacer(Modifier.height(10.dp))
        Text("System reports low memory right now.", color = CritRed, fontSize = 13.sp)
    }
    Spacer(Modifier.height(18.dp))
    ActionButton("Refresh reading", Icons.Filled.Refresh, onRefresh)
    FootNote(
        "Since Android 5.0, apps can no longer see or close other apps' background processes — " +
            "only the system's own memory manager can do that. Android already reclaims memory " +
            "automatically as needed, which is why standalone \"RAM booster\" claims from other " +
            "cleaner apps don't actually do anything useful on modern Android."
    )
}

fun formatBytes(bytes: Long): String {
    val mb = bytes / 1_048_576.0
    return if (mb > 1024) "${"%.2f".format(mb / 1024)} GB" else "${"%.0f".format(mb)} MB"
}

@Composable
fun AppsTab(context: Context) {
    var granted by remember { mutableStateOf(hasUsageAccess(context)) }
    var apps by remember { mutableStateOf<List<AppUsage>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var showSystem by remember { mutableStateOf(false) }

    fun load() {
        loading = true
        apps = listAppStorage(context)
        loading = false
    }

    LaunchedEffect(granted) {
        if (granted) load()
    }

    if (!granted) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(24.dp))
            Text(
                "See what's using storage",
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Vitals needs \"Usage access\" permission — a system setting — to read how much " +
                    "space each app takes up. This only lets it read sizes, not activity or content.",
                color = TextDim,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(18.dp))
            ActionButton("Grant usage access", Icons.Filled.Apps) {
                openUsageAccessSettings(context)
            }
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = { granted = hasUsageAccess(context) }) {
                Text("I granted it — refresh", color = AccentTeal)
            }
        }
        return
    }

    val visibleApps = if (showSystem) apps else apps.filter { !it.isSystem }

    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("${visibleApps.size} apps · sorted by size", color = TextDim, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("System apps", color = TextDim, fontSize = 12.sp)
                Switch(
                    checked = showSystem,
                    onCheckedChange = { showSystem = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = AccentTeal, checkedTrackColor = AccentTeal.copy(alpha = 0.4f))
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        if (loading) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentTeal)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                items(visibleApps, key = { it.packageName }) { app ->
                    AppRow(app, onUninstall = { requestUninstall(context, app.packageName) })
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        ActionButton("Refresh list", Icons.Filled.Refresh) { load() }
    }
}

@Composable
fun AppRow(app: AppUsage, onUninstall: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCo
