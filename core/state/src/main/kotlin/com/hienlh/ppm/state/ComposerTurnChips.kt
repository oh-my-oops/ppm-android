package com.hienlh.ppm.state

import com.hienlh.ppm.client.TurnErrorCodes
import com.hienlh.ppm.protocol.ChatTurnRefs
import com.hienlh.ppm.protocol.TurnLinkTarget
import com.hienlh.ppm.protocol.generated.TurnReference
import com.hienlh.ppm.protocol.key
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One chip in the composer: a pasted turn link, still loading, ready to send, or no longer available. */
sealed interface ComposerTurnChip {
    val key: String
    val target: TurnLinkTarget

    /** [nonce] names this one paste's request, so its answer lands on this chip and no other. */
    data class Loading(override val key: String, override val target: TurnLinkTarget, val nonce: Long) : ComposerTurnChip

    data class Ready(override val key: String, override val target: TurnLinkTarget, val ref: TurnReference) : ComposerTurnChip

    /** Can only be removed. */
    data class Failed(override val key: String, override val target: TurnLinkTarget, val reason: String) : ComposerTurnChip
}

/**
 * The composer's turn chips (`use-composer-turn-refs.ts`): one per pasted turn link, resolved to
 * the turn's title, number and prompt before the message may go. Only ready chips are sent; a
 * chip still resolving, or one whose turn is gone, holds the send back with a reason
 * ([blockedReason]). Like every controller here, driven from one thread.
 */
class ComposerTurnChips(
    private val scope: CoroutineScope,
    /** What `GET …/turns/:turnId` makes of a link; null when the answer is not a reference a chip can send. */
    private val resolve: suspend (TurnLinkTarget) -> TurnReference?,
) {
    private val _chips = MutableStateFlow<List<ComposerTurnChip>>(emptyList())
    val chips: StateFlow<List<ComposerTurnChip>> = _chips.asStateFlow()

    /** Says why links stayed text: the composer had no room for them (a toast on the web). */
    var onOverflow: ((title: String, description: String) -> Unit)? = null

    /** Every change the user made: the composer saves its draft again. */
    var onChanged: (() -> Unit)? = null

    private var nonce = 0L

    /** The references the ready chips send. */
    val refs: List<TurnReference> get() = refsOf(_chips.value)

    val blockedReason: String? get() = blockedReason(_chips.value)

    /**
     * Adds a chip per new link, at most [ChatTurnRefs.MAX_TURN_REFS] in all, each resolved at once.
     * Answers the links there was no room for, so the paste keeps them as text; a link already
     * shown as a chip is not one of them.
     */
    fun addLinks(links: List<TurnLinkTarget>): List<TurnLinkTarget> {
        val (fit, overflow) = splitByRoom(_chips.value, links) { it.key }
        if (fit.isEmpty()) return overflow
        val added = fit.map { it to ++nonce }
        _chips.value = _chips.value + added.map { (link, n) -> ComposerTurnChip.Loading(link.key, link, n) }
        onChanged?.invoke()
        for ((link, n) in added) {
            scope.launch {
                val chip = resolveChip(link)
                // Lands only where its own loading chip still is: not after a removal, a send, or a switch.
                val now = _chips.value
                if (now.none { it is ComposerTurnChip.Loading && it.nonce == n }) return@launch
                _chips.value = now.map { if (it is ComposerTurnChip.Loading && it.nonce == n) chip else it }
                onChanged?.invoke()
            }
        }
        return overflow
    }

    fun remove(key: String) {
        val now = _chips.value
        if (now.none { it.key == key }) return
        _chips.value = now.filter { it.key != key }
        onChanged?.invoke()
    }

    /** Chips back from a stored message (an edit, a restore); null clears them. */
    fun replace(refs: List<TurnReference>?) {
        _chips.value = refs.orEmpty().map(::readyChip)
    }

    /**
     * A stored message's chips (an unsent message given back) added to the ones the composer holds
     * now, rather than replacing chips pasted since. Answers the references there was no room for.
     */
    fun merge(refs: List<TurnReference>?): List<TurnReference> {
        val (fit, overflow) = splitByRoom(_chips.value, refs.orEmpty()) { it.key }
        if (fit.isNotEmpty()) _chips.value = _chips.value + fit.map(::readyChip)
        return overflow
    }

    private suspend fun resolveChip(link: TurnLinkTarget): ComposerTurnChip {
        val ref = try {
            resolve(link)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return ComposerTurnChip.Failed(link.key, link, if (TurnErrorCodes.gone(error)) GONE_REASON else FAILED_REASON)
        }
        return if (ref != null) ComposerTurnChip.Ready(link.key, link, ref) else ComposerTurnChip.Failed(link.key, link, FAILED_REASON)
    }

    /**
     * Which of [items] there is room for after [chips]: one chip per turn (a turn already shown, or
     * named twice, is dropped) and at most [ChatTurnRefs.MAX_TURN_REFS] in all (`splitByRoom`).
     */
    private fun <T> splitByRoom(chips: List<ComposerTurnChip>, items: List<T>, key: (T) -> String): Pair<List<T>, List<T>> {
        val present = HashSet<String>()
        for (chip in chips) {
            present += chip.key
            if (chip is ComposerTurnChip.Ready) present += chip.ref.key
        }
        val fresh = items.filter { present.add(key(it)) }
        val room = maxOf(0, ChatTurnRefs.MAX_TURN_REFS - chips.size)
        if (fresh.size > room) onOverflow?.invoke("A message can reference at most ${ChatTurnRefs.MAX_TURN_REFS} turns", "The rest stay in the text as links.")
        return fresh.take(room) to fresh.drop(room)
    }

    companion object {
        const val GONE_REASON = "No longer available"
        const val FAILED_REASON = "Could not load this turn"
        const val LOADING_REASON = "Loading the referenced turns…"
        const val ERROR_REASON = "Remove the turns that can no longer be referenced"

        private fun readyChip(ref: TurnReference): ComposerTurnChip =
            ComposerTurnChip.Ready(ref.key, TurnLinkTarget(ref.projectName, ref.providerId, ref.sessionId, ref.turnId), ref)

        fun refsOf(chips: List<ComposerTurnChip>): List<TurnReference> = chips.mapNotNull { (it as? ComposerTurnChip.Ready)?.ref }

        /** Why the send waits, or null when every chip is ready (`turnChipsBlockReason`). */
        fun blockedReason(chips: List<ComposerTurnChip>): String? = when {
            chips.any { it is ComposerTurnChip.Loading } -> LOADING_REASON
            chips.any { it is ComposerTurnChip.Failed } -> ERROR_REASON
            else -> null
        }
    }
}
