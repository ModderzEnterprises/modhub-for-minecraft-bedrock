package us.fishfarts.modhub

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

enum class Source(val label: String) { MCPEDL("MCPEDL"), CURSEFORGE("CurseForge"), MODRINTH("Modrinth") }

/** One installable file (a .mcpack/.mcaddon/.mcworld) belonging to a mod. */
data class ModFile(val name: String, val url: String, val group: String = "", val date: String = "")

data class ModItem(
    val source: Source,
    val id: String,          // mcpedl: slug, curseforge: mod id, modrinth: project id
    val title: String,
    val summary: String,
    val image: String?,
    val author: String,
    val downloads: Long,
    val categories: List<String>,
    val updated: String = "",
    val created: String = "",
    val webUrl: String = "",
    // Sources that hand back files inline (CurseForge) fill this; others resolve lazily.
    val files: List<ModFile>? = null,
)

val installableExts = listOf(".mcpack", ".mcaddon", ".mcworld", ".mctemplate")
fun isInstallable(name: String) = installableExts.any { name.lowercase().endsWith(it) }

val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .followRedirects(true)
    .build()

// Identify honestly (the sites answer this fine): no pretending to be a browser.
const val UA = "ModHub/1.6 (open-source Minecraft Bedrock addon browser)"

private fun get(url: String, headers: Map<String, String> = emptyMap()): String {
    val b = Request.Builder().url(url).header("User-Agent", UA)
    headers.forEach { (k, v) -> b.header(k, v) }
    http.newCall(b.build()).execute().use { r ->
        if (!r.isSuccessful) error("HTTP ${r.code} from ${Request.Builder().url(url).build().url.host}")
        return r.body?.string() ?: ""
    }
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
private fun JsonObject.str(k: String): String =
    get(k)?.takeIf { !it.isJsonNull }?.asString ?: ""
private fun JsonObject.lng(k: String): Long =
    get(k)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asLong }.getOrNull() } ?: 0L
private fun JsonElement?.arr(): JsonArray = if (this != null && this.isJsonArray) this.asJsonArray else JsonArray()

enum class Sort(val label: String) { POPULAR("Most popular"), NEWEST("Newest"), RELEVANCE("Relevance") }

enum class Category(val label: String, val typeId: Int?, val shelf: String?) {
    ALL("All", null, null), ADDONS("Addons", 1, "addons"), MAPS("Maps", 2, "maps"),
    TEXTURES("Textures", 3, "texture_packs"), SKINS("Skins", 6, "skins"), SCRIPTS("Scripts", 9, "scripts"),
}

object Mcpedl {
    private const val API = "https://api.mcpedl.com/api"

    private fun parse(results: JsonArray): List<ModItem> = results.map { it.asJsonObject }.map { o ->
        ModItem(
            source = Source.MCPEDL, id = o.str("slug"), title = o.str("title"),
            summary = o.str("summary"), image = o.str("image").ifBlank { null },
            author = o.str("display_name").ifBlank { o.str("user_nicename") },
            downloads = o.lng("downloadCount"),
            categories = o.get("cf_tags").arr().map { it.asJsonObject.str("name") }.distinct().take(3),
            updated = o.str("updated_at").take(10), created = o.str("created_at").take(10), webUrl = "https://mcpedl.com/${o.str("slug")}/",
        )
    }

    private val json = mapOf("Accept" to "application/json")

