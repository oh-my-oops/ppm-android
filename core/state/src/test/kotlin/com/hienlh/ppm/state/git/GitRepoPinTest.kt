package com.hienlh.ppm.state.git

import com.hienlh.ppm.client.GitRepo
import com.hienlh.ppm.client.GitRepos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `useGitRepo(project, pinned)`: one surface on a repository of its own, the device's choice left alone. */
class GitRepoPinTest {
    private val repos = GitRepos(
        rootIsRepo = false,
        repos = listOf(GitRepo("/w/demo/api", "api", "api"), GitRepo("/w/demo/web", "web", "web")),
        root = "/w/demo",
    )

    @Test
    fun `a pin among the repositories wins over the device's choice`() {
        assertEquals("/w/demo/web", GitRepoScope.pinnedOrChosen(repos, "/w/demo/web", "/w/demo/api"))
        assertEquals("/w/demo/web", GitRepoScope.resolveRepo(repos, GitRepoScope.pinnedOrChosen(repos, "/w/demo/web", null))?.path)
    }

    @Test
    fun `no pin, a pin no longer there, or nothing discovered yet follows the device's choice`() {
        assertEquals("/w/demo/api", GitRepoScope.pinnedOrChosen(repos, null, "/w/demo/api"))
        assertEquals("/w/demo/api", GitRepoScope.pinnedOrChosen(repos, "", "/w/demo/api"))
        assertEquals("/w/demo/api", GitRepoScope.pinnedOrChosen(repos, "/w/demo/gone", "/w/demo/api"))
        assertEquals("/w/demo/api", GitRepoScope.pinnedOrChosen(null, "/w/demo/web", "/w/demo/api"))
        assertNull(GitRepoScope.pinnedOrChosen(repos, "/w/demo/gone", null))
    }
}
