package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.util.Base64
import kotlin.math.max

/*
 * The messages between a page PPM shows and the bridge script the server puts in it
 * (src/shared/design-bridge-protocol.ts and design-bridge-messages-check.ts): the ones an HTML
 * preview uses.
 *
 * Every message is an envelope `{ppm: "design-bridge", v: 1, nonce, type, …}`. The nonce is minted
 * by the parent for every load (`?n=` on the page's URL) and baked into the page by the server;
 * the bridge echoes it on every message. A child message counts only when it comes from the
 * page's frame *and* carries the current nonce: a page the frame navigated to on its own never
 * saw it, which is why parent → frame envelopes never carry one. Everything a child sends is
 * untrusted — the page's own scripts can post the same shapes — so every field is capped here,
 * and nothing a child says ever causes a write.
 */

const val BRIDGE_CHANNEL = "design-bridge"
const val BRIDGE_VERSION = 1

/** Shape of the per-load nonce. Anything else is dropped by the server and the parent. */
val BRIDGE_NONCE_RE = Regex("^[A-Za-z0-9_-]{16,64}$")

/** Most `issue` messages one page load is kept for. */
const val MAX_BRIDGE_ISSUES = 20

/** Generous for the screenshot library's ~30 KB; anything bigger is not the library. */
const val MAX_CHECK_LIB_CHARS = 256 * 1024

private val ISSUE_KINDS = listOf("error", "rejection", "resource", "csp")

/** A message from the page's bridge, validated; [nonce] is for the caller to compare. */
sealed interface BridgeChildMessage {
    val nonce: String?

    /** A new document has loaded and the bridge in it is listening. */
    data class Ready(
        override val nonce: String?,
        val gen: String,
        val cssGens: Map<String, String>,
        val file: String,
        val instrumented: Boolean,
        val title: String,
        val docHeight: Double,
    ) : BridgeChildMessage

    /** One of [ISSUE_KINDS]: an error, a rejected promise, a failed load or a CSP refusal. */
    data class Issue(
        override val nonce: String?,
        val kind: String,
        val message: String,
        val source: String? = null,
        val line: Double? = null,
    ) : BridgeChildMessage

    data class CheckResult(override val nonce: String?, val requestId: String, val report: LayoutCheckReport) : BridgeChildMessage

    data class CheckError(override val nonce: String?, val requestId: String, val message: String) : BridgeChildMessage

    companion object {
        /**
         * A validated child message, or null: a wrong envelope, a type an HTML preview does not use, or
         * a field out of shape (`parseChildMessage`).
         */
        fun parse(data: JsonElement?): BridgeChildMessage? {
            if (data !is JsonObject) return null
            if (data["ppm"].string != BRIDGE_CHANNEL || data["v"].double != BRIDGE_VERSION.toDouble()) return null
            val type = data["type"].string ?: return null
            val rawNonce = data["nonce"]
            val nonce = when {
                rawNonce is JsonNull -> null
                else -> rawNonce.string?.takeIf { BRIDGE_NONCE_RE.matches(it) } ?: return null
            }
            return when (type) {
                "ready" -> ready(data, nonce)
                "issue" -> {
                    val kind = data["kind"].string?.takeIf { it in ISSUE_KINDS } ?: return null
                    val message = data["message"].string?.take(500) ?: return null
                    Issue(nonce, kind, message, data["source"].string?.take(1024), data["line"].double?.takeIf { it.isFinite() })
                }
                "check-result" -> {
                    val id = requestId(data["requestId"]) ?: return null
                    val report = LayoutCheckReport.from(data["report"]) ?: return null
                    CheckResult(nonce, id, report)
                }
                "check-error" -> {
                    val id = requestId(data["requestId"]) ?: return null
                    val message = data["message"].string ?: return null
                    CheckError(nonce, id, message.take(300))
                }
                else -> null
            }
        }

        private fun ready(m: JsonObject, nonce: String?): Ready? {
            val gen = m["gen"].string?.takeIf { DESIGN_GEN_RE.matches(it) } ?: return null
            val file = m["file"].string?.take(1024) ?: return null
            val gens = cssGens(m["cssGens"]) ?: return null
            val instrumented = m["instrumented"].bool ?: return null
            val docHeight = m["docHeight"].double?.takeIf { it.isFinite() } ?: 0.0
            return Ready(nonce, gen, gens, file, instrumented, m["title"].string?.take(200) ?: "", max(0.0, docHeight))
        }

        private fun cssGens(value: JsonElement?): Map<String, String>? {
            if (value !is JsonObject || value.size > 64) return null
            val out = LinkedHashMap<String, String>()
            for ((file, gen) in value) {
                val g = gen.string
                if (file.length > 512 || g == null || !DESIGN_GEN_RE.matches(g)) return null
                out[file] = g
            }
            return out
        }

        private fun requestId(value: JsonElement?): String? = value.string?.takeIf { CHECK_REQUEST_ID_RE.matches(it) }
    }
}

/**
 * `check-run`, as the parent posts it: asks the bridge to measure the live page, with an image
 * when [screenshot], for which [lib] carries the screenshot library's source — the frame can load
 * nothing from the app, so the parent hands the code over. Null when the request is out of shape,
 * as the web's parent validates a message before it leaves.
 *
 * No nonce: the frame never needs one to trust its parent, and sending one would hand it to any
 * page the frame navigated itself to, for that page to echo straight back and forge a `ready`.
 */
fun checkRunEnvelope(requestId: String, screenshot: Boolean, lib: String? = null): JsonObject? {
    if (!CHECK_REQUEST_ID_RE.matches(requestId)) return null
    if (lib != null && lib.length > MAX_CHECK_LIB_CHARS) return null
    return buildJsonObject {
        put("type", "check-run")
        put("requestId", requestId)
        put("screenshot", screenshot)
        if (lib != null) put("lib", lib)
        put("ppm", BRIDGE_CHANNEL)
        put("v", BRIDGE_VERSION)
        put("nonce", null as String?)
    }
}

private val random = SecureRandom()

/** 16 random bytes as base64url: 22 characters, inside the nonce's shape. Also a check's request id. */
fun newBridgeNonce(): String {
    val bytes = ByteArray(16)
    random.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
