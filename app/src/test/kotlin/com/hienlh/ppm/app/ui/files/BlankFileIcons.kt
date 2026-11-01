package com.hienlh.ppm.app.ui.files

import androidx.test.core.app.ApplicationProvider
import com.hienlh.ppm.design.FileIconTables
import com.hienlh.ppm.design.FileIcons

/**
 * This module's unit tests have no assets, so a project-tree row, which asks for the tables that name its file icon, would fail
 * reading them. An empty set of tables gets the row an icon name no glyph file exists for, which the icon takes as "not read
 * yet" and leaves blank, as it does on a device until its artwork is loaded.
 */
internal fun installBlankFileIcons() {
    val icons = FileIcons.get(ApplicationProvider.getApplicationContext())
    val blank = FileIconTables(
        emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(),
        defaultFile = "default_file", defaultFolder = "default_folder", defaultFolderOpen = "default_folder_open",
        maxExtensionSegments = 3, light = emptySet(),
    )
    FileIcons::class.java.getDeclaredField("tables").apply { isAccessible = true }.set(icons, blank)
}
