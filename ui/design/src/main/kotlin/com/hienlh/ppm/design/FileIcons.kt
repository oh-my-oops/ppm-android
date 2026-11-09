package com.hienlh.ppm.design

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.caverock.androidsvg.RenderOptions
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int

/** A project's file-naming convention, which draws `.module.ts` and five other suffixes its own way. */
enum class IconFramework(val wire: String) {
    NEST("nest"),
    ANGULAR("angular");

    companion object {
        /** Checked in order, so a repository holding both is read as Nest. */
        private val MARKERS = listOf(NEST to "@nestjs/core", ANGULAR to "@angular/core")

        /** The framework a `package.json` depends on (`project-framework-store.ts`); null for neither, or for no JSON. */
        fun of(packageJson: String): IconFramework? {
            val pkg = try {
                Json.parseToJsonElement(packageJson) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: return null
            val deps = listOf("dependencies", "devDependencies", "peerDependencies").flatMapTo(HashSet()) { (pkg[it] as? JsonObject)?.keys.orEmpty() }
            return MARKERS.firstOrNull { it.second in deps }?.first
        }
    }
}

/**
 * Which glyph of the vscode-icons theme a name gets (`file-icon-name.ts`), from the web's own
 * tables. Resolution is the theme's: the whole filename first, then each dotted suffix from the
 * longest, the project's framework consulted at each length before the base table.
 */
class FileIconTables(
    private val extensions: Map<String, String>,
    private val filenames: Map<String, String>,
    private val frameworkExtensions: Map<IconFramework, Map<String, String>>,
    private val frameworkFilenames: Map<IconFramework, Map<String, String>>,
    private val folders: Map<String, String>,
    private val foldersOpen: Map<String, String>,
    private val dbEngines: Map<String, String>,
    val defaultFile: String,
    private val defaultFolder: String,
    private val defaultFolderOpen: String,
    private val maxExtensionSegments: Int,
    /** Glyphs drawn again for a light workbench. */
    val light: Set<String>,
) {
    private fun basename(path: String) = path.substring(maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1)

    fun file(path: String, framework: IconFramework? = null): String {
        val name = basename(path).lowercase()
        (framework?.let { frameworkFilenames[it]?.get(name) } ?: filenames[name])?.let { return it }
        val overlay = framework?.let { frameworkExtensions[it] }
        // A leading dot makes the first part empty, so `.npmrc` enters the loop at `npmrc`.
        val parts = name.split('.')
        for (i in maxOf(1, parts.size - maxExtensionSegments) until parts.size) {
            val suffix = parts.subList(i, parts.size).joinToString(".")
            (overlay?.get(suffix) ?: extensions[suffix])?.let { return it }
        }
        return defaultFile
    }

    fun folder(path: String, open: Boolean = false): String {
        val name = basename(path).lowercase()
        return (if (open) foldersOpen else folders)[name] ?: if (open) defaultFolderOpen else defaultFolder
    }

    /** A database engine's logo; an engine with none gets the generic SQL glyph. */
    fun dbEngine(type: String): String = dbEngines[type] ?: extensions["sql"] ?: defaultFile

    companion object {
        fun parse(text: String): FileIconTables {
            val json = Json.parseToJsonElement(text) as JsonObject
            fun map(key: String) = (json[key] as JsonObject).mapValues { (it.value as JsonPrimitive).content }
            fun frameworks(key: String) = (json[key] as JsonObject).entries.mapNotNull { (name, table) ->
                val framework = IconFramework.entries.firstOrNull { it.wire == name } ?: return@mapNotNull null
                framework to (table as JsonObject).mapValues { (it.value as JsonPrimitive).content }
            }.toMap()
            fun string(key: String) = (json[key] as JsonPrimitive).content
            return FileIconTables(
                map("extensions"), map("filenames"), frameworks("frameworkExtensions"), frameworks("frameworkFilenames"),
                map("folders"), map("foldersOpen"), map("dbEngines"),
                string("defaultFile"), string("defaultFolder"), string("defaultFolderOpen"),
                (json["maxExtensionSegments"] as JsonPrimitive).int,
                (json["light"] as JsonArray).mapTo(HashSet()) { (it as JsonPrimitive).content },
            )
        }
    }
}

/**
 * The file icons' artwork, read from the assets `buildFileIcons` makes and drawn by AndroidSVG at
 * the size asked for, as Chrome draws the theme's SVG backgrounds (`bg-contain bg-center`).
 */
class FileIcons private constructor(private val context: Context) {
    private val loading = Mutex()
    private var tables: FileIconTables? = null
    private val bitmaps = LruCache<String, ImageBitmap>(256)

