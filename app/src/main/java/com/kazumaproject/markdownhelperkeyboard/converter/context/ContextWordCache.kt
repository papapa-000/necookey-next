package com.kazumaproject.markdownhelperkeyboard.converter.context

/**
 * necookey-exp: words seen around the input (text before/after the cursor and, when the
 * accessibility service is enabled, the visible screen). Memory only, never persisted or logged.
 * Lattice nodes whose surface appears here get a small cost bonus, so 「橋」 vs 「箸」 follows the
 * topic on screen.
 */
object ContextWordCache {
    const val FIELD_BONUS = 1800
    const val SCREEN_BONUS = 1200
    private const val MAX_TEXT = 4000
    private const val MAX_RUN = 10

    @Volatile private var fieldWords: Set<String> = emptySet()
    @Volatile private var screenWords: Set<String> = emptySet()

    fun setFieldText(text: String) { fieldWords = extract(text) }
    fun setScreenText(text: String) { screenWords = extract(text) }
    fun clear() { fieldWords = emptySet(); screenWords = emptySet() }

    /** Cost to subtract for a node surface (0 when unknown or not content-like). */
    fun bonus(surface: String): Int {
        if (surface.length < 2 || !surface.any { it.isKanji() || it.isKatakana() }) return 0
        if (surface in fieldWords) return FIELD_BONUS
        if (surface in screenWords) return SCREEN_BONUS
        return 0
    }

    private fun extract(raw: String): Set<String> {
        val text = if (raw.length > MAX_TEXT) raw.substring(raw.length - MAX_TEXT) else raw
        val out = HashSet<String>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val kind = when {
                c.isKanji() -> 1
                c.isKatakana() -> 2
                else -> 0
            }
            if (kind == 0) { i++; continue }
            var j = i + 1
            while (j < text.length && kindOf(text[j]) == kind) j++
            val run = text.substring(i, j)
            if (run.length in 2..MAX_RUN) out += run
            if (kind == 1 && run.length in 3..8) {
                // compound kanji: also keep its 2..n-1 character pieces (「電話番号」→「電話」「番号」…)
                for (a in run.indices) for (b in a + 2..run.length) {
                    if (b - a < run.length) out += run.substring(a, b)
                }
            }
            // kanji + okurigana (「掛け」「架ける」) as a single surface
            if (kind == 1 && j < text.length && text[j].isHiragana()) {
                var k = j
                while (k < text.length && text[k].isHiragana() && k - j < 3) k++
                for (e in j + 1..k) out += text.substring(i, e)
            }
            i = j
        }
        return out
    }

    private fun kindOf(c: Char) = when {
        c.isKanji() -> 1
        c.isKatakana() -> 2
        else -> 0
    }

    private fun Char.isKanji() = this in '\u4E00'..'\u9FFF' || this in '\u3400'..'\u4DBF' || this == '々'
    private fun Char.isKatakana() = this in '\u30A1'..'\u30FA' || this == 'ー'
    private fun Char.isHiragana() = this in '\u3041'..'\u3096'
}
