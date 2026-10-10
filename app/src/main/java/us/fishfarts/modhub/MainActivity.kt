package us.fishfarts.modhub

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// CurseForge-style palette: near-black surfaces, orange accent.
val CfBg = androidx.compose.ui.graphics.Color(0xFF1B1B1B)
val CfSurface = androidx.compose.ui.graphics.Color(0xFF262626)
val CfSurface2 = androidx.compose.ui.graphics.Color(0xFF333333)
val CfOrange = androidx.compose.ui.graphics.Color(0xFFF16436)
val CfText = androidx.compose.ui.graphics.Color(0xFFEDEDED)
val CfMuted = androidx.compose.ui.graphics.Color(0xFF9A9A9A)

class ModVm(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("modhub", Context.MODE_PRIVATE)
    var cfKey by mutableStateOf(prefs.getString("cf_key", "") ?: "")
        private set
    var enabled by mutableStateOf(
        (prefs.getStringSet("sources", null) ?: setOf(Source.MCPEDL.name, Source.CURSEFORGE.name))
            .mapNotNull { runCatching { Source.valueOf(it) }.getOrNull() }.toSet()
    )
        private set
    var query by mutableStateOf("")
    var category by mutableStateOf(Category.ALL)
    var page by mutableStateOf(1)
    var hasMore by mutableStateOf(false)
    var loadingMore by mutableStateOf(false)
    var items by mutableStateOf<List<ModItem>>(emptyList())
    private var raw: List<ModItem> = emptyList()
    private val homeCache = HashMap<String, Pair<Long, SearchResult>>()   // must be declared before init { search() }
    var sort by mutableStateOf(runCatching { Sort.valueOf(prefs.getString("sort", "POPULAR") ?: "POPULAR") }.getOrDefault(Sort.POPULAR))
    var descText by mutableStateOf<String?>(null)
    var descImages by mutableStateOf<List<String>>(emptyList())
    var descLoading by mutableStateOf(false)
    var errors by mutableStateOf<Map<Source, String>>(emptyMap())
    var loading by mutableStateOf(false)
    var selected by mutableStateOf<ModItem?>(null)
    var files by mutableStateOf<List<ModFile>?>(null)
    var deps by mutableStateOf<List<ModItem>>(emptyList())
    var filesError by mutableStateOf<String?>(null)
    var status by mutableStateOf<String?>(null)
    var installing by mutableStateOf(false)
    var installed by mutableStateOf((prefs.getStringSet("installed", emptySet()) ?: emptySet()).toSet())
        private set
    var stepsOpen by mutableStateOf(false)
    var showReadme by mutableStateOf(!prefs.getBoolean("ack_readme_1", false))
    fun acceptReadme() { prefs.edit().putBoolean("ack_readme_1", true).apply(); showReadme = false }
    fun openReadme() { showReadme = true }
    private var job: Job? = null


    private fun isWorldFile(f: ModFile) = f.name.lowercase().let { it.endsWith(".mcworld") || it.endsWith(".mctemplate") }

    /** Minecraft imports the file itself; packs/addons then get a short "how to switch it on" screen. */
    fun install() {
        val f = files ?: return
        val todo = latestGroup(f)
        if (todo.isEmpty() || installing) return
        installViaMinecraft(showSteps = !todo.all { isWorldFile(it) })
    }

    private fun titleKey(t: String) = "t:" + t.lowercase().filter { it.isLetterOrDigit() }

    /** Installed = by source+id OR by normalised title, so the same addon listed on another site (MCPEDL mirrors CurseForge) is marked too. */
    fun isInstalled(m: ModItem) = "${m.source}:${m.id}" in installed || titleKey(m.title) in installed

    private fun markInstalled(item: ModItem) {
        installed = installed + "${item.source}:${item.id}" + titleKey(item.title)
        prefs.edit().putStringSet("installed", HashSet(installed)).commit()   // commit: survive the process being killed right after
    }

    init { search() }

    fun setKey(k: String) { cfKey = CurseForge.cleanKey(k); prefs.edit().putString("cf_key", cfKey).apply(); search() }
    fun toggle(s: Source) {
        enabled = if (s in enabled && enabled.size > 1) enabled - s else enabled + s
        prefs.edit().putStringSet("sources", enabled.map { it.name }.toSet()).apply()
        search()
    }

    fun setCat(c: Category) { category = c; search() }

    private fun applySort() {
        val sorted = when (sort) {
            Sort.POPULAR -> raw.sortedByDescending { it.downloads }
            Sort.NEWEST -> raw.sortedByDescending { it.created.ifBlank { it.updated } }
            Sort.RELEVANCE -> raw
        }
        items = if (query.isBlank()) sorted.take(100) else sorted   // home lists show the top 100 of the chosen sort
    }
    fun chooseSort(s: Sort) { sort = s; prefs.edit().putString("sort", s.name).apply(); applySort() }

    fun search() {
        job?.cancel()
        job = viewModelScope.launch {
            loading = true; page = 1
            val q = query.trim()
            val ck = "${category.name}|${enabled.sorted()}|${cfKey.isNotBlank()}"
            val cached = if (q.isBlank()) homeCache[ck]?.takeIf { System.currentTimeMillis() - it.first < 60 * 60_000 } else null
            val r = cached?.second ?: searchAll(q, cfKey, enabled, 1, category).also { if (q.isBlank() && it.items.isNotEmpty()) homeCache[ck] = System.currentTimeMillis() to it }
            raw = r.items; applySort(); errors = r.errors; loading = false
            hasMore = q.isNotBlank() && r.items.size >= 10
        }
    }

    fun loadMore() {
        if (loadingMore || loading || !hasMore) return
        viewModelScope.launch {
            loadingMore = true
            val next = page + 1
            val r = searchAll(query.trim(), cfKey, enabled, next, category)
            val known = raw.map { "${it.source}:${it.id}" }.toSet()
            val fresh = r.items.filter { "${it.source}:${it.id}" !in known }
            raw = raw + fresh; applySort(); page = next
            hasMore = fresh.isNotEmpty()
            loadingMore = false
        }
    }

    fun open(item: ModItem) {
        selected = item; files = null; filesError = null; status = null; deps = emptyList()
        if (item.source == Source.CURSEFORGE && cfKey.isNotBlank()) viewModelScope.launch {
            runCatching { CurseForge.requiredDeps(item.id, cfKey) }.onSuccess { if (selected == item) deps = it }
        }
        descText = null; descImages = emptyList(); descLoading = true
        viewModelScope.launch {
            runCatching { fullDescription(item, cfKey) }
                .onSuccess { (t, imgs) -> if (selected == item && t.isNotBlank()) { descText = t; descImages = imgs } }
            descLoading = false
        }
        viewModelScope.launch {
            runCatching { resolveFiles(item, cfKey) }
                .onSuccess { files = it }
                .onFailure { filesError = it.message; files = emptyList() }
        }
    }
    fun close() { selected = null }

    fun installViaMinecraft(showSteps: Boolean = false) {
        val item = selected ?: return
        val f = files ?: return
        val todo = latestGroup(f)
        if (todo.isEmpty() || installing) return
        viewModelScope.launch {
            installing = true
            val ctx = getApplication<Application>()
            try {
                val dl = Installer.download(ctx, todo) { status = it }
                ImportBus.status.value = null; ImportBus.result.value = null
                androidx.core.content.ContextCompat.startForegroundService(ctx, android.content.Intent(ctx, ImportService::class.java)
                    .putExtra("paths", dl.map { it.absolutePath }.toTypedArray())
                    .putExtra("isWorld", todo.all { isWorldFile(it) }))
                val watcher = launch { ImportBus.status.collect { t -> if (t != null) status = t } }
                val res = ImportBus.result.first { it != null } ?: "error: no result"
                watcher.cancel()
                if (res.startsWith("error")) { status = "Failed: ${res.removePrefix("error: ")}"; return@launch }
                markInstalled(item)
                if (showSteps) stepsOpen = true
            } catch (e: Exception) {
                status = "Failed: ${e.message}"
            } finally {
                installing = false
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    private fun handleIntent(i: android.content.Intent?) {
        if (i?.getBooleanExtra("restart_mc", false) == true) {
            i.removeExtra("restart_mc")
            MinecraftRestarter.openAppInfo(applicationContext)
        }
    }

    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); handleIntent(intent) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = CfOrange, background = CfBg, surface = CfSurface, onSurface = CfText,
                onBackground = CfText, onPrimary = androidx.compose.ui.graphics.Color.White,
            )) {
                Surface(Modifier.fillMaxSize(), color = CfBg) { App() }
            }
        }
    }
}