    /**
     * Home lists. mcpedl cannot sort server-side (its search ignores every sort option and returns 20 per page, most recently
     * updated first), so pull [pages] pages in parallel plus its own popular shelves and let the app sort them locally.
     */
    suspend fun browse(cat: Category, pages: Int = 10): List<ModItem> = coroutineScope {
        val sem = Semaphore(5)
        val type = cat.typeId?.let { "&type_id=$it" } ?: ""
        var failures = 0
        val jobs = (1..pages).map { p ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    runCatching { parse(JsonParser.parseString(get("$API/search/advanced?page=$p$type", json)).asJsonObject.get("results").arr()) }
                        .getOrElse { failures++; emptyList() }
                }
            }
        }
        val shelf = async(Dispatchers.IO) {
            runCatching {
                val root = JsonParser.parseString(get("$API/search/frontpage/v2", json)).asJsonObject
                root.getAsJsonObject("shelves").getAsJsonObject("popular").entrySet()
                    .filter { cat.shelf == null || it.key == cat.shelf }
                    .flatMap { parse(it.value.asJsonObject.get("results").arr()) }
            }.getOrDefault(emptyList())
        }
        val all = (jobs.awaitAll().flatten() + shelf.await()).distinctBy { it.id }
        if (all.isEmpty()) error("MCPEDL didn't answer ($failures of $pages requests failed)")
        all
    }

    suspend fun search(q: String, page: Int = 1, cat: Category = Category.ALL): List<ModItem> = withContext(Dispatchers.IO) {
        if (q.isBlank()) return@withContext if (page > 1) emptyList() else browse(cat)
        val type = cat.typeId?.let { "&type_id=$it" } ?: ""
        val url = "$API/search/advanced?q=${enc(q)}&page=$page$type"
        val root = JsonParser.parseString(get(url, mapOf("Accept" to "application/json"))).asJsonObject
        parse(root.get("results").arr())
    }

    /** Full description as HTML, from the `description:"..."` string in the server-rendered page state. */
    suspend fun description(slug: String): String = withContext(Dispatchers.IO) {
        val html = get("https://mcpedl.com/$slug/")
        val start = html.indexOf("window.__NUXT__").takeIf { it >= 0 } ?: error("no page data")
        val script = html.substring(start, html.indexOf("</script>", start).takeIf { it > start } ?: html.length)
        val best = Regex("""\bdescription:"((?:[^"\\]|\\.)*)"""").findAll(script).map { it.groupValues[1] }.maxByOrNull { it.length } ?: error("no description")
        JsonParser.parseString("\"" + best + "\"").asString
    }

    /** The detail page is server-rendered; its embedded state contains the edge.mcpedl.com file links. */
    suspend fun files(slug: String): List<ModFile> = withContext(Dispatchers.IO) {
        val html = get("https://mcpedl.com/$slug/").replace("\\u002F", "/")
        // Each link is a JS string, optionally followed by its upload timestamp. Filenames can contain
        // spaces, brackets, '+', parentheses - so match up to the closing quote, not a character class.
        val rx = Regex(""""(https://edge\.mcpedl\.com/files/[^"\\]+)"(?:,"(\d{4}-\d\d-\d\dT[^"]*)")?""")
        rx.findAll(html).map { it.groupValues[1] to it.groupValues[2] }.distinctBy { it.first }.mapNotNull { (u, date) ->
            val name = runCatching { java.net.URLDecoder.decode(u.substringAfterLast('/').replace("+", "%2B"), "UTF-8") }
                .getOrDefault(u.substringAfterLast('/'))
            if (!isInstallable(name)) null
            else ModFile(name, u.replace(" ", "%20"), Regex("""^\[(v?\d[^\]]*)]""", RegexOption.IGNORE_CASE).find(name)?.groupValues?.get(1) ?: "", date)
        }.toList().sortedByDescending { it.date }   // newest first (stable: undated keep page order)
    }
}

object CurseForge {
    private const val API = "https://api.curseforge.com/v1"
    const val BEDROCK_GAME_ID = 78022

