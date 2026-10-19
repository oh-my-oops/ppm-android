package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/** A PPM server the app knows. The password lives in the [SecretStore], never here. */
data class ServerProfile(val id: String = UUID.randomUUID().toString(), val name: String, val url: String)

/** Small values that survive the app (SharedPreferences on Android). */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/** Secrets, encrypted by a key the device keeps (the Android Keystore). */
interface SecretStore {
    fun read(account: String): String?
    fun write(account: String, secret: String)
    fun delete(account: String)
}

/** The servers added to this app, and which one is open. */
class ServerStore(private val values: KeyValueStore, private val secrets: SecretStore) {
    private val _servers = MutableStateFlow(load())
    val servers: StateFlow<List<ServerProfile>> = _servers.asStateFlow()

    private val _selectedId = MutableStateFlow(
        values.get(SELECTED_KEY)?.takeIf { id -> _servers.value.any { it.id == id } } ?: _servers.value.firstOrNull()?.id,
    )
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    val selected: ServerProfile? get() = _servers.value.firstOrNull { it.id == _selectedId.value }

    fun token(server: ServerProfile): String? = secrets.read(server.id)

    fun add(name: String, url: String, token: String): ServerProfile {
        val server = ServerProfile(name = name, url = url)
        secrets.write(server.id, token)
        _servers.value = _servers.value + server
        _selectedId.value = server.id
        persist()
        return server
    }

    fun updateToken(token: String, server: ServerProfile) = secrets.write(server.id, token)

    fun rename(server: ServerProfile, name: String) {
        _servers.value = _servers.value.map { if (it.id == server.id) it.copy(name = name) else it }
        persist()
    }

    fun remove(server: ServerProfile) {
        secrets.delete(server.id)
        _servers.value = _servers.value.filter { it.id != server.id }
        if (_selectedId.value == server.id) _selectedId.value = _servers.value.firstOrNull()?.id
        persist()
    }

    fun select(server: ServerProfile) {
        _selectedId.value = server.id
        persist()
    }

    private fun load(): List<ServerProfile> {
        val json = values.get(SERVERS_KEY)?.let(WireJson::parseOrNull) ?: return emptyList()
        return json.array.orEmpty().mapNotNull { row ->
            val id = row["id"].string ?: return@mapNotNull null
            val url = row["url"].string ?: return@mapNotNull null
            ServerProfile(id = id, name = row["name"].string ?: url, url = url)
        }
    }

    private fun persist() {
        val list = JsonArray(
            _servers.value.map {
                buildJsonObject {
                    put("id", it.id)
                    put("name", it.name)
                    put("url", it.url)
                }
            },
        )
        values.put(SERVERS_KEY, list.toString())
        values.put(SELECTED_KEY, _selectedId.value)
    }

    private companion object {
        const val SERVERS_KEY = "servers"
        const val SELECTED_KEY = "selectedServer"
    }
}
