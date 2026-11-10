package com.hienlh.ppm.app.ui.phone.settings

import com.hienlh.ppm.app.ui.SettingsCategory
import com.hienlh.ppm.design.ProductIcon

/**
 * Grouping for the Phone 6 · Settings home screen (`designs/ppm-mobile-tablet-redesign/phone/6-settings/`).
 */
enum class PhoneSettingsGroup(val title: String) {
    AI("AI"),
    THIS_PHONE("This phone"),
    SERVER("Server"),
}

/**
 * The 6 full-page sections available in Phone Settings (Requirement 15.1):
 * - [Accounts] (sign in, usage, enable or disable)
 * - [AiProvider] (model, effort, permission mode)
 * - [Notifications] (events, delay, push, Telegram, ntfy)
 * - [Theme] (Light / Dark / System mode + color theme grid)
 * - [Servers] (connected server, switch, remove, log out, add server)
 * - [About] (server & app versions, server update, debug redesign toggle)
 */
enum class PhoneSettingsSection(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ProductIcon,
    val group: PhoneSettingsGroup,
    val category: SettingsCategory?,
) {
    Accounts(
        id = "accounts",
        title = "Accounts",
        subtitle = "Sign in, usage & enable or disable",
        icon = ProductIcon.Users,
        group = PhoneSettingsGroup.AI,
        category = SettingsCategory.Accounts,
    ),
    AiProvider(
        id = "ai",
        title = "AI Provider",
        subtitle = "Model, effort & permission mode",
        icon = ProductIcon.Sparkles,
        group = PhoneSettingsGroup.AI,
        category = SettingsCategory.Ai,
    ),
    Notifications(
        id = "notifications",
        title = "Notifications",
        subtitle = "Push, Telegram & ntfy",
        icon = ProductIcon.Bell,
        group = PhoneSettingsGroup.THIS_PHONE,
        category = SettingsCategory.Notifications,
    ),
    Theme(
        id = "appearance",
        title = "Theme",
        subtitle = "Appearance · Light, Dark, System & themes",
        icon = ProductIcon.Palette,
        group = PhoneSettingsGroup.THIS_PHONE,
        category = SettingsCategory.Appearance,
    ),
    Servers(
        id = "servers",
        title = "Servers",
        subtitle = "Connected server & saved hosts",
        icon = ProductIcon.HardDrive,
        group = PhoneSettingsGroup.SERVER,
        category = SettingsCategory.Servers,
    ),
    About(
        id = "about",
        title = "About",
        subtitle = "PPM server & Android app versions",
        icon = ProductIcon.Info,
        group = PhoneSettingsGroup.SERVER,
        category = null,
    ),
    ;

    companion object {
        fun forGroup(group: PhoneSettingsGroup): List<PhoneSettingsSection> =
            entries.filter { it.group == group }
    }
}

/**
 * Destination state for Phone Settings (Requirements 15.1 & 15.2).
 */
sealed interface PhoneSettingsRoute {
    /** The Phone Settings home list showing the 6 everyday sections. */
    data object Home : PhoneSettingsRoute

    /** One of the 6 full phone settings pages (Requirement 15.1). */
    data class FullPage(val section: PhoneSettingsSection) : PhoneSettingsRoute

    /**
     * Any other settings section opened on a phone (Requirement 15.2):
     * displays `"Open on a tablet"`.
     */
    data class TabletOnlyPage(val category: SettingsCategory) : PhoneSettingsRoute
}

/**
 * Returns `true` if [category] has a dedicated full page on a phone (Requirement 15.1),
 * or `false` if opening it on a phone must show `"Open on a tablet"` (Requirement 15.2).
 */
fun isPhoneFullSettingsCategory(category: SettingsCategory): Boolean = when (category) {
    SettingsCategory.Accounts,
    SettingsCategory.Ai,
    SettingsCategory.Notifications,
    SettingsCategory.Appearance,
    SettingsCategory.Servers,
    -> true
    else -> false
}

/**
 * Maps a [SettingsCategory] to its [PhoneSettingsRoute] on a phone (Requirements 15.1 & 15.2).
 */
fun resolvePhoneSettingsCategory(category: SettingsCategory): PhoneSettingsRoute = when (category) {
    SettingsCategory.Accounts -> PhoneSettingsRoute.FullPage(PhoneSettingsSection.Accounts)
    SettingsCategory.Ai -> PhoneSettingsRoute.FullPage(PhoneSettingsSection.AiProvider)
    SettingsCategory.Notifications -> PhoneSettingsRoute.FullPage(PhoneSettingsSection.Notifications)
    SettingsCategory.Appearance -> PhoneSettingsRoute.FullPage(PhoneSettingsSection.Theme)
    SettingsCategory.Servers -> PhoneSettingsRoute.FullPage(PhoneSettingsSection.Servers)
    else -> PhoneSettingsRoute.TabletOnlyPage(category)
}

/**
 * Resolves a raw category identifier (e.g. from `model.requestedSettingsCategory` or deep link)
 * into a [PhoneSettingsRoute].
 */
fun resolvePhoneSettingsRoute(rawCategory: String?): PhoneSettingsRoute {
    val cleaned = rawCategory?.trim()?.lowercase().orEmpty()
    if (cleaned.isEmpty()) return PhoneSettingsRoute.Home
    return when (cleaned) {
        "accounts", "account" -> PhoneSettingsRoute.FullPage(PhoneSettingsSection.Accounts)
        "ai", "ai-provider", "aiprovider" -> PhoneSettingsRoute.FullPage(PhoneSettingsSection.AiProvider)
        "notifications", "notification" -> PhoneSettingsRoute.FullPage(PhoneSettingsSection.Notifications)
        "appearance", "theme", "themes" -> PhoneSettingsRoute.FullPage(PhoneSettingsSection.Theme)
        "servers", "server" -> PhoneSettingsRoute.FullPage(PhoneSettingsSection.Servers)
        "about" -> PhoneSettingsRoute.FullPage(PhoneSettingsSection.About)
        else -> {
            val category = SettingsCategory.resolveOrNull(cleaned) ?: SettingsCategory.General
            resolvePhoneSettingsCategory(category)
        }
    }
}
