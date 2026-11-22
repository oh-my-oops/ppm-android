package com.hienlh.ppm.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.answerCollabApproval
import com.hienlh.ppm.client.answerDbApproval
import com.hienlh.ppm.client.answerVaultCard
import com.hienlh.ppm.client.vaultCardPrefill
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ProviderLogo
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssScrollY
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.protocol.SessionCollabAnswer
import com.hienlh.ppm.protocol.SessionCollabApprovalInput
import com.hienlh.ppm.protocol.VaultCardResult
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.double
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.long
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.database.SqlSplitOptions
import com.hienlh.ppm.state.database.splitSqlScript
import com.hienlh.ppm.state.database.sqlCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.text.NumberFormat
import java.util.Locale

/**
 * PPM's own approvals, answered over HTTP (`ws/chat-ppm-approvals.ts`): the server drops a chat
 * socket's `approval_response` for them, so a card that answered there left the AI waiting until
 * the request timed out. A failure is thrown, its message what the card shows.
 */
interface PpmApprovals {
    suspend fun answerCollab(requestId: String, answer: SessionCollabAnswer)

    suspend fun answerDb(requestId: String, approved: Boolean, password: String?)

    /** A vault card's answer (`VaultCardAnswers`); a sealed value shown to the user comes back only here. */
    suspend fun answerVault(requestId: String, answer: JsonObject): VaultCardResult = throw UnsupportedOperationException("Vault cards are not answered here")

    /** The value an AI put into a vault proposal, for its card to show masked. */
    suspend fun vaultPrefill(requestId: String): String? = null
}

/** [PpmApprovals] on the open server. Equal for the same client, so providing it again recomposes nothing. */
data class ServerPpmApprovals(private val api: ApiClient) : PpmApprovals {
    override suspend fun answerCollab(requestId: String, answer: SessionCollabAnswer) = api.answerCollabApproval(requestId, answer)

    override suspend fun answerDb(requestId: String, approved: Boolean, password: String?) = api.answerDbApproval(requestId, approved, password)

    override suspend fun answerVault(requestId: String, answer: JsonObject): VaultCardResult = api.answerVaultCard(requestId, answer)

    override suspend fun vaultPrefill(requestId: String): String? = api.vaultCardPrefill(requestId)
}

/** How this chat answers PPM's own approvals; none, and their cards show what is asked without buttons. */
val LocalPpmApprovals = staticCompositionLocalOf<PpmApprovals?> { null }

/** The `tool` of a `db_execute` approval (`DB_EXECUTE_APPROVAL`). */
internal const val DB_EXECUTE_APPROVAL_TOOL = "ppm:db_execute"

/** What a `db_execute` approval shows (`DbExecuteApprovalInput` in `shared/db-ai-tools.ts`), built by the server. */
data class DbExecuteApproval(
    val connectionName: String,
    val dbType: String,
    val group: String?,
    val color: String?,
    val readonly: Boolean,
    val database: String?,
    val sql: String,
    val reason: String,
    val expectedRows: Long?,
    val passwordRequired: Boolean,
) {
    companion object {
        /** A `db_execute` approval's input, or null for anything else (`dbExecuteApprovalInput`). */
        fun from(tool: String, input: JsonElement?): DbExecuteApproval? {
            if (tool != DB_EXECUTE_APPROVAL_TOOL || input !is JsonObject) return null
            if (input["connectionId"].double == null) return null
            val connectionName = input["connectionName"].string ?: return null
            val sql = input["sql"].string ?: return null
            return DbExecuteApproval(
                connectionName = connectionName,
                dbType = input["dbType"].string.orEmpty(),
                group = input["group"].string,
                color = input["color"].string,
                readonly = input["readonly"].bool == true,
                database = input["database"].string,
                sql = sql,
                reason = input["reason"].string.orEmpty(),
                expectedRows = input["expectedRows"].long,
                // Only a PPM with no password asks for none; a field missing is asked for.
                passwordRequired = input["passwordRequired"].bool != false,
            )
        }
    }
}

