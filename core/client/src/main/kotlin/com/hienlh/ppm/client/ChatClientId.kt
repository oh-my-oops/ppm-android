package com.hienlh.ppm.client

import java.util.UUID

/**
 * Which device sent a chat message (`src/shared/chat-client-id.ts`): one id per running app, as
 * the web mints one per browser tab, sent on every chat socket as `?clientId=`. The server keeps
 * it beside the sender's socket, so the PPM Assistant's screen tools still reach this device after
 * a reconnect. A routing hint, never a credential.
 */
object ChatClientId {
    private val SHAPE = Regex("^[A-Za-z0-9-]{8,64}$")

    val value: String = UUID.randomUUID().toString()

    fun valid(id: String): Boolean = SHAPE.matches(id)
}
