package com.salyvn.glyphsmith.pack

import com.salyvn.glyphsmith.codepoint.CodepointAllocator
import com.salyvn.glyphsmith.codepoint.Codepoints
import com.salyvn.glyphsmith.core.Sha256
import com.salyvn.glyphsmith.glyph.Anchor
import com.salyvn.glyphsmith.glyph.GlyphSolveException
import com.salyvn.glyphsmith.glyph.SolvedGlyph
import com.salyvn.glyphsmith.glyph.Solver
import com.salyvn.glyphsmith.image.Texture
import com.salyvn.glyphsmith.lock.LockFile
import com.salyvn.glyphsmith.lock.LockedGlyph
import com.salyvn.glyphsmith.spec.GlyphSpec
import com.salyvn.glyphsmith.spec.Spec
import com.salyvn.glyphsmith.text.Snippet
import java.nio.file.Path

/** One glyph as built: what the client will do with it, and the string that puts it on screen. */
data class BuiltGlyph(
    val spec: GlyphSpec,
    val solved: SolvedGlyph,
    val codepoints: List<Int>,
    val textureRef: String,
    val snippet: Snippet,
    val notes: List<String>,
)

data class BuildResult(
    val spec: Spec,
    val glyphs: List<BuiltGlyph>,
    val lock: LockFile,
    val root: Path,
    val zip: Path?,
    val newCodepoints: List<String>,
    val warnings: List<String>,
    val shifts: Shifts?,
)

/** The chat line width at the default `chatWidth` and `chatScale`, from `ChatComponent.getWidth`. */
private const val DEFAULT_CHAT_WIDTH = 320

/**
 * Turns a spec into a resource pack.
 *
 * The order of work matters and is fixed: the lock file is read first so existing codepoints are honoured,
 * shift glyphs are allocated before image glyphs so a spec that grows does not renumber them, and glyphs
 * are processed in the order the spec lists them.
 */
class PackBuilder(private val spec: Spec, private val previous: LockFile?) {
    /** [dryRun] solves everything and writes nothing, which is what the query commands need. */
    fun build(root: Path, zipTarget: Path? = null, dryRun: Boolean = false): BuildResult {
        val allocator = CodepointAllocator(spec.startCodepoint, previous?.codepoints ?: emptyMap())
        val warnings = mutableListOf<String>()

        val shifts = if (spec.usesShifts) Shifts.build(spec.shiftRange, allocator::allocate) else null
        val built = spec.glyphs.map { glyph -> build(glyph, allocator, shifts, root, warnings, dryRun) }

        val providers = buildList {
            shifts?.let { add(it.provider()) }
            built.forEach { glyph ->
                add(
                    Provider.Bitmap(
                        file = glyph.textureRef,
                        height = glyph.solved.height,
                        ascent = glyph.solved.ascent,
                        chars = listOf(glyph.codepoints.joinToString("") { code -> Codepoints.raw(code) }),
                    ),
                )
            }
        }

        if (!dryRun) {
            PackWriter.writeText(
                root,
                "assets/${spec.fontNamespace}/font/${spec.fontPath}.json",
                FontFile(providers).render(),
            )
            PackWriter.writeText(root, "pack.mcmeta", PackMeta.render(spec.description, spec.minFormat, spec.maxFormat))
        }

        val lock = LockFile(
            font = spec.font,
            codepoints = allocator.assignments(),
            glyphs = built.associate { glyph ->
                glyph.spec.id to LockedGlyph(
                    codepoints = glyph.codepoints,
                    sha256 = Sha256.ofFile(glyph.spec.image),
                    height = glyph.solved.height,
                    ascent = glyph.solved.ascent,
                    advances = glyph.solved.advances,
                )
            },
        )

        previous?.glyphs?.keys?.filter { id -> spec.glyphs.none { glyph -> glyph.id == id } }?.forEach { id ->
            warnings += "'$id' is in the lock file but no longer in the spec. Its codepoints stay reserved so " +
                "nothing that already uses them changes meaning."
        }

        val zip = if (dryRun) null else zipTarget?.also { target -> PackWriter.zip(root, target) }
        val newCodepoints = allocator.assignments().keys.filter { id -> previous?.codepoints?.containsKey(id) != true }

        return BuildResult(spec, built, lock, root, zip, newCodepoints, warnings, shifts)
    }

