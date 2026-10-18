package com.example.tut.data

/** One activation-word pair from settings: saying [from] counts as saying [to] ("poles" = "pull-ups"). */
data class WordAlias(val from: String = "", val to: String = "")

object WordAliases {
    /** Only pairs with both boxes filled in. */
    private fun usable(aliases: List<WordAlias>) = aliases
        .map { WordAlias(it.from.trim(), it.to.trim()) }
        .filter { it.from.isNotEmpty() && it.to.isNotEmpty() && !it.from.equals(it.to, ignoreCase = true) }
        // Longest first, so "incline bench" wins over "bench".
        .sortedByDescending { it.from.length }

    /**
     * Replaces every whole-word [WordAlias.from] in [text] with its [WordAlias.to], ignoring case.
     * Where the full word is already said ("bench press" with bench = bench press) it is left alone.
     */
    fun apply(text: String, aliases: List<WordAlias>): String {
        val pairs = usable(aliases)
        if (pairs.isEmpty()) return text
        val out = StringBuilder()
        var i = 0
        outer@ while (i < text.length) {
            if (i == 0 || !text[i - 1].isLetterOrDigit()) {
                for (p in pairs) {
                    if (!wordAt(text, i, p.from)) continue
                    // Already the full word: copy it through untouched.
                    val keep = if (wordAt(text, i, p.to)) p.to.length else 0
                    if (keep > 0) out.append(text, i, i + keep) else out.append(p.to)
                    i += if (keep > 0) keep else p.from.length
                    continue@outer
                }
            }
            out.append(text[i])
            i++
        }
        return out.toString()
    }

    /**
     * An exercise name that is one of the pairs, either word, becomes the second box exactly as
     * typed there ("pull ups" heard, "pull-ups" typed: the name is "pull-ups"). Otherwise null.
     */
    fun nameFor(name: String, aliases: List<WordAlias>): String? {
        val key = squash(name)
        if (key.isEmpty()) return null
        return usable(aliases).firstOrNull { squash(it.from) == key || squash(it.to) == key }?.to
    }

    private fun squash(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun wordAt(text: String, at: Int, word: String): Boolean {
        if (!text.regionMatches(at, word, 0, word.length, ignoreCase = true)) return false
        val end = at + word.length
        return end == text.length || !text[end].isLetterOrDigit()
    }
}
