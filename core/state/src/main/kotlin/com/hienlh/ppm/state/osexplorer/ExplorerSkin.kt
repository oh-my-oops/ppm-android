package com.hienlh.ppm.state.osexplorer

/**
 * Which look the explorer and every window wear (use-explorer-skin.ts): the skin picked in Settings
 * wins, and `auto` — or none picked — follows the server's OS, Windows' on `win32` and macOS' on any
 * other. [picked] is the raw `explorerSkin` setting, which is `auto` unless the user chose one.
 */
fun explorerSkinIsWindows(picked: String?, hostPlatform: String?): Boolean = when (picked) {
    "windows" -> true
    "macos" -> false
    else -> hostPlatform == "win32"
}
