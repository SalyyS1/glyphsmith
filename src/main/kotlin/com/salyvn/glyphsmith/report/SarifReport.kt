package com.salyvn.glyphsmith.report

import com.salyvn.glyphsmith.audit.AuditFinding
import com.salyvn.glyphsmith.audit.AuditReport
import com.salyvn.glyphsmith.audit.FindingSeverity
import com.salyvn.glyphsmith.core.BuildInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Renders an [AuditReport] as an OASIS SARIF v2.1.0 document for GitHub Code Scanning
 * (`github/codeql-action/upload-sarif`) and IDE SARIF viewers.
 */
object SarifReport {

    private data class RuleMeta(
        val id: String,
        val defaultLevel: String,
        val shortText: String,
        val helpText: String,
    )

    private val KNOWN_RULES: Map<String, RuleMeta> = listOf(
        RuleMeta(
            "FONT_UNREADABLE",
            "error",
            "Font definition file could not be read from the resource pack.",
            "Ensure the font JSON entry is valid UTF-8 and within the 32 MiB pack entry limit.",
        ),
        RuleMeta(
            "FONT_INVALID_JSON",
            "error",
            "Font definition file contains malformed JSON.",
            "Fix JSON syntax errors in the font definition file so the client resource loader can parse it.",
        ),
        RuleMeta(
            "FONT_MISSING_PROVIDERS",
            "error",
            "Font definition file does not declare a 'providers' array.",
            "Every Minecraft font JSON file must contain a top-level 'providers' array.",
        ),
        RuleMeta(
            "PROVIDER_NOT_OBJECT",
            "error",
            "Entry inside 'providers' is not a JSON object.",
            "Replace non-object elements in 'providers' with valid provider definitions.",
        ),
        RuleMeta(
            "PROVIDER_MISSING_TYPE",
            "error",
            "Font provider object is missing required 'type' field.",
            "Specify a valid provider type ('bitmap', 'space', 'reference', 'ttf', or 'unihex').",
        ),
        RuleMeta(
            "PROVIDER_UNKNOWN_TYPE",
            "warning",
            "Font provider declares an unrecognized provider type.",
            "Check for typos in 'type'; vanilla clients accept 'bitmap', 'space', 'reference', 'ttf', and 'unihex'.",
        ),
        RuleMeta(
            "BITMAP_INVALID_HEIGHT",
            "error",
            "Bitmap provider declares a non-positive render height.",
            "Set 'height' to a positive integer (>= 1).",
        ),
        RuleMeta(
            "BITMAP_MISSING_ASCENT",
            "error",
            "Bitmap provider is missing required integer 'ascent'.",
            "Declare an integer 'ascent' <= 'height' (or run 'glyphsmith plan' to compute it).",
        ),
        RuleMeta(
            "BITMAP_ASCENT_EXCEEDS_HEIGHT",
            "error",
            "Bitmap provider declares ascent greater than height, causing the client to reject the entire font file.",
            "Pad the source PNG bottom with transparent pixels and increase 'height' so ascent <= height without scaling.",
        ),
        RuleMeta(
            "BITMAP_IRREGULAR_GRID",
            "error",
            "Bitmap provider declares an empty or ragged 'chars' codepoint grid.",
            "Ensure 'chars' is a non-empty array of strings where every row has the same codepoint length.",
        ),
        RuleMeta(
            "BITMAP_MISSING_TEXTURE",
            "error",
            "Bitmap provider references a missing or unreadable PNG texture.",
            "Verify the 'file' identifier maps to an existing valid PNG under assets/<namespace>/textures/.",
        ),
        RuleMeta(
            "BITMAP_GRID_MISMATCH",
            "warning",
            "PNG dimensions do not divide evenly into the 'chars' column and row grid.",
            "Pad the PNG width/height to an exact multiple of the grid dimensions so right/bottom pixels are not truncated.",
        ),
        RuleMeta(
            "BITMAP_CELL_EXCEEDS_ATLAS",
            "error",
            "Individual glyph cell exceeds the 256x256px vanilla FontTexture page limit.",
            "Slice wide banners across multiple adjacent <=256px cells (glyphsmith build handles this automatically).",
        ),
        RuleMeta(
            "SPACE_MISSING_ADVANCES",
            "error",
            "Space provider is missing required 'advances' object.",
            "Add an 'advances' map of single-codepoint keys to numeric pixel widths.",
        ),
        RuleMeta(
            "SPACE_INVALID_CODEPOINT_KEY",
            "error",
            "Space provider 'advances' key is not a single Unicode codepoint.",
            "Use keys containing exactly one Unicode codepoint (a supplementary plane character counts as 1 codepoint).",
        ),
        RuleMeta(
            "SPACE_INVALID_ADVANCE",
            "error",
            "Space provider advance value is non-numeric or outside [-8192, 8192].",
            "Use a raw JSON number (not a quoted string) within [-8192.0, 8192.0] to satisfy SpaceProvider's Codec.",
        ),
        RuleMeta(
            "REFERENCE_MISSING_ID",
            "error",
            "Reference font provider is missing a valid 'id' resource location.",
            "Set 'id' to a valid '<namespace>:<font_path>' identifier.",
        ),
        RuleMeta(
            "REFERENCE_MISSING_TARGET",
            "warning",
            "Reference font provider points to a custom font ID missing from the pack or stack.",
            "Include assets/<namespace>/font/<path>.json in the pack or stacked base pack.",
        ),
        RuleMeta(
            "REFERENCE_CYCLE",
            "error",
            "Circular reference chain detected across font 'reference' providers.",
            "Break the cycle between 'reference' providers so FontManager can resolve providers acyclically.",
        ),
        RuleMeta(
            "OVERLAY_INVALID_DIRECTORY",
            "error",
            "pack.mcmeta overlay entry declares an invalid or empty overlay directory.",
            "Ensure every overlays.entries[].directory matches [a-z0-9_-]+ and exists in the archive.",
        ),
        RuleMeta(
            "CODEPOINT_SHADOWED",
            "warning",
            "Later provider in the same font file overwrites a codepoint defined by an earlier provider.",
            "Assign distinct codepoints or remove the duplicate provider entry.",
        ),
        RuleMeta(
            "CODEPOINT_BMP_PUA_HAZARD",
            "warning",
            "Font allocates inside BMP Private Use Area (U+E000..U+F8FF), risking plugin pack collisions.",
            "Allocate custom glyphs in Plane 15 Supplementary Private Use Area A (U+F0000..U+FFFFD).",
        ),
        RuleMeta(
            "CROSS_PACK_CODEPOINT_COLLISION",
            "warning",
            "Higher-priority pack in the stack overwrites a codepoint allocated in the same font by an earlier pack.",
            "Re-allocate one pack's codepoints into a disjoint Plane 15 range or compile through glyphsmith/packmerge.",
        ),
    ).associateBy { it.id }