    suspend fun tables(): FileIconTables = tables ?: loading.withLock {
        tables ?: withContext(Dispatchers.IO) {
            FileIconTables.parse(context.assets.open("file-icons/tables.json").use { it.readBytes().decodeToString() })
        }.also { tables = it }
    }

    /** [name]'s glyph at [px] square, the light drawing when there is one and [light]. */
    suspend fun bitmap(name: String, light: Boolean, px: Int): ImageBitmap? {
        val table = tables()
        val variant = if (light && name in table.light) "light" else "dark"
        val key = "$variant/$name@$px"
        bitmaps.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val svg = context.assets.open("file-icons/$variant/$name.svg").use { SVG.getFromInputStream(it) }
                val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
                svg.renderToCanvas(Canvas(bitmap), RenderOptions.create().viewPort(0f, 0f, px.toFloat(), px.toFloat()))
                bitmap.asImageBitmap().also { bitmaps.put(key, it) }
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Resolves [names] for an extension webview (`__ppm.fileIcons` in `webview-file-icons.ts`),
     * returning `(icons, rules)` where `icons` is `[(name, "vsi-<glyph>")]` and `rules` is
     * `[("vsi-<glyph>", css)]` with inline `data:image/svg+xml;base64,...` rules for only
     * the unique glyphs needed by [names].
     */
    suspend fun webviewFileIcons(
        names: List<String>,
        framework: IconFramework? = null,
    ): Pair<List<Pair<String, String>>, List<Pair<String, String>>> {
        if (names.isEmpty()) return emptyList<Pair<String, String>>() to emptyList()
        val table = tables()
        return withContext(Dispatchers.IO) {
            resolveWebviewFileIcons(
                names = names,
                table = table,
                framework = framework,
                readSvg = { variant, glyph ->
                    runCatching {
                        context.assets.open("file-icons/$variant/$glyph.svg").use { it.readBytes() }
                    }.getOrNull()
                },
            )
        }
    }

    companion object {
        @Volatile private var instance: FileIcons? = null

        fun get(context: Context): FileIcons = instance ?: synchronized(this) {
            instance ?: FileIcons(context.applicationContext).also { instance = it }
        }

        internal fun resolveWebviewFileIcons(
            names: List<String>,
            table: FileIconTables,
            framework: IconFramework? = null,
            readSvg: (variant: String, glyph: String) -> ByteArray?,
        ): Pair<List<Pair<String, String>>, List<Pair<String, String>>> {
            val icons = ArrayList<Pair<String, String>>(names.size)
            val rules = ArrayList<Pair<String, String>>()
            val seen = HashSet<String>()
            for (name in names) {
                val glyph = table.file(name, framework)
                val cls = "vsi-$glyph"
                icons.add(name to cls)
                if (!seen.add(cls)) continue
                val sb = StringBuilder()
                val darkBytes = readSvg("dark", glyph)
                if (darkBytes != null) {
                    val b64 = java.util.Base64.getEncoder().encodeToString(darkBytes)
                    sb.append('.').append(cls)
                        .append(" { background-image: url(\"data:image/svg+xml;base64,")
                        .append(b64).append("\"); }\n")
                }
                if (glyph in table.light) {
                    val lightBytes = readSvg("light", glyph)
                    if (lightBytes != null) {
                        val b64 = java.util.Base64.getEncoder().encodeToString(lightBytes)
                        sb.append(":root[data-ppm-theme=\"light\"] .").append(cls)
                            .append(" { background-image: url(\"data:image/svg+xml;base64,")
                            .append(b64).append("\"); }\n")
                    }
                }
                val css = sb.toString()
                if (css.isNotEmpty()) {
                    rules.add(cls to css)
                }
            }
            return icons to rules
        }
    }
}

/** The framework the project on screen follows (`useIconFramework`); null for neither. */
val LocalIconFramework = staticCompositionLocalOf<IconFramework?> { null }

/**
 * The one file icon (`FileIcon`): the vscode-icons glyph for [name] (a whole path is fine), a
 * folder's when [folder], open or closed. Blank until its artwork is read, as the web's is until
 * its stylesheet lands.
 */
@Composable
fun FileIcon(name: String, modifier: Modifier = Modifier, size: Dp = 16.dp, folder: Boolean = false, open: Boolean = false) {
    val icons = FileIcons.get(LocalContext.current)
    val framework = LocalIconFramework.current
    val light = !LocalPpmTheme.current.isDark
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(null, name, folder, open, framework, light, px) {
        val tables = icons.tables()
        value = icons.bitmap(if (folder) tables.folder(name, open) else tables.file(name, framework), light, px)
    }
    val image = bitmap
    if (image == null) Box(modifier.size(size)) else Image(image, null, modifier.size(size), filterQuality = FilterQuality.None)
}