/** `DB_TYPE_LABELS`. */
internal fun dbTypeLabel(type: String): String = when (type) {
    "postgres" -> "PostgreSQL"
    "mysql" -> "MySQL"
    "mariadb" -> "MariaDB"
    "sqlite" -> "SQLite"
    else -> type
}

private val COMMITS_BY_ITSELF =
    Regex("""^(alter|create|drop|rename|truncate|grant|revoke|set\s+password|lock|unlock|analyze|optimize|repair|check|flush|reset(?!\s+persist\b)|install|uninstall)\b""", RegexOption.IGNORE_CASE)
private val MYSQL_COMMITS_BY_ITSELF = Regex("""^(cache\s+index|load\s+index)\b""", RegexOption.IGNORE_CASE)
private val TEMPORARY_TABLE = Regex("""^(create|drop)\s+(or\s+replace\s+)?temporary\b""", RegexOption.IGNORE_CASE)

/** The words that make MySQL or MariaDB commit [statement] by itself (`commitsByItself`), or null. */
internal fun dbCommitsByItself(dbType: String, statement: String): String? {
    if (dbType != "mysql" && dbType != "mariadb") return null
    val code = sqlCode(statement, SqlSplitOptions(dialect = "mysql")).trim()
    if (TEMPORARY_TABLE.containsMatchIn(code)) return null
    val match = COMMITS_BY_ITSELF.find(code) ?: (if (dbType == "mysql") MYSQL_COMMITS_BY_ITSELF.find(code) else null) ?: return null
    return match.groupValues[1].replace(Regex("""\s+"""), " ").uppercase()
}

/**
 * What the card adds when part of a MySQL or MariaDB script cannot be rolled back (`selfCommitNote`):
 * the last statement that commits by itself takes everything before it along. Null elsewhere.
 */
internal fun dbSelfCommitNote(dbType: String, sql: String): String? {
    if (dbType != "mysql" && dbType != "mariadb") return null
    val statements = splitSqlScript(sql, "mysql").statements
    for (i in statements.indices.reversed()) {
        val words = dbCommitsByItself(dbType, statements[i].text) ?: continue
        val what = "${dbTypeLabel(dbType)} commits the $words${if (statements.size > 1) " in statement ${i + 1}" else ""} by itself"
        return if (i == 0) "$what, so it cannot be rolled back." else "$what, together with everything before it, so statements 1–${i + 1} cannot be rolled back."
    }
    return null
}

private val WHITE = Rgba(1.0, 1.0, 1.0, 1.0)

private val xs = ppmText(12f, lineHeight = 16f)
private val xsMedium = ppmText(12f, FontWeight.Medium, lineHeight = 16f)
private val xsMono = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f)

/** What a failed answer says: the server's own message, as `api.post` throws it. */
private fun failure(error: Throwable): String = error.message?.takeIf { it.isNotBlank() } ?: error.toString()

/** The approval cards' frame: `rounded-lg border-2 border-warning/40 bg-warning/10 p-3 space-y-2`, a ShieldAlert heading. */
@Composable
private fun ApprovalFrame(heading: String, modifier: Modifier, content: @Composable () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier
            .fillMaxWidth()
            .background(tokens.warning.opacity(0.1).color, shape)
            .cssBorder(tokens.warning.opacity(0.4).color, shape, width = 2.dp)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PpmIcon(ProductIcon.ShieldAlert, 16.dp, tokens.warning.color)
            BasicText(heading, style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = tokens.warning.color))
        }
        content()
    }
}

/**
 * One of the cards' answer buttons: `h-11 md:h-8 px-4 rounded text-xs font-medium`, at half
 * opacity while disabled. [fill] fills it, else it is outlined in [stroke].
 */
