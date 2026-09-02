package com.salyvn.glyphsmith.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.glyphsmith.glyph.Anchor
import com.salyvn.glyphsmith.glyph.GlyphMath
import com.salyvn.glyphsmith.glyph.Solver
import com.salyvn.glyphsmith.image.Texture

/**
 * Answers the question that otherwise costs an evening: given this image and where I want it, what do the
 * numbers have to be?
 *
 * Nothing is written. It is the solver with its work shown, which is also how you check the tool against a
 * pack somebody else built.
 */
class PlanCommand : GlyphsmithCommand(name = "plan") {

    override fun help(context: Context) = "Work out height, ascent, padding and advance for one image."

    private val image by argument("image", help = "The PNG to place").path()

    private val at by option("--at", help = "Where the top-left corner goes, as x,y (default: 0,0)")

    private val anchor by option(
        "--anchor",
        help = "What the coordinates are relative to: ${Anchor.entries.joinToString(", ") { it.id }}",
    )

    private val scale by option("--scale", help = "Source pixels per rendered pixel (default: 1)").int()

    private val height by option("--height", help = "Force the provider's height instead of deriving it").int()

    private val ascent by option("--ascent", help = "Force the ascent instead of deriving it").int()

    override fun execute() {
        val texture = Texture.read(image)
        val target = Anchor.of(anchor ?: Anchor.TEXT.id)
        val placement = at?.let { text ->
            val parts = text.split(",")
            if (parts.size != 2) fail("--at takes two numbers, like --at 8,6")
            val x = parts[0].trim().toIntOrNull() ?: fail("--at x is not a number: '${parts[0]}'")
            val y = parts[1].trim().toIntOrNull() ?: fail("--at y is not a number: '${parts[1]}'")
            target.toPlacement(x, y)
        }

        val solved = Solver.solve(
            texture = texture,
            placement = placement,
            scale = scale ?: 1,
            ascentOverride = ascent,
            heightOverride = height,
        )

        echo("$image  ${texture.width}x${texture.height}, alpha ${if (texture.hadAlphaChannel) "yes" else "no"}")
        echo("   ink        ${texture.inkWidth(0, 0, texture.width, texture.height)}px wide, ${texture.inkHeight()}px tall")
        if (placement != null) {
            echo("   placement  ${at} ${target.note} -> (${placement.x}, ${placement.y}) from the text origin")
        }
        echo("   height     ${solved.height}")
        echo("   ascent     ${solved.ascent}   (top edge lands ${GlyphMath.topOffset(solved.ascent)}px below the text origin)")
        if (solved.padBottom > 0 || solved.padRight > 0) {
            echo("   padding    ${solved.padBottom}px below, ${solved.padRight}px right, added so the cell divides evenly and the ascent is legal")
        }
        echo("   cells      ${solved.columns} of ${solved.cellWidth}x${solved.cellHeight}px, scale ${solved.pixelScale}")
        echo("   advance    ${solved.advances.joinToString(" + ")} = ${solved.totalAdvance}px")
        if (solved.leadShift != 0) echo("   lead shift ${solved.leadShift}px")
        echo("   restore    ${solved.restoreShift}px puts the cursor back where it started")
    }
}
