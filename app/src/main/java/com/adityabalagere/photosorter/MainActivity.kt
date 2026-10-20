package com.adityabalagere.photosorter

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Luggage
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Luggage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val model = appModel
        setContent {
            PhotoSorterTheme {
                MainScreen(model)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        appModel.appVisible = true
        appModel.refreshPermissions() // the user may have changed access in Settings
        Notifications.cancelResult(this)
    }

    override fun onStop() {
        appModel.appVisible = false
        super.onStop()
    }
}

private data class Dest(val key: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val DESTS = listOf(
    Dest("sort", "Sort", Icons.Outlined.AutoAwesome, Icons.Filled.AutoAwesome),
    Dest("trips", "Organize", Icons.Outlined.Luggage, Icons.Filled.Luggage),
    Dest("dups", "Duplicates", Icons.Outlined.CleaningServices, Icons.Filled.CleaningServices),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(model: AppModel) {
    val ctx = LocalContext.current
    var settingsOpen by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted -> model.onPermissionsChanged(granted) }
    val extraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { res -> model.onDeleteResult(res.resultCode == Activity.RESULT_OK) }

    // Users who granted photo access with an older version get asked for the newer
    // permissions (photo locations, notifications) once per launch.
    LaunchedEffect(model.permissions) {
        if (!model.permissions) return@LaunchedEffect
        val missing = buildList {
            if (model.missingLocation) add(Manifest.permission.ACCESS_MEDIA_LOCATION)
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isNotEmpty()) extraLauncher.launch(missing.toTypedArray())
    }
    // System "delete these photos?" confirmation - shown as soon as the app is visible.
    val req = model.deleteRequest
    LaunchedEffect(req, model.appVisible) {
        if (req != null && model.appVisible) {
            model.deleteRequest = null
            runCatching { deleteLauncher.launch(IntentSenderRequest.Builder(req).build()) }
                .onFailure { model.onDeleteResult(false) }
        }
    }

    if (!model.permissions) {
        Scaffold { pad ->
            Column(Modifier.padding(pad)) {
                WelcomeScreen(onAllow = { permLauncher.launch(model.permissionsToRequest()) })
            }
        }
        return
    }

    if (settingsOpen) AiSettingsSheet(model, onDismiss = { settingsOpen = false })

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.ic_logo), null, Modifier.size(34.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("PhotoSorter", fontWeight = FontWeight.SemiBold)
                    }
                },
                actions = {
                    IconButton(onClick = { settingsOpen = true }) { Icon(Icons.Outlined.Tune, "AI settings") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            NavigationBar {
                DESTS.forEach { d ->
                    val running = when (d.key) {
                        "sort" -> model.busy
                        "trips" -> model.tripBusy
                        else -> model.dupBusy
                    }
                    NavigationBarItem(
                        selected = model.tab == d.key,
                        onClick = { model.tab = d.key },
                        icon = {
                            BadgedBox(badge = { if (running) Badge() }) {
                                Icon(if (model.tab == d.key) d.selectedIcon else d.icon, d.label)
                            }
                        },
                        label = { Text(d.label) },
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (model.job != null || model.partialAccess) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    JobCard(model)
                    if (model.partialAccess) {
                        PartialAccessBanner(onChange = { permLauncher.launch(model.permissionsToRequest()) })
                    }
                }
            }
            when (model.tab) {
                "dups" -> DupsScreen(model)
                "trips" -> TripsScreen(model, openSettings = { settingsOpen = true })
                else -> SortScreen(model, openSettings = { settingsOpen = true })
            }
        }
    }
}

@Composable
private fun PartialAccessBanner(onChange: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.width(8.dp))
            Text(
                "Only selected photos are visible to PhotoSorter.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onChange) { Text("Allow all") }
        }
    }
}
