package com.kazumaproject.markdownhelperkeyboard.converter.candidatebar

import com.kazumaproject.markdownhelperkeyboard.converter.candidate.Candidate

/** What the two rows of the candidate bar show. */
data class TwoRowCandidateBar(
    /** Leftmost slot of the top row: Sumire's conversion (possibly zenz-corrected). */
    val primary: Candidate?,
    /** Rest of the top row: alternatives for the first bunsetsu, in Sumire order. */
    val firstBunsetsuAlternatives: List<Candidate>,
    /** Bottom row: prediction candidates not already shown in the top row. */
    val predictions: List<Candidate>,
    /** necookey-exp: indices (into the analysis slots) of bunsetsu the engine is unsure about. */
    val uncertainSlotIndices: List<Int> = emptyList(),
    /** necookey-exp: the slot whose alternatives the top row shows, or -1 (old first-bunsetsu mode). */
    val focusedSlotIndex: Int = -1,
) {
    /** True when the bar should show the small "next uncertain bunsetsu" arrow. */
    val hasNextUncertain: Boolean get() = uncertainSlotIndices.size > 1
    val topRow: List<Candidate> get() = listOfNotNull(primary) + firstBunsetsuAlternatives

    companion object {
        val EMPTY = TwoRowCandidateBar(null, emptyList(), emptyList())
    }
}

object TwoRowCandidateBarPlanner {

    /** Sumire's type 5 ("combine part of letters"): a candidate covering a reading prefix. */
    const val CANDIDATE_TYPE_NBEST_PARTIAL: Byte = 5

    /** necookey-exp: a bunsetsu is "uncertain" when its runner-up path costs less than this extra. */
    const val UNCERTAIN_MARGIN = 1200

