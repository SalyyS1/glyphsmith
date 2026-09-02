package com.salyvn.glyphsmith.glyph

/**
 * The arithmetic the client performs on a `bitmap` font provider, reimplemented so a pack can be
 * generated with the numbers it will really render with.
 *
 * Every formula here was read out of the vanilla loader (`BitmapProvider.Definition#load`,
 * `BitmapProvider.Definition#getActualGlyphWidth`, `GlyphBitmap`) rather than inferred from how packs
 * are usually written, because the interesting values — the advance and the vertical position — are
 * *derived* by the game and never declared by the author. Nothing here is copied from that code; the
 * expressions are the observable contract.
 */
object GlyphMath {
    /**
     * `GlyphBitmap.getTop()` is `7.0F - getBearingTop()` and a bitmap glyph's bearing is its `ascent`,
     * so a glyph with `ascent: 7` has its top edge exactly on the y the text was drawn at. Every
     * vertical placement in this tool is that one subtraction.
     */
    const val BASELINE: Int = 7

    /** `FontTexture.SIZE`. A source cell larger than one atlas page can never be stitched. */
    const val MAX_CELL: Int = 256

    /** The codepoint the client skips while reading a `chars` row, which is what pads short rows. */
    const val PADDING_CODEPOINT: Int = 0

    /** `int glyphWidth = w / this.codepointGrid[0].length` — integer division, remainder discarded. */
    fun cellWidth(imageWidth: Int, columns: Int): Int = imageWidth / columns

    /** `int glyphHeight = h / this.codepointGrid.length` — likewise. */
    fun cellHeight(imageHeight: Int, rows: Int): Int = imageHeight / rows

    /**
     * `float pixelScale = (float)this.height / glyphHeight`. The provider's `height` is a *render*
     * height: it doubles as the scale factor, which is why it cannot be raised to buy ascent headroom
     * without magnifying the texture.
     */
    fun pixelScale(height: Int, cellHeight: Int): Float = height.toFloat() / cellHeight

    /**
     * `(int)(0.5 + actualGlyphWidth * pixelScale) + 1`, where `actualGlyphWidth` is one past the
     * rightmost column of the cell that has any ink. The `+ 1` is unconditional, so vanilla's five
     * pixel wide `A` advances six, and a completely empty cell still advances one.
     */
    fun advance(inkWidth: Int, pixelScale: Float): Int = (0.5 + (inkWidth * pixelScale).toDouble()).toInt() + 1

    /** Where the top edge of the glyph lands, in pixels below the y the text was drawn at. */
    fun topOffset(ascent: Int): Int = BASELINE - ascent

    /** The inverse: the `ascent` that puts the top edge at [topOffset]. */
    fun ascentFor(topOffset: Int): Int = BASELINE - topOffset

    /** `getBottom() - getTop()` reduces to the provider's `height` for any cell size. */
    fun renderedHeight(height: Int): Int = height

    /** `getRight() - getLeft()`, which is the cell width in render pixels and not the advance. */
    fun renderedWidth(cellWidth: Int, pixelScale: Float): Float = cellWidth * pixelScale
}