@Composable
private fun AnswerButton(
    label: String,
    foreground: Rgba,
    modifier: Modifier = Modifier,
    fill: Rgba? = null,
    stroke: Rgba? = null,
    hoverFill: Rgba,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(4.dp)
    val hover = rememberHover()
    val base = fill ?: Rgba(0.0, 0.0, 0.0, 0.0)
    val height = if (LocalPpmMobile.current) 44.dp else 32.dp
    Box(
        modifier
            .heightIn(min = height)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(shape)
            .background(hoverFill.mix(base, hover.lit.toDouble()).color)
            .ppmClickable(enabled = enabled, pressedColor = hoverFill.color, source = hover.source, onClick = onClick)
            .then(if (stroke != null) Modifier.cssBorder(stroke.color, shape) else Modifier)
            .semantics { contentDescription = label }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { BasicText(label, style = xsMedium.copy(color = foreground.color), maxLines = 1) }
}

/** The answer's label (`answerLabel`). */
internal fun collabAnswerLabel(input: SessionCollabApprovalInput, answer: SessionCollabAnswer): String = when (answer) {
    SessionCollabAnswer.DENY -> "Deny"
    SessionCollabAnswer.ALLOW_SESSION -> "Allow for this session"
    SessionCollabAnswer.ALLOW_ONCE -> if (input is SessionCollabApprovalInput.PairLimit) "Allow ${input.grant} more" else "Allow once"
}

private fun collabHeading(input: SessionCollabApprovalInput): String = when (input) {
    is SessionCollabApprovalInput.Create -> "The AI wants to create a session"
    is SessionCollabApprovalInput.PairLimit -> "These sessions reached their message limit"
    is SessionCollabApprovalInput.Escalation -> "The AI wants to message a session with more rights"
    is SessionCollabApprovalInput.Schedule -> input.heading
    is SessionCollabApprovalInput.Message -> "The Assistant wants to message a chat in ${input.projectName}"
}

/** How long a text is, as the card states it (`lengthLabel`). */
internal fun lengthLabel(text: String): String {
    val chars = text.codePointCount(0, text.length)
    val lines = text.split("\n").size
    val format = NumberFormat.getIntegerInstance(Locale.US)
    return "${format.format(chars)} character${if (chars == 1) "" else "s"}" + (if (lines > 1) ", ${format.format(lines)} lines" else "")
}

private val MODE_LABELS = mapOf("plan" to "Plan", "default" to "Ask", "acceptEdits" to "Accept edits", "bypassPermissions" to "Bypass permissions")

internal fun modeLabel(mode: String) = MODE_LABELS[mode] ?: mode

/** `providerLabel` in session-collab-client.ts. */
internal fun providerLabel(providerId: String?): String = when (providerId) {
    "claude" -> "Claude"
    "codex" -> "Codex"
    null, "" -> "AI"
    else -> providerId
}

/**
 * A session tool's card (`collab-approval-card.tsx`): an AI asking to create a session, two
 * sessions at their message limit, a message to a session with more rights, or a script
 * schedule to create or a schedule to pause or turn on. The answer goes
 * over HTTP; the card leaves when `approval_resolved` arrives. Every title and text came from an
 * AI, so all of it is text.
 */
@Composable
fun CollabApprovalCard(requestId: String, input: SessionCollabApprovalInput, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val approvals = LocalPpmApprovals.current
    val scope = rememberCoroutineScope()
    var busy by remember(requestId) { mutableStateOf(false) }
    var error by remember(requestId) { mutableStateOf<String?>(null) }
    fun answer(choice: SessionCollabAnswer) {
        val target = approvals ?: return
        busy = true
        error = null
        scope.launch {
            try {
                target.answerCollab(requestId, choice)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = failure(e)
                busy = false
            }
        }
    }
    ApprovalFrame(collabHeading(input), modifier) {
        CollabApprovalBody(input)
        if (approvals != null) {
            @Composable
            fun button(choice: SessionCollabAnswer, each: Modifier) {
                val label = collabAnswerLabel(input, choice)
                when (choice) {
                    SessionCollabAnswer.DENY ->
                        AnswerButton(label, tokens.text, each, stroke = tokens.border, hoverFill = tokens.panel2, enabled = !busy) { answer(choice) }
                    SessionCollabAnswer.ALLOW_ONCE ->
                        AnswerButton(label, WHITE, each, fill = tokens.warning, hoverFill = tokens.warning.opacity(0.8), enabled = !busy) { answer(choice) }
                    SessionCollabAnswer.ALLOW_SESSION ->
                        AnswerButton(label, tokens.warning, each, stroke = tokens.warning.opacity(0.5), hoverFill = tokens.warning.opacity(0.1), enabled = !busy) { answer(choice) }
                }
            }
            if (LocalPpmMobile.current) {
                // `flex-col-reverse`: stacked bottom-up on a phone, the main answer nearest the thumb, Deny furthest from it.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (choice in input.answers.reversed()) button(choice, Modifier.fillMaxWidth())
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (choice in input.answers) button(choice, Modifier)
                }
            }
        }
        error?.let { BasicText(it, style = xs.copy(color = tokens.error.color)) }
    }
}

