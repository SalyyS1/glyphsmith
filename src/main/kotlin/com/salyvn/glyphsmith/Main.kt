package com.salyvn.glyphsmith

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.versionOption
import com.salyvn.glyphsmith.cli.AuditCommand
import com.salyvn.glyphsmith.cli.BuildCommand
import com.salyvn.glyphsmith.cli.CoverageCommand
import com.salyvn.glyphsmith.cli.InitCommand
import com.salyvn.glyphsmith.cli.KernCommand
import com.salyvn.glyphsmith.cli.LayoutCommand
import com.salyvn.glyphsmith.cli.OpszCommand
import com.salyvn.glyphsmith.cli.PlanCommand
import com.salyvn.glyphsmith.cli.RasterizeCommand
import com.salyvn.glyphsmith.cli.SdfCommand
import com.salyvn.glyphsmith.cli.ShiftCommand
import com.salyvn.glyphsmith.cli.StringCommand
import com.salyvn.glyphsmith.cli.TtfCommand
import com.salyvn.glyphsmith.core.BuildInfo

/**
 * Entry point.
 *
 * The tool exists for pixel-exact Minecraft font engineering: solving bitmap ascent/padding/advance,
 * generating exact 2D Signed Distance Field (SDF) glyphs, solving composite 26.3 HUD negative-space
 * layouts, inspecting TrueType and OpenType variable fonts, optimizing glyph kerning pairs and signed-digit
 * shift runs, compiling dual-era format 97 packs, and auditing single or stacked resource packs with SARIF v2.1.0.
 */
class GlyphsmithCli : CliktCommand(name = "glyphsmith") {

    override fun help(context: Context) =
        "Build, optimize, and audit Minecraft bitmap and TrueType/OpenType font packs through format 97 (26.3)."

    override fun run() = Unit
}

fun main(args: Array<String>) = GlyphsmithCli()
    .versionOption(BuildInfo.version, names = setOf("--version"))
    .subcommands(
        BuildCommand(),
        PlanCommand(),
        LayoutCommand(),
        SdfCommand(),
        AuditCommand(),
        TtfCommand(),
        OpszCommand(),
        CoverageCommand(),
        RasterizeCommand(),
        KernCommand(),
        StringCommand(),
        ShiftCommand(),
        InitCommand(),
    )
    .main(args)
