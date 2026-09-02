package com.salyvn.glyphsmith.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.glyphsmith.lock.LockFile
import com.salyvn.glyphsmith.pack.PackBuilder
import com.salyvn.glyphsmith.spec.SpecReader
import com.salyvn.glyphsmith.text.SnippetFormat
import java.nio.file.Path

/**
 * Prints a glyph's string again, without rebuilding the pack.
 *
 * It resolves the spec and the lock the same way `build` does, so the answer is the one the pack on disk
 * agrees with — the codepoints come from the lock, and the shifts are recomputed from the artwork.
 */
class StringCommand : GlyphsmithCommand(name = "string") {

    override fun help(context: Context) = "Print the paste-ready string for one glyph."

    private val id by argument("glyph", help = "The glyph id from the spec")

    private val spec by option("--spec", help = "The spec file (default: glyphs.yml)").path()

    private val lockPath by option("--lock", help = "Codepoint lock file").path()

    private val format by option("--as", help = "raw, escape, minimessage or json (default: minimessage)")

    override fun execute() {
        val specFile = spec ?: Path.of("glyphs.yml")
        val parsed = SpecReader.read(specFile)
        val lockFile = lockPath ?: parsed.source.parent.resolve("glyphs.lock.json")
        val result = PackBuilder(parsed, LockFile.read(lockFile)).build(Path.of("pack"), dryRun = true)
        val glyph = result.glyphs.firstOrNull { built -> built.spec.id == id }
            ?: fail("No glyph '$id' in $specFile. It has: ${result.glyphs.joinToString(", ") { it.spec.id }}")
        echo(glyph.snippet.asFormat(SnippetFormat.of(format ?: SnippetFormat.MINIMESSAGE.id)))
    }
}
