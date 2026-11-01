package com.hienlh.ppm.state.files

import java.util.Locale

/** Same quoted-field and short/long-row rules as web/lib/csv-parser.ts. */
data class CsvDocument(val headers: List<String>, val rows: List<List<String>>) {
    fun sortedRows(column: Int?, descending: Boolean = false): List<Int> {
        val indices = rows.indices.toList()
        if (column == null || column !in headers.indices) return indices
        // TanStack's auto detector examines rows AFTER the first ten (slice(10)).
        val sample = rows.drop(10)
        val numeric = sample.any { row -> row[column].any { it in '0'..'9' } }
        val foldCase = sample.isNotEmpty()
        return indices.sortedWith { left, right ->
            val a = rows[left][column].let { if (foldCase) it.lowercase(Locale.ROOT) else it }
            val b = rows[right][column].let { if (foldCase) it.lowercase(Locale.ROOT) else it }
            val compared = if (numeric) compareCsvNatural(a, b) else a.compareTo(b)
            (if (descending) -compared.sign() else compared.sign()).takeIf { it != 0 } ?: left.compareTo(right)
        }
    }

    companion object {
        fun parse(content: String): CsvDocument {
            val rows = mutableListOf<List<String>>()
            var row = mutableListOf<String>()
            val field = StringBuilder()
            var state = 0 // field start, unquoted, quoted, closing quote
            fun flushField() { row.add(field.toString()); field.setLength(0) }
            fun flushRow() { flushField(); rows.add(row); row = mutableListOf() }
            for (ch in content) when (state) {
                0 -> when (ch) {
                    '"' -> state = 2
                    ',' -> flushField()
                    '\r' -> Unit
                    '\n' -> flushRow()
                    else -> { field.append(ch); state = 1 }
                }
                1 -> when (ch) {
                    ',' -> { flushField(); state = 0 }
                    '\r' -> Unit
                    '\n' -> { flushRow(); state = 0 }
                    else -> field.append(ch)
                }
                2 -> if (ch == '"') state = 3 else field.append(ch)
                3 -> when (ch) {
                    '"' -> { field.append(ch); state = 2 }
                    ',' -> { flushField(); state = 0 }
                    '\r' -> Unit
                    '\n' -> { flushRow(); state = 0 }
                    else -> { field.append(ch); state = 1 }
                }
            }
            if (field.isNotEmpty() || row.isNotEmpty()) flushRow()
            if (rows.isEmpty()) return CsvDocument(emptyList(), emptyList())
            val headers = rows.first()
            return CsvDocument(headers, rows.drop(1).map { values -> List(headers.size) { values.getOrElse(it) { "" } } })
        }
    }
}

private fun Int.sign() = compareTo(0)
private val csvParts = Regex("[0-9]+|[^0-9]+")
private fun compareCsvNatural(left: String, right: String): Int {
    val a = csvParts.findAll(left).map { it.value }.toList()
    val b = csvParts.findAll(right).map { it.value }.toList()
    for (i in 0 until minOf(a.size, b.size)) {
        val an = a[i].first() in '0'..'9'
        val bn = b[i].first() in '0'..'9'
        val value = when {
            an && bn -> a[i].toDouble().compareTo(b[i].toDouble())
            an != bn -> if (an) 1 else -1
            else -> a[i].compareTo(b[i])
        }
        if (value != 0) return value
    }
    return a.size.compareTo(b.size)
}
