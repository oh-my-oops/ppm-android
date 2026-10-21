package com.hienlh.ppm.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `/chat/prepare` as the server answers it (chat-prepare.service.ts), and the usage it may carry. */
class ChatPrepareTest {
    @Test
    fun `a preparation is read as the server sends it`() {
        val prepared = PreparedChat.from(
            WireJson.parse(
                """
                {"resolvedProviderId":"claude","providerId":"mock",
                 "settings":{"default_provider":"claude","new_chat_provider_mode":"follow-focus",
                             "providers":{"claude":{"permission_mode":"default"},"codex":{}}},
                 "providers":[{"id":"claude","name":"Claude"},{"id":"codex","name":"Codex"}],
                 "pickedAccount":null,"usage":null,
                 "draft":{"content":"half a thought","attachments":"[{\"name\":\"a.png\",\"path\":\"/u/a.png\"}]","updatedAt":"2026-10-21 12:00:00"},
                 "tags":{"tags":[{"id":5,"projectPath":"/p","name":"Todo","color":"#22c55e","sortOrder":0}],"counts":{"5":2},"defaultTagId":5},
                 "slash":{"items":[{"type":"builtin","name":"skills","description":"List skills","scope":"bundled","handler":"ppm"}],"recentNames":["skills"]}}
                """,
            ),
        )!!
        assertEquals("claude", prepared.resolvedProviderId)
        assertEquals("mock", prepared.providerId)
        assertEquals(listOf("claude", "codex"), prepared.providers.map { it.id })
        assertEquals(AccountPick.None, prepared.pickedAccount)
        assertNull(prepared.usage)
        assertEquals("half a thought", prepared.draft?.content)
        assertEquals(listOf(DraftAttachment("a.png", "/u/a.png")), prepared.draft?.attachments)
        assertEquals(mapOf(5 to 2), prepared.tags?.counts)
        assertEquals(5, prepared.tags?.defaultTagId)
        assertEquals(listOf("skills"), prepared.slash?.items?.map { it.name })
        assertEquals(listOf("skills"), prepared.slash?.recentNames)
        // A provider with no mode of its own starts as the web starts it.
        assertEquals("default", prepared.settings.permissionMode("claude"))
        assertEquals("bypassPermissions", prepared.settings.permissionMode("codex"))
    }

    @Test
    fun `the parts that took too long are null, and the pick says how it came out`() {
        fun pick(json: String) = PreparedChat.from(
            WireJson.parse(
                """{"resolvedProviderId":"codex","providerId":"codex","settings":{"default_provider":"codex","providers":{}},
                    "providers":[],"pickedAccount":$json,"usage":null,"draft":null,"tags":null,"slash":null}""",
            ),
        )!!
        assertEquals(AccountPick.Timeout, pick("\"timeout\"").pickedAccount)
        assertEquals(AccountPick.Skipped, pick("\"skipped\"").pickedAccount)
        assertEquals(AccountPick.Picked(PickedAccount("acc-1", null)), pick("""{"id":"acc-1","label":null}""").pickedAccount)
        val bare = pick("null")
        assertNull(bare.draft)
        assertNull(bare.tags)
        assertNull(bare.slash)
    }

    @Test
    fun `the provider follows focus only when the settings say so`() {
        val follow = ChatPreparationSettings("claude", "follow-focus", emptyMap())
        assertEquals("codex", follow.resolveProvider("codex"))
        assertEquals("claude", follow.resolveProvider("default"))
        assertEquals("claude", follow.resolveProvider(null))
        assertEquals("claude", ChatPreparationSettings("claude", "default", emptyMap()).resolveProvider("codex"))
        assertEquals("claude", ChatPreparationSettings("", null, emptyMap()).resolveProvider(null))
    }

    @Test
    fun `usage keeps its windows as fractions and names the account`() {
        val usage = UsageInfo.from(
            WireJson.parse(
                """
                {"fiveHour":0.42,"sevenDay":0.91,"queryCostUsd":0.0123,"totalCostUsd":1.5,
                 "session":{"utilization":0.42,"resetsAt":"2026-10-21T15:00:00Z","resetsInMinutes":90,"resetsInHours":1.5,"windowHours":5},
                 "weeklyScoped":[{"label":"Fable","utilization":0.3,"resetsAt":"2026-10-25T00:00:00Z","resetsInMinutes":null,"resetsInHours":80,"windowHours":168}],
                 "resetCredits":{"available":2,"title":"Full reset (Weekly + 5 hr)","nextCreditId":"c1"},
                 "activeAccountId":"acc-1","activeAccountLabel":"Work","lastFetchedAt":"2026-10-21T12:00:00Z"}
                """,
            ),
        )!!
        assertEquals(0.42, usage.fiveHour!!, 0.0)
        assertEquals(90L, usage.session?.resetsInMinutes)
        assertEquals("Fable", usage.weeklyScoped.single().label)
        assertNull(usage.weeklyScoped.single().resetsInMinutes)
        assertEquals(2, usage.resetCredits?.available)
        assertEquals("Work", usage.activeAccountLabel)
        assertNull(usage.weekly)
        assertNull(UsageInfo.from(WireJson.parse("null")))
    }
}
