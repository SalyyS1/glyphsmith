package com.salyvn.glyphsmith.glyph

/**
 * The screen positions a glyph can be aimed at, and where each one's text origin sits.
 *
 * Only origins that were read out of the client are here. A container's title is drawn at
 * `titleLabelX = 8`, `titleLabelY = 6` inside the window, so a glyph aimed at the window's own
 * top-left corner needs to be pulled 8px left and lifted 6px, and this is the conversion people
 * otherwise do by restarting the client until it looks right.
 */
enum class Anchor(val id: String, val originX: Int, val originY: Int, val note: String) {
    /** Coordinates are relative to the text origin itself: what you ask for is what `ascent` encodes. */
    TEXT("text", 0, 0, "relative to wherever the text is drawn"),

    /** Coordinates are relative to the top-left corner of the container window. */
    CONTAINER_TITLE(
        "container-title",
        8,
        6,
        "relative to the container window's top-left corner (title is drawn at 8,6 inside it)",
    ),

    /** Coordinates are relative to the first pixel of a chat line. */
    CHAT("chat", 0, 0, "relative to the start of the chat line"),

    /** Coordinates are relative to the centered actionbar overlay text origin. */
    ACTIONBAR("actionbar", 0, 0, "relative to the centered actionbar text origin"),

    /** Coordinates are relative to the 182x5 bossbar bar center-top (title text sits 9px above bar). */
    BOSSBAR("bossbar", 0, -9, "relative to the 182x5 bossbar bar center-top (title is drawn 9px above bar)"),
    ;

    /** Converts author coordinates into the offsets the solver works in. */
    fun toPlacement(x: Int, y: Int): Placement = Placement(x - originX, y - originY)

    companion object {
        fun of(id: String): Anchor = entries.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException(
                "Unknown anchor '$id'. Known: ${entries.joinToString(", ") { it.id }}.",
            )
    }
}
