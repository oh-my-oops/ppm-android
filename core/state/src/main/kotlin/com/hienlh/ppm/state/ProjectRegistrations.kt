package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.ProjectInfo

data class ProjectRegistrationChanges(val invalidated: Set<String>, val selected: String?)

/** Retain the open directory through an external rename; deletion falls back to server order. */
fun reconcileProjectRegistrations(previous: List<ProjectInfo>, fresh: List<ProjectInfo>, selected: String?): ProjectRegistrationChanges {
    val invalidated = previous.filter { old -> fresh.none { it.name == old.name && it.path == old.path } }.map { it.name }.toSet()
    val current = previous.firstOrNull { it.name == selected }
    val next = when {
        selected == null -> null
        selected !in invalidated && fresh.any { it.name == selected } -> selected
        else -> fresh.firstOrNull { it.path == current?.path }?.name ?: fresh.firstOrNull()?.name
    }
    return ProjectRegistrationChanges(invalidated, next)
}