    private fun build(
        glyph: GlyphSpec,
        allocator: CodepointAllocator,
        shifts: Shifts?,
        root: Path,
        warnings: MutableList<String>,
        dryRun: Boolean,
    ): BuiltGlyph {
        val texture = Texture.read(glyph.image)
        val solved = try {
            Solver.solve(
                texture = texture,
                placement = glyph.placement,
                scale = glyph.scale,
                ascentOverride = glyph.ascent,
                heightOverride = glyph.height,
            )
        } catch (failure: GlyphSolveException) {
            throw GlyphSolveException("glyph '${glyph.id}': ${failure.message}")
        }

        val codepoints = allocator.allocateRun(glyph.id, solved.columns)
        val fileName = "${glyph.id}.png"
        if (!dryRun) solved.texture.write(root.resolve("assets/${spec.namespace}/textures/font/$fileName"))

        val notes = mutableListOf<String>()
        if (solved.padBottom > 0) {
            notes += "padded ${solved.padBottom}px of transparency below the artwork so ascent ${solved.ascent} " +
                "is legal at 1:1 scale"
        }
        if (solved.padRight > 0) {
            notes += "padded ${solved.padRight}px to the right so ${solved.columns} cells divide the width evenly"
        }
        if (solved.columns > 1) {
            notes += "split into ${solved.columns} cells of ${solved.cellWidth}px, joined by shift glyphs"
        }
        if (!texture.hadAlphaChannel) {
            warnings += "'${glyph.id}': ${glyph.image.fileName} has no alpha channel, so every column counts as " +
                "ink and the glyph advances the whole cell. Save it with transparency unless that is intended."
        }
        codepoints.firstOrNull()?.let { first ->
            if (first in Codepoints.BMP_PRIVATE_USE_FIRST..Codepoints.BMP_PRIVATE_USE_LAST) {
                warnings += "'${glyph.id}' sits at ${Codepoints.describe(first)}, in ${Codepoints.areaOf(first)}. " +
                    "A pack merged with one of those plugins can overwrite it with only a warning in the client log."
            }
        }
        val renderedWidth = solved.cellWidth * solved.columns * solved.height / solved.cellHeight
        if (glyph.anchor == Anchor.CHAT && renderedWidth > DEFAULT_CHAT_WIDTH) {
            warnings += "'${glyph.id}' renders ${renderedWidth}px wide, past the ${DEFAULT_CHAT_WIDTH}px a chat line " +
                "holds at default settings, so it will wrap."
        }

        return BuiltGlyph(
            spec = glyph,
            solved = solved,
            codepoints = codepoints,
            textureRef = "${spec.namespace}:font/$fileName",
            snippet = Snippet(sequence(glyph, solved, codepoints, shifts), spec.font),
            notes = notes,
        )
    }

    /**
     * The characters that draw the glyph where it was asked for: the lead shift, the cells with a
     * correction between them for the gap between a cell's pixels and where its advance left the cursor,
     * and — for a placed glyph — the shift that puts the cursor back so the title or line it sits on is
     * not pushed sideways.
     */
    private fun sequence(glyph: GlyphSpec, solved: SolvedGlyph, codepoints: List<Int>, shifts: Shifts?): String {
        val out = StringBuilder()
        var travelled = 0
        val corrections = solved.seamCorrections()

        if (solved.leadShift != 0) {
            out.append(requireShifts(shifts, glyph).string(solved.leadShift))
            travelled += solved.leadShift
        }
        codepoints.forEachIndexed { index, codepoint ->
            out.append(Codepoints.raw(codepoint))
            travelled += solved.advances[index]
            val isLast = index == codepoints.lastIndex
            if (!isLast && corrections[index] != 0) {
                out.append(requireShifts(shifts, glyph).string(corrections[index]))
                travelled += corrections[index]
            }
        }
        if (glyph.restoreCursor && travelled != 0) {
            out.append(requireShifts(shifts, glyph).string(-travelled))
        }
        return out.toString()
    }

    private fun requireShifts(shifts: Shifts?, glyph: GlyphSpec): Shifts = shifts
        ?: throw GlyphSolveException(
            "glyph '${glyph.id}' needs shift glyphs to land where it was placed, but 'shifts' is 0 in the spec",
        )
}