fun fmt(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fK".format(n / 1_000.0)
    else -> n.toString()
}

@Composable
fun App(vm: ModVm = viewModel()) {
    var showSettings by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val notifPerm = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
    BackHandler(vm.selected != null) { vm.close() }
    if (vm.selected != null) DetailScreen(vm) else HomeScreen(vm) { showSettings = true }
    if (showSettings) SettingsDialog(vm) { showSettings = false }
    if (vm.stepsOpen) GuidedStepsDialog { vm.stepsOpen = false }
    if (vm.showReadme) ReadmeDialog { vm.acceptReadme() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: ModVm, openSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().background(CfSurface).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Hardware, null, tint = CfOrange)
            Spacer(Modifier.width(8.dp))
            Text("ModHub", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = CfText)
            Text("  for Minecraft Bedrock", fontSize = 12.sp, color = CfMuted)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = openSettings) { Icon(Icons.Filled.Settings, "Settings", tint = CfMuted) }
        }
        OutlinedTextField(
            value = vm.query, onValueChange = { vm.query = it },
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            singleLine = true, shape = RoundedCornerShape(10.dp),
            placeholder = { Text("Search all sites at once...", color = CfMuted) },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = CfMuted) },
            trailingIcon = {
                if (vm.query.isNotEmpty()) IconButton(onClick = { vm.query = ""; vm.search() }) {
                    Icon(Icons.Filled.Close, "Clear", tint = CfMuted)
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.search() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = CfSurface, unfocusedContainerColor = CfSurface,
                focusedBorderColor = CfOrange, unfocusedBorderColor = CfSurface2,
            ),
        )
        // horizontalScroll: FilterChip rows silently clip without it on narrow phones.
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Source.values().forEach { s ->
                FilterChip(
                    selected = s in vm.enabled, onClick = { vm.toggle(s) }, label = { Text(s.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CfOrange, selectedLabelColor = androidx.compose.ui.graphics.Color.White,
                        containerColor = CfSurface, labelColor = CfMuted,
                    ),
                )
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Category.values().forEach { c ->
                FilterChip(
                    selected = vm.category == c, onClick = { vm.setCat(c) }, label = { Text(c.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CfSurface2, selectedLabelColor = CfOrange,
                        containerColor = CfBg, labelColor = CfMuted,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true, selected = vm.category == c,
                        borderColor = CfSurface2, selectedBorderColor = CfOrange,
                    ),
                )
            }
        }
        // horizontalScroll: chip rows silently clip without it on narrow phones.
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Sort, "Sort", tint = CfMuted, modifier = Modifier.size(18.dp))
            Sort.values().forEach { so ->
                FilterChip(
                    selected = vm.sort == so, onClick = { vm.chooseSort(so) }, label = { Text(so.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CfSurface2, selectedLabelColor = CfOrange,
                        containerColor = CfBg, labelColor = CfMuted,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true, selected = vm.sort == so,
                        borderColor = CfSurface2, selectedBorderColor = CfOrange,
                    ),
                )
            }
        }
        if (Source.CURSEFORGE in vm.enabled && vm.cfKey.isNotBlank() && CurseForge.searchRestricted) {
            Text(
                "CurseForge: this API key can't search, showing its popular list only.",
                color = CfMuted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (Source.MODRINTH in vm.enabled) {
            Text(
                "Modrinth is mostly Java mods, which can't be installed into Bedrock. Leave it off unless you are curious.",
                color = CfMuted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (Source.CURSEFORGE in vm.enabled && vm.cfKey.isBlank()) {
            Text(
                "CurseForge needs an API key - tap the gear to add it.",
                color = CfOrange, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        vm.errors.forEach { (s, e) ->
            Text("${s.label}: $e", color = CfMuted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp))
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxSize()) {
            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (vm.query.isBlank() && vm.items.isNotEmpty()) item {
                    Text("Top ${vm.items.size} " + (if (vm.category == Category.ALL) "of everything" else vm.category.label.lowercase()) + " - " + vm.sort.label.lowercase(), color = CfMuted, fontSize = 12.sp)
                }
                items(vm.items, key = { "${it.source}:${it.id}" }) { ModCard(it, vm.isInstalled(it)) { vm.open(it) } }
                if (vm.hasMore) item {
                    LaunchedEffect(vm.items.size) { vm.loadMore() }
                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                        if (vm.loadingMore) CircularProgressIndicator(color = CfOrange, modifier = Modifier.size(24.dp))
                    }
                }
                if (!vm.loading && vm.items.isEmpty()) item {
                    Text("No results.", color = CfMuted, modifier = Modifier.padding(24.dp))
                }
            }
            if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter), color = CfOrange)
        }
    }
}

