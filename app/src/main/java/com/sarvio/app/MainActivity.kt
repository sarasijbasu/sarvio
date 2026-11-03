package com.sarvio.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.sarvio.app.data.Status
import com.sarvio.app.download.DownloadService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.sarvio.app.ui.MainViewModel
import com.sarvio.app.ui.components.FormatSheet
import com.sarvio.app.ui.components.MiniPlayer
import com.sarvio.app.ui.screens.BrowserHolder
import com.sarvio.app.ui.screens.BrowserScreen
import com.sarvio.app.ui.screens.DownloadsScreen
import com.sarvio.app.ui.screens.HomeScreen
import com.sarvio.app.ui.screens.LibraryScreen
import com.sarvio.app.ui.screens.SettingsScreen
import com.sarvio.app.ui.screens.HistoryScreen
import com.sarvio.app.ui.screens.StatusScreen
import com.sarvio.app.ui.screens.VaultScreen
import com.sarvio.app.ui.theme.SarvioTheme
import com.sarvio.app.util.Links

class MainActivity : FragmentActivity() {

    companion object {
        const val EXTRA_TAB = "tab"
        /** Opens a page in the Sarvio browser (used by the player's "Open source" button). */
        const val EXTRA_OPEN_URL = "open_url"
    }

    private val vm: MainViewModel by viewModels()
    private var requestedTab by mutableStateOf<String?>(null)
    private var requestedUrl by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        resumeQueue()

        setContent {
            val settings by vm.settings.collectAsState()
            SarvioTheme(settings.theme, dynamic = settings.dynamicColor) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppShell(vm, requestedTab, requestedUrl, onUrlHandled = { requestedUrl = null }) { requestedTab = null }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * Downloads stopped by closing the app from recents resume when it is opened again.
     * (The process can survive the swipe, so SarvioApp.onCreate does not always run.)
     */
    private fun resumeQueue() {
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = SarvioApp.instance.db.downloads()
            if (DownloadService.activeIds().isEmpty()) dao.requeueInterrupted()
            if (dao.countUnfinished() > 0) DownloadService.start(this@MainActivity)
        }
    }

    /** Links shared from other apps go straight to the quality picker. */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_TAB)?.let { requestedTab = it }
        intent.getStringExtra(EXTRA_OPEN_URL)?.let { url ->
            intent.removeExtra(EXTRA_OPEN_URL)
            requestedUrl = url
            return
        }
        val link = when (intent.action) {
            Intent.ACTION_SEND -> Links.extract(intent.getStringExtra(Intent.EXTRA_TEXT))
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        if (link != null) {
            requestedTab = "home"
            vm.analyze(link)
        }
    }
}

private data class NavTab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    NavTab("home", "Home", Icons.Filled.Home),
    NavTab("browser", "Browse", Icons.Filled.Public),
    NavTab("downloads", "Downloads", Icons.Filled.Download),
    NavTab("history", "History", Icons.Filled.History),
    NavTab("library", "Library", Icons.Filled.VideoLibrary),
    NavTab("settings", "Me", Icons.Filled.Person),
)

@Composable
private fun AppShell(
    vm: MainViewModel,
    requestedTab: String?,
    requestedUrl: String?,
    onUrlHandled: () -> Unit,
    onTabHandled: () -> Unit,
) {
    val context = LocalContext.current
    var route by rememberSaveable { mutableStateOf("home") }
    var showVault by rememberSaveable { mutableStateOf(false) }
    var showStatus by rememberSaveable { mutableStateOf(false) }
    val browser = remember { BrowserHolder(context) }
    val snackbar = remember { SnackbarHostState() }
    val analyze by vm.analyze.collectAsState()
    val settings by vm.settings.collectAsState()
    val active by SarvioApp.instance.db.downloads().observeActive().collectAsState(initial = emptyList())
    LaunchedEffect(settings.blockAds, settings.backgroundPlay) {
        browser.applyBlockAds(settings.blockAds)
        browser.applyBackgroundPlay(settings.backgroundPlay)
    }
    val runningCount = active.count { it.status == Status.RUNNING || it.status == Status.QUEUED }

    // First-run permissions: notifications + media access.
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(Unit) {
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.READ_MEDIA_VIDEO)
                add(Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
                if (Build.VERSION.SDK_INT <= 29) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        permLauncher.launch(perms.toTypedArray())
    }

    LaunchedEffect(requestedTab) {
        requestedTab?.let { route = it; showVault = false; showStatus = false; onTabHandled() }
    }
    LaunchedEffect(requestedUrl) {
        requestedUrl?.let { browser.load(it); route = "browser"; showVault = false; showStatus = false; onUrlHandled() }
    }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { vm.navigate.collect { route = it; showVault = false; showStatus = false } }
    LaunchedEffect(Unit) { vm.browserUrl.collect { browser.load(it) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column {
                MiniPlayer()
                BottomBar(
                    selected = if (showVault || showStatus) null else route,
                    runningCount = runningCount,
                ) { r -> route = r; showVault = false; showStatus = false }
            }
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0)
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (showStatus) {
                StatusScreen(vm, onBack = { showStatus = false })
            } else if (showVault) {
                VaultScreen(onBack = { showVault = false })
            } else when (route) {
                "home" -> HomeScreen(vm, onOpenStatus = { showStatus = true })
                "browser" -> BrowserScreen(browser, vm)
                "downloads" -> DownloadsScreen()
                "history" -> HistoryScreen(asTab = true, onBack = { route = "home" })
                "library" -> LibraryScreen(onOpenVault = { showVault = true })
                "settings" -> SettingsScreen(vm, onOpenVault = { showVault = true }, onOpenStatus = { showStatus = true })
            }
        }
    }

    FormatSheet(analyze, settings, vm)
}

/**
 * Bottom navigation with circular selection indicators. Each tab gets an equal slot inside
 * the bar's side padding and its circle is sized to fit that slot, so with six tabs the
 * indicator never runs past the slot or the screen edge (the stock pill is 64 dp wide).
 */
@Composable
private fun BottomBar(selected: String?, runningCount: Int, onSelect: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(64.dp)
                .padding(horizontal = 12.dp)
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEach { t ->
                val sel = selected == t.route
                val bg by animateColorAsState(if (sel) cs.secondaryContainer else Color.Transparent, label = "tab")
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    val d = if (maxWidth < 48.dp) maxWidth else 48.dp
                    val circle: @Composable () -> Unit = {
                        Box(
                            Modifier
                                .size(d)
                                .clip(CircleShape)
                                .background(bg)
                                .selectable(selected = sel, role = Role.Tab, onClick = { onSelect(t.route) }),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(t.icon, t.label, tint = if (sel) cs.onSecondaryContainer else cs.onSurfaceVariant)
                        }
                    }
                    if (t.route == "downloads" && runningCount > 0) {
                        BadgedBox(badge = { Badge { Text("$runningCount") } }) { circle() }
                    } else circle()
                }
            }
        }
    }
}
