package com.sarvio.app.ui.screens

import android.accounts.AccountManager
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.sarvio.app.SarvioApp
import com.sarvio.app.engine.Engine
import com.sarvio.app.engine.Entry
import com.sarvio.app.flow.FlowPlayer
import com.sarvio.app.flow.MicroG
import com.sarvio.app.ui.MainViewModel
import com.sarvio.app.ui.components.EmptyState
import com.sarvio.app.util.Format
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/** A feed chip at the top of the Flow screen. */
private data class FlowTab(val id: String, val label: String, val target: String? = null, val needsLogin: Boolean = false)

private val FlowTabs = listOf(
    FlowTab("home", "For you"),
    FlowTab("subs", "Subscriptions", ":ytsubs", needsLogin = true),
    FlowTab("rec", "Recommended", ":ytrec", needsLogin = true),
    FlowTab("music", "Music", "ytsearch30:official music video"),
    FlowTab("gaming", "Gaming", "ytsearch30:gaming highlights"),
    FlowTab("news", "News", "ytsearch30:news today"),
    FlowTab("live", "Live", "https://www.youtube.com/results?search_query=live+now&sp=EgJAAQ%3D%3D"),
    FlowTab("sports", "Sports", "ytsearch30:sports highlights"),
    FlowTab("learning", "Learning", "ytsearch30:explained"),
    FlowTab("podcasts", "Podcasts", "ytsearch30:podcast full episode"),
    FlowTab("comedy", "Comedy", "ytsearch30:stand up comedy"),
)

data class FlowFeed(
    val key: String = "",
    val loading: Boolean = false,
    val items: List<Entry> = emptyList(),
    val error: String? = null,
)

/** Loads the Flow feeds (yt-dlp, on the phone) and keeps them while you switch tabs. */
class FlowViewModel(app: Application) : AndroidViewModel(app) {
    private val sarvio = app as SarvioApp
    private val _feed = MutableStateFlow(FlowFeed())
    val feed: StateFlow<FlowFeed> = _feed
    private val _tab = MutableStateFlow("home")
    val tab: StateFlow<String> = _tab
    private val _query = MutableStateFlow<String?>(null)
    /** Non-null while search results are shown. */
    val query: StateFlow<String?> = _query

    private val cache = HashMap<String, List<Entry>>()
    private var job: Job? = null

    fun select(id: String, force: Boolean = false) {
        _tab.value = id
        _query.value = null
        load("tab:$id", force) { targetsFor(id) }
    }

    fun search(q: String) {
        val t = q.trim()
        if (t.isEmpty()) return
        _query.value = t
        viewModelScope.launch { sarvio.settings.addSearch(t) }
        load("search:$t", force = true) { listOf("ytsearch40:$t") }
    }

    fun closeSearch() {
        _query.value = null
        select(_tab.value)
    }

    fun refresh() {
        val q = _query.value
        if (q != null) search(q) else select(_tab.value, force = true)
    }

    /** Signing in or out changes what the feeds contain. */
    fun clearCache() = cache.clear()

    /**
     * "For you" works like a small on-device recommender: the account's recommendations when
     * signed in, otherwise your latest searches and recently streamed channels, otherwise trending.
     */
    private suspend fun targetsFor(id: String): List<String> {
        FlowTabs.firstOrNull { it.id == id }?.target?.let { return listOf(it) }
        val s = sarvio.settings.current()
        if (s.flowLogin && s.flowAccount != null) return listOf(":ytrec")
        val topics = LinkedHashSet<String>()
        s.searchHistory.take(2).forEach { topics += it }
        runCatching { sarvio.db.history().recentArtists(6) }.getOrNull()?.forEach { if (topics.size < 3) topics += it }
        if (topics.isEmpty()) return listOf("ytsearch30:trending today")
        return topics.map { "ytsearch12:$it" } + "ytsearch10:trending today"
    }