    private fun parse(data: JsonArray): List<ModItem> = data.map { it.asJsonObject }.map { o ->
        ModItem(
            source = Source.CURSEFORGE, id = o.str("id").ifBlank { o.lng("id").toString() },
            title = o.str("name"), summary = o.str("summary"),
            image = o.getAsJsonObject("logo")?.str("thumbnailUrl")?.ifBlank { null },
            author = o.get("authors").arr().firstOrNull()?.asJsonObject?.str("name") ?: "",
            downloads = o.lng("downloadCount"),
            categories = o.get("categories").arr().map { it.asJsonObject.str("name") }.take(3),
            updated = o.str("dateModified").take(10),
            created = o.str("dateCreated").take(10),
            webUrl = o.getAsJsonObject("links")?.str("websiteUrl") ?: "",
        )
    }

    /** Some API keys get 403 on /mods/search (seen 2026-09-30) while featured/files still work. */
    @Volatile var searchRestricted = false

    /** CurseForge's Bedrock content classes, matching the app's categories. */
    private val classIds = mapOf(Category.ADDONS to 4984, Category.MAPS to 6913, Category.TEXTURES to 6929, Category.SCRIPTS to 6940, Category.SKINS to 6925)

    /**
     * Home lists with CurseForge's real server-side sorting: the most popular and the newest (release date) per class,
     * 50 per request. Returns nothing when there is no key or the key can't search.
     */
    suspend fun browse(cat: Category, key: String): List<ModItem> = coroutineScope {
        if (key.isBlank() || searchRestricted) return@coroutineScope emptyList<ModItem>()
        val classes = if (cat == Category.ALL) classIds.values.toList() else listOfNotNull(classIds[cat])
        val indices = if (cat == Category.ALL) listOf(0) else listOf(0, 50)
        val jobs = classes.flatMap { cid -> listOf(2, 11).flatMap { sortField -> indices.map { idx -> Triple(cid, sortField, idx) } } }.map { (cid, sf, idx) ->
            async(Dispatchers.IO) {
                runCatching {
                    val url = "$API/mods/search?gameId=$BEDROCK_GAME_ID&classId=$cid&sortField=$sf&sortOrder=desc&pageSize=50&index=$idx"
                    parse(JsonParser.parseString(get(url, mapOf("x-api-key" to key, "Accept" to "application/json"))).asJsonObject.get("data").arr())
                }.getOrElse { if (it.message?.contains("403") == true) searchRestricted = true; emptyList() }
            }
        }
        jobs.awaitAll().flatten().distinctBy { it.id }
    }

    suspend fun search(q: String, key: String, page: Int = 1, cat: Category = Category.ALL): List<ModItem> = withContext(Dispatchers.IO) {
        if (key.isBlank()) return@withContext emptyList()
        if (q.isBlank() && !searchRestricted) return@withContext if (page > 1) emptyList() else browse(cat, key)
        if (!searchRestricted) {
            val idx = (page - 1) * 20
            val url = "$API/mods/search?gameId=$BEDROCK_GAME_ID&pageSize=20&index=$idx&sortField=2&sortOrder=desc" +
                (classIds[cat]?.let { "&classId=$it" } ?: "") + if (q.isBlank()) "" else "&searchFilter=${enc(q)}"
            try {
                val root = JsonParser.parseString(get(url, mapOf("x-api-key" to key, "Accept" to "application/json"))).asJsonObject
                return@withContext parse(root.get("data").arr())
            } catch (e: IllegalStateException) {
                if (e.message?.contains("403") != true) throw e
                searchRestricted = true
            }
        }
        // Fallback: the popular list, filtered locally by the query.
        if (page > 1) return@withContext emptyList()
        val body = """{"gameId":$BEDROCK_GAME_ID,"excludedModIds":[],"gameVersionTypeId":null}"""
        val req = Request.Builder().url("$API/mods/featured").header("x-api-key", key)
            .header("Accept", "application/json").header("User-Agent", UA)
            .post(body.toRequestBody("application/json".toMediaType())).build()
        val text = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code} from api.curseforge.com")
            r.body?.string() ?: ""
        }
        val d = JsonParser.parseString(text).asJsonObject.getAsJsonObject("data")
        val all = (d.get("popular").arr() + d.get("featured").arr() + d.get("recentlyUpdated").arr())
        val words = q.lowercase().split(' ').filter { it.isNotBlank() }
        parse(JsonArray().also { a -> all.forEach { a.add(it) } })
            .filter { m -> words.all { w -> (m.title + " " + m.summary).lowercase().contains(w) } }
            .distinctBy { it.id }
    }

    suspend fun description(modId: String, key: String): String = withContext(Dispatchers.IO) {
        if (key.isBlank()) error("no API key")
        JsonParser.parseString(get("$API/mods/$modId/description", mapOf("x-api-key" to key, "Accept" to "application/json")))
            .asJsonObject.get("data").asString
    }

    suspend fun files(modId: String, key: String): List<ModFile> = withContext(Dispatchers.IO) {
        val root = JsonParser.parseString(
            get("$API/mods/$modId/files?pageSize=10", mapOf("x-api-key" to key, "Accept" to "application/json"))
        ).asJsonObject
        var blocked = 0
        val out = root.get("data").arr().map { it.asJsonObject }.mapNotNull { f ->
            val name = f.str("fileName")
            if (!isInstallable(name)) return@mapNotNull null
            val dl = f.str("downloadUrl")
            // The author turned off third-party downloads (CurseForge returns no download URL). Respect that: do NOT rebuild
            // the CDN link from the file id, send the user to the CurseForge page instead.
            if (dl.isBlank()) { blocked++; return@mapNotNull null }
            ModFile(name, dl, f.str("displayName"))
        }
        if (out.isEmpty() && blocked > 0) error("The author of this mod doesn't allow downloads outside CurseForge. Use \"Open website\" to get it there.")
        out
    }
}

