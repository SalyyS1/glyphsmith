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
import com.salyvn.glyphsmith.spec.SpecException
import com.salyvn.glyphsmith.spec.TtfSpec
import com.salyvn.glyphsmith.text.Snippet
import com.salyvn.glyphsmith.ttf.OpenTypeException
import com.salyvn.glyphsmith.ttf.OpenTypeFont
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

/** One glyph as built: what the client will do with it, and the string that puts it on screen. */
data class BuiltGlyph(
    val spec: GlyphSpec,
    val solved: SolvedGlyph,
    val codepoints: List<Int>,
    val textureRef: String,
    val snippet: Snippet,
    val notes: List<String>,
)

data class BuiltTtfProvider(
    val spec: TtfSpec,
    val fontRef: String,
    val provider: Provider.Ttf,
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
    val ttfProviders: List<BuiltTtfProvider> = emptyList(),
    val overlays: List<OverlaySpec> = emptyList(),
)

/** The chat line width at the default `chatWidth` and `chatScale`, from `ChatComponent.getWidth`. */
private const val DEFAULT_CHAT_WIDTH = 320

/**
 * Turns a spec into a resource pack.
 *
 * The order of work matters and is fixed: the lock file is read first so existing codepoints are honoured,
 * shift glyphs are allocated before image glyphs so a spec that grows does not renumber them, and glyphs
 * are processed in the order the spec lists them. When era-scoped `versions:` are set on individual glyphs
 * or `ttf` providers (for example format 97 / Minecraft 26.3), `PackBuilder` automatically emits dual-era
 * `overlays.entries` directories so both legacy (`1.20.1..1.21.8`) and modern (`1.21.9..26.3`) clients
 * receive complete, schema-valid font files.
 */
