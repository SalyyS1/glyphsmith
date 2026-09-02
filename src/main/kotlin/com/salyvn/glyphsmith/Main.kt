package com.salyvn.glyphsmith

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.versionOption
import com.salyvn.glyphsmith.cli.BuildCommand
import com.salyvn.glyphsmith.cli.InitCommand
import com.salyvn.glyphsmith.cli.PlanCommand
import com.salyvn.glyphsmith.cli.ShiftCommand
import com.salyvn.glyphsmith.cli.StringCommand
import com.salyvn.glyphsmith.core.BuildInfo

/**
 * Entry point.
 *
 * The tool exists for one conversion: pixels where you want them, into the numbers a `bitmap` font
 * provider has to declare. Everything else here — the codepoint lock, the shift glyphs, the pack.mcmeta —
 * is what that conversion needs in order to be usable twice.
 */
class GlyphsmithCli : CliktCommand(name = "glyphsmith") {

    override fun help(context: Context) =
        "Build Minecraft font glyph packs from images, with the ascent, padding, advance and shifts worked out."

    override fun run() = Unit
}

fun main(args: Array<String>) = GlyphsmithCli()
    .versionOption(BuildInfo.version, names = setOf("--version"))
    .subcommands(
        BuildCommand(),
        PlanCommand(),
        StringCommand(),
        ShiftCommand(),
        InitCommand(),
    )
    .main(args)
