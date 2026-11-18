package com.hienlh.ppm.client

import java.util.UUID

/**
 * Which chat tab a chat socket belongs to, kept across that tab's reconnects (`chat-client-id.ts`).
 *
 * A chat reconnects its socket for ordinary reasons — a network blip, the server's idle timeout,
 * and for every new Codex chat the rename to its thread id. The server remembers the device a
 * turn's message came from so the PPM Assistant's screen requests go to that device only; keyed
 * by the socket alone, that memory died with the old socket. The tab sends this id with every
 * connection (`?clientId=`), so its new socket is recognised as the same tab. A routing hint,
 * never a credential.
 */
object ChatClientId {
    const val PARAM = "clientId"

    private val VALID = Regex("^[A-Za-z0-9-]{8,64}$")

    /** A fresh id, one per chat tab for that tab's whole life. */
    fun mint(): String = UUID.randomUUID().toString()

    fun isValid(id: String): Boolean = VALID.matches(id)
}
