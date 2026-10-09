package us.fishfarts.modhub

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

const val MC_PKG = "com.mojang.minecraftpe"
const val MC_PREVIEW_PKG = "com.mojang.minecraftpe.preview"

fun minecraftPackage(ctx: Context): String? =
    listOf(MC_PKG, MC_PREVIEW_PKG).firstOrNull {
        runCatching { ctx.packageManager.getPackageInfo(it, 0) }.isSuccess
    }

object Installer {
    /** Downloads [files] into the cache and returns them (no import yet). */
    suspend fun download(ctx: Context, files: List<ModFile>, onStatus: (String) -> Unit): List<File> {
        val dir = File(ctx.cacheDir, "dl").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        return files.mapIndexed { i, f ->
            onStatus("Downloading ${i + 1}/${files.size}: ${f.name}")
            val out = File(dir, f.name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
            withContext(Dispatchers.IO) {
                val req = Request.Builder().url(f.url).header("User-Agent", UA).build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) error("Download failed (HTTP ${r.code})")
                    r.body!!.byteStream().use { inp -> out.outputStream().use { inp.copyTo(it) } }
                }
                if (out.length() < 64) error("Downloaded file is empty")
            }
            out
        }
    }

    /** Hands one downloaded file to Minecraft's own importer. */
    fun openInMinecraft(ctx: Context, file: File) {
        val pkg = minecraftPackage(ctx) ?: error("Minecraft Bedrock isn't installed on this phone")
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        ctx.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/octet-stream")
            setPackage(pkg)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
