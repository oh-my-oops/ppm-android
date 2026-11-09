package com.hienlh.ppm.design

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.IsolateTerminatedException
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import androidx.javascriptengine.SandboxDeadException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A code block as Shiki colours it: the runs, over the theme's own text colour and background. */
@Immutable
class HighlightedCode(val text: AnnotatedString, val foreground: Color, val background: Color)

/**
 * The chat's code highlighting: the web's Shiki (shiki-adapter.ts), with its themes and grammars,
 * in a V8 isolate of androidx.javascriptengine — WebView's sandboxed process — run from the assets
 * `buildHighlighter` makes. One per process, started with the app as the web warms Shiki at load.
 * A grammar past the web's preloaded ones is read the first time a block asks for it. Where there
 * is no sandbox (a WebView too old for it) or it keeps dying, code stays plain, as on the web
 * before Shiki has loaded.
 */
class SyntaxHighlighter private constructor(private val context: Context) {
    private class Engine(val sandbox: JavaScriptSandbox, val isolate: JavaScriptIsolate, val loaded: MutableSet<String>) {
        @Volatile var dead = false

        fun close() {
            runCatching { isolate.close() }
            // One sandbox per process: the next start needs this one gone.
            runCatching { sandbox.close() }
        }
    }

    /** `langs.json`: every grammar with the ones it embeds, and every alias. */
    private class Index(val embeds: Map<String, List<String>>, val aliases: Map<String, String>)

