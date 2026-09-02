package com.salyvn.glyphsmith.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.glyphsmith.lock.LockFile
import com.salyvn.glyphsmith.pack.PackBuilder
import com.salyvn.glyphsmith.spec.SpecReader
import com.salyvn.glyphsmith.text.Snippet
import com.salyvn.glyphsmith.text.SnippetFormat
import java.nio.file.Path

/**
 * Prints the characters that move the cursor a given number of pixels.
 *
 * Useful on its own: a lot of layout work is nudging something that is already on screen, and this is the
 * shift run for that nudge, taken from the same font the pack ships so the codepoints are the pack's own.
 */
class ShiftCommand : GlyphsmithCommand(name = "shift") {

    override fun help(context: Context) = "Print the characters that shift the cursor by N pixels."

    private val pixels by argument(
        "pixels",
        help = "How far to move. A leading minus needs to be fenced off — 'shift -- -13' — or use --left 13",
    )

    private val left by option("--left", help = "Move left by this many pixels instead of right").flag()

    private val spec by option("--spec", help = "The spec file (default: glyphs.yml)").path()

    private val lockPath by option("--lock", help = "Codepoint lock file").path()

    private val format by option("--as", help = "raw, escape, minimessage or json (default: minimessage)")

    override fun execute() {
        val requested = pixels.trim().toIntOrNull()
            ?: fail("'$pixels' is not a number of pixels")
        val distance = if (left) -kotlin.math.abs(requested) else requested
        val specFile = spec ?: Path.of("glyphs.yml")
        val parsed = SpecReader.read(specFile)
        val lockFile = lockPath ?: parsed.source.parent.resolve("glyphs.lock.json")
        val result = PackBuilder(parsed, LockFile.read(lockFile)).build(Path.of("pack"), dryRun = true)
        val shifts = result.shifts
            ?: fail(
                "$specFile has no shift glyphs: either 'shifts' is 0 or no glyph is placed with 'at', " +
                    "and shift glyphs are only generated when something needs them.",
            )
        if (kotlin.math.abs(distance) > parsed.shiftRange) {
            echo(
                "${distance}px is past the ${parsed.shiftRange}px range in the spec, so it is written as several " +
                    "of the largest step.",
                err = true,
            )
        }
        val snippet = Snippet(shifts.string(distance), parsed.font)
        echo(snippet.asFormat(SnippetFormat.of(format ?: SnippetFormat.MINIMESSAGE.id)))
    }
}
