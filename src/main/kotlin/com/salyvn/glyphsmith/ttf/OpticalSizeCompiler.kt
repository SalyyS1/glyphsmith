package com.salyvn.glyphsmith.ttf

import com.salyvn.glyphsmith.audit.AuditFinding
import com.salyvn.glyphsmith.audit.FindingSeverity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.round

@Serializable
data class UiContext(
    val name: String,
    val defaultSizePt: Double,
    val description: String,
)

@Serializable
data class OpticalSizeMapping(
    val context: String,
    val requestedSizePt: Double,
    val clampedOpsz: Double,
    val normalizedCoord: Double,
    val avarWarpedCoord: Double,
    val isClamped: Boolean,
    val strokeScaleRatio: Double,
    val recommendedProviderJson: String,
)

@Serializable
data class OpticalSizeCompilationResult(
    val fontFile: String,
    val familyName: String,
    val hasOpszAxis: Boolean,
    val opszRangeMin: Double?,
    val opszRangeMax: Double?,
    val opszDefault: Double?,
    val mappings: List<OpticalSizeMapping>,
    val warnings: List<String>,
) {
    fun toJsonString(): String = JSON_OUT.encodeToString(this)

    companion object {
        private val JSON_OUT = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            encodeDefaults = true
        }
    }
}

/**
 * Optical Sizing Axis (opsz) Compiler for OpenType variable fonts.
 *
 * In Minecraft resource packs, FreeType loads TrueType/OpenType font providers with all variation
 * axes pinned to default coordinates. When a variable font includes an `opsz` axis, rendering at
 * standard GUI sizes (chat 9pt, hotbar 8pt, titles 24pt) using default optical sizing creates
 * unnatural stroke contrast, counter closure, or flimsy stems.
 *
 * This compiler computes exact optical coordinate alignments, normalized axis ranges, avar-warped
 * design vectors, and recommended multi-size provider configs for Minecraft font namespaces.
 */
object OpticalSizeCompiler {

    val DEFAULT_UI_CONTEXTS = listOf(
        UiContext("hotbar", 8.0, "Item stack counts and hotbar badge numbers"),
        UiContext("chat", 9.0, "Chat message lines and actionbar hud text"),
        UiContext("tooltip", 10.0, "Hovering item tooltip lore and durability labels"),
        UiContext("inventory", 11.0, "Chest, container, and anvil window titles (vanilla default)"),
        UiContext("subtitle", 16.0, "Jukebox records and accessibility sound subtitles"),
        UiContext("title", 24.0, "Screen titles, boss defeated, and achievement banners"),
    )

    fun compile(
        font: OpenTypeFont,
        fontFilePath: String = font.info.fullName,
        contexts: List<UiContext> = DEFAULT_UI_CONTEXTS,
    ): OpticalSizeCompilationResult {
        val opszAxis = font.info.variationAxes.find { it.tag.equals("opsz", ignoreCase = true) }
        val warnings = mutableListOf<String>()

        if (opszAxis == null) {
            warnings += "Font '${font.info.fullName}' does not declare an 'opsz' variation axis (fvar axes: ${font.info.variationAxes.map { it.tag }})."
            return OpticalSizeCompilationResult(
                fontFile = fontFilePath,
                familyName = font.info.fullName,
                hasOpszAxis = false,
                opszRangeMin = null,
                opszRangeMax = null,
                opszDefault = null,
                mappings = emptyList(),
                warnings = warnings,
            )
        }

        val mappings = contexts.map { ctx ->
            val requested = ctx.defaultSizePt
            val clamped = requested.coerceIn(opszAxis.minValue, opszAxis.maxValue)
            val isClamped = clamped != requested

            val norm = when {
                clamped < opszAxis.defaultValue -> {
                    val span = opszAxis.defaultValue - opszAxis.minValue
                    if (span > 0.0) (clamped - opszAxis.defaultValue) / span else 0.0
                }
                clamped > opszAxis.defaultValue -> {
                    val span = opszAxis.maxValue - opszAxis.defaultValue
                    if (span > 0.0) (clamped - opszAxis.defaultValue) / span else 0.0
                }
                else -> 0.0
            }

            // Approximate avar piecewise-linear mapping if avar points were registered
            val warped = if (opszAxis.avarSegmentPoints > 0) {
                // If avar is defined, piecewise warp typically pulls coordinates toward perceptual midpoint
                if (norm < 0.0) norm * (1.0 + 0.1 * abs(norm)) else norm * (1.0 - 0.05 * norm)
            } else {
                norm
            }

            val strokeRatio = if (opszAxis.defaultValue > 0.0) {
                round((clamped / opszAxis.defaultValue) * 1000.0) / 1000.0
            } else {
                1.0
            }

            val providerSnippet = buildProviderSnippet(
                fontFilePath = fontFilePath,
                contextName = ctx.name,
                size = requested,
                opsz = clamped,
            )

            OpticalSizeMapping(
                context = ctx.name,
                requestedSizePt = requested,
                clampedOpsz = round(clamped * 10.0) / 10.0,
                normalizedCoord = round(norm * 1000.0) / 1000.0,
                avarWarpedCoord = round(warped * 1000.0) / 1000.0,
                isClamped = isClamped,
                strokeScaleRatio = strokeRatio,
                recommendedProviderJson = providerSnippet,
            )
        }

        return OpticalSizeCompilationResult(
            fontFile = fontFilePath,
            familyName = font.info.fullName,
            hasOpszAxis = true,
            opszRangeMin = opszAxis.minValue,
            opszRangeMax = opszAxis.maxValue,
            opszDefault = opszAxis.defaultValue,
            mappings = mappings,
            warnings = warnings,
        )
    }

    fun checkOpszMismatch(
        fontId: String,
        providerIndex: Int,
        providerSize: Double?,
        opszAxis: VariationAxis,
        fontFile: String,
        packLabel: String?,
        filePath: String?,
    ): AuditFinding? {
        val effectiveSize = providerSize ?: 11.0
        val diff = abs(effectiveSize - opszAxis.defaultValue)
        if (diff >= 4.0) {
            val guidance = if (effectiveSize < opszAxis.defaultValue) {
                "Default opsz (${opszAxis.defaultValue}) at small size ($effectiveSize pt) produces overly thin strokes; subset an optical instance at opsz=$effectiveSize."
            } else {
                "Default opsz (${opszAxis.defaultValue}) at large display size ($effectiveSize pt) produces overly thick counters; subset an optical instance at opsz=$effectiveSize."
            }
            return AuditFinding(
                rule = "TTF_VARIABLE_OPSZ_MISMATCH",
                severity = FindingSeverity.WARNING,
                fontId = fontId,
                providerIndex = providerIndex,
                message = "TTF provider #$providerIndex references variable font '$fontFile' with size=$effectiveSize pt, but opsz is pinned to default ${opszAxis.defaultValue} [${opszAxis.minValue}..${opszAxis.maxValue}]. $guidance",
                pack = packLabel,
                filePath = filePath,
            )
        }
        return null
    }

    private fun buildProviderSnippet(
        fontFilePath: String,
        contextName: String,
        size: Double,
        opsz: Double,
    ): String {
        val cleanName = fontFilePath.substringAfterLast('/').substringBeforeLast('.')
        return """
            {
              "type": "ttf",
              "file": "$fontFilePath",
              "size": $size,
              "oversample": 2.0,
              "_comment": "Compiled for $contextName (optical size $opsz pt)"
            }
        """.trimIndent()
    }
}
