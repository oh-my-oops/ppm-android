package com.hienlh.ppm.app.ui.tasks

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The task views' icons the generated product set does not carry yet (`Scale`, `Reply`,
 * `Calendar`, `Ticket`, `GitPullRequest` in `@/lib/icons`): the same Fluent 20 Regular glyphs, in
 * their 20×20 box, filled black to be tinted like every [com.hienlh.ppm.design.ProductIcon].
 */
internal object TaskIcons {
    private fun glyph(name: String, d: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 20.dp, defaultHeight = 20.dp, viewportWidth = 20f, viewportHeight = 20f)
            .apply { addPath(addPathNodes(d), fill = SolidColor(Color.Black)) }
            .build()

    /** `arrow-reply`: the Reply type. */
    val Reply: ImageVector by lazy {
        glyph("Reply", "M7.354 3.646a.5.5 0 0 1 0 .708L3.707 8H10.5a7.5 7.5 0 0 1 7.5 7.5a.5.5 0 0 1-1 0A6.5 6.5 0 0 0 10.5 9H3.707l3.647 3.646a.5.5 0 0 1-.708.708l-4.5-4.5a.5.5 0 0 1 0-.708l4.5-4.5a.5.5 0 0 1 .708 0")
    }

    /** `branch-request`: a pull-request link. */
    val GitPullRequest: ImageVector by lazy {
        glyph(
            "GitPullRequest",
            "M5.5 7.959a3 3 0 1 0-1 0v4.082a3 3 0 1 0 1 0zM5 7a2 2 0 1 1 0-4a2 2 0 0 1 0 4m2 8a2 2 0 1 1-4 0a2 2 0 0 1 4 0m4.146-8.146a.5.5 0 0 0 .708-.708L10.707 5H12.5A2.5 2.5 0 0 1 15 7.5V12a3 3 0 1 0 1 .17V7.5A3.5 3.5 0 0 0 12.5 4h-1.793l1.147-1.146a.5.5 0 0 0-.708-.708l-2 2a.5.5 0 0 0 0 .708zM17 15a2 2 0 1 1-4 0a2 2 0 0 1 4 0",
        )
    }

    /** `calendar-ltr`: the due date. */
    val Calendar: ImageVector by lazy {
        glyph(
            "Calendar",
            "M7 11a1 1 0 1 0 0-2a1 1 0 0 0 0 2m1 2a1 1 0 1 1-2 0a1 1 0 0 1 2 0m2-2a1 1 0 1 0 0-2a1 1 0 0 0 0 2m1 2a1 1 0 1 1-2 0a1 1 0 0 1 2 0m2-2a1 1 0 1 0 0-2a1 1 0 0 0 0 2m4-5.5A2.5 2.5 0 0 0 14.5 3h-9A2.5 2.5 0 0 0 3 5.5v9A2.5 2.5 0 0 0 5.5 17h9a2.5 2.5 0 0 0 2.5-2.5zM4 7h12v7.5a1.5 1.5 0 0 1-1.5 1.5h-9A1.5 1.5 0 0 1 4 14.5zm1.5-3h9A1.5 1.5 0 0 1 16 5.5V6H4v-.5A1.5 1.5 0 0 1 5.5 4",
        )
    }

    /** `scales`: the Decision type. */
    val Scale: ImageVector by lazy {
        glyph(
            "Scale",
            "M3.5 3a.5.5 0 1 0 0 1h.75L2.077 9.215a1 1 0 0 0-.075.405a3 3 0 0 0 5.996 0a1 1 0 0 0-.075-.405L5.75 4H9.5v10H6a2 2 0 1 0 0 4h8a2 2 0 0 0 0-4h-3.5V4h3.75l-2.173 5.215a1 1 0 0 0-.075.405a3 3 0 0 0 5.996 0a1 1 0 0 0-.075-.405L15.75 4h.75a.5.5 0 0 0 0-1zM5 11.5A2 2 0 0 1 3.063 10h3.874A2 2 0 0 1 5 11.5m0-6.7L6.75 9h-3.5zM6 15h8a1 1 0 0 1 0 2H6a1 1 0 1 1 0-2m10.75-6h-3.5L15 4.8zm-3.687 1h3.874a2.001 2.001 0 0 1-3.874 0",
        )
    }

    /** `ticket-diagonal`: a Jira link. */
    val Ticket: ImageVector by lazy {
        glyph(
            "Ticket",
            "M9.943 2.59a2 2 0 0 1 2.829 0l.907.908c.3.301.265.739.063 1.013a1.25 1.25 0 0 0 1.745 1.746l.11-.068a.78.78 0 0 1 .846.079l.058.052l.917.917c.78.781.781 2.048 0 2.83l-7.353 7.353a2 2 0 0 1-2.829 0l-.917-.917c-.3-.301-.265-.74-.063-1.014l.103-.164a1.25 1.25 0 0 0-1.85-1.582c-.273.202-.712.237-1.013-.063l-.906-.906a2 2 0 0 1 0-2.83zm2.122.708a1 1 0 0 0-1.415 0L8.832 5.115l1.07 1.07a.5.5 0 0 1-.707.708l-1.07-1.07l-4.828 4.828a1 1 0 0 0 0 1.415l.77.77a2.25 2.25 0 0 1 3.096 3.096l.78.781a1 1 0 0 0 1.414 0l4.83-4.83l-1.078-1.076a.5.5 0 0 1 .707-.707l1.078 1.077l1.817-1.818c.39-.39.39-1.024 0-1.415l-.781-.78a2.23 2.23 0 0 1-1.18.336a2.25 2.25 0 0 1-1.915-3.432zM10.609 7.6a.5.5 0 0 1 .707 0l1.086 1.086a.5.5 0 0 1-.707.707L10.61 8.307a.5.5 0 0 1 0-.707",
        )
    }
}
