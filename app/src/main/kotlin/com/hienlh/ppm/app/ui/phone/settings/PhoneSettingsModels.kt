package com.hienlh.ppm.app.ui.phone.settings

import com.hienlh.ppm.app.ui.SettingsCategory
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.protocol.AiSettings
import com.hienlh.ppm.state.ModelNames
import com.hienlh.ppm.state.settings.NtfyStatusState
import com.hienlh.ppm.state.settings.PushStatusState
import com.hienlh.ppm.state.settings.TelegramNotifyState

/**
 * Grouping for the Phone 6 · Settings home screen (`designs/ppm-mobile-tablet-redesign/phone/6-settings/`).
 */
enum class PhoneSettingsGroup(val title: String) {
    AI("AI"),
    THIS_PHONE("This phone"),
    SERVER("Server"),
    INTEGRATIONS("Integrations"),
    ADVANCED("Advanced"),
}

/**
 * Every Settings category, each a full page on a phone, as the web's narrow Settings drills from
 * its list into one pane (`settings-category-list.tsx`). Some have a page of their own:
 * - [Accounts] (sign in, usage, enable or disable; rotation, backup and token tests one level down)
 * - [AiProvider] (model, effort, permission mode; every other provider setting one level down)
 * - [Notifications] (events, delay, push, Telegram, ntfy)
 * - [Theme] (Light / Dark / System mode + color theme grid, then the rest of Appearance)
 * - [Servers] (connected server, switch, remove, log out, add server)
 * - [Schedules] (run now / restart, on or off, run history and full log, new, edit, import, delete)
 * - [Storage] (what the database, backups, session trace and Query Audit take, and their limits)
 * - [About] (server & app versions, server update, then General: device name, password, Learn PPM)
 * and every other one hosts the tablet's own section ([PhoneHostedSettingsPage]).
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
    Memory(
        id = "memory",
        title = "Memory",
        subtitle = "One memory store for every provider, synced",
        icon = ProductIcon.Brain,
        group = PhoneSettingsGroup.AI,
        category = SettingsCategory.Memory,
    ),
    Tools(
        id = "tools",
        title = "Tools",
        subtitle = "PPM tools the AI can use",
        icon = ProductIcon.Wrench,
        group = PhoneSettingsGroup.AI,
        category = SettingsCategory.Tools,
    ),
    Mcp(
        id = SettingsCategory.Mcp.id,
        title = SettingsCategory.Mcp.label,
        subtitle = SettingsCategory.Mcp.subtitle,
        icon = SettingsCategory.Mcp.icon,
        group = PhoneSettingsGroup.AI,
        category = SettingsCategory.Mcp,
    ),
    AiResources(
        id = SettingsCategory.AiResources.id,
        title = SettingsCategory.AiResources.label,
        subtitle = SettingsCategory.AiResources.subtitle,
        icon = SettingsCategory.AiResources.icon,
        group = PhoneSettingsGroup.AI,
        category = SettingsCategory.AiResources,
    ),
    Assistant(
        id = "assistant",
        title = "PPM Assistant",
        subtitle = "Its own model, instructions, MCP servers, Telegram",
        icon = ProductIcon.Sparkles,
        group = PhoneSettingsGroup.AI,
        category = SettingsCategory.Assistant,
    ),
    Vault(
        id = "vault",
        title = "Vault",
        subtitle = "Personal details the AI may read, behind passwords",
        icon = ProductIcon.Lock,
        group = PhoneSettingsGroup.AI,
        category = SettingsCategory.Vault,
    ),
    Design(
        id = SettingsCategory.Design.id,
        title = SettingsCategory.Design.label,
        subtitle = SettingsCategory.Design.subtitle,
        icon = SettingsCategory.Design.icon,
        group = PhoneSettingsGroup.AI,
        category = SettingsCategory.Design,
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
    Voice(
        id = SettingsCategory.Voice.id,
        title = SettingsCategory.Voice.label,
        subtitle = SettingsCategory.Voice.subtitle,
        icon = SettingsCategory.Voice.icon,
        group = PhoneSettingsGroup.THIS_PHONE,
        category = SettingsCategory.Voice,
    ),
    Shortcuts(
        id = SettingsCategory.Shortcuts.id,
        title = SettingsCategory.Shortcuts.label,
        subtitle = SettingsCategory.Shortcuts.subtitle,
        icon = SettingsCategory.Shortcuts.icon,
        group = PhoneSettingsGroup.THIS_PHONE,
        category = SettingsCategory.Shortcuts,
    ),
    Servers(
        id = "servers",
        title = "Servers",
        subtitle = "Connected server & saved hosts",
        icon = ProductIcon.HardDrive,
        group = PhoneSettingsGroup.SERVER,
        category = SettingsCategory.Servers,
    ),
    Schedules(
        id = "schedules",
        title = "Schedules",
        subtitle = "Run now, on or off, runs & logs",
        icon = ProductIcon.CalendarClock,
        group = PhoneSettingsGroup.SERVER,
        category = SettingsCategory.Schedules,
    ),
    Storage(
        id = "storage",
        title = "Storage",
        subtitle = "Database, backups, session trace & Query Audit",
        icon = ProductIcon.Database,
        group = PhoneSettingsGroup.SERVER,
        // The web folded the Query Audit Log section into Storage.
        category = SettingsCategory.QueryAudit,
    ),
    About(
        id = "about",
        title = "About",
        subtitle = "PPM server & Android app versions",
        icon = ProductIcon.Info,
        group = PhoneSettingsGroup.SERVER,
        // The tablet's General: device name, password and Learn PPM sit under the versions.
        category = SettingsCategory.General,
    ),
    RemoteAccess(
        id = SettingsCategory.RemoteAccess.id,
        title = SettingsCategory.RemoteAccess.label,
        subtitle = SettingsCategory.RemoteAccess.subtitle,
        icon = SettingsCategory.RemoteAccess.icon,
        group = PhoneSettingsGroup.INTEGRATIONS,
        category = SettingsCategory.RemoteAccess,
    ),
    Cloud(
        id = SettingsCategory.Cloud.id,
        title = SettingsCategory.Cloud.label,
        subtitle = SettingsCategory.Cloud.subtitle,
        icon = SettingsCategory.Cloud.icon,
        group = PhoneSettingsGroup.INTEGRATIONS,
        category = SettingsCategory.Cloud,
    ),
    Proxy(
        id = SettingsCategory.Proxy.id,
        title = SettingsCategory.Proxy.label,
        subtitle = SettingsCategory.Proxy.subtitle,
        icon = SettingsCategory.Proxy.icon,
        group = PhoneSettingsGroup.INTEGRATIONS,
        category = SettingsCategory.Proxy,
    ),
    Extensions(
        id = SettingsCategory.Extensions.id,
        title = SettingsCategory.Extensions.label,
        subtitle = SettingsCategory.Extensions.subtitle,
        icon = SettingsCategory.Extensions.icon,
        group = PhoneSettingsGroup.INTEGRATIONS,
        category = SettingsCategory.Extensions,
    ),
    Jira(
        id = SettingsCategory.Jira.id,
        title = SettingsCategory.Jira.label,
        subtitle = SettingsCategory.Jira.subtitle,
        icon = SettingsCategory.Jira.icon,
        group = PhoneSettingsGroup.INTEGRATIONS,
        category = SettingsCategory.Jira,
    ),
    Files(
        id = SettingsCategory.Files.id,
        title = SettingsCategory.Files.label,
        subtitle = SettingsCategory.Files.subtitle,
        icon = SettingsCategory.Files.icon,
        group = PhoneSettingsGroup.ADVANCED,
        category = SettingsCategory.Files,
    ),
    LanguageServers(
        id = SettingsCategory.LanguageServers.id,
        title = SettingsCategory.LanguageServers.label,
        subtitle = SettingsCategory.LanguageServers.subtitle,
        icon = SettingsCategory.LanguageServers.icon,
        group = PhoneSettingsGroup.ADVANCED,
        category = SettingsCategory.LanguageServers,
    ),
    DatabaseDrivers(
        id = SettingsCategory.DatabaseDrivers.id,
        title = SettingsCategory.DatabaseDrivers.label,
        subtitle = SettingsCategory.DatabaseDrivers.subtitle,
        icon = SettingsCategory.DatabaseDrivers.icon,
        group = PhoneSettingsGroup.ADVANCED,
        category = SettingsCategory.DatabaseDrivers,
    ),
    RemoteDesktop(
        id = SettingsCategory.RemoteDesktop.id,
        title = SettingsCategory.RemoteDesktop.label,
        subtitle = SettingsCategory.RemoteDesktop.subtitle,
        icon = SettingsCategory.RemoteDesktop.icon,
        group = PhoneSettingsGroup.ADVANCED,
        category = SettingsCategory.RemoteDesktop,
    ),
    ;

    /** False for a section a server started with --minimal does not run (`settingsCategoryAvailable`). */
    fun availableIn(minimalMode: Boolean): Boolean = !minimalMode || category?.minimalSafe != false

    companion object {
        fun forGroup(group: PhoneSettingsGroup, minimalMode: Boolean = false): List<PhoneSettingsSection> =
            entries.filter { it.group == group && it.availableIn(minimalMode) }

        /** The page a [category] opens on a phone: its own section, General's being About. */
        fun forCategory(category: SettingsCategory): PhoneSettingsSection = entries.first { it.category == category }
    }
}