    /**
     * @param input the current reading.
     * @param conversionCandidates Sumire's merged conversion-mode list (no prediction), in order.
     * @param analysis bunsetsu analysis of the engine primary, or null when unavailable.
     * @param primaryOverride the zenz-corrected primary, when one is available for this input.
     * @param predictionCandidates Sumire's merged prediction-mode list, in order.
     * @param isActionCandidate candidates that are not text (macros, selection actions) are
     *   never deduplicated away and never treated as bunsetsu alternatives.
     */
    fun plan(
        input: String,
        conversionCandidates: List<Candidate>,
        analysis: BunsetsuAnalysis?,
        primaryOverride: Candidate?,
        predictionCandidates: List<Candidate>,
        config: NecookeyCandidateBarConfig = NecookeyCandidateBarConfig.DEFAULT,
        isActionCandidate: (Candidate) -> Boolean = { false },
        /** necookey-exp: which uncertain bunsetsu to focus (index into the uncertain list, wraps). */
        uncertainFocus: Int = 0,
    ): TwoRowCandidateBar {
        if (input.isEmpty()) return TwoRowCandidateBar.EMPTY

        val sumirePrimary = conversionCandidates.firstOrNull {
            !isActionCandidate(it) && it.length.toInt() == input.length
        } ?: conversionCandidates.firstOrNull { !isActionCandidate(it) }
        // zenz only ever replaces the engine's own best path. When learning / the user dictionary
        // put something else first, the user's history wins and the override is ignored.
        val primary = if (
            primaryOverride != null && analysis != null && sumirePrimary != null &&
            sumirePrimary.string == analysis.primary.string &&
            primaryOverride.length.toInt() == input.length
        ) {
            primaryOverride
        } else {
            sumirePrimary
        }

        val shownTop = LinkedHashSet<String>()
        primary?.let { shownTop += it.string }
        val alternatives = ArrayList<Candidate>()
        // The engine primary stays reachable (right next to the leftmost slot) when zenz replaced it.
        if (primary != null && sumirePrimary != null && primary !== sumirePrimary &&
            shownTop.add(sumirePrimary.string)
        ) {
            alternatives += sumirePrimary
        }

        // The first bunsetsu follows the accepted path: a zenz-corrected primary carries its own
        // segmentation, which may put the first boundary elsewhere than Sumire's analysis.
        val sumireFirstEnd = analysis?.takeIf { it.hasMultipleBunsetsu }?.firstBunsetsuEnd
        val acceptedSegments = primary?.takeIf { it === primaryOverride }?.conversionSegments.orEmpty()
        val firstEnd = if (acceptedSegments.isNotEmpty()) {
            acceptedSegments.first().inputEnd.takeIf { acceptedSegments.size > 1 }
        } else {
            sumireFirstEnd
        }
        // necookey-exp: show alternatives only for bunsetsu the engine hesitates on. Each
        // alternative is a whole-reading candidate with just that bunsetsu swapped, so picking it
        // commits the full conversion and needs no partial-commit handling.
        val slots = analysis?.slots.orEmpty()
        val engineOwnsPrimary = analysis != null && sumirePrimary != null && primary === sumirePrimary &&
            sumirePrimary.string == analysis.primary.string && slots.size > 1 &&
            slots.last().span.end == input.length
        val uncertainSlots = if (engineOwnsPrimary) {
            slots.indices.filter { idx ->
                val a = slots[idx].alternatives
                a.size > 1 && a[1].pathCost - a[0].pathCost < UNCERTAIN_MARGIN
            }.sortedBy { idx -> slots[idx].span.start }
        } else {
            emptyList()
        }
        var focusedSlot = -1
        if (uncertainSlots.isNotEmpty()) {
            focusedSlot = uncertainSlots[Math.floorMod(uncertainFocus, uncertainSlots.size)]
            for (alt in slots[focusedSlot].alternatives.drop(1)) {
                if (alt.output.isEmpty()) continue
                val whole = slots.mapIndexed { j, slot ->
                    if (j == focusedSlot) alt.output else slot.primaryOutput
                }.joinToString("")
                if (!shownTop.add(whole)) continue
                alternatives += Candidate(
                    string = whole,
                    type = sumirePrimary!!.type,
                    length = input.length.toUByte(),
                    score = alt.pathCost,
                    yomi = input,
                )
            }
        } else if (analysis != null && firstEnd != null && firstEnd in 1 until input.length) {
            // 1) Bunsetsu-level alternatives from Sumire's N-best paths (same [0, firstEnd) range).
            val slotAlternatives =
                if (firstEnd == sumireFirstEnd) analysis.slots.first().alternatives else emptyList()
            for (alt in slotAlternatives) {
                if (alt.output.isEmpty() || !shownTop.add(alt.output)) continue
                alternatives += Candidate(
                    string = alt.output,
                    type = CANDIDATE_TYPE_NBEST_PARTIAL,
                    length = firstEnd.toUByte(),
                    score = alt.pathCost,
                    yomi = input.substring(0, firstEnd),
                )
            }
            // 2) Sumire's own partial candidates covering exactly the first bunsetsu.
            for (candidate in conversionCandidates) {
                if (isActionCandidate(candidate)) continue
                if (candidate.length.toInt() != firstEnd) continue
                if (shownTop.add(candidate.string)) alternatives += candidate
            }
        } else {
            // Single bunsetsu: the alternatives for "the first bunsetsu" are the remaining
            // whole-reading conversion candidates.
            for (candidate in conversionCandidates) {
                if (isActionCandidate(candidate)) continue
                if (candidate.length.toInt() != input.length) continue
                if (shownTop.add(candidate.string)) alternatives += candidate
            }
        }

        val maxTop = (config.maxTopRowCandidates - (if (primary != null) 1 else 0)).coerceAtLeast(0)
        val topAlternatives = alternatives.take(maxTop)
        // Dedupe against what is actually visible in the top row (after truncation).
        val visibleTop = HashSet<String>()
        primary?.let { visibleTop += it.string }
        topAlternatives.forEach { visibleTop += it.string }
        val shownBottom = HashSet<String>()
        // necookey-exp: no look-ahead prediction. The bottom row shows whole-reading conversions
        // from Sumire's N-best (different segmentations / word choices), then the rest.
        val wholeReadingAlternatives = conversionCandidates.filter {
            !isActionCandidate(it) && it.length.toInt() == input.length
        }
        val predictions = (wholeReadingAlternatives + conversionCandidates).distinct().filter { candidate ->
            if (isActionCandidate(candidate)) return@filter true
            candidate.string.isNotEmpty() &&
                candidate.string !in visibleTop &&
                shownBottom.add(candidate.string)
        }.take(config.maxBottomRowCandidates.coerceAtLeast(0))

        return TwoRowCandidateBar(
            primary = primary,
            firstBunsetsuAlternatives = topAlternatives,
            predictions = predictions,
            uncertainSlotIndices = uncertainSlots,
            focusedSlotIndex = focusedSlot,
        )
    }
}
