package com.hienlh.ppm.client

/** Everything a call to a PPM server can fail with; the message is what the UI shows. */
sealed class PpmError(message: String) : Exception(message) {
    class InvalidUrl : PpmError("That is not a valid server address.") {
        override fun equals(other: Any?) = other is InvalidUrl
        override fun hashCode() = 1
    }

    class Unreachable(val reason: String) : PpmError("Can't reach the server. $reason") {
        override fun equals(other: Any?) = other is Unreachable && other.reason == reason
        override fun hashCode() = reason.hashCode()
    }

    class Unauthorized : PpmError("Wrong password, or it was changed on the server.") {
        override fun equals(other: Any?) = other is Unauthorized
        override fun hashCode() = 2
    }

    /**
     * `{ ok: false }`: the server's message, and its machine-readable [code] when it sent one
     * (`DB_LOGIN_REQUIRED`, `EEXIST`…).
     */
    class Server(val status: Int, val serverMessage: String, val code: String? = null, val hint: String? = null) : PpmError(serverMessage) {
        override fun equals(other: Any?) =
            other is Server && other.status == status && other.serverMessage == serverMessage && other.code == code && other.hint == hint

        override fun hashCode() = status * 31 + serverMessage.hashCode()
        override fun toString() = "Server(status=$status, message=$serverMessage, code=$code)"
    }

    class BadResponse(val reason: String) : PpmError(reason) {
        override fun equals(other: Any?) = other is BadResponse && other.reason == reason
        override fun hashCode() = reason.hashCode()
        override fun toString() = "BadResponse($reason)"
    }

    /** The server answered a route it does not have with the web app's page: it is older than this app. */
    class OutdatedServer : PpmError("This needs a newer PPM. Update PPM on that machine.") {
        override fun equals(other: Any?) = other is OutdatedServer
        override fun hashCode() = 3
    }
}
