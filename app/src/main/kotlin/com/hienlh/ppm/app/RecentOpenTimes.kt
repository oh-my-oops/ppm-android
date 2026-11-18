package com.hienlh.ppm.app

/**
 * Two devices' "project → last opened" maps as one: each project keeps its newer time. The server
 * keeps every device's times the same way (`mergeRecentOpen` in `settings.ts`), so a map read when
 * this app connected never hides a project another device opened since.
 */
internal fun newerOpenTimes(local: Map<String, Long>, server: Map<String, Long>): Map<String, Long> =
    (local.keys + server.keys).associateWith { name -> maxOf(local[name] ?: Long.MIN_VALUE, server[name] ?: Long.MIN_VALUE) }