class PackBuilder(private val spec: Spec, private val previous: LockFile?) {
    /** [dryRun] solves everything and writes nothing, which is what the query commands need. */
    fun build(root: Path, zipTarget: Path? = null, dryRun: Boolean = false): BuildResult {
        val allocator = CodepointAllocator(spec.startCodepoint, previous?.codepoints ?: emptyMap())
        val warnings = mutableListOf<String>()

        val shifts = if (spec.usesShifts) Shifts.build(spec.shiftRange, allocator::allocate) else null
        val built = spec.glyphs.map { glyph -> buildGlyph(glyph, allocator, shifts, root, warnings, dryRun) }
        val builtTtf = spec.ttfProviders.map { ttf -> buildTtf(ttf, root, warnings, dryRun) }

        val baseProviders = buildList {
            shifts?.let { add(it.provider()) }
            built.filter { it.spec.targetRange == null }.forEach { glyph ->
                add(toBitmapProvider(glyph))
            }
            builtTtf.filter { it.spec.targetRange == null }.forEach { ttf ->
                add(ttf.provider)
            }
        }

        // Collect distinct era-scoped target ranges (e.g. 26.3 -> 97..97) in deterministic order.
        val distinctRanges = LinkedHashSet<Pair<PackFormat, PackFormat>>()
        built.mapNotNullTo(distinctRanges) { it.spec.targetRange }
        builtTtf.mapNotNullTo(distinctRanges) { it.spec.targetRange }

        val compiledOverlays = distinctRanges
            .sortedWith(compareBy<Pair<PackFormat, PackFormat>> { it.first.major }.thenBy { it.second.major })
            .map { (ovMin, ovMax) ->
                val dirName = overlayDirectoryName(ovMin, ovMax)
                OverlaySpec(directory = dirName, min = ovMin, max = ovMax)
            }

        if (!dryRun) {
            PackWriter.writeText(
                root,
                "assets/${spec.fontNamespace}/font/${spec.fontPath}.json",
                FontFile(baseProviders).render(),
            )
            for (ov in compiledOverlays) {
                // In vanilla resource packs, an overlay font file replaces the base font file at the same path
                // for clients matching that format window, so include base providers plus all era-compatible
                // overlay providers.
                val overlayProviders = buildList {
                    addAll(baseProviders)
                    built.filter { g -> g.spec.targetRange?.let { rangeCovers(it, ov.min, ov.max) } == true }
                        .forEach { add(toBitmapProvider(it)) }
                    builtTtf.filter { t -> t.spec.targetRange?.let { rangeCovers(it, ov.min, ov.max) } == true }
                        .forEach { add(it.provider) }
                }
                PackWriter.writeText(
                    root,
                    "${ov.directory}/assets/${spec.fontNamespace}/font/${spec.fontPath}.json",
                    FontFile(overlayProviders).render(),
                )
            }
            PackWriter.writeText(
                root,
                "pack.mcmeta",
                PackMeta.render(spec.description, spec.minFormat, spec.maxFormat, compiledOverlays),
            )
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

        return BuildResult(
            spec = spec,
            glyphs = built,
            lock = lock,
            root = root,
            zip = zip,
            newCodepoints = newCodepoints,
            warnings = warnings,
            shifts = shifts,
            ttfProviders = builtTtf,
            overlays = compiledOverlays,
        )
    }

    private fun toBitmapProvider(glyph: BuiltGlyph): Provider.Bitmap = Provider.Bitmap(
        file = glyph.textureRef,
        height = glyph.solved.height,
        ascent = glyph.solved.ascent,
        chars = listOf(glyph.codepoints.joinToString("") { code -> Codepoints.raw(code) }),
        filter = glyph.spec.filter,
    )

    private fun overlayDirectoryName(min: PackFormat, max: PackFormat): String {
        val minPart = min.toString().replace('.', '_')
        val maxPart = max.toString().replace('.', '_')
        return if (min == max) "overlay_fmt_$minPart" else "overlay_fmt_${minPart}_to_$maxPart"
    }

    private fun rangeCovers(
        providerRange: Pair<PackFormat, PackFormat>,
        ovMin: PackFormat,
        ovMax: PackFormat,
    ): Boolean = providerRange.first.major <= ovMin.major && providerRange.second.major >= ovMax.major

    private fun buildTtf(
        ttf: TtfSpec,
        root: Path,
        warnings: MutableList<String>,
        dryRun: Boolean,
    ): BuiltTtfProvider {
        val parsedFont = try {
            OpenTypeFont.read(ttf.file)
        } catch (failure: OpenTypeException) {
            throw SpecException("ttf '${ttf.id}': ${failure.message}")
        }

        val notes = mutableListOf<String>()
        if (parsedFont.info.isVariableFont) {
            val axesSummary = parsedFont.info.variationAxes.joinToString(", ") { ax ->
                "${ax.tag}[${ax.minValue}..${ax.maxValue}, default=${ax.defaultValue}]"
            }
            warnings += "ttf '${ttf.id}' (${ttf.file.fileName}) is an OpenType variable font ($axesSummary, " +
                "${parsedFont.info.variationPayloadBytes} variation bytes); Minecraft FreeType pins all variation " +
                "axes to their default coordinates."
        }
        if (parsedFont.info.mappedCodepointCount == 0) {
            warnings += "ttf '${ttf.id}' (${ttf.file.fileName}) maps 0 Unicode codepoints in its cmap table."
        }

        val ext = ttf.file.extension.lowercase().let { if (it == "otf" || it == "ttc") it else "ttf" }
        val targetFileName = "${ttf.id}.$ext"
        val entryPrefix = ttf.targetRange?.let { (ovMin, ovMax) -> "${overlayDirectoryName(ovMin, ovMax)}/" } ?: ""
        if (!dryRun) {
            val dest = root.resolve("${entryPrefix}assets/${spec.namespace}/font/$targetFileName")
            Files.createDirectories(dest.parent)
            Files.copy(ttf.file, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        notes += "${parsedFont.info.fullName} (${parsedFont.info.unitsPerEm} upm, ${parsedFont.info.mappedCodepointCount} mapped codepoints)"

        val fontRef = "${spec.namespace}:$targetFileName"
        val provider = Provider.Ttf(
            file = fontRef,
            size = ttf.size,
            oversample = ttf.oversample,
            shift = ttf.shift,
            skip = ttf.skip,
            filter = ttf.filter,
        )
        return BuiltTtfProvider(spec = ttf, fontRef = fontRef, provider = provider, notes = notes)
    }

    private fun buildGlyph(
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
        val entryPrefix = glyph.targetRange?.let { (ovMin, ovMax) -> "${overlayDirectoryName(ovMin, ovMax)}/" } ?: ""
        if (!dryRun) {
            solved.texture.write(root.resolve("${entryPrefix}assets/${spec.namespace}/textures/font/$fileName"))
        }

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
     * and - for a placed glyph - the shift that puts the cursor back so the title or line it sits on is
     * not pushed sideways.
     */
    private fun sequence(glyph: GlyphSpec, solved: SolvedGlyph, codepoints: List<Int>, shifts: Shifts?): String {
        val out = StringBuilder()
        var travelled = 0
        val corrections = solved.seamCorrections()
        val compact = spec.compactShifts

        if (solved.leadShift != 0) {
            out.append(requireShifts(shifts, glyph).string(solved.leadShift, compact = compact))
            travelled += solved.leadShift
        }
        codepoints.forEachIndexed { index, codepoint ->
            out.append(Codepoints.raw(codepoint))
            travelled += solved.advances[index]
            val isLast = index == codepoints.lastIndex
            if (!isLast && corrections[index] != 0) {
                out.append(requireShifts(shifts, glyph).string(corrections[index], compact = compact))
                travelled += corrections[index]
            }
        }
        if (glyph.restoreCursor && travelled != 0) {
            out.append(requireShifts(shifts, glyph).string(-travelled, compact = compact))
        }
        return out.toString()
    }

    private fun requireShifts(shifts: Shifts?, glyph: GlyphSpec): Shifts = shifts
        ?: throw GlyphSolveException(
            "glyph '${glyph.id}' needs shift glyphs to land where it was placed, but 'shifts' is 0 in the spec",
        )
}
