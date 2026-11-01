package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonElement

/** An fs move may copy successfully but fail to remove the source; HTTP 200 alone is insufficient. */
data class FileTransferResult(
    val source: String,
    val destination: String,
    val crossDevice: Boolean? = null,
    val sourceRemoved: Boolean? = null,
) {
    companion object {
        fun fromFilesystem(value: JsonElement?): FileTransferResult? {
            return FileTransferResult(
                source = value["source"].string ?: return null,
                destination = value["destination"].string ?: return null,
                crossDevice = value["crossDevice"].bool,
                sourceRemoved = value["sourceRemoved"].bool,
            )
        }

        /** Project routes wrap `{ from, to }` in `moved` or `copied`. */
        fun fromProject(value: JsonElement?, operation: String): FileTransferResult? {
            return FileTransferResult(
                source = value[operation]["from"].string ?: return null,
                destination = value[operation]["to"].string ?: return null,
            )
        }
    }
}
