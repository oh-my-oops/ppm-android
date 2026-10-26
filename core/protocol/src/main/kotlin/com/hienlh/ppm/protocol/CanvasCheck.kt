package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.ceil
import kotlin.math.floor

/*
 * The page self-check's report (src/shared/design-canvas-check.ts): what the bridge measured in
 * the live page, plus what the parent adds (the runtime issues the bridge already reported, the
 * device frame). Everything in it was produced inside the page, where the page's own scripts
 * run, so it is untrusted: [LayoutCheckReport.from] caps every field, and the server parses the
 * report again before an agent reads it as text.
 */

val CHECK_KINDS = listOf("implicit-grid", "runtime", "page-overflow", "offscreen", "clipped", "collapsed", "overlap")

const val MAX_CHECK_FINDINGS = 30
const val MAX_FINDING_CHARS = 300
const val MAX_ELEMENT_CHARS = 200

/** The decoded image may be at most this big; the data URL is base64, a third larger. */
const val MAX_SCREENSHOT_BYTES = 400 * 1024

/** Shape of a check request id: minted by whoever asks, echoed by whoever answers. */
val CHECK_REQUEST_ID_RE = Regex("^[A-Za-z0-9_-]{8,64}$")

/** A design document's generation, as the bridge reports it in `ready`. */
val DESIGN_GEN_RE = Regex("^[0-9a-f]{16}$")

private val DATA_URL_RE = Regex("^data:image/(?:jpeg|png);base64,[A-Za-z0-9+/]+={0,2}$")
private val MAX_DATA_URL_CHARS = ceil(MAX_SCREENSHOT_BYTES / 3.0).toInt() * 4 + 32

data class CanvasCheckFinding(
    /** One of [CHECK_KINDS]. */
    val kind: String,
    val message: String,
    /** The element it is about: tag, id/classes, `data-ppm-id`, a little text. */
    val element: String? = null,
) {
    val json: JsonObject
        get() = buildJsonObject {
            put("kind", kind)
            put("message", message)
            if (element != null) put("element", element)
        }
}

data class CheckSize(val width: Int, val height: Int) {
    val json: JsonObject
        get() = buildJsonObject {
            put("width", width)
            put("height", height)
        }
}

data class CanvasCheckScreenshot(val dataUrl: String, val width: Int, val height: Int)

