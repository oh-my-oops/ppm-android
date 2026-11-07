package com.hienlh.ppm.app.ui.extensions

import androidx.compose.ui.graphics.vector.ImageVector
import com.hienlh.ppm.design.LucideIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.vector
import com.hienlh.ppm.state.extensions.ExtensionContributes

/**
 * Shared icon map for extension-contributed views, commands, and tree items
 * (`src/web/lib/extension-icons.ts`).
 */
object ExtensionIcons {
    val EXTENSION_ICONS: Map<String, ImageVector> = mapOf(
        "git-branch" to ProductIcon.GitBranch.vector,
        "git-commit" to LucideIcon.GitCommitHorizontal.vector,
        "git-compare" to ProductIcon.GitMerge.vector,
        "history" to ProductIcon.History.vector,
        "users" to ProductIcon.Users.vector,
        "list-ordered" to ProductIcon.ListOrdered.vector,
        "refresh" to ProductIcon.RefreshCw.vector,
        "edit" to ProductIcon.Pencil.vector,
        "trash" to ProductIcon.Trash2.vector,
        "plus" to ProductIcon.Plus.vector,
        "search" to ProductIcon.Search.vector,
        "database" to ProductIcon.Database.vector,
        "terminal" to ProductIcon.Terminal.vector,
        "settings" to ProductIcon.Settings.vector,
        "file-code" to ProductIcon.FileCode.vector,
        "globe" to ProductIcon.Globe.vector,
    )

    fun icon(name: String?): ImageVector? = name?.let(EXTENSION_ICONS::get)

    fun viewTypeIcon(contributions: ExtensionContributes?, viewType: String?): ImageVector {
        if (viewType.isNullOrEmpty()) return ProductIcon.Puzzle.vector
        val slug = viewType.removeSuffix(".view")
        if (contributions != null) {
            for (views in contributions.views.values) {
                for (v in views) {
                    if ((v.id == viewType || v.id.startsWith("$slug.")) && v.icon != null) {
                        EXTENSION_ICONS[v.icon]?.let { return it }
                    }
                }
            }
            for (c in contributions.commands) {
                if ((c.command == viewType || c.command.startsWith("$slug.")) && c.icon != null) {
                    EXTENSION_ICONS[c.icon]?.let { return it }
                }
            }
        }
        if (slug == "git-graph") return ProductIcon.GitBranch.vector
        return ProductIcon.Puzzle.vector
    }
}
