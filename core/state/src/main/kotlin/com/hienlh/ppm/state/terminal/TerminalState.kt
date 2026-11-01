package com.hienlh.ppm.state.terminal

/** Connection and shell status; terminal bytes go directly to the owner's emulator. */
data class TerminalState(
    val connected: Boolean = false,
    val reconnecting: Boolean = false,
    val exited: Boolean = false,
    val shellReady: Boolean = false,
    val error: String? = null,
    /** Last server-confirmed id; retained during reconnect and cleared when restarting a shell. */
    val sessionId: String? = null,
)