    private data class Key(val code: String, val language: String, val theme: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private var index: Index? = null
    private var engine: Engine? = null
    private var startsLeft = MAX_STARTS

    /** The web's `htmlCache`: the last 600 blocks by code, language and theme; null for one that failed. */
    private val cache = object : LinkedHashMap<Key, HighlightedCode?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, HighlightedCode?>) = size > CACHE_SIZE
    }

    /** Starts the isolate now, so the first finished block is not the one that waits for it. */
    fun warm() {
        scope.launch { mutex.withLock { engine() } }
    }

    /** [code] already highlighted, for a block's first frame; null when it was not. */
    fun cached(code: String, language: String?, theme: String): HighlightedCode? =
        synchronized(cache) { cache[Key(code, normalize(language), theme)] }

    /** [code] in [language] (a fence's info word, or null) and the Shiki [theme]; null when it cannot be. */
    suspend fun highlight(code: String, language: String?, theme: String): HighlightedCode? {
        val key = Key(code, normalize(language), theme)
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }
        return mutex.withLock {
            synchronized(cache) { if (cache.containsKey(key)) return@withLock cache[key] }
            val engine = engine() ?: return@withLock null
            val result = try {
                tokens(engine, key)
            } catch (e: Exception) {
                Log.w(TAG, "could not highlight a block of ${key.language.ifEmpty { "text" }}", e)
                if (e is IsolateTerminatedException || e is SandboxDeadException) engine.dead = true
                null
            }
            synchronized(cache) { cache[key] = result }
            result
        }
    }

    /** Search/replace uses this process's existing V8 isolate, serialized with highlighting. */
    suspend fun evaluateRegexOperations(request: RegexOperationsRequest): RegexOperationsResult {
        val script = withContext(Dispatchers.Default) { regexOperationsScript(request) }
        val result = evaluateDocumentOperation(script, 3_000, "Regular expression timed out. Simplify the pattern and retry.")
        return withContext(Dispatchers.Default) { parseRegexOperations(result) }
    }

    /** The bundled web sanitizer must succeed; never return the original HTML on failure. */
    suspend fun sanitizeDocumentHtml(html: String): String {
        val script = withContext(Dispatchers.Default) { "ppmSanitizeDocx(${quote(html)})" }
        return evaluateDocumentOperation(script, 10_000, "Document sanitization timed out. Retry with a smaller document.")
    }

    private suspend fun evaluateDocumentOperation(script: String, timeoutMillis: Long, timeoutMessage: String): String = mutex.withLock {
        val engine = engine() ?: throw IllegalStateException("JavaScript is unavailable. Update Android System WebView and retry.")
        try {
            // A disappearing result row must not kill the shared engine. Drain this fixed operation;
            // a pathological regex is bounded and its isolate is retired before another use.
            withContext(NonCancellable) {
                withTimeout(timeoutMillis) { engine.isolate.evaluateJavaScriptAsync(script).await() }
            }
        } catch (error: TimeoutCancellationException) {
            engine.dead = true
            engine.close()
            throw IllegalStateException(timeoutMessage, error)
        } catch (error: Exception) {
            if (error is IsolateTerminatedException || error is SandboxDeadException) engine.dead = true
            throw error
        }
    }

    private suspend fun tokens(engine: Engine, key: Key): HighlightedCode {
        val language = resolve(key.language)
        load(engine, language)
        val themeArg = if (key.theme.startsWith("{")) key.theme else quote(key.theme)
        val json = engine.isolate.evaluateJavaScriptAsync("ppmHighlight(${quote(key.code)},${quote(language)},$themeArg)").await()
        return withContext(Dispatchers.Default) { parse(json) }
    }

    /** The web's `normalizeLang`, then Shiki's aliases; "text" for a language Shiki does not have. */
    private fun resolve(language: String): String {
        val name = WEB_ALIASES[language] ?: language
        if (name.isEmpty()) return "text"
        if (name in SHIKI_SPECIAL) return name
        val index = index ?: return "text"
        val id = index.aliases[name] ?: name
        return if (id in index.embeds) id else "text"
    }

    private suspend fun load(engine: Engine, language: String) {
        if (language in engine.loaded) return
        val embeds = index?.embeds?.get(language) ?: return
        for (embedded in embeds) load(engine, embedded)
        engine.isolate.evaluateJavaScriptAsync("ppmLoadLanguage(${asset("shiki/langs/$language.json")})").await()
        engine.loaded += language
    }

    /** The isolate, started (again) if it is not running; null when the sandbox is not to be had. */
    private suspend fun engine(): Engine? {
        engine?.let {
            if (!it.dead) return it
            it.close()
        }
        engine = null
        if (startsLeft == 0) return null
        startsLeft--
        if (!JavaScriptSandbox.isSupported()) {
            startsLeft = 0
            return null
        }
        var sandbox: JavaScriptSandbox? = null
        return try {
            if (index == null) index = readIndex()
            sandbox = JavaScriptSandbox.createConnectedInstanceAsync(context).await()
            // The bundle is 1.3 MB, past a binder transaction.
            if (!sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT)) {
                sandbox.close()
                startsLeft = 0
                return null
            }
            val parameters = IsolateStartupParameters()
            if (sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE)) parameters.maxHeapSizeBytes = HEAP_BYTES
            val isolate = sandbox.createIsolate(parameters)
            val started = Engine(sandbox, isolate, mutableSetOf())
            if (sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_TERMINATION)) {
                isolate.addOnTerminatedCallback { started.dead = true }
            }
            isolate.evaluateJavaScriptAsync(asset("shiki/highlighter.js")).await()
            val loaded = isolate.evaluateJavaScriptAsync("ppmLanguages()").await()
            started.loaded += Json.parseToJsonElement(loaded).jsonArray.map { it.jsonPrimitive.content }
            engine = started
            started
        } catch (e: Exception) {
            Log.w(TAG, "the highlighter did not start", e)
            runCatching { sandbox?.close() }
            null
        }
    }

    private suspend fun readIndex(): Index {
        val root = Json.parseToJsonElement(asset("shiki/langs.json")).jsonObject
        return Index(
            embeds = root.getValue("languages").jsonObject.mapValues { (_, it) ->
                it.jsonObject.getValue("embedded").jsonArray.map { name -> name.jsonPrimitive.content }
            },
            aliases = root.getValue("aliases").jsonObject.mapValues { (_, it) -> it.jsonPrimitive.content },
        )
    }

    private suspend fun asset(path: String): String = withContext(Dispatchers.IO) {
        context.assets.open(path).use { it.readBytes().decodeToString() }
    }

    companion object {
        private const val TAG = "SyntaxHighlighter"
        private const val CACHE_SIZE = 600
        private const val HEAP_BYTES = 128L shl 20

        /** Starts of the isolate one process may make, so a block that kills it is not retried for ever. */
        private const val MAX_STARTS = 3

        /** shiki-adapter.ts's `LANG_ALIASES`, applied before Shiki's own. */
        private val WEB_ALIASES = mapOf(
            "js" to "javascript",
            "ts" to "typescript",
            "sh" to "bash",
            "shell" to "bash",
            "zsh" to "bash",
            "py" to "python",
            "rs" to "rust",
            "yml" to "yaml",
            "md" to "markdown",
            "dockerfile" to "docker",
            "golang" to "go",
        )

        /** Languages Shiki handles with no grammar: plain text in the theme's colours, and ANSI escapes. */
        private val SHIKI_SPECIAL = setOf("text", "plaintext", "txt", "plain", "ansi")

        @Volatile private var instance: SyntaxHighlighter? = null

        fun get(context: Context): SyntaxHighlighter = instance ?: synchronized(this) {
            instance ?: SyntaxHighlighter(context.applicationContext).also { instance = it }
        }

        private fun normalize(language: String?): String = language.orEmpty().trim().lowercase()

        private fun quote(text: String): String = JsonPrimitive(text).toString()

        /** `ppmHighlight`'s JSON: lines of `[text, colour, fontStyle]` runs, and the theme's colours. */
        private fun parse(json: String): HighlightedCode {
            val root = Json.parseToJsonElement(json).jsonObject
            fun colour(name: String) = root[name]?.jsonPrimitive?.contentOrNull?.let(Rgba::hex)?.color ?: Color.Unspecified
            val text = buildAnnotatedString {
                root.getValue("lines").jsonArray.forEachIndexed { index, line ->
                    if (index > 0) append('\n')
                    for (run in line.jsonArray) {
                        val (content, color, fontStyle) = run.jsonArray
                        val style = fontStyle.jsonPrimitive.int.coerceAtLeast(0)
                        val decorations = listOfNotNull(
                            TextDecoration.Underline.takeIf { style and 4 != 0 },
                            TextDecoration.LineThrough.takeIf { style and 8 != 0 },
                        )
                        withStyle(
                            SpanStyle(
                                color = color.jsonPrimitive.contentOrNull?.let(Rgba::hex)?.color ?: Color.Unspecified,
                                fontStyle = if (style and 1 != 0) FontStyle.Italic else null,
                                fontWeight = if (style and 2 != 0) FontWeight.Bold else null,
                                textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
                            ),
                        ) { append(content.jsonPrimitive.content) }
                    }
                }
            }
            return HighlightedCode(text, colour("fg"), colour("bg"))
        }
    }
}