/**
 * Destination state for Phone Settings: the list, a section's page, or the page one level below
 * a section's own (the tablet's whole Accounts or AI Provider pane).
 */
sealed interface PhoneSettingsRoute {
    /** The Phone Settings home list: every section, grouped. */
    data object Home : PhoneSettingsRoute

    /** A section's full page. */
    data class FullPage(val section: PhoneSettingsSection) : PhoneSettingsRoute

    /** Every setting of [section]'s category as the tablet shows it, under [title]; Back returns to [section]. */
    data class AllSettings(val section: PhoneSettingsSection, val title: String) : PhoneSettingsRoute
}

/** Maps a [SettingsCategory] to its page on a phone: every category has one. */
fun resolvePhoneSettingsCategory(category: SettingsCategory): PhoneSettingsRoute =
    PhoneSettingsRoute.FullPage(PhoneSettingsSection.forCategory(category))

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
        "memory" -> PhoneSettingsRoute.FullPage(PhoneSettingsSection.Memory)
        "notifications", "notification" -> PhoneSettingsRoute.FullPage(PhoneSettingsSection.Notifications)
        "appearance", "theme", "themes" -> PhoneSettingsRoute.FullPage(PhoneSettingsSection.Theme)
        "servers", "server" -> PhoneSettingsRoute.FullPage(PhoneSettingsSection.Servers)
        "schedules", "schedule" -> PhoneSettingsRoute.FullPage(PhoneSettingsSection.Schedules)
        "storage", "query-audit" -> PhoneSettingsRoute.FullPage(PhoneSettingsSection.Storage)
        "about" -> PhoneSettingsRoute.FullPage(PhoneSettingsSection.About)
        else -> {
            val category = SettingsCategory.resolveOrNull(cleaned) ?: SettingsCategory.General
            resolvePhoneSettingsCategory(category)
        }
    }
}

