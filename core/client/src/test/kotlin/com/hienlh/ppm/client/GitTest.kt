package com.hienlh.ppm.client

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `routes/git.ts`: which repository a project's git runs in, and the branch the status bar shows. */
class GitTest {
    private fun <T> withServer(vararg responses: MockResponse, block: suspend (MockWebServer, ApiClient) -> T): T = runBlocking {
        val server = MockWebServer()
        responses.forEach(server::enqueue)
        server.start()
        try {
            block(server, ApiClient(server.url("/"), "t"))
        } finally {
            server.close()
        }
    }

    private fun ok(data: String) = MockResponse.Builder().body("""{"ok":true,"data":$data}""").build()

    @Test
    fun `a project that is a repository is asked without a scope`() = withServer(
        ok("""{"root":"/w/web","rootIsRepo":true,"repos":[{"path":"/w/web","name":"web","relative":"."}]}"""),
        ok("""{"current":"main","ahead":2,"behind":0,"tracking":"origin/main","staged":[],"unstaged":[],"untracked":["a"]}"""),
    ) { server, api ->
        val repo = api.gitRepos("my web").resolved
        assertEquals(GitRepo("/w/web", "web", "."), repo)
        assertEquals(GitBranchStatus("main", 2, 0, "origin/main"), api.gitStatus("my web", repo))
        assertEquals("/api/project/my%20web/git/repos", server.takeRequest().target)
        assertEquals("/api/project/my%20web/git/status", server.takeRequest().target)
    }

    @Test
    fun `the only repository under a container folder is scoped by its path`() = withServer(
        ok("""{"root":"/w","rootIsRepo":false,"repos":[{"path":"/w/api server","name":"api server","relative":"api server"}]}"""),
        ok("""{"current":"dev","ahead":0,"behind":3,"tracking":null,"staged":[],"unstaged":[],"untracked":[]}"""),
    ) { server, api ->
        val repo = api.gitRepos("w").resolved
        assertEquals(GitBranchStatus("dev", 0, 3, null), api.gitStatus("w", repo))
        server.takeRequest()
        assertEquals("/api/project/w/git/status?repo=%2Fw%2Fapi%20server", server.takeRequest().target)
    }

    @Test
    fun `several repositories, or none, are a choice the app does not make`() {
        val two = GitRepos(false, listOf(GitRepo("/w/a", "a", "a"), GitRepo("/w/b", "b", "b")))
        assertNull(two.resolved)
        assertNull(GitRepos(false, emptyList()).resolved)
    }
}
