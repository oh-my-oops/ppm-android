package com.hienlh.ppm.app

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentOpenTimesTest {
    @Test
    fun `a project another device opened since comes in, and a newer time this app holds stays`() {
        val local = mapOf("nxsys-workspace" to 300L, "velox" to 100L)
        val server = mapOf("nesku" to 500L, "ppm" to 400L, "nxsys-workspace" to 200L)
        assertEquals(
            mapOf("nxsys-workspace" to 300L, "velox" to 100L, "nesku" to 500L, "ppm" to 400L),
            newerOpenTimes(local, server),
        )
    }
}
