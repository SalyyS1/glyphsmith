package com.salyvn.glyphsmith.report

import com.salyvn.glyphsmith.codepoint.Codepoints
import com.salyvn.glyphsmith.glyph.GlyphMath
import com.salyvn.glyphsmith.glyph.SolvedGlyph
import com.salyvn.glyphsmith.pack.BuildResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path

@Serializable
data class SnippetJson(
    val miniMessage: String,
    val escaped: String,
    val componentJson: String,
    val raw: String,
)

@Serializable
data class BuiltGlyphJson(
    val id: String,
    val codepoints: List<String>,
    val textureRef: String,
    val width: Int,
    val height: Int,
    val ascent: Int,
    val advances: List<Int>,
    val totalAdvance: Int,
    val notes: List<String>,
    val snippets: SnippetJson,
)

@Serializable
data class BuildReportJson(
    val font: String,
    val outputDir: String,
    val zipPath: String? = null,
    val lockPath: String,
    val snippetsPath: String,
    val glyphCount: Int,
    val newCodepoints: List<String>,
    val warnings: List<String>,
    val glyphs: List<BuiltGlyphJson>,
)

@Serializable
data class PlanReportJson(
    val image: String,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val hadAlphaChannel: Boolean,
    val inkWidth: Int,
    val inkHeight: Int,
    val height: Int,
    val ascent: Int,
    val topOffset: Int,
    val padBottom: Int,
    val padRight: Int,
    val columns: Int,
    val cellWidth: Int,
    val cellHeight: Int,
    val pixelScale: Float,
    val advances: List<Int>,
    val totalAdvance: Int,
    val leadShift: Int,
    val restoreShift: Int,
)

object JsonReport {
    private val FORMAT = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
    }

    fun renderBuild(result: BuildResult, lockFile: Path, snippetsFile: Path): String {
        val payload = BuildReportJson(
            font = result.spec.font,
            outputDir = result.root.toString(),
            zipPath = result.zip?.toString(),
            lockPath = lockFile.toString(),
            snippetsPath = snippetsFile.toString(),
            glyphCount = result.glyphs.size,
            newCodepoints = result.newCodepoints,
            warnings = result.warnings,
            glyphs = result.glyphs.map { glyph ->
                BuiltGlyphJson(
                    id = glyph.spec.id,
                    codepoints = glyph.codepoints.map { cp -> Codepoints.describe(cp) },
                    textureRef = glyph.textureRef,
                    width = glyph.solved.texture.width,
                    height = glyph.solved.height,
                    ascent = glyph.solved.ascent,
                    advances = glyph.solved.advances,
                    totalAdvance = glyph.solved.totalAdvance,
                    notes = glyph.notes,
                    snippets = SnippetJson(
                        miniMessage = glyph.snippet.miniMessage,
                        escaped = glyph.snippet.escaped,
                        componentJson = glyph.snippet.component,
                        raw = glyph.snippet.raw,
                    ),
                )
            },
        )
        return FORMAT.encodeToString(payload)
    }

    fun renderPlan(
        image: Path,
        sourceWidth: Int,
        sourceHeight: Int,
        hadAlphaChannel: Boolean,
        inkWidth: Int,
        inkHeight: Int,
        solved: SolvedGlyph,
    ): String {
        val payload = PlanReportJson(
            image = image.toString(),
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            hadAlphaChannel = hadAlphaChannel,
            inkWidth = inkWidth,
            inkHeight = inkHeight,
            height = solved.height,
            ascent = solved.ascent,
            topOffset = GlyphMath.topOffset(solved.ascent),
            padBottom = solved.padBottom,
            padRight = solved.padRight,
            columns = solved.columns,
            cellWidth = solved.cellWidth,
            cellHeight = solved.cellHeight,
            pixelScale = solved.pixelScale,
            advances = solved.advances,
            totalAdvance = solved.totalAdvance,
            leadShift = solved.leadShift,
            restoreShift = solved.restoreShift,
        )
        return FORMAT.encodeToString(payload)
    }
}
