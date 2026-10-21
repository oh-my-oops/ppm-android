package com.hienlh.ppm.protocol

import java.text.Collator
import java.util.Locale

/** Anything the picker search ranks: a name and a description. */
interface FuzzySearchable {
    val name: String
    val description: String
}

/**
 * `fuzzy-search.ts`: a prefix beats a match inside, which beats a near spelling. Strings are
 * compared as JavaScript compares them, by UTF-16 unit, lower-cased as it lower-cases ([jsLowercase]).
 */
object FuzzySearch {
    data class Score(
        /** 0 prefix, 1 contains, 2 a near spelling. */
        val rank: Int,
        /** Where it was found, or how many edits away. */
        val distance: Int,
    )

    /**
     * A tie on rank between a recent item and a stranger goes to the recent one; so does a
     * recent "contains" against a stranger's prefix. Only a match on the name earns it:
     * descriptions are prose and match almost anything short.
     */
    private const val RECENT_TIER_BOOST = 1

    /** `localeCompare`, the last tie-break. */
    private val collator: Collator = Collator.getInstance(Locale.ENGLISH)

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    fun score(query: String, candidate: String): Score? {
        val lq = jsLowercase(query)
        val lc = jsLowercase(candidate)
        if (lc.startsWith(lq)) return Score(0, 0)
        val index = lc.indexOf(lq)
        if (index >= 0) return Score(1, index)
        // `Math.floor(length * 0.4)`, at least 2.
        val maxDistance = maxOf(lq.length * 2 / 5, 2)
        val distance = levenshtein(lq, lc.take(lq.length + maxDistance))
        return if (distance <= maxDistance) Score(2, distance) else null
    }

    /**
     * The items matching [query], best first, at most [limit]; every item, unranked, for no
     * query. Recently picked ones come up a tier, most recent first among equals; then the
     * nearer match, then the name.
     */
    fun <T : FuzzySearchable> search(items: List<T>, query: String, limit: Int = 20, recentNames: List<String> = emptyList()): List<T> {
        if (query.isEmpty()) return items
        // Capped, as Levenshtein is quadratic.
        val capped = query.take(50)
        val recentRank = HashMap<String, Int>()
        recentNames.forEachIndexed { index, name -> recentRank[name] = index }
        class Scored(val item: T, val rank: Int, val distance: Int, val recency: Int)
        val scored = ArrayList<Scored>()
        for (item in items) {
            val nameScore = score(capped, item.name)
            val best = listOfNotNull(nameScore, score(capped, item.description))
                .minWithOrNull(compareBy<Score>({ it.rank }, { it.distance })) ?: continue
            val recency = recentRank[item.name]
            // "The name reached the winning tier", not "the name beat the description".
            val boosted = recency != null && nameScore?.rank == best.rank
            scored += Scored(item, best.rank - if (boosted) RECENT_TIER_BOOST else 0, best.distance, recency ?: recentNames.size)
        }
        // Stable, so names that collate equal keep the list's order.
        scored.sortWith(
            compareBy<Scored>({ it.rank }, { it.recency }, { it.distance })
                .then { a, b -> collator.compare(a.item.name, b.item.name) },
        )
        return scored.take(limit).map { it.item }
    }
}