    private fun load(key: String, force: Boolean, targets: suspend () -> List<String>) {
        job?.cancel()
        val cached = cache[key]
        if (cached != null && !force) {
            _feed.value = FlowFeed(key, false, cached)
            return
        }
        _feed.value = FlowFeed(key, true, if (_feed.value.key == key) _feed.value.items else emptyList())
        job = viewModelScope.launch {
            runCatching {
                val cookies = FlowPlayer.cookieFile(sarvio)
                val lists = targets().map { t -> async { runCatching { Engine.feed(sarvio, t, cookies) }.getOrElse { emptyList() } } }.awaitAll()
                check(lists.any { it.isNotEmpty() }) { "Nothing came back. Pull down to try again." }
                interleave(lists)
            }.onSuccess {
                cache[key] = it
                _feed.value = FlowFeed(key, false, it)
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                _feed.value = FlowFeed(key, false, emptyList(), it.message ?: "Couldn't load this feed")
            }
        }
    }

    /** Mixes several result lists so one topic doesn't fill the whole feed; drops duplicates. */
    private fun interleave(lists: List<List<Entry>>): List<Entry> {
        val seen = HashSet<String>()
        val out = ArrayList<Entry>()
        val max = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until max) for (l in lists) {
            val e = l.getOrNull(i) ?: continue
            if (seen.add(e.id ?: e.url)) out += e
        }
        return out
    }
}

