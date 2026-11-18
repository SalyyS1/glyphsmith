package com.salyvn.glyphsmith.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.glyphsmith.glyph.HudContext
import com.salyvn.glyphsmith.glyph.HudLayoutSolver
import com.salyvn.glyphsmith.glyph.HudLayoutSpec
import com.salyvn.glyphsmith.lock.LockFile
import com.salyvn.glyphsmith.text.SnippetFormat

/**
 * Solves multi-layer Minecraft 26.3 composite HUD, actionbar, bossbar, and container negative-space layouts.
 */
class LayoutCommand : GlyphsmithCommand(name = "layout") {

    override fun help(context: Context) =
        "Solve composite Minecraft 26.3 HUD, bossbar, actionbar, and container negative-space glyph layouts."

    private val specFile by argument("spec", help = "Optional HUD layout YAML file (e.g. hud.yml)").path().optional()

    private val inlineLayers by option(
        "-l",
        "--layer",
        help = "Inline layer '<id>:<W>x<H>@<X>,<Y>[:z=<Z>][:adv=<A>]' (repeatable)",
    ).multiple()

    private val contextName by option(
        "--context",
        help = "Screen context: actionbar (default), bossbar, container-title, title, subtitle, chat, scoreboard",
    )

    private val fontId by option("--font", help = "Target font resource location (default: myserver:hud)")

    private val shiftRange by option("--shifts", help = "Maximum power-of-two shift step in px (default: 1024)").int()

    private val lockPath by option("--lock", help = "Optional glyphs.lock.json to reuse pinned codepoints and advances").path()

    private val noCompact by option("--no-compact", help = "Disable signed-digit NAF minimal shift compression").flag()

    private val noBalance by option(
        "--no-balance",
        help = "Disable trailing zero-width cursor balancing shift",
    ).flag()

    private val targetAdvance by option(
        "--target-advance",
        help = "Target net cursor advance in px when cursor balancing is enabled (default: 0)",
    ).int()

    private val preserveOrder by option(
        "--preserve-order",
        help = "Keep exact declared layer order instead of Held-Karp minimum-shift scheduling",
    ).flag()

    private val formatName by option(
        "--as",
        help = "Primary snippet format to highlight: minimessage (default), json, escape, or raw",
    ).default("minimessage")

    private val json by option("--json", help = "Emit structured JSON layout report").flag()

    override fun execute() {
        val outFormat = SnippetFormat.of(formatName)

        val baseSpec = when {
            specFile != null -> HudLayoutSolver.parseYamlSpec(specFile!!)
            inlineLayers.isNotEmpty() -> HudLayoutSpec(
                font = fontId ?: "myserver:hud",
                context = contextName?.let { HudContext.of(it) } ?: HudContext.ACTIONBAR,
                shiftRange = shiftRange ?: 1024,
                compactShifts = !noCompact,
                balanceCursor = !noBalance,
                targetNetAdvancePx = targetAdvance ?: 0,
                optimizeOrder = !preserveOrder,
                layers = inlineLayers.map { HudLayoutSolver.parseInlineLayer(it) },
            )
            else -> throw IllegalArgumentException(
                "Provide either a layout YAML file (glyphsmith layout hud.yml) or one or more --layer flags (e.g. --layer bg:182x10@-91,-5).",
            )
        }

        val effectiveSpec = baseSpec.copy(
            font = fontId ?: baseSpec.font,
            context = contextName?.let { HudContext.of(it) } ?: baseSpec.context,
            shiftRange = shiftRange ?: baseSpec.shiftRange,
            compactShifts = if (noCompact) false else baseSpec.compactShifts,
            balanceCursor = if (noBalance) false else baseSpec.balanceCursor,
            targetNetAdvancePx = targetAdvance ?: baseSpec.targetNetAdvancePx,
            optimizeOrder = if (preserveOrder) false else baseSpec.optimizeOrder,
            layers = if (specFile != null && inlineLayers.isNotEmpty()) {
                baseSpec.layers + inlineLayers.map { HudLayoutSolver.parseInlineLayer(it) }
            } else {
                baseSpec.layers
            },
        )

        val resolvedLock = when {
            lockPath != null -> LockFile.read(lockPath!!)
                ?: throw IllegalArgumentException("Lock file not found: $lockPath")
            specFile != null -> {
                val sibling = specFile!!.toAbsolutePath().normalize().parent?.resolve("glyphs.lock.json")
                if (sibling != null) LockFile.read(sibling) else null
            }
            else -> null
        }

        val solved = HudLayoutSolver.solve(effectiveSpec, resolvedLock)

        if (json) {
            echo(solved.toJsonString())
            return
        }

        echo("== layout ${solved.font} [${solved.context}]")
        solved.layers.forEach { layer ->
            val padInfo = if (layer.padBottomPx > 0) " (pad +${layer.padBottomPx}px)" else ""
            echo(
                "   %-16s z=%-2d at (%4d,%4d)  %3dx%-3d  h=%-3d a=%-3d adv=%-3d  shift=%+5dpx (%d NAF / %d greedy)%s".format(
                    layer.id,
                    layer.zIndex,
                    layer.targetX,
                    layer.targetY,
                    layer.widthPx,
                    layer.heightPx,
                    layer.solvedHeight,
                    layer.solvedAscent,
                    layer.vanillaAdvancePx,
                    layer.preShiftPx,
                    layer.nafShiftGlyphs,
                    layer.greedyShiftGlyphs,
                    padInfo,
                ),
            )
        }
        if (solved.balanceCursor) {
            echo(
                "   tail balance     %+dpx -> final net advance %dpx (prevents %+dpx centered drift)".format(
                    solved.tailShiftPx,
                    solved.finalNetAdvancePx,
                    solved.unbalancedCenterDriftPx,
                ),
            )
        }
        echo(
            "   budget           %d layer glyphs + %d shift glyphs (greedy: %d) | %d UTF-16 units (%d UTF-8 bytes)".format(
                solved.totalGlyphCodepoints,
                solved.totalShiftGlyphsNaf,
                solved.totalShiftGlyphsGreedy,
                solved.utf16Length,
                solved.utf8Bytes,
            ),
        )
        solved.warnings.forEach { w ->
            echo("   warning [${w.code}] ${w.message}")
        }
        echo("\n   ${outFormat.id}:")
        echo("   ${solved.snippets.getValue(outFormat.id)}")
    }
}