    private val JSON_OUT = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
    }

    fun render(report: AuditReport): String {
        val activeRuleIds = report.findings.map { it.rule }.distinct()
        val rules = activeRuleIds.map { id ->
            val meta = KNOWN_RULES[id] ?: RuleMeta(
                id = id,
                defaultLevel = "warning",
                shortText = "Glyphsmith font audit rule $id.",
                helpText = "Inspect the reported font provider in the resource pack.",
            )
            SarifReportingDescriptor(
                id = meta.id,
                shortDescription = SarifMessage(text = meta.shortText),
                help = SarifMessage(text = meta.helpText),
                defaultConfiguration = SarifReportingConfiguration(level = meta.defaultLevel),
            )
        }

        val results = report.findings.map { finding -> toResult(finding) }
        val doc = SarifLog(
            runs = listOf(
                SarifRun(
                    tool = SarifTool(
                        driver = SarifDriver(
                            name = "glyphsmith",
                            version = BuildInfo.version,
                            informationUri = "https://github.com/SalyyS1/glyphsmith",
                            rules = rules,
                        ),
                    ),
                    results = results,
                ),
            ),
        )
        return JSON_OUT.encodeToString(doc)
    }

    private fun toResult(finding: AuditFinding): SarifResult {
        val level = when (finding.severity) {
            FindingSeverity.ERROR -> "error"
            FindingSeverity.WARNING -> "warning"
        }
        val uri = finding.filePath ?: fontIdToDefaultPath(finding.fontId)
        val fqName = buildString {
            if (!finding.pack.isNullOrBlank()) {
                append(finding.pack)
                append("::")
            }
            append(finding.fontId)
            if (finding.providerIndex != null) {
                append("#")
                append(finding.providerIndex)
            }
        }
        return SarifResult(
            ruleId = finding.rule,
            level = level,
            message = SarifMessage(text = finding.message),
            locations = listOf(
                SarifLocation(
                    physicalLocation = SarifPhysicalLocation(
                        artifactLocation = SarifArtifactLocation(uri = uri),
                        region = SarifRegion(startLine = 1),
                    ),
                    logicalLocations = listOf(
                        SarifLogicalLocation(
                            name = finding.fontId,
                            fullyQualifiedName = fqName,
                            kind = "fontProvider",
                        ),
                    ),
                ),
            ),
        )
    }

    private fun fontIdToDefaultPath(fontId: String): String {
        if (fontId == "pack.mcmeta") return "pack.mcmeta"
        val parts = fontId.split(":", limit = 2)
        return if (parts.size == 2) {
            "assets/${parts[0]}/font/${parts[1]}.json"
        } else {
            "assets/minecraft/font/$fontId.json"
        }
    }

    @Serializable
    private data class SarifLog(
        @SerialName("\$schema")
        val schema: String = "https://json.schemastore.org/sarif-2.1.0.json",
        val version: String = "2.1.0",
        val runs: List<SarifRun>,
    )

    @Serializable
    private data class SarifRun(
        val tool: SarifTool,
        val results: List<SarifResult>,
    )

    @Serializable
    private data class SarifTool(
        val driver: SarifDriver,
    )

    @Serializable
    private data class SarifDriver(
        val name: String,
        val version: String,
        val informationUri: String,
        val rules: List<SarifReportingDescriptor>,
    )

    @Serializable
    private data class SarifReportingDescriptor(
        val id: String,
        val shortDescription: SarifMessage,
        val help: SarifMessage,
        val defaultConfiguration: SarifReportingConfiguration,
    )

    @Serializable
    private data class SarifReportingConfiguration(
        val level: String,
    )

    @Serializable
    private data class SarifResult(
        val ruleId: String,
        val level: String,
        val message: SarifMessage,
        val locations: List<SarifLocation>,
    )

    @Serializable
    private data class SarifLocation(
        val physicalLocation: SarifPhysicalLocation,
        val logicalLocations: List<SarifLogicalLocation>,
    )

    @Serializable
    private data class SarifPhysicalLocation(
        val artifactLocation: SarifArtifactLocation,
        val region: SarifRegion,
    )

    @Serializable
    private data class SarifArtifactLocation(
        val uri: String,
    )

    @Serializable
    private data class SarifRegion(
        val startLine: Int,
    )

    @Serializable
    private data class SarifLogicalLocation(
        val name: String,
        val fullyQualifiedName: String,
        val kind: String,
    )

    @Serializable
    private data class SarifMessage(
        val text: String,
    )
}