object Modrinth {
    private const val API = "https://api.modrinth.com/v2"

    suspend fun search(q: String, page: Int = 1): List<ModItem> = withContext(Dispatchers.IO) {
        // Modrinth has no Bedrock loader; results are mostly Java and get flagged when no Bedrock file exists.
        if (q.isBlank()) return@withContext emptyList()
        val url = "$API/search?query=${enc(q)}&limit=20&offset=${(page - 1) * 20}"
        val root = JsonParser.parseString(get(url)).asJsonObject
        root.get("hits").arr().map { it.asJsonObject }.map { o ->
            ModItem(
                source = Source.MODRINTH, id = o.str("project_id"), title = o.str("title"),
                summary = o.str("description"), image = o.str("icon_url").ifBlank { null },
                author = o.str("author"), downloads = o.lng("downloads"),
                categories = o.get("categories").arr().map { it.asString }.take(3),
                updated = o.str("date_modified").take(10),
                webUrl = "https://modrinth.com/project/${o.str("slug")}",
            )
        }
    }

    suspend fun body(projectId: String): String = withContext(Dispatchers.IO) {
        JsonParser.parseString(get("$API/project/$projectId")).asJsonObject.str("body")
    }

    suspend fun files(projectId: String): List<ModFile> = withContext(Dispatchers.IO) {
        val arr = JsonParser.parseString(get("$API/project/$projectId/version")).asJsonArray
        arr.flatMap { v -> v.asJsonObject.get("files").arr().map { it.asJsonObject } }
            .filter { isInstallable(it.str("filename")) }
            .map { ModFile(it.str("filename"), it.str("url")) }
    }
}

data class SearchResult(val items: List<ModItem>, val errors: Map<Source, String>)

