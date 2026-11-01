package com.hienlh.ppm.state.terminal

import com.hienlh.ppm.client.ApiClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A terminal opened with no project: the web asks `/ws/project//terminal/<id>` (`projectName ?? ""`)
 * and the server starts the shell in the host's home folder for the empty name. The empty segment
 * has to survive: with it dropped the socket would name `terminal` as the project.
 */
class TerminalNoProjectUrlTest {
    private val api = ApiClient("http://host:8080/".toHttpUrl(), "t")

    @Test
    fun `an empty project keeps its empty path segment`() {
        assertEquals("ws://host:8080/ws/project//terminal/new?token=t", terminalSocketUrl(api, "", "new", null))
    }

    @Test
    fun `a start folder rides along for a new shell with no project`() {
        assertEquals(
            "ws://host:8080/ws/project//terminal/new?token=t&cwd=%2Fhome%2Fme%2Fsrc",
            terminalSocketUrl(api, "", "new", "/home/me/src"),
        )
    }
}
