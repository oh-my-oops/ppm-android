package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException

/** A file as the server serves it raw: its bytes and the type it was served as. */
class RawFile(val bytes: ByteArray, val contentType: String?)

/** A path that names a file from the root: `/…`, or a Windows drive. */
private val ABSOLUTE_PATH = Regex("""^(/|[A-Za-z]:[/\\])""")

/**
 * A file's bytes, read as `useBlobUrl` reads them: an absolute path from wherever the server lets
 * it be read (`GET /api/fs/raw`, which serves an image from any path), anything else from inside
 * [project] (`GET /api/project/:name/files/raw`). A refusal keeps its HTTP status, which the web
 * shows ("preview unavailable · 403").
 */
suspend fun ApiClient.rawFile(project: String, path: String): RawFile {
    val request = if (ABSOLUTE_PATH.containsMatchIn(path)) {
        request("GET", "/api/fs/raw", listOf("path" to path))
    } else {
        request("GET", "/api/project/${ApiClient.escape(project)}/files/raw", listOf("path" to path))
    }
    val response = try {
        PpmHttp.getClient.newCall(request).await()
    } catch (error: IOException) {
        throw PpmError.Unreachable(describe(error))
    }
    return withContext(Dispatchers.IO) {
        response.use {
            try {
                if (!it.isSuccessful) {
                    // An answer that is not PPM's JSON (a proxy's page) still keeps its status.
                    try {
                        ApiClient.unwrap(it.code, it.header("Content-Type"), it.body.string())
                    } catch (_: PpmError.BadResponse) {
                    }
                    throw PpmError.Server(it.code, "The server answered HTTP ${it.code}.")
                }
                RawFile(it.body.bytes(), it.header("Content-Type"))
            } catch (error: IOException) {
                throw PpmError.Unreachable(describe(error))
            }
        }
    }
}

/**
 * A picture a message shows from the web (`<img src="https://…">`): fetched as the browser
 * fetches one, from wherever it is, with nothing of the server's sent along.
 */
suspend fun webFile(url: String): RawFile {
    val request = try {
        Request.Builder().url(url).header("Accept", "image/*,*/*;q=0.8").get().build()
    } catch (_: IllegalArgumentException) {
        throw PpmError.InvalidUrl()
    }
    val response = try {
        PpmHttp.getClient.newCall(request).await()
    } catch (error: IOException) {
        throw PpmError.Unreachable(describe(error))
    }
    return withContext(Dispatchers.IO) {
        response.use {
            try {
                if (!it.isSuccessful) throw PpmError.Server(it.code, "The server answered HTTP ${it.code}.")
                RawFile(it.body.bytes(), it.header("Content-Type"))
            } catch (error: IOException) {
                throw PpmError.Unreachable(describe(error))
            }
        }
    }
}

/**
 * What `GET /api/fs/stat` says of a path: the path it resolved to on the host, and whether it is a
 * folder. A link is described as itself, a `symlink`, with what it points at in [target].
 */
data class FsStat(val path: String, val name: String, val kind: String, val target: String? = null) {
    val isDirectory: Boolean get() = kind == "directory"
}

/** `fsApi.stat`: [path] as the host reads it, `~` and all. */
suspend fun ApiClient.fsStat(path: String): FsStat {
    val data = call("GET", "/api/fs/stat", listOf("path" to path))
    val resolved = data["path"].string ?: throw PpmError.BadResponse(ApiClient.NOT_PPM)
    return FsStat(resolved, data["name"].string ?: "", data["kind"].string ?: "", data["target"].string)
}

/**
 * `GET /api/fs/list?dir=`: the files below [dir] on the host as absolute paths, breadth first, at
 * most 200 and four levels down (the command palette's filesystem mode). [dir] is read as the
 * host reads it, `~` and all.
 */
suspend fun ApiClient.fsList(dir: String): List<String> =
    call("GET", "/api/fs/list", listOf("dir" to dir)).array.orEmpty().mapNotNull { it.string }

/** `GET /api/fs/read`: a text file anywhere on the host, as the background output panel reads a command's `.output`. */
suspend fun ApiClient.fsRead(path: String): String = call("GET", "/api/fs/read", listOf("path" to path))["content"].string.orEmpty()

/** `GET /api/project/:name/files/read`: a text file inside [project], as the web reads `package.json` for its icons. */
suspend fun ApiClient.readProjectFile(project: String, path: String): String =
    call("GET", "/api/project/${ApiClient.escape(project)}/files/read", listOf("path" to path))["content"].string.orEmpty()

/**
 * One sheet of a workbook as `GET …/files/xlsx-sheet` answers it (`XlsxSheet` in `xlsx-reader.ts`):
 * every sheet's name, the index of the one in [rows], the rows from row 1 (`null` an empty cell, a
 * row may be shorter than [columns]), and whether rows or columns were cut to the server's limits.
 */
data class XlsxSheet(
    val sheets: List<String>,
    val sheet: Int,
    val rows: List<List<String?>>,
    val columns: Int,
    val truncatedRows: Boolean,
    val truncatedColumns: Boolean,
)

/**
 * The read-only preview's sheet [sheet] of [path] (`xlsx-preview.tsx` `sheetUrl`): an absolute path
 * through `GET /api/fs/xlsx-sheet`, anything else from inside [project]. A workbook the server
 * cannot show (legacy `.xls`, password-protected, too large, a sheet that is gone) is a
 * [PpmError.Server] carrying the server's reason and status.
 */
suspend fun ApiClient.xlsxSheet(project: String?, path: String, sheet: Int): XlsxSheet {
    val query = listOf("path" to path, "sheet" to sheet.toString())
    val data = if (ABSOLUTE_PATH.containsMatchIn(path) || project == null) {
        call("GET", "/api/fs/xlsx-sheet", query)
    } else {
        call("GET", "/api/project/${ApiClient.escape(project)}/files/xlsx-sheet", query)
    }
    val rows = data["rows"].array?.map { row ->
        row.array?.map { cell -> (cell as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content } ?: emptyList()
    } ?: throw PpmError.BadResponse("The workbook could not be read.")
    return XlsxSheet(
        sheets = data["sheets"].array?.map { it.string.orEmpty() } ?: emptyList(),
        sheet = data["sheet"].int ?: 0,
        rows = rows,
        columns = data["columns"].int ?: rows.maxOfOrNull { it.size } ?: 0,
        truncatedRows = data["truncatedRows"].bool == true,
        truncatedColumns = data["truncatedColumns"].bool == true,
    )
}