/** What the bridge measures. */
data class LayoutCheckReport(
    val viewport: CheckSize,
    val page: CheckSize,
    val findings: List<CanvasCheckFinding>,
    /** Findings per kind before the cap, so a truncated list still says how much was cut; in the order they were counted. */
    val counts: Map<String, Int>,
    val screenshot: CanvasCheckScreenshot? = null,
    /** Why there is no screenshot, or what it may get wrong. */
    val screenshotNote: String? = null,
) {
    companion object {
        /** A validated bridge report, or null. Findings beyond the cap and unknown kinds are dropped. */
        fun from(raw: JsonElement?): LayoutCheckReport? {
            if (raw !is JsonObject) return null
            val rawFindings = raw["findings"].array ?: return null
            val viewport = size(raw["viewport"]) ?: return null
            val page = size(raw["page"]) ?: return null
            val findings = rawFindings.take(MAX_CHECK_FINDINGS).mapNotNull { f ->
                if (f !is JsonObject) return@mapNotNull null
                val kind = f["kind"].string?.takeIf { it in CHECK_KINDS } ?: return@mapNotNull null
                val message = text(f["message"], MAX_FINDING_CHARS)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                CanvasCheckFinding(kind, message, text(f["element"], MAX_ELEMENT_CHARS)?.takeIf { it.isNotEmpty() })
            }
            val counts = LinkedHashMap<String, Int>()
            val rawCounts = raw["counts"]
            if (rawCounts is JsonObject) {
                for (kind in CHECK_KINDS) {
                    val n = dim(rawCounts[kind], 1_000_000.0)
                    if (n != null && n != 0) counts[kind] = n
                }
            }
            return LayoutCheckReport(
                viewport = viewport,
                page = page,
                findings = findings,
                counts = counts,
                screenshot = screenshot(raw["screenshot"]),
                screenshotNote = text(raw["screenshotNote"], MAX_FINDING_CHARS)?.takeIf { it.isNotEmpty() },
            )
        }

        /** A number from 0 to [max], rounded as `Math.round` rounds; null for anything else. */
        private fun dim(value: JsonElement?, max: Double = 100_000.0): Int? {
            val v = value.double ?: return null
            return if (v.isFinite() && v >= 0 && v <= max) floor(v + 0.5).toInt() else null
        }

        private fun text(value: JsonElement?, max: Int): String? = value.string?.take(max)

        private fun size(value: JsonElement?): CheckSize? {
            if (value !is JsonObject) return null
            val width = dim(value["width"]) ?: return null
            val height = dim(value["height"]) ?: return null
            return CheckSize(width, height)
        }

        private fun screenshot(value: JsonElement?): CanvasCheckScreenshot? {
            if (value !is JsonObject) return null
            val dataUrl = value["dataUrl"].string ?: return null
            if (dataUrl.length > MAX_DATA_URL_CHARS || !DATA_URL_RE.matches(dataUrl)) return null
            val width = dim(value["width"], 4096.0)?.takeIf { it != 0 } ?: return null
            val height = dim(value["height"], 8192.0)?.takeIf { it != 0 } ?: return null
            return CanvasCheckScreenshot(dataUrl, width, height)
        }
    }
}

/** What the parent sends on: the bridge's report plus the document and frame it came from. */
data class CanvasCheckReport(
    val layout: LayoutCheckReport,
    val file: String,
    val gen: String?,
    /** The device frame the page was showing ("Phone", "Desktop"). */
    val frame: String,
) {
    val json: JsonObject
        get() = buildJsonObject {
            put("viewport", layout.viewport.json)
            put("page", layout.page.json)
            put("findings", buildJsonArray { layout.findings.forEach { add(it.json) } })
            put("counts", buildJsonObject { layout.counts.forEach { (kind, n) -> put(kind, n) } })
            layout.screenshot?.let { shot ->
                put(
                    "screenshot",
                    buildJsonObject {
                        put("dataUrl", shot.dataUrl)
                        put("width", shot.width)
                        put("height", shot.height)
                    },
                )
            }
            layout.screenshotNote?.let { put("screenshotNote", it) }
            put("file", file)
            put("gen", gen)
            put("frame", frame)
        }
}

/** An error, a rejected promise, a failed load or a CSP refusal the page reported while it loaded. */
data class RuntimeIssue(val kind: String, val message: String, val source: String? = null, val line: Double? = null)

/**
 * Folds the runtime issues the parent collected for this load into a bridge report, in the order
 * [CHECK_KINDS] lists, keeping the overall cap (`withRuntimeIssues`).
 */
fun LayoutCheckReport.withRuntimeIssues(issues: List<RuntimeIssue>): LayoutCheckReport {
    val runtime = issues.map { issue ->
        val line = issue.line?.takeIf { it != 0.0 && !it.isNaN() }?.let { ":${jsNumber(it)}" } ?: ""
        val where = if (!issue.source.isNullOrEmpty()) " (${issue.source}$line)" else ""
        CanvasCheckFinding("runtime", "${issue.kind}: ${issue.message}$where".take(MAX_FINDING_CHARS))
    }
    val sorted = (findings + runtime).sortedBy { CHECK_KINDS.indexOf(it.kind) }
    val counts = LinkedHashMap(counts)
    if (runtime.isNotEmpty()) counts["runtime"] = (counts["runtime"] ?: 0) + runtime.size
    return copy(findings = sorted.take(MAX_CHECK_FINDINGS), counts = counts)
}