@Composable
fun SourceBadge(s: Source) {
    val c = when (s) { Source.MCPEDL -> androidx.compose.ui.graphics.Color(0xFF4CAF50); Source.CURSEFORGE -> CfOrange; Source.MODRINTH -> androidx.compose.ui.graphics.Color(0xFF1BD96A) }
    Surface(shape = RoundedCornerShape(4.dp), color = c.copy(alpha = 0.18f), border = BorderStroke(1.dp, c.copy(alpha = 0.6f))) {
        Text(s.label, color = c, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
fun ModCard(m: ModItem, installed: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CfSurface).clickable(onClick = onClick).padding(12.dp),
    ) {
        AsyncImage(
            model = m.image, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)).background(CfSurface2),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.title, color = CfText, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (installed) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Filled.CheckCircle, "Installed", tint = CfOrange, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("Installed", color = CfOrange, fontSize = 11.sp, maxLines = 1)
                }
            }
            Text("by ${m.author}", color = CfMuted, fontSize = 12.sp, maxLines = 1)
            Text(m.summary, color = CfMuted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Filled.Download, null, tint = CfMuted, modifier = Modifier.size(14.dp))
                Text(fmt(m.downloads), color = CfMuted, fontSize = 12.sp)
                if (m.updated.isNotBlank()) Text(m.updated, color = CfMuted, fontSize = 12.sp)
                SourceBadge(m.source)
            }
        }
    }
}

