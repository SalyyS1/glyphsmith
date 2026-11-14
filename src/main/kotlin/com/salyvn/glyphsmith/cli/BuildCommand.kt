package com.salyvn.glyphsmith.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.glyphsmith.lock.LockFile
import com.salyvn.glyphsmith.pack.PackBuilder
import com.salyvn.glyphsmith.report.ConsoleReport
import com.salyvn.glyphsmith.report.JsonReport
import com.salyvn.glyphsmith.spec.SpecReader
import java.nio.file.Files
import java.nio.file.Path

/**
 * Builds the pack.
 *
 * One command, one spec file, and afterwards a pack directory, a lock file that pins every codepoint, and
 * the strings to paste. Nothing about the output depends on when it ran.
 */
class BuildCommand : GlyphsmithCommand(name = "build") {

    override fun help(context: Context) = "Build a resource pack from a glyph spec."

    private val spec by argument("spec", help = "The spec file").path().default(Path.of("glyphs.yml"))

    private val out by option("--out", help = "Where the pack goes (default: pack)").path()

    private val zip by option("--zip", help = "Also write a zip of the pack to this path").path()

    private val lockPath by option(
        "--lock",
        help = "Codepoint lock file (default: glyphs.lock.json beside the spec)",
    ).path()

    private val snippetsPath by option(
        "--snippets",
        help = "Where to write the paste-ready strings (default: glyphs.snippets.txt beside the lock)",
    ).path()

    private val strict by option("--strict", help = "Treat warnings as failures").flag()

    private val locked by option(
        "--locked",
        help = "Fail instead of allocating new codepoints or changing locked glyph hashes/metrics",
    ).flag()

    private val json by option("--json", help = "Emit machine-readable JSON build report to stdout").flag()

    override fun execute() {
        val parsed = SpecReader.read(spec)
        val lockFile = lockPath ?: parsed.source.parent.resolve("glyphs.lock.json")
        val previous = LockFile.read(lockFile)
        if (previous != null && previous.font != parsed.font) {
            fail(
                "$lockFile was written for font '${previous.font}' and the spec now says '${parsed.font}'. " +
                    "Renaming the font means the old codepoints belong to a font nothing reads any more; " +
                    "point --lock at a new file if that is what you meant.",
            )
        }

        val root = out ?: Path.of("pack")
        if (locked) {
            if (previous == null) {
                fail("--locked requires an existing lock file at $lockFile, but none was found.")
            }
            val check = PackBuilder(parsed, previous).build(root, zipTarget = null, dryRun = true)
            if (check.newCodepoints.isNotEmpty()) {
                fail(
                    "--locked refused to allocate ${check.newCodepoints.size} unpinned codepoint id(s): " +
                        "${check.newCodepoints.take(5).joinToString(", ")}. Run 'glyphsmith build' locally and commit $lockFile.",
                )
            }
            for ((id, currentGlyph) in check.lock.glyphs) {
                val lockedGlyph = previous.glyphs[id]
                    ?: fail("--locked found glyph '$id' missing from $lockFile. Rebuild locally and commit $lockFile.")
                if (lockedGlyph != currentGlyph) {
                    fail(
                        "--locked detected drift for glyph '$id' (image sha256 or solved metrics changed relative to $lockFile). " +
                            "Rebuild locally and commit $lockFile.",
                    )
                }
            }
        }

        val result = PackBuilder(parsed, previous).build(root, zip)
        if (!locked) {
            result.lock.write(lockFile)
        }

        val snippets = snippetsPath ?: defaultSnippetPath(lockFile)
        snippets.parent?.let { parent -> Files.createDirectories(parent) }
        Files.writeString(snippets, ConsoleReport.snippetFile(result))

        if (json) {
            echo(JsonReport.renderBuild(result, lockFile, snippets))
        } else {
            ConsoleReport.lines(result).forEach { line -> echo(line) }
            echo("")
            echo("Wrote ${result.glyphs.size} glyph${if (result.glyphs.size == 1) "" else "s"} to $root")
            echo("Codepoints pinned in $lockFile${if (previous == null) " (new)" else ""}")
            echo("Strings in $snippets")
            zip?.let { target -> echo("Zipped to $target") }
        }

        if (strict && result.warnings.isNotEmpty()) {
            fail("${result.warnings.size} warning(s) and --strict was given.")
        }
    }

    /**
     * The strings land beside the lock and are named after it, so two specs built in one directory do not
     * overwrite each other's output.
     */
    private fun defaultSnippetPath(lockFile: Path): Path {
        val name = lockFile.fileName.toString()
        val stem = name.removeSuffix(".json").removeSuffix(".lock")
        return lockFile.resolveSibling("$stem.snippets.txt")
    }
}