/** "Claude, Codex & Antigravity": the providers this server offers, as the Accounts row's summary. */
fun phoneAccountsSummary(providerNames: List<String>): String? {
    val names = providerNames.filter { it.isNotBlank() }.distinct()
    return when (names.size) {
        0 -> null
        1 -> names[0]
        else -> names.dropLast(1).joinToString(", ") + " & " + names.last()
    }
}

/** "Claude · Opus 4.7 · High": the default provider for new chats, with its model and effort as saved. */
fun phoneAiProviderSummary(settings: AiSettings): String {
    val id = settings.defaultProvider.ifEmpty { "claude" }
    val cfg = settings.providers[id]
    return buildList {
        add(phoneProviderDisplayName(id))
        cfg?.model?.takeIf { it.isNotBlank() }?.let { add(ModelNames.displayName(it)) }
        cfg?.effort?.takeIf { it.isNotBlank() }?.let { e ->
            add(PhoneEffortOptions.firstOrNull { it.value == e }?.label ?: e)
        }
    }.joinToString(" · ")
}

/** "Push on 2 devices · Telegram · ntfy", or that no channel is set up, from the three notification channels. */
fun phoneNotificationsSummary(
    push: PushStatusState?,
    telegram: TelegramNotifyState?,
    ntfy: NtfyStatusState?,
): String {
    val parts = buildList {
        val devices = push?.devices?.size ?: 0
        if (devices > 0) add("Push on $devices ${if (devices == 1) "device" else "devices"}")
        if (telegram?.configured == true && telegram.chats.isNotEmpty()) add("Telegram")
        if (ntfy?.configured == true) add("ntfy")
    }
    return if (parts.isEmpty()) "No channels set up" else parts.joinToString(" · ")
}