@Composable
fun DetailScreen(vm: ModVm) {
    val m = vm.selected ?: return
    val files = vm.files
    val group = files?.let { latestGroup(it) } ?: emptyList()
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().background(CfSurface).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.close() }) { Icon(Icons.Filled.ArrowBack, "Back", tint = CfText) }
            Text(m.title, color = CfText, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            AsyncImage(
                model = m.image, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(200.dp).background(CfSurface2),
            )
            Column(Modifier.padding(16.dp)) {
                Text(m.title, color = CfText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("by ${m.author}", color = CfMuted)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Download, null, tint = CfMuted, modifier = Modifier.size(16.dp))
                    Text("${fmt(m.downloads)} downloads", color = CfMuted)
                    SourceBadge(m.source)
                }
                if (m.categories.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        m.categories.forEach {
                            Surface(shape = RoundedCornerShape(50), color = CfSurface2) {
                                Text(it, color = CfText, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                            }
                        }
                    }
                }
                if (vm.deps.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Surface(shape = RoundedCornerShape(10.dp), color = CfSurface2) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Needs other packs", color = CfOrange, fontWeight = FontWeight.SemiBold)
                            Text("Install these too or Minecraft will report missing dependencies:", color = CfMuted, fontSize = 12.sp)
                            vm.deps.forEach { d ->
                                TextButton(onClick = { vm.open(d) }) { Text("${d.title}  \u203a", color = CfText) }
                            }
                        }
                    }
                }
                val hint = remember(vm.descText, files) { if (m.source == Source.MCPEDL) dependencyHint(vm.descText ?: m.summary, files?.map { it.name } ?: emptyList()) else null }
                if (hint != null) {
                    Spacer(Modifier.height(16.dp))
                    Surface(shape = RoundedCornerShape(10.dp), color = CfSurface2) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Heads up: this may need other packs", color = CfOrange, fontWeight = FontWeight.SemiBold)
                            Text(hint, color = CfMuted, fontSize = 12.sp)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                if (vm.descImages.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        vm.descImages.forEach { u ->
                            AsyncImage(
                                model = u, contentDescription = null, contentScale = ContentScale.Crop,
                                modifier = Modifier.height(150.dp).width(240.dp).clip(RoundedCornerShape(8.dp)).background(CfSurface2),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(vm.descText ?: m.summary, color = CfText, fontSize = 15.sp, lineHeight = 22.sp)
                if (vm.descLoading && vm.descText == null) {
                    Spacer(Modifier.height(8.dp))
                    Text("Loading full description…", color = CfMuted, fontSize = 12.sp)
                }
                Spacer(Modifier.height(20.dp))
                Text("Files", color = CfText, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Spacer(Modifier.height(6.dp))
                when {
                    files == null -> CircularProgressIndicator(color = CfOrange, modifier = Modifier.size(24.dp))
                    files.isEmpty() -> Text(
                        vm.filesError ?: "No Bedrock-installable file (.mcpack/.mcaddon/.mcworld) found for this listing.",
                        color = CfMuted,
                    )
                    else -> files.forEach { f ->
                        val picked = f in group
                        Text(
                            (if (picked) "✓ " else "   ") + f.name,
                            color = if (picked) CfText else CfMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
                vm.status?.let { Spacer(Modifier.height(12.dp)); Text(it, color = CfOrange, fontSize = 13.sp) }
                val ctx = androidx.compose.ui.platform.LocalContext.current
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (m.webUrl.isNotBlank()) OutlinedButton(onClick = {
                        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(m.webUrl)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text("Open website", color = CfText) }
                    OutlinedButton(onClick = {
                        minecraftPackage(ctx)?.let { pkg ->
                            ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { ctx.startActivity(it) }
                        }
                    }) { Text("Open Minecraft", color = CfText) }
                }
            }
        }
        Surface(color = CfSurface, tonalElevation = 4.dp) {
            Button(
                onClick = { vm.install() },
                enabled = group.isNotEmpty() && !vm.installing,
                modifier = Modifier.fillMaxWidth().padding(12.dp).navigationBarsPadding().height(52.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CfOrange, contentColor = androidx.compose.ui.graphics.Color.White),
            ) {
                Icon(Icons.Filled.Download, null)
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        vm.installing -> "Installing..."
                        vm.isInstalled(m) -> "Reinstall to Minecraft"
                        else -> "Install to Minecraft"
                    },
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
fun SettingsDialog(vm: ModVm, onDismiss: () -> Unit) {
    var key by remember { mutableStateOf(vm.cfKey) }
    var keyMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CfSurface,
        title = { Text("Settings", color = CfText) },
        text = {
            Column {
                Text("CurseForge API key (free at console.curseforge.com). Stored only on this phone.", color = CfMuted, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = key, onValueChange = { key = it; keyMsg = null }, singleLine = true, label = { Text("API key") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { keyMsg = "Checking\u2026"; scope.launch { keyMsg = CurseForge.checkKey(key) } }) { Text("Test key", color = CfOrange) }
                    keyMsg?.let { Text(it, color = if (it.startsWith("\u2713")) CfText else CfMuted, fontSize = 12.sp, lineHeight = 16.sp) }
                }
                Spacer(Modifier.height(16.dp))
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val canOverlay = android.provider.Settings.canDrawOverlays(ctx)
                val helperOn = (android.provider.Settings.Secure.getString(ctx.contentResolver, "enabled_accessibility_services") ?: "").contains("${ctx.packageName}/")
                Text("Automatic Minecraft restart (optional)", color = CfText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "After an import, Minecraft only shows the new pack once it has been restarted. With both switches below on, ModHub does that for you. " +
                        "Otherwise you get a notification that opens Minecraft's App info for you to tap Force stop.",
                    color = CfMuted, fontSize = 12.sp, lineHeight = 17.sp,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (canOverlay) Icons.Filled.CheckCircle else Icons.Filled.Close, null, tint = if (canOverlay) CfOrange else CfMuted, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp)); Text("Display over other apps", color = CfText, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    if (!canOverlay) OutlinedButton(onClick = {
                        ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${ctx.packageName}")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text("Allow", color = CfText) }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (helperOn) Icons.Filled.CheckCircle else Icons.Filled.Close, null, tint = if (helperOn) CfOrange else CfMuted, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp)); Text("Restart helper (accessibility)", color = CfText, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    if (!helperOn) OutlinedButton(onClick = {
                        ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text("Open", color = CfText) }
                }
                if (!helperOn) {
                    Text(
                        "Find ModHub under Installed apps and switch it on. If Android says it is a restricted setting: Settings > Apps > ModHub > the 3-dot menu > Allow restricted settings (you may need to try switching it on once first).",
                        color = CfMuted, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { vm.openReadme() }) { Text("Read what ModHub does and asks for", color = CfOrange) }            }
        },
        confirmButton = { TextButton(onClick = { vm.setKey(key); onDismiss() }) { Text("Save", color = CfOrange) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = CfMuted) } },
    )
}


@Composable
fun GuidedStepsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = CfSurface,
        title = { Text("Imported! Now switch it on", color = CfText) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Minecraft has imported it and been restarted, so it should be ready. (If you used the tap-to-restart notification, tap it first. If a pack is still missing, close and reopen Minecraft once.)\n\n" +
                        "Textures, sounds and UI packs (resource packs) - for every world:\n" +
                        "Minecraft > Settings > Global Resources > My Packs > Activate.\n\n" +
                        "Addons with game logic (behavior packs) - per world:\n" +
                        "Minecraft > Play > the pencil next to your world > Behavior Packs > Activate (and Resource Packs too if it has one). " +
                        "Turn on any Experiments it asks for. Minecraft will warn the world may behave differently.",
                    color = CfText, fontSize = 14.sp, lineHeight = 21.sp,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it", color = CfOrange) } },
    )
}


@Composable
fun ReadmeDialog(onAccept: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},   // must be read and accepted
        containerColor = CfSurface,
        title = { Text("Please read this first", color = CfText) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                @Composable fun h(t: String) { Text(t, color = CfText, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp)) }
                @Composable fun p(t: String) { Text(t, color = CfMuted, fontSize = 13.sp, lineHeight = 18.sp) }
                p("ModHub is a personal, self-hosted app. It is not on Google Play, so Android treats it with extra suspicion. Here is everything that might look alarming, and why it is there.")
                h("What it does")
                p("Searches public Minecraft Bedrock sites (MCPEDL, CurseForge, optionally Modrinth), downloads the file you pick into its own cache, and hands it to Minecraft to import. No account, no ads, no analytics. The only things sent anywhere are your searches and download requests to those sites, and your CurseForge key (which stays on this phone) to CurseForge.")
                h("Optional: the automatic restart")
                p("Minecraft only lists a new pack after it restarts. ModHub can do that restart for you, which needs these two permissions. You can skip both: you will get a notification to do it by hand.")
                h("1. \"Display over other apps\"")
                p("Used only so ModHub can open its own screens from the background while it works, and it draws one invisible 1-pixel dot while doing so. It never draws over what you are looking at.")
                h("2. An accessibility service (the scary-looking one)")
                p("Android shows a strong warning for every accessibility service: that it can observe your actions and read what is on screen. That is Android's standard wording, it is not specific to ModHub. ModHub's service is limited by its own declaration (which Android enforces) to the Settings app only, does nothing unless ModHub arms it right after an import, and then only presses \"Force stop\" and its confirmation on Minecraft's App info page. It cannot see your other apps, messages, passwords, keyboard input or notifications, and it stores and sends nothing. Turn it off any time in Settings > Accessibility. If you never turn it on, it does nothing at all.")
                p("Because ModHub was installed outside Google Play, Android also asks you to \"Allow restricted settings\" for it first. That is Android's way of warning about sideloaded apps and is expected.")
                h("Other things you will notice")
                p("A notification while an import is running (a foreground service, so Android does not freeze the app while Minecraft is in front). It can see whether Minecraft is installed so it can open it. It asks for notification permission to show the progress.")
                h("About the downloads")
                p("Addons come from third-party sites and are not scanned or vetted by ModHub. They run inside Minecraft, but some can break or change a world, so back up worlds you care about. Switching a pack on is always left to you.")
                h("Not official")
                p("ModHub is an unofficial fan project. It is not approved by or associated with Mojang Studios, Microsoft, CurseForge / Overwolf or MCPEDL, and the sites keep their own terms. Minecraft is a trademark of Mojang. Please also use each addon page's own link to credit and support its creator.")
                h("What it never does")
                p("No contacts, messages, call log, location, camera, microphone, photos or file access outside its own cache.")
            }
        },
        confirmButton = { TextButton(onClick = onAccept) { Text("I've read this", color = CfOrange) } },
    )
}