/** Queries every enabled source at once and merges the answers (de-duplicated) into one list. */
suspend fun searchAll(q: String, cfKey: String, enabled: Set<Source>, page: Int = 1, cat: Category = Category.ALL): SearchResult = coroutineScope {
    val errors = mutableMapOf<Source, String>()
    val jobs = enabled.map { s ->
        async {
            s to runCatching {
                when (s) {
                    Source.MCPEDL -> Mcpedl.search(q, page, cat)
                    Source.CURSEFORGE -> CurseForge.search(q, cfKey, page, cat)
                    Source.MODRINTH -> if (cat == Category.ALL || cat == Category.ADDONS) Modrinth.search(q, page) else emptyList()
                }
            }
        }
    }
    val all = mutableListOf<ModItem>()
    jobs.awaitAll().forEach { (s, r) ->
        r.onSuccess { all += it }.onFailure { errors[s] = it.message ?: "failed" }
    }
    // De-dupe: MCPEDL mirrors CurseForge, so the same addon often appears twice. Keep the richer / first copy.
    fun key(t: String) = t.lowercase().filter { c -> c.isLetterOrDigit() }
    // Keep each source's own (relevance) order; the app applies the user's chosen sort afterwards.
    val merged = if (q.isBlank()) {
        // Home lists: the same addon on two sites keeps the copy with more downloads.
        val best = all.groupBy { key(it.title) }.mapValues { (_, v) -> v.maxByOrNull { it.downloads }!! }
        val seen = HashSet<String>()
        all.filter { seen.add(key(it.title)) }.map { best.getValue(key(it.title)) }
    } else {
        val seen = HashSet<String>()
        all.filter { seen.add(key(it.title)) }
    }
    SearchResult(merged, errors)
}

suspend fun resolveFiles(item: ModItem, cfKey: String): List<ModFile> = when (item.source) {
    Source.MCPEDL -> Mcpedl.files(item.id)
    Source.CURSEFORGE -> CurseForge.files(item.id, cfKey)
    Source.MODRINTH -> Modrinth.files(item.id)
}

/** Full description for the detail page: readable text plus the images used in it. */
suspend fun fullDescription(item: ModItem, cfKey: String): Pair<String, List<String>> {
    val html = when (item.source) {
        Source.MCPEDL -> Mcpedl.description(item.id)
        Source.CURSEFORGE -> CurseForge.description(item.id, cfKey)
        Source.MODRINTH -> {
            // Modrinth bodies are markdown: pull images out, drop link/emphasis syntax, keep line breaks.
            val md = Modrinth.body(item.id)
            val imgs = Regex("""!\[[^\]]*]\(([^)\s]+)""").findAll(md).joinToString("") { "<img src=\"${it.groupValues[1]}\">" }
            val txt = md.replace(Regex("""!\[[^\]]*]\([^)]*\)"""), "").replace(Regex("""\[([^\]]*)]\([^)]*\)"""), "$1")
                .replace(Regex("""(?m)^#+\s*"""), "").replace("**", "").replace("__", "").replace("`", "")
                .replace("<", "&lt;").replace("\n", "<br>")
            imgs + txt
        }
    }
    return htmlToDescription(html)
}

fun htmlToDescription(html: String): Pair<String, List<String>> {
    val imgs = Regex("""<img[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(html)
        .map { it.groupValues[1] }.filter { it.startsWith("http") }.distinct().take(12).toList()
    val text = androidx.core.text.HtmlCompat.fromHtml(html, androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
        .replace('\uFFFC', ' ').replace('\u00A0', ' ')
        .replace(Regex("(?m)^[ \t]+$"), "").replace(Regex("[ \t]+\n"), "\n").replace(Regex("\n{3,}"), "\n\n").trim()
    return text to imgs
}

/** Pick what to install: the newest release = same version tag, or uploaded together (R+B pair). */
fun latestGroup(files: List<ModFile>): List<ModFile> {
    if (files.isEmpty()) return files
    val first = files.first()
    fun mins(d: String) = runCatching { java.time.Instant.parse(d).epochSecond / 60 }.getOrNull()
    val t0 = mins(first.date)
    return files.filter { f ->
        (first.group.isNotBlank() && f.group == first.group) ||
            (first.group.isBlank() && t0 != null && mins(f.date)?.let { kotlin.math.abs(it - t0) <= 30 } == true) ||
            f === first
    }
}