@Composable
private fun CollabApprovalBody(input: SessionCollabApprovalInput) {
    val tokens = LocalPpmTokens.current
    val primary = xs.copy(color = tokens.text.color)
    when (input) {
        is SessionCollabApprovalInput.Create -> {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                ProviderLogo(input.providerId, 16.dp, tokens.text.color)
                BasicText(input.title, Modifier.widthIn(max = 480.dp), xsMedium.copy(color = tokens.text.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicText(
                    providerLabel(input.providerId) + (input.model?.let { " · $it" } ?: "") + " · depth ${input.depth}",
                    style = xs.copy(color = tokens.text3.color),
                )
            }
            if (input.task.isNotEmpty()) WholeText("The whole task", input.task)
        }
        is SessionCollabApprovalInput.PairLimit -> BasicText(
            buildAnnotatedString {
                append("This session and ")
                withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(input.targetTitle) }
                append(" have sent each other ${input.sent} of ${input.limit} automatic messages since you last wrote in either. ")
                append("Allowing lets them send ${input.grant} more.")
            },
            style = primary,
        )
        is SessionCollabApprovalInput.Message -> CollabMessageBody(input)
        is SessionCollabApprovalInput.Escalation -> {
            BasicText(
                buildAnnotatedString {
                    append("To ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(input.targetTitle) }
                    append(", which runs in ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(modeLabel(input.targetMode)) }
                    append(" mode; this session runs in ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(modeLabel(input.senderMode)) }
                    append(" mode.")
                },
                style = primary,
            )
            if (input.message.isNotEmpty()) WholeText("The whole message", input.message)
            BasicText(
                "Allow for this session lasts until PPM restarts, and ends if that session switches to a mode with more rights.",
                style = xs.copy(color = tokens.text3.color),
            )
        }
        // The server's own summary of the schedule, as the Assistant's schedule cards show it.
        is SessionCollabApprovalInput.Schedule -> ApprovalSummaryBody(input.summary)
    }
}

/**
 * Everything the user approves, line breaks kept, in a box that scrolls rather than pushing the
 * buttons off screen (`max-h-60`). Nothing is cut.
 */
@Composable
internal fun WholeText(label: String, text: String) {
    val tokens = LocalPpmTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText("$label (${lengthLabel(text)}):", style = xs.copy(color = tokens.text3.color))
        CodeBox(text, maxHeight = 240)
    }
}

/** `pre text-xs font-mono text-text-secondary overflow-auto whitespace-pre-wrap bg-background rounded p-2 border`. */
@Composable
private fun CodeBox(text: String, maxHeight: Int) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight.dp)
            .background(tokens.bgSolid.color, shape)
            .cssBorder(tokens.border.color, shape)
            .cssScrollY(),
    ) {
        SelectionContainer {
            BasicText(text, Modifier.padding(8.dp), xsMono.copy(color = tokens.text2.color))
        }
    }
}

/**
 * A change the AI asked to make with `db_execute` (`db-execute-approval-card.tsx`): the
 * connection, the AI's reason and the exact SQL, run once if the user types PPM's password. The
 * answer goes over HTTP, so a wrong password is answered here while the card stays.
 */