/**
 * Shiki JSON theme matching Monaco's inherited `vs` / `vs-dark` base (`src/web/theme/adapters/monaco-adapter.ts`):
 * workbench foreground/background come from [tokens], while syntax tokens follow VS Code's `vs` / `vs-dark`.
 */
fun monacoEditorShikiTheme(isDark: Boolean, tokens: ThemeTokens): String {
    val fg = tokens.text.toHexRgb()
    val bg = tokens.bgSolid.toHexRgb()
    return if (isDark) {
        """{"name":"ppm-monaco-dark","type":"dark","colors":{"editor.foreground":"$fg","editor.background":"$bg"},"tokenColors":[""" +
            """{"scope":["comment","punctuation.definition.comment"],"settings":{"foreground":"#608B4E"}},""" +
            """{"scope":["comment.line.shebang","comment.line.shebang punctuation.definition.comment"],"settings":{"foreground":"#DD6A6F"}},""" +
            """{"scope":["string","punctuation.definition.string"],"settings":{"foreground":"#CE9178"}},""" +
            """{"scope":["meta.template.expression"],"settings":{"foreground":"$fg"}},""" +
            """{"scope":["punctuation.definition.template-expression"],"settings":{"foreground":"#CE9178"}},""" +
            """{"scope":["constant.numeric"],"settings":{"foreground":"#B5CEA8"}},""" +
            """{"scope":["string.regexp"],"settings":{"foreground":"#B46695"}},""" +
            """{"scope":["keyword","storage","constant.language","variable.language"],"settings":{"foreground":"#569CD6"}},""" +
            """{"scope":["keyword.operator","storage.type.function.arrow"],"settings":{"foreground":"$fg"}},""" +
            """{"scope":["keyword.operator.new","keyword.operator.expression","keyword.operator.word","keyword.operator.Logical"],"settings":{"foreground":"#569CD6"}},""" +
            """{"scope":["entity.name.tag"],"settings":{"foreground":"#569CD6"}},""" +
            """{"scope":["entity.other.attribute-name"],"settings":{"foreground":"#9CDCFE"}},""" +
            """{"scope":["support.type.property-name.json"],"settings":{"foreground":"#9CDCFE"}},""" +
            """{"scope":["string.quoted.double.json"],"settings":{"foreground":"#CE9178"}}""" +
            """]}"""
    } else {
        """{"name":"ppm-monaco-light","type":"light","colors":{"editor.foreground":"$fg","editor.background":"$bg"},"tokenColors":[""" +
            """{"scope":["comment","punctuation.definition.comment"],"settings":{"foreground":"#008000"}},""" +
            """{"scope":["comment.line.shebang","comment.line.shebang punctuation.definition.comment"],"settings":{"foreground":"#E00000"}},""" +
            """{"scope":["string","punctuation.definition.string"],"settings":{"foreground":"#A31515"}},""" +
            """{"scope":["meta.template.expression"],"settings":{"foreground":"$fg"}},""" +
            """{"scope":["punctuation.definition.template-expression"],"settings":{"foreground":"#A31515"}},""" +
            """{"scope":["constant.numeric"],"settings":{"foreground":"#098658"}},""" +
            """{"scope":["string.regexp"],"settings":{"foreground":"#800000"}},""" +
            """{"scope":["keyword","storage","constant.language","variable.language"],"settings":{"foreground":"#0000FF"}},""" +
            """{"scope":["keyword.operator","storage.type.function.arrow"],"settings":{"foreground":"$fg"}},""" +
            """{"scope":["keyword.operator.new","keyword.operator.expression","keyword.operator.word","keyword.operator.Logical"],"settings":{"foreground":"#0000FF"}},""" +
            """{"scope":["entity.name.tag"],"settings":{"foreground":"#800000"}},""" +
            """{"scope":["entity.other.attribute-name"],"settings":{"foreground":"#FF0000"}},""" +
            """{"scope":["support.type.property-name.json"],"settings":{"foreground":"#A31515"}},""" +
            """{"scope":["string.quoted.double.json"],"settings":{"foreground":"#0451A5"}}""" +
            """]}"""
    }
}
