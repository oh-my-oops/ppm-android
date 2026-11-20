package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.long
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** What `POST /api/project/:name/shell/run` answers (`CodeBlockRunResult`). */
data class ShellRunResult(
    val output: String,
    /** Null when the command never started. */
    val exitCode: Int?,
    val timedOut: Boolean,
    val stopped: Boolean,
    val durationMs: Long,
) {
    companion object {
        fun from(data: JsonElement?): ShellRunResult? {
            val output = data["output"].string ?: return null
            return ShellRunResult(
                output = output,
                exitCode = data["exitCode"].int,
                timedOut = data["timedOut"].bool ?: false,
                stopped = data["stopped"].bool ?: false,
                durationMs = data["durationMs"].long ?: 0L,
            )
        }
    }
}

/**
 * A run ends at the server's 2 minute limit (`CODE_BLOCK_RUN_TIMEOUT_MS`), with nothing sent until
 * then, so the read waits 2.5 minutes.
 */
internal const val SHELL_RUN_READ_TIMEOUT_SECONDS = 150L

internal val shellRunClient: OkHttpClient by lazy { PpmHttp.client.newBuilder().readTimeout(SHELL_RUN_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS).build() }

/**
 * `POST /api/project/:name/shell/run` (the Run button on a chat's shell code block): [command]
 * run in the project's folder, and what it printed. Cancelling the coroutine cancels the call,
 * and the server kills the run when its request closes.
 */
suspend fun ApiClient.runShellCommand(project: String, command: String): ShellRunResult {
    val body = buildJsonObject { put("command", command) }
    val request = request("POST", "/api/project/${ApiClient.escape(project)}/shell/run", body = body.toString().toRequestBody("application/json".toMediaType()))
    return ShellRunResult.from(send(shellRunClient, request)) ?: throw PpmError.BadResponse("The run's output could not be read.")
}
