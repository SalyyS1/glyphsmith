package com.salyvn.glyphsmith.codepoint

/**
 * Hands out codepoints and never takes one back.
 *
 * A glyph's codepoint leaks into places a rebuild cannot reach: item lore already written to a world,
 * signs, database rows, other plugins' configs. So allocation is append-only — ids that were assigned
 * before keep their codepoint, and only new ids get new ones, taken from the first free slot after the
 * highest one already handed out.
 */
class CodepointAllocator(start: Int, assigned: Map<String, Int> = emptyMap()) {
    private val byId = LinkedHashMap<String, Int>()
    private val used = HashSet<Int>()
    private var highest: Int

    init {
        require(Codepoints.isUsable(start)) { "${Codepoints.describe(start)} cannot be used as a starting codepoint" }
        assigned.forEach { (id, codepoint) ->
            byId[id] = codepoint
            used += codepoint
        }
        highest = (assigned.values.maxOrNull() ?: (start - 1)).coerceAtLeast(start - 1)
    }

    /** The codepoint for [id], reused if it was assigned before and freshly taken if not. */
    fun allocate(id: String): Int = byId.getOrPut(id) {
        var candidate = Codepoints.next(highest)
        while (candidate in used) candidate = Codepoints.next(candidate)
        highest = candidate
        used += candidate
        candidate
    }

    /** Reserves a run of consecutive codepoints, which is what a sliced image needs. */
    fun allocateRun(id: String, count: Int): List<Int> =
        (0 until count).map { index -> allocate(if (index == 0) id else "$id#$index") }

    fun assignments(): Map<String, Int> = LinkedHashMap(byId)
}