@Composable
fun DbExecuteApprovalCard(requestId: String, input: DbExecuteApproval, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val approvals = LocalPpmApprovals.current
    val scope = rememberCoroutineScope()
    var password by remember(requestId) { mutableStateOf("") }
    var busy by remember(requestId) { mutableStateOf(false) }
    var error by remember(requestId) { mutableStateOf<String?>(null) }
    val canApprove = !busy && (!input.passwordRequired || password.isNotEmpty())
    val selfCommit = remember(input.dbType, input.sql) { dbSelfCommitNote(input.dbType, input.sql) }
    fun answer(approved: Boolean) {
        val target = approvals ?: return
        busy = true
        error = null
        scope.launch {
            try {
                target.answerDb(requestId, approved, if (approved && input.passwordRequired) password else null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = failure(e)
                busy = false
            }
        }
    }
    ApprovalFrame("Approve a database change", modifier) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            input.color?.let { Rgba.hex(it) }?.let { Box(Modifier.size(10.dp).clip(CircleShape).background(it.color)) }
            BasicText(input.connectionName, style = xsMedium.copy(color = tokens.text.color))
            input.database?.let { BasicText("/ $it", style = xsMono.copy(color = tokens.text2.color)) }
            BasicText(dbTypeLabel(input.dbType) + (input.group?.let { " · $it" } ?: ""), style = xs.copy(color = tokens.text3.color))
            if (input.readonly) {
                BasicText(
                    "readonly — lifted for this script only",
                    Modifier.background(tokens.panel2.color, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    ppmText(10f, lineHeight = 15f).copy(color = tokens.text3.color),
                )
            }
        }
        if (input.reason.isNotEmpty()) BasicText(input.reason, style = xs.copy(color = tokens.text.color))
        CodeBox(input.sql, maxHeight = 256)
        val rows = input.expectedRows
        BasicText(
            "Runs once, in one transaction." + (rows?.let { " Rolled back unless exactly $it row${if (it == 1L) "" else "s"} change." } ?: ""),
            style = ppmText(11f, lineHeight = 44f / 3).copy(color = tokens.text3.color),
        )
        selfCommit?.let { BasicText(it, style = ppmText(11f, lineHeight = 44f / 3).copy(color = tokens.warning.color)) }
        if (approvals != null) {
            if (input.passwordRequired) PasswordField(password, { password = it }, enabled = !busy, invalid = error != null) { if (canApprove) answer(true) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val each = if (LocalPpmMobile.current) Modifier.weight(1f) else Modifier
                AnswerButton("Run once", WHITE, each, fill = tokens.warning, hoverFill = tokens.warning.opacity(0.8), enabled = canApprove) { answer(true) }
                AnswerButton("Decline", tokens.text, each, stroke = tokens.border, hoverFill = tokens.panel2, enabled = !busy) { answer(false) }
            }
        }
        error?.let { BasicText(it, style = xs.copy(color = tokens.error.color)) }
    }
}

/** "PPM password": `type=password`, `h-11 md:h-8 md:max-w-56`, red-edged after a wrong one. */
@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, enabled: Boolean, invalid: Boolean, onDone: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(6.dp)
    val text = if (mobile) ppmText(16f, lineHeight = 24f) else ppmText(14f, lineHeight = 20f)
    val edge = when {
        invalid -> tokens.error
        focused -> tokens.accent
        else -> tokens.border
    }
    BasicTextField(
        value,
        onChange,
        Modifier
            .then(if (mobile) Modifier.fillMaxWidth() else Modifier.widthIn(max = 224.dp).fillMaxWidth())
            .heightIn(min = if (mobile) 44.dp else 32.dp)
            .cardTextField(focused)
            .semantics { contentDescription = "PPM password" }
            .background(Color.Transparent, shape)
            .cssBorder(edge.color, shape),
        enabled = enabled,
        textStyle = text.copy(color = tokens.text.color),
        cursorBrush = SolidColor(tokens.accent.color),
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        interactionSource = source,
        decorationBox = { field ->
            Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) BasicText("PPM password", style = text.copy(color = tokens.text3.color))
                field()
            }
        },
    )
}
