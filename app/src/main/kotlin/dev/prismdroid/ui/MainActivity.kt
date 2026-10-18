package dev.prismdroid.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.prismdroid.game.GameActivity
import dev.prismdroid.install.Instance
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val req by vm.launches.collectAsStateWithLifecycle()
                LaunchedEffect(req) {
                    req?.let {
                        startActivity(GameActivity.intent(this@MainActivity, it.launchFile, it.logFile, it.workDir))
                        vm.launches.value = null
                    }
                }
                LauncherScreen(vm)
            }
        }
    }

    override fun onResume() { super.onResume(); vm.refresh() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherScreen(vm: MainViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    var showNew by remember { mutableStateOf(false) }
    var showAccounts by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PrismDroid") },
                actions = {
                    TextButton(onClick = { showAccounts = true }) {
                        Icon(Icons.Default.AccountCircle, null); Spacer(Modifier.width(6.dp))
                        Text(s.active?.name ?: "Sign in")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { showNew = true }, icon = { Icon(Icons.Default.Add, null) },
                text = { Text("New instance") })
        },
    ) { pad ->
        if (s.instances.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("No instances yet. Create one to install Minecraft.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(s.instances, key = { it.id }) { InstanceCard(it, vm) }
            }
        }
    }

    if (showNew) NewInstanceDialog(s, onDismiss = { showNew = false }) { name, ver, fabric, mem ->
        vm.createInstance(name, ver, fabric, mem); showNew = false
    }
    if (showAccounts) AccountsDialog(s, vm) { showAccounts = false }
    s.deviceCode?.let { DeviceCodeDialog(it, vm::cancelSignIn) }
    s.busy?.let { ProgressDialog(it) }
    s.error?.let { err ->
        AlertDialog(onDismissRequest = vm::dismissError, confirmButton = { TextButton(onClick = vm::dismissError) { Text("OK") } },
            title = { Text("Something went wrong") }, text = { SelectionContainer { Text(err) } })
    }
}

@Composable
fun InstanceCard(inst: Instance, vm: MainViewModel) {
    var server by remember(inst.id) { mutableStateOf(inst.config.lastServer) }
    var confirmDelete by remember { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(inst.config.name, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    Text(buildString {
                        append("Minecraft ${inst.config.mcVersion}")
                        inst.config.loader?.let { append(" · Fabric ${it.version}") }
                        append(" · ${inst.config.memoryMb} MB")
                    }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
                if (dev.prismdroid.BuildConfig.DEBUG) TextButton(onClick = { vm.play(inst, null, demo = true) }) { Text("Demo") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete") }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(server, { server = it }, Modifier.weight(1f), singleLine = true,
                    label = { Text("Server (optional)") }, placeholder = { Text("play.example.net:25565") })
                Spacer(Modifier.width(12.dp))
                Button(onClick = { vm.play(inst, server.trim().ifBlank { null }) }) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(4.dp))
                    Text(if (server.isBlank()) "Play" else "Join")
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${inst.config.name}?") },
        text = { Text("Worlds, mods and settings in this instance will be removed.") },
        confirmButton = { TextButton(onClick = { vm.deleteInstance(inst); confirmDelete = false }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewInstanceDialog(s: UiState, onDismiss: () -> Unit, onCreate: (String, String, Boolean, Int) -> Unit) {
    var snapshots by remember { mutableStateOf(false) }
    val versions = s.versions.filter { it.type == "release" || (snapshots && it.type == "snapshot") }
    var version by remember(versions) { mutableStateOf(versions.firstOrNull()?.id ?: "") }
    var name by remember { mutableStateOf("") }
    var fabric by remember { mutableStateOf(false) }
    var memory by remember { mutableFloatStateOf(2048f) }
    var expanded by remember { mutableStateOf(false) }

    AlertDialog(onDismissRequest = onDismiss, title = { Text("New instance") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") },
                    placeholder = { Text(version) })
                ExposedDropdownMenuBox(expanded, { expanded = it }) {
                    OutlinedTextField(version, {}, readOnly = true, label = { Text("Minecraft version") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth())
                    ExposedDropdownMenu(expanded, { expanded = false }) {
                        versions.take(60).forEach { v ->
                            DropdownMenuItem(text = { Text(v.id) }, onClick = { version = v.id; expanded = false })
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { snapshots = !snapshots }) {
                    Checkbox(snapshots, { snapshots = it }); Text("Show snapshots")
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { fabric = !fabric }) {
                    Checkbox(fabric, { fabric = it }); Text("Fabric loader")
                }
                Text("Memory: ${memory.roundToInt()} MB")
                Slider(memory, { memory = (it / 256).roundToInt() * 256f }, valueRange = 1024f..8192f)
                Text("Android support covers Minecraft 26.1+ (SDL3 + Vulkan).", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(enabled = version.isNotBlank(), onClick = { onCreate(name, version, fabric, memory.roundToInt()) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
fun AccountsDialog(s: UiState, vm: MainViewModel, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Accounts") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.accounts.isEmpty()) Text("No accounts. Sign in with the Microsoft account that owns Minecraft: Java Edition.")
                s.accounts.forEach { a ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { vm.selectAccount(a.uuid) }) {
                        RadioButton(a.uuid == s.active?.uuid, { vm.selectAccount(a.uuid) })
                        Text(a.name, Modifier.weight(1f))
                        IconButton(onClick = { vm.removeAccount(a.uuid) }) { Icon(Icons.Default.Close, "Remove") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.signIn(); onDismiss() }) { Text("Add Microsoft account") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

@Composable
fun DeviceCodeDialog(dc: dev.prismdroid.auth.MicrosoftAuth.DeviceCode, onCancel: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(onDismissRequest = {}, title = { Text("Sign in with Microsoft") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text("Open the Microsoft page and enter this code:")
                Spacer(Modifier.height(12.dp))
                SelectionContainer { Text(dc.userCode, fontSize = 30.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(8.dp))
                Text(dc.verificationUri, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cm = ctx.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("code", dc.userCode))
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(dc.verificationUri)))
            }) { Text("Copy code & open") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } })
}

@Composable
fun ProgressDialog(p: dev.prismdroid.install.Progress) {
    AlertDialog(onDismissRequest = {}, confirmButton = {}, title = { Text(p.stage) },
        text = {
            Column {
                if (p.total > 0) {
                    LinearProgressIndicator(progress = { (p.done.toFloat() / p.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("%.1f / %.1f MB".format(p.done / 1e6, p.total / 1e6), fontSize = 13.sp)
                } else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        })
}
