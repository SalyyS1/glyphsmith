package com.salyvn.glyphsmith.spec

import com.salyvn.glyphsmith.glyph.Anchor
import com.salyvn.glyphsmith.glyph.Placement
import com.salyvn.glyphsmith.pack.PackFormat
import java.nio.file.Path

/** One glyph as the author declared it. */
data class GlyphSpec(
    val id: String,
    val image: Path,
    val anchor: Anchor,
    val placement: Placement?,
    val scale: Int,
    val ascent: Int?,
    val height: Int?,
    val restoreCursor: Boolean,
) {
    /** True when the author asked for a position and the solver has to derive the numbers. */
    val isPlaced: Boolean get() = placement != null
}

/** A whole spec file, resolved: paths absolute, defaults filled in, versions turned into pack formats. */
data class Spec(
    val namespace: String,
    val font: String,
    val description: String,
    val minFormat: PackFormat,
    val maxFormat: PackFormat,
    val startCodepoint: Int,
    val shiftRange: Int,
    val glyphs: List<GlyphSpec>,
    val source: Path,
) {
    /** The font id split for use as a path, since a font lives at `assets/<namespace>/font/<path>.json`. */
    val fontNamespace: String get() = font.substringBefore(':')
    val fontPath: String get() = font.substringAfter(':')

    val usesShifts: Boolean get() = shiftRange > 0 && glyphs.any { glyph -> glyph.isPlaced }
}
