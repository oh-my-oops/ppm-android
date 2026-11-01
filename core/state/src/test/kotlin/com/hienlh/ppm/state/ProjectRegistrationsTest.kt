package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.ProjectInfo
import org.junit.Assert.*
import org.junit.Test

class ProjectRegistrationsTest {
    private val a = ProjectInfo("a", "/a")
    private val b = ProjectInfo("b", "/b")
    @Test fun renameKeepsSelectedDirectoryAndEvictsOldName() {
        val renamed = a.copy(name = "renamed")
        assertEquals(ProjectRegistrationChanges(setOf("a"), "renamed"), reconcileProjectRegistrations(listOf(a, b), listOf(b, renamed), "a"))
    }
    @Test fun deletionChoosesFirstRemainingOrClearsLastSelection() {
        assertEquals(ProjectRegistrationChanges(setOf("a"), "b"), reconcileProjectRegistrations(listOf(a, b), listOf(b), "a"))
        assertEquals(ProjectRegistrationChanges(setOf("a"), null), reconcileProjectRegistrations(listOf(a), emptyList(), "a"))
    }
    @Test fun appearanceDoesNotInvalidateAndPathReplacementDoes() {
        assertEquals(ProjectRegistrationChanges(emptySet(), "a"), reconcileProjectRegistrations(listOf(a), listOf(a.copy(color = "red")), "a"))
        assertEquals(ProjectRegistrationChanges(setOf("a"), "a"), reconcileProjectRegistrations(listOf(a), listOf(a.copy(path = "/new")), "a"))
        assertNull(reconcileProjectRegistrations(listOf(a), listOf(b), null).selected)
    }
}
