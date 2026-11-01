package com.hienlh.ppm.client

import org.junit.Assert.*
import org.junit.Test

class ServerStoreTest {
    private class Values : KeyValueStore {
        val entries = mutableMapOf<String, String>()
        override fun get(key: String) = entries[key]
        override fun put(key: String, value: String?) { if (value == null) entries.remove(key) else entries[key] = value }
    }
    private class Secrets : SecretStore {
        val entries = mutableMapOf<String, String>()
        override fun read(account: String) = entries[account]
        override fun write(account: String, secret: String) { entries[account] = secret }
        override fun delete(account: String) { entries.remove(account) }
    }

    @Test fun independentSelectionsShareRegistrationsAndSurviveReload() {
        val values = Values(); val secrets = Secrets()
        val first = ServerStore(values, secrets)
        val a = first.add("A", "https://a.test", "a")
        val second = ServerStore(values, secrets, "window.two.selectedServer")
        val b = second.add("B", "https://b.test", "b")
        first.select(a)
        assertEquals(a.id, first.selected?.id)
        assertEquals(b.id, second.selected?.id)
        assertEquals(setOf(a.id, b.id), first.servers.value.map { it.id }.toSet())
        assertEquals(a.id, ServerStore(values, secrets).selected?.id)
        assertEquals(b.id, ServerStore(values, secrets, "window.two.selectedServer").selected?.id)
    }

    @Test fun staleWindowMutationDoesNotLoseAnotherWindowsAdditionOrResurrectRemoval() {
        val values = Values(); val secrets = Secrets()
        val first = ServerStore(values, secrets)
        val a = first.add("A", "https://a.test", "a")
        val second = ServerStore(values, secrets, "window.two.selectedServer")
        val b = first.add("B", "https://b.test", "b")
        second.rename(a, "Renamed")
        assertEquals(setOf(a.id, b.id), second.servers.value.map { it.id }.toSet())
        first.remove(a)
        second.select(a)
        assertEquals(listOf(b.id), second.servers.value.map { it.id })
        assertEquals(b.id, second.selected?.id)
        assertNull(secrets.read(a.id))
        assertEquals("b", second.token(b))
    }
}