/**
 * Browse → YouTube: a clean, Material 3 YouTube client view (in the spirit of the open-source
 * Flow app). Videos stream in Sarvio's own player, with background play and a Download button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlowScreen(vm: MainViewModel, onOpenWeb: (String) -> Unit) {
    val flow: FlowViewModel = viewModel()
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val repo = SarvioApp.instance.settings
    val settings by vm.settings.collectAsState()
    val feed by flow.feed.collectAsState()
    val tab by flow.tab.collectAsState()
    val query by flow.query.collectAsState()
    val busy by FlowPlayer.busy.collectAsState()

    var searching by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    var accountMenu by remember { mutableStateOf(false) }
    var needMicroG by remember { mutableStateOf(false) }
    var signingIn by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val signedIn = settings.flowLogin && settings.flowAccount != null

    LaunchedEffect(Unit) { if (feed.key.isEmpty()) flow.select(tab) }
    // A login-only tab can't stay selected after signing out or turning sign-in off.
    LaunchedEffect(signedIn) {
        if (!signedIn && FlowTabs.firstOrNull { it.id == tab }?.needsLogin == true) flow.select("home")
    }
    BackHandler(enabled = searching || query != null) {
        searching = false; text = ""; if (query != null) flow.closeSearch()
    }

    fun signIn(account: String) {
        signingIn = true
        scope.launch {
            val activity = context.findActivity()
            val url = activity?.let { MicroG.webLoginUrl(it, account) }
            val ok = url != null && MicroG.applyWebLogin(context, url)
            repo.setFlowAccount(account)
            flow.clearCache()
            signingIn = false
            if (ok || MicroG.hasYoutubeSession()) {
                vm.toast("Signed in as $account")
                flow.refresh()
            } else {
                vm.toast("microG didn't share a web session. Finish signing in on the Google page.")
                onOpenWeb(MicroG.WEB_SIGN_IN)
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val name = res.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        if (name != null) signIn(name)
    }

    fun startSignIn() {
        if (!MicroG.isInstalled(context)) { needMicroG = true; return }
        runCatching { picker.launch(MicroG.chooseAccountIntent()) }
            .onFailure { vm.toast("Couldn't open the account picker") }
    }

    fun signOut() {
        MicroG.clearYoutubeSession()
        scope.launch {
            repo.setFlowAccount(null)
            flow.clearCache()
            flow.select("home", force = true)
            vm.toast("Signed out")
        }
    }

    Column(Modifier.fillMaxSize()) {
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.statusBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (searching) {
                        val fr = remember { FocusRequester() }
                        LaunchedEffect(Unit) { fr.requestFocus() }
                        IconButton(onClick = { searching = false; text = ""; if (query != null) flow.closeSearch() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search")
                        }
                        TextField(
                            value = text,
                            onValueChange = { text = it },
                            singleLine = true,
                            placeholder = { Text("Search YouTube") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); flow.search(text) }),
                            trailingIcon = {
                                if (text.isNotEmpty()) IconButton(onClick = { text = "" }) { Icon(Icons.Filled.Close, "Clear") }
                            },
                            shape = RoundedCornerShape(24.dp),
                            colors = TextFieldDefaults.colors(
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier.weight(1f).focusRequester(fr)
                        )
                    } else {
                        Spacer(Modifier.width(12.dp))
                        Box(
                            Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Filled.PlayArrow, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp)) }
                        Text(
                            "Flow", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 10.dp).weight(1f)
                        )
                        IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, "Search") }
                    }
                    // Background play switch (same setting as Me → Player → Background play).
                    IconButton(onClick = {
                        val on = !settings.backgroundPlay
                        scope.launch { repo.setBackgroundPlay(on) }
                        vm.toast(if (on) "Background play on" else "Background play off")
                    }) {
                        Icon(
                            if (settings.backgroundPlay) Icons.Filled.Headphones else Icons.Filled.HeadsetOff,
                            "Background play",
                            tint = if (settings.backgroundPlay) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!searching) IconButton(onClick = { onOpenWeb("https://www.google.com") }) { Icon(Icons.Filled.Public, "Web browser") }
                    if (settings.flowLogin) {
                        Box {
                            IconButton(onClick = { if (signedIn) accountMenu = true else startSignIn() }, enabled = !signingIn) {
                                when {
                                    signingIn -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                    signedIn -> LetterAvatar(settings.flowAccount.orEmpty(), 30)
                                    else -> Icon(Icons.Filled.AccountCircle, "Sign in with microG")
                                }
                            }
                            DropdownMenu(expanded = accountMenu, onDismissRequest = { accountMenu = false }) {
                                Text(
                                    "Signed in with microG\n${settings.flowAccount.orEmpty()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Switch account") },
                                    leadingIcon = { Icon(Icons.Filled.SwitchAccount, null) },
                                    onClick = { accountMenu = false; startSignIn() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Sign out") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, null) },
                                    onClick = { accountMenu = false; signOut() }
                                )
                            }
                        }
                    }
                }
                if (query == null) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        items(FlowTabs.filter { !it.needsLogin || signedIn }, key = { it.id }) { t ->
                            FilterChip(
                                selected = t.id == tab,
                                onClick = { flow.select(t.id, force = t.id == tab); scope.launch { listState.scrollToItem(0) } },
                                label = { Text(t.label) },
                                shape = RoundedCornerShape(10.dp),
                            )
                        }
                    }
                } else {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Results for “$query”", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { searching = false; text = ""; flow.closeSearch() }) { Text("Close") }
                    }
                }
                if (busy != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }

        PullToRefreshBox(
            isRefreshing = feed.loading && feed.items.isNotEmpty(),
            onRefresh = { flow.refresh() },
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            when {
                feed.loading && feed.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                feed.error != null && feed.items.isEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                    item { EmptyState(Icons.Filled.SearchOff, "Couldn't load videos", feed.error ?: "") }
                    item {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            TextButton(onClick = { flow.refresh() }) { Text("Try again") }
                        }
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
                    items(feed.items, key = { it.id ?: it.url }) { e ->
                        VideoCard(
                            e,
                            loading = busy == e.url,
                            onPlay = { FlowPlayer.play(context, e.url) },
                            onBackground = { FlowPlayer.play(context, e.url, audioOnly = true) },
                            onDownload = { vm.analyze(e.url) },
                            onShare = { share(context, e) },
                            onOpenWeb = { onOpenWeb(e.url) },
                        )
                    }
                }
            }
        }
    }

    if (needMicroG) AlertDialog(
        onDismissRequest = { needMicroG = false },
        title = { Text("microG RE needed") },
        text = {
            Text(
                "Sign-in uses microG RE, which keeps your Google account on the phone — Sarvio never sees your password.\n\n" +
                    "Install microG RE, add your Google account in it, then tap the account button here again."
            )
        },
        confirmButton = { TextButton(onClick = { needMicroG = false; MicroG.openDownloadPage(context) }) { Text("Get microG RE") } },
        dismissButton = { TextButton(onClick = { needMicroG = false }) { Text("Not now") } }
    )
}

@Composable
private fun VideoCard(
    e: Entry,
    loading: Boolean,
    onPlay: () -> Unit,
    onBackground: () -> Unit,
    onDownload: () -> Unit,
    onShare: () -> Unit,
    onOpenWeb: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(bottom = 14.dp)) {
        Box(
            Modifier
                .padding(horizontal = 12.dp)
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        ) {
            AsyncImage(
                model = e.thumbnail ?: e.id?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            val badge = if (e.isLive) "LIVE" else Format.duration(e.durationSec)
            if (badge.isNotEmpty()) Text(
                badge,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .background(if (e.isLive) Color(0xFFD32F2F) else Color(0xCC000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
            if (loading) Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
        }
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 10.dp), verticalAlignment = Alignment.Top) {
            LetterAvatar(e.uploader ?: e.title, 36)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    e.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                val meta = listOfNotNull(e.uploader, views(e.viewCount)).joinToString(" · ")
                if (meta.isNotEmpty()) Text(
                    meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)
                )
            }
            IconButton(onClick = onBackground) { Icon(Icons.Filled.Headphones, "Play in background") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Play") }, leadingIcon = { Icon(Icons.Filled.PlayArrow, null) }, onClick = { menu = false; onPlay() })
                    DropdownMenuItem(text = { Text("Play in background") }, leadingIcon = { Icon(Icons.Filled.Headphones, null) }, onClick = { menu = false; onBackground() })
                    DropdownMenuItem(text = { Text("Download") }, leadingIcon = { Icon(Icons.Filled.Download, null) }, onClick = { menu = false; onDownload() })
                    DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Filled.Share, null) }, onClick = { menu = false; onShare() })
                    DropdownMenuItem(text = { Text("Open in web browser") }, leadingIcon = { Icon(Icons.Filled.Public, null) }, onClick = { menu = false; onOpenWeb() })
                }
            }
        }
    }
}

@Composable
private fun LetterAvatar(name: String, sizeDp: Int) {
    val palette = listOf(
        Color(0xFFE53935), Color(0xFF8E24AA), Color(0xFF3949AB), Color(0xFF039BE5),
        Color(0xFF00897B), Color(0xFF7CB342), Color(0xFFF4511E), Color(0xFF6D4C41),
    )
    val c = palette[(name.hashCode() and 0x7fffffff) % palette.size]
    Box(Modifier.size(sizeDp.dp).clip(CircleShape).background(c), contentAlignment = Alignment.Center) {
        Text(
            name.trim().firstOrNull()?.uppercase() ?: "?", color = Color.White,
            style = if (sizeDp >= 34) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge
        )
    }
}

private fun views(n: Long): String? {
    if (n <= 0) return null
    val s = when {
        n >= 1_000_000_000 -> String.format(Locale.US, "%.1fB", n / 1e9)
        n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1e6)
        n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1e3)
        else -> n.toString()
    }.replace(".0", "")
    return "$s views"
}

private fun share(context: Context, e: Entry) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, e.title)
        .putExtra(Intent.EXTRA_TEXT, e.url)
    runCatching { context.startActivity(Intent.createChooser(i, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
