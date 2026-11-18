package com.salyvn.glyphsmith.audit

import com.salyvn.glyphsmith.codepoint.Codepoints
import com.salyvn.glyphsmith.codepoint.UnicodeCoverageValidator
import com.salyvn.glyphsmith.glyph.GlyphMath
import com.salyvn.glyphsmith.pack.PackFormat
import com.salyvn.glyphsmith.pack.PackMeta
import com.salyvn.glyphsmith.ttf.OpenTypeException
import com.salyvn.glyphsmith.ttf.OpenTypeFont
import com.salyvn.glyphsmith.ttf.OpticalSizeCompiler
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

@Serializable
enum class FindingSeverity {
    ERROR,
    WARNING,
}

@Serializable
data class AuditFinding(
    val rule: String,
    val severity: FindingSeverity,
    val fontId: String,
    val providerIndex: Int? = null,
    val message: String,
    val pack: String? = null,
    val filePath: String? = null,
)

@Serializable
data class AuditReport(
    val target: String,
    val packCount: Int = 1,
    val fontCount: Int,
    val providerCount: Int,
    val errorCount: Int,
    val warningCount: Int,
    val findings: List<AuditFinding>,
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
 * Audits one resource pack or an ordered stack of resource packs (directory or `.zip` archive) for font
 * provider bugs that cause the Minecraft client to reject a font file, truncate glyph pixels, fail
 * circular reference resolution, break overlay directories, or silently overwrite codepoints across
 * stacked packs.
 */
object PackAuditor {
    private const val MAX_ZIP_ENTRIES = 20_000
    private const val MAX_ENTRY_BYTES = 32 * 1024 * 1024L
    private const val MIN_SPACE_ADVANCE = -8192.0
    private const val MAX_SPACE_ADVANCE = 8192.0

    private val FONT_PATH_REGEX = Regex("^assets/([a-z0-9_.-]+)/font/([a-z0-9_./-]+)\\.json$")
    private val OVERLAY_DIR_REGEX = Regex("^[a-z0-9_-]+$")
    private val NAMESPACE_REGEX = Regex("^[a-z0-9_.-]+$")
    private val FONT_REL_REGEX = Regex("^[a-z0-9_./-]+$")

    private data class CodepointOrigin(
        val packLabel: String,
        val filePath: String,
        val providerIndex: Int,
    )

    private data class ReferenceEdge(
        val fromFont: String,
        val toFont: String,
        val packLabel: String?,
        val filePath: String,
        val providerIndex: Int,
    )

    private data class SinglePackResult(
        val packLabel: String,
        val fontCount: Int,
        val providerCount: Int,
        val findings: List<AuditFinding>,
        val baseFontIds: Set<String>,
        val baseCodepoints: Map<Pair<String, Int>, CodepointOrigin>,
        val baseReferenceEdges: List<ReferenceEdge>,
    )

    fun audit(target: Path): AuditReport = auditStack(listOf(target))

    fun auditStack(targets: List<Path>): AuditReport {
        require(targets.isNotEmpty()) { "At least one resource pack path is required." }
        val multiPack = targets.size > 1
        val perPackResults = targets.map { path -> auditSinglePack(path, includePackLabel = multiPack) }

        val allFindings = mutableListOf<AuditFinding>()
        perPackResults.forEach { allFindings += it.findings }

        val allBaseFontIds = perPackResults.flatMapTo(linkedSetOf()) { it.baseFontIds }
        val allBaseEdges = perPackResults.flatMap { it.baseReferenceEdges }

        // Missing reference targets across the whole stack (skipping built-in minecraft:* fonts).
        for (edge in allBaseEdges) {
            val refNamespace = edge.toFont.substringBefore(':')
            if (refNamespace != "minecraft" && edge.toFont !in allBaseFontIds) {
                allFindings += AuditFinding(
                    rule = "REFERENCE_MISSING_TARGET",
                    severity = FindingSeverity.WARNING,
                    fontId = edge.fromFont,
                    providerIndex = edge.providerIndex,
                    message = "Reference provider #${edge.providerIndex} points to '${edge.toFont}', which is not present in ${if (multiPack) "any pack in the stack" else "the pack"}.",
                    pack = edge.packLabel,
                    filePath = edge.filePath,
                )
            }
        }

        // Cycle detection across base reference edges.
        detectReferenceCycles(allBaseEdges, allFindings)

        // Cross-pack codepoint shadowing in load order (lower pack -> higher pack).
        if (multiPack) {
            val stackCodepoints = mutableMapOf<Pair<String, Int>, CodepointOrigin>()
            for (packResult in perPackResults) {
                for ((key, origin) in packResult.baseCodepoints) {
                    val previous = stackCodepoints.put(key, origin)
                    if (previous != null && previous.packLabel != origin.packLabel) {
                        val (fontId, cp) = key
                        allFindings += AuditFinding(
                            rule = "CROSS_PACK_CODEPOINT_COLLISION",
                            severity = FindingSeverity.WARNING,
                            fontId = fontId,
                            providerIndex = origin.providerIndex,
                            message = "Codepoint ${Codepoints.describe(cp)} in '${origin.packLabel}' (${origin.filePath}#${origin.providerIndex}) overwrites '${previous.packLabel}' (${previous.filePath}#${previous.providerIndex}).",
                            pack = origin.packLabel,
                            filePath = origin.filePath,
                        )
                    }
                }
            }
        }

        val errors = allFindings.count { it.severity == FindingSeverity.ERROR }
        val warnings = allFindings.count { it.severity == FindingSeverity.WARNING }
        val targetSummary = if (targets.size == 1) {
            targets.single().toString()
        } else {
            targets.joinToString(" + ") { it.toString() }
        }
        return AuditReport(
            target = targetSummary,
            packCount = targets.size,
            fontCount = perPackResults.sumOf { it.fontCount },
            providerCount = perPackResults.sumOf { it.providerCount },
            errorCount = errors,
            warningCount = warnings,
            findings = allFindings,
        )
    }

    private fun auditSinglePack(target: Path, includePackLabel: Boolean): SinglePackResult {
        val packLabel = target.name.ifEmpty { target.toString() }
        val reportedPack = if (includePackLabel) packLabel else null
        val source: PackSource = when {
            target.isDirectory() -> DirectoryPackSource(target)
            target.isRegularFile() && target.extension.lowercase() == "zip" -> ZipPackSource(target)
            target.isRegularFile() -> ZipPackSource(target)
            else -> throw IOException("No resource pack directory or zip archive found at $target")
        }

        source.use { pack ->
            val allEntries = pack.listEntries()
            val findings = mutableListOf<AuditFinding>()

            // 1. Audit base assets/<ns>/font/*.json
            val baseFontEntries = allEntries
                .mapNotNull { path ->
                    val match = FONT_PATH_REGEX.matchEntire(path) ?: return@mapNotNull null
                    Triple(path, match.groupValues[1], match.groupValues[2])
                }
                .sortedBy { it.first }

            val baseCodepoints = LinkedHashMap<Pair<String, Int>, CodepointOrigin>()
            val baseReferenceEdges = mutableListOf<ReferenceEdge>()
            val baseFontIds = baseFontEntries.mapTo(linkedSetOf()) { (_, ns, rel) -> "$ns:$rel" }

            var totalFontFiles = baseFontEntries.size
            var totalProviders = auditFontGroup(
                pack = pack,
                fontEntries = baseFontEntries,
                entryPrefix = "",
                packLabel = packLabel,
                reportedPack = reportedPack,
                firstCodepointOut = baseCodepoints,
                referenceEdgesOut = baseReferenceEdges,
                findings = findings,
            )

            // 2. Inspect pack.mcmeta overlays if present and audit each overlay directory against base fallback.
            val overlayDirs = parseAndValidateOverlays(pack, allEntries, reportedPack, findings)
            for (overlayDir in overlayDirs) {
                val prefix = "$overlayDir/"
                val overlayFontEntries = allEntries
                    .filter { it.startsWith(prefix) }
                    .mapNotNull { fullPath ->
                        val relToOverlay = fullPath.removePrefix(prefix)
                        val match = FONT_PATH_REGEX.matchEntire(relToOverlay) ?: return@mapNotNull null
                        Triple(relToOverlay, match.groupValues[1], match.groupValues[2])
                    }
                    .sortedBy { it.first }

                if (overlayFontEntries.isEmpty()) continue
                totalFontFiles += overlayFontEntries.size

                val overlayView = OverlayPackSource(overlayDir, pack)
                val overlayEdges = mutableListOf<ReferenceEdge>()
                val overlayFontIds = LinkedHashSet<String>(baseFontIds).apply {
                    overlayFontEntries.forEach { (_, ns, rel) -> add("$ns:$rel") }
                }

                totalProviders += auditFontGroup(
                    pack = overlayView,
                    fontEntries = overlayFontEntries,
                    entryPrefix = prefix,
                    packLabel = packLabel,
                    reportedPack = reportedPack,
                    firstCodepointOut = null,
                    referenceEdgesOut = overlayEdges,
                    findings = findings,
                )

                for (edge in overlayEdges) {
                    val refNs = edge.toFont.substringBefore(':')
                    if (refNs != "minecraft" && edge.toFont !in overlayFontIds) {
                        findings += AuditFinding(
                            rule = "REFERENCE_MISSING_TARGET",
                            severity = FindingSeverity.WARNING,
                            fontId = edge.fromFont,
                            providerIndex = edge.providerIndex,
                            message = "Overlay '$overlayDir' reference provider #${edge.providerIndex} points to '${edge.toFont}', which is missing from both overlay '$overlayDir' and base assets.",
                            pack = reportedPack,
                            filePath = edge.filePath,
                        )
                    }
                }
                detectReferenceCycles(baseReferenceEdges.filter { e -> e.fromFont !in overlayFontEntries.map { "${it.second}:${it.third}" }.toSet() } + overlayEdges, findings)
            }

            return SinglePackResult(
                packLabel = packLabel,
                fontCount = totalFontFiles,
                providerCount = totalProviders,
                findings = findings,
                baseFontIds = baseFontIds,
                baseCodepoints = baseCodepoints,
                baseReferenceEdges = baseReferenceEdges,
            )
        }
    }

    private fun parseAndValidateOverlays(
        pack: PackSource,
        allEntries: List<String>,
        reportedPack: String?,
        findings: MutableList<AuditFinding>,
    ): List<String> {
        if (!pack.hasEntry("pack.mcmeta")) return emptyList()
        val text = try {
            String(pack.readBytes("pack.mcmeta"), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            return emptyList()
        }
        val rootObj = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (_: Exception) {
            return emptyList()
        }

        validatePackFormatHeader(rootObj, reportedPack, findings)

        val overlaysObj = rootObj["overlays"] as? JsonObject ?: return emptyList()
        val entriesArr = overlaysObj["entries"] as? JsonArray ?: return emptyList()

        val validDirs = LinkedHashSet<String>()
        entriesArr.forEachIndexed { idx, elem ->
            val entryObj = elem as? JsonObject
            val dir = entryObj?.stringField("directory")
            if (entryObj != null) {
                validateOverlayFormatBounds(entryObj, dir ?: "<entry #$idx>", idx, reportedPack, findings)
            }
            if (dir.isNullOrBlank() || !OVERLAY_DIR_REGEX.matches(dir)) {
                findings += AuditFinding(
                    rule = "OVERLAY_INVALID_DIRECTORY",
                    severity = FindingSeverity.ERROR,
                    fontId = "pack.mcmeta",
                    providerIndex = idx,
                    message = "Overlay entry #$idx declares invalid directory '${dir ?: "<missing>"}' (must match [a-z0-9_-]+).",
                    pack = reportedPack,
                    filePath = "pack.mcmeta",
                )
            } else {
                val prefix = "$dir/"
                if (allEntries.none { it.startsWith(prefix) }) {
                    findings += AuditFinding(
                        rule = "OVERLAY_INVALID_DIRECTORY",
                        severity = FindingSeverity.ERROR,
                        fontId = "pack.mcmeta",
                        providerIndex = idx,
                        message = "Overlay entry #$idx declares directory '$dir', which contains no files in the pack.",
                        pack = reportedPack,
                        filePath = "pack.mcmeta",
                    )
                } else {
                    validDirs += dir
                }
            }
        }
        return validDirs.toList()
    }

    private fun validatePackFormatHeader(
        rootObj: JsonObject,
        reportedPack: String?,
        findings: MutableList<AuditFinding>,
    ) {
        val packObj = rootObj["pack"] as? JsonObject ?: return
        val hasMin = packObj.containsKey("min_format")
        val hasMax = packObj.containsKey("max_format")
        val hasSupported = packObj.containsKey("supported_formats")
        val packFormatVal = packObj.intField("pack_format")

        if (hasMin != hasMax) {
            findings += AuditFinding(
                rule = "PACK_MCMETA_FORMAT_CONFLICT",
                severity = FindingSeverity.ERROR,
                fontId = "pack.mcmeta",
                message = "pack.mcmeta declares only one of 'min_format' and 'max_format'; Minecraft 1.21.9..26.3 requires both together.",
                pack = reportedPack,
                filePath = "pack.mcmeta",
            )
            return
        }

        if (hasMin && hasMax) {
            val minFmt = parsePackFormatElement(packObj["min_format"])
            val maxFmt = parsePackFormatElement(packObj["max_format"])
            if (minFmt == null || maxFmt == null) {
                findings += AuditFinding(
                    rule = "PACK_MCMETA_FORMAT_CONFLICT",
                    severity = FindingSeverity.ERROR,
                    fontId = "pack.mcmeta",
                    message = "pack.mcmeta 'min_format' and 'max_format' must be non-negative integers or [major, minor] integer tuples.",
                    pack = reportedPack,
                    filePath = "pack.mcmeta",
                )
                return
            }
            if (PackFormat.exceedsRange(minFmt, maxFmt)) {
                findings += AuditFinding(
                    rule = "PACK_MCMETA_FORMAT_CONFLICT",
                    severity = FindingSeverity.ERROR,
                    fontId = "pack.mcmeta",
                    message = "pack.mcmeta min_format ($minFmt) is greater than max_format ($maxFmt).",
                    pack = reportedPack,
                    filePath = "pack.mcmeta",
                )
            }
            if (minFmt.major > PackMeta.LAST_PRE_MINOR_FORMAT && hasSupported) {
                findings += AuditFinding(
                    rule = "PACK_MCMETA_FORMAT_CONFLICT",
                    severity = FindingSeverity.ERROR,
                    fontId = "pack.mcmeta",
                    message = "Modern pack (min_format $minFmt >= 65) declares 'supported_formats', which Minecraft 1.21.9..26.3 rejects.",
                    pack = reportedPack,
                    filePath = "pack.mcmeta",
                )
            }
            if (minFmt.major <= PackMeta.LAST_PRE_MINOR_FORMAT && maxFmt.major > PackMeta.LAST_PRE_MINOR_FORMAT) {
                val supBounds = parseLegacyFormatBounds(packObj["supported_formats"])
                if (packFormatVal == null || !hasSupported) {
                    findings += AuditFinding(
                        rule = "PACK_MCMETA_FORMAT_CONFLICT",
                        severity = FindingSeverity.ERROR,
                        fontId = "pack.mcmeta",
                        message = "Dual-era pack ($minFmt..$maxFmt) must also declare 'pack_format' and 'supported_formats: [${minFmt.major}, 64]' for pre-1.21.9 clients.",
                        pack = reportedPack,
                        filePath = "pack.mcmeta",
                    )
                } else if (supBounds == null || supBounds.second > PackMeta.LAST_PRE_MINOR_FORMAT) {
                    findings += AuditFinding(
                        rule = "PACK_MCMETA_FORMAT_CONFLICT",
                        severity = FindingSeverity.ERROR,
                        fontId = "pack.mcmeta",
                        message = "Dual-era pack ($minFmt..$maxFmt) 'supported_formats' must be clamped at 64 (got ${packObj["supported_formats"]}).",
                        pack = reportedPack,
                        filePath = "pack.mcmeta",
                    )
                }
            }
        } else if (packFormatVal != null && packFormatVal > PackMeta.LAST_PRE_MINOR_FORMAT) {
            findings += AuditFinding(
                rule = "PACK_MCMETA_FORMAT_CONFLICT",
                severity = FindingSeverity.ERROR,
                fontId = "pack.mcmeta",
                message = "pack.mcmeta declares pack_format $packFormatVal (>= 65) without 'min_format' and 'max_format'; rejected by Minecraft 1.21.9..26.3.",
                pack = reportedPack,
                filePath = "pack.mcmeta",
            )
        } else if (hasSupported) {
            val supBounds = parseLegacyFormatBounds(packObj["supported_formats"])
            if (supBounds != null && supBounds.second > PackMeta.LAST_PRE_MINOR_FORMAT) {
                findings += AuditFinding(
                    rule = "PACK_MCMETA_FORMAT_CONFLICT",
                    severity = FindingSeverity.ERROR,
                    fontId = "pack.mcmeta",
                    message = "pack.mcmeta 'supported_formats' reaches ${supBounds.second} (> 64) without 'min_format' and 'max_format'.",
                    pack = reportedPack,
                    filePath = "pack.mcmeta",
                )
            }
        }
    }

    private fun validateOverlayFormatBounds(
        entryObj: JsonObject,
        dirLabel: String,
        idx: Int,
        reportedPack: String?,
        findings: MutableList<AuditFinding>,
    ) {
        val hasMin = entryObj.containsKey("min_format")
        val hasMax = entryObj.containsKey("max_format")
        val hasFormats = entryObj.containsKey("formats")

        if (!hasMin && !hasMax && !hasFormats) {
            findings += AuditFinding(
                rule = "OVERLAY_FORMAT_CONFLICT",
                severity = FindingSeverity.ERROR,
                fontId = "pack.mcmeta",
                providerIndex = idx,
                message = "Overlay '$dirLabel' must declare 'min_format'/'max_format' (or legacy 'formats' for <= 64).",
                pack = reportedPack,
                filePath = "pack.mcmeta",
            )
            return
        }
        if (hasMin != hasMax) {
            findings += AuditFinding(
                rule = "OVERLAY_FORMAT_CONFLICT",
                severity = FindingSeverity.ERROR,
                fontId = "pack.mcmeta",
                providerIndex = idx,
                message = "Overlay '$dirLabel' declares only one of 'min_format' and 'max_format'.",
                pack = reportedPack,
                filePath = "pack.mcmeta",
            )
            return
        }
        if (hasMin && hasMax) {
            val minFmt = parsePackFormatElement(entryObj["min_format"])
            val maxFmt = parsePackFormatElement(entryObj["max_format"])
            if (minFmt == null || maxFmt == null) {
                findings += AuditFinding(
                    rule = "OVERLAY_FORMAT_CONFLICT",
                    severity = FindingSeverity.ERROR,
                    fontId = "pack.mcmeta",
                    providerIndex = idx,
                    message = "Overlay '$dirLabel' has malformed 'min_format' or 'max_format'.",
                    pack = reportedPack,
                    filePath = "pack.mcmeta",
                )
                return
            }
            if (PackFormat.exceedsRange(minFmt, maxFmt)) {
                findings += AuditFinding(
                    rule = "OVERLAY_FORMAT_CONFLICT",
                    severity = FindingSeverity.ERROR,
                    fontId = "pack.mcmeta",
                    providerIndex = idx,
                    message = "Overlay '$dirLabel' min_format ($minFmt) exceeds max_format ($maxFmt).",
                    pack = reportedPack,
                    filePath = "pack.mcmeta",
                )
            }
            if (minFmt.major > PackMeta.LAST_PRE_MINOR_FORMAT && hasFormats) {
                findings += AuditFinding(
                    rule = "OVERLAY_FORMAT_CONFLICT",
                    severity = FindingSeverity.ERROR,
                    fontId = "pack.mcmeta",
                    providerIndex = idx,
                    message = "Modern overlay '$dirLabel' (min_format $minFmt >= 65) must not declare legacy 'formats'; Minecraft 1.21.9..26.3 rejects it.",
                    pack = reportedPack,
                    filePath = "pack.mcmeta",
                )
            }
            if (minFmt.major <= PackMeta.LAST_PRE_MINOR_FORMAT && maxFmt.major > PackMeta.LAST_PRE_MINOR_FORMAT && !hasFormats) {
                findings += AuditFinding(
                    rule = "OVERLAY_FORMAT_CONFLICT",
                    severity = FindingSeverity.ERROR,
                    fontId = "pack.mcmeta",
                    providerIndex = idx,
                    message = "Dual-era overlay '$dirLabel' ($minFmt..$maxFmt) is missing 'formats: [${minFmt.major}, 64]' for pre-1.21.9 clients.",
                    pack = reportedPack,
                    filePath = "pack.mcmeta",
                )
            }
        } else if (hasFormats) {
            val bounds = parseLegacyFormatBounds(entryObj["formats"])
            if (bounds == null) {
                findings += AuditFinding(
                    rule = "OVERLAY_FORMAT_CONFLICT",
                    severity = FindingSeverity.ERROR,
                    fontId = "pack.mcmeta",
                    providerIndex = idx,
                    message = "Overlay '$dirLabel' has malformed 'formats' field.",
                    pack = reportedPack,
                    filePath = "pack.mcmeta",
                )
            } else if (bounds.second > PackMeta.LAST_PRE_MINOR_FORMAT) {
                findings += AuditFinding(
                    rule = "OVERLAY_FORMAT_CONFLICT",
                    severity = FindingSeverity.ERROR,
                    fontId = "pack.mcmeta",
                    providerIndex = idx,
                    message = "Overlay '$dirLabel' targets format ${bounds.second} (>= 65) via legacy 'formats' without 'min_format' and 'max_format'.",
                    pack = reportedPack,
                    filePath = "pack.mcmeta",
                )
            }
        }
    }

    private fun parsePackFormatElement(elem: JsonElement?): PackFormat? = when (elem) {
        is JsonPrimitive -> {
            if (elem.isString) null else elem.intOrNull?.takeIf { it >= 0 }?.let { PackFormat(it) }
        }
        is JsonArray -> {
            if (elem.size !in 1..2) {
                null
            } else {
                val maj = (elem[0] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                val min = if (elem.size == 2) (elem[1] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull else null
                if (maj == null || maj < 0 || (elem.size == 2 && (min == null || min < 0))) {
                    null
                } else {
                    PackFormat(maj, min)
                }
            }
        }
        else -> null
    }

    private fun parseLegacyFormatBounds(elem: JsonElement?): Pair<Int, Int>? = when (elem) {
        is JsonPrimitive -> {
            if (elem.isString) null else elem.intOrNull?.let { it to it }
        }
        is JsonArray -> {
            if (elem.size == 2) {
                val a = (elem[0] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                val b = (elem[1] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                if (a != null && b != null && a <= b) a to b else null
            } else {
                null
            }
        }
        is JsonObject -> {
            val a = elem.intField("min_inclusive")
            val b = elem.intField("max_inclusive")
            if (a != null && b != null && a <= b) a to b else null
        }
        else -> null
    }

    private fun auditFontGroup(
        pack: PackSource,
        fontEntries: List<Triple<String, String, String>>,
        entryPrefix: String,
        packLabel: String,
        reportedPack: String?,
        firstCodepointOut: MutableMap<Pair<String, Int>, CodepointOrigin>?,
        referenceEdgesOut: MutableList<ReferenceEdge>,
        findings: MutableList<AuditFinding>,
    ): Int {
        var providerCount = 0

        for ((entryPath, namespace, fontRelPath) in fontEntries) {
            val displayPath = "$entryPrefix$entryPath"
            val fontId = "$namespace:$fontRelPath"
            val text = try {
                String(pack.readBytes(entryPath), StandardCharsets.UTF_8)
            } catch (failure: Exception) {
                findings += AuditFinding(
                    rule = "FONT_UNREADABLE",
                    severity = FindingSeverity.ERROR,
                    fontId = fontId,
                    message = "Could not read $displayPath: ${failure.message}",
                    pack = reportedPack,
                    filePath = displayPath,
                )
                continue
            }

            val rootObj = try {
                Json.parseToJsonElement(text).jsonObject
            } catch (failure: Exception) {
                findings += AuditFinding(
                    rule = "FONT_INVALID_JSON",
                    severity = FindingSeverity.ERROR,
                    fontId = fontId,
                    message = "Malformed JSON in $displayPath: ${failure.message}",
                    pack = reportedPack,
                    filePath = displayPath,
                )
                continue
            }

            findings += UnicodeCoverageValidator.checkSurrogateHazards(
                text = text,
                fontId = fontId,
                providerIndex = null,
                packLabel = reportedPack,
                filePath = displayPath,
            )

            val providersElem = rootObj["providers"]
            if (providersElem !is JsonArray) {
                findings += AuditFinding(
                    rule = "FONT_MISSING_PROVIDERS",
                    severity = FindingSeverity.ERROR,
                    fontId = fontId,
                    message = "$displayPath does not declare a 'providers' array.",
                    pack = reportedPack,
                    filePath = displayPath,
                )
                continue
            }

            val seenCodepoints = mutableMapOf<Int, Int>()
            var reportedBmpPuaForFont = false

            providersElem.forEachIndexed { index, item ->
                providerCount++
                val providerObj = item as? JsonObject
                if (providerObj == null) {
                    findings += AuditFinding(
                        rule = "PROVIDER_NOT_OBJECT",
                        severity = FindingSeverity.ERROR,
                        fontId = fontId,
                        providerIndex = index,
                        message = "Provider #$index is not a JSON object.",
                        pack = reportedPack,
                        filePath = displayPath,
                    )
                    return@forEachIndexed
                }

                if (providerObj.containsKey("filter")) {
                    auditProviderFilter(
                        fontId = fontId,
                        displayPath = displayPath,
                        reportedPack = reportedPack,
                        index = index,
                        filterElem = providerObj["filter"],
                        findings = findings,
                    )
                }

                val type = providerObj.stringField("type")
                when (type) {
                    "bitmap" -> auditBitmapProvider(
                        pack = pack,
                        fontId = fontId,
                        displayPath = displayPath,
                        packLabel = packLabel,
                        reportedPack = reportedPack,
                        index = index,
                        obj = providerObj,
                        seenCodepoints = seenCodepoints,
                        firstCodepointOut = firstCodepointOut,
                        onBmpPua = {
                            if (!reportedBmpPuaForFont) {
                                reportedBmpPuaForFont = true
                                true
                            } else {
                                false
                            }
                        },
                        findings = findings,
                    )
                    "space" -> auditSpaceProvider(
                        fontId = fontId,
                        displayPath = displayPath,
                        packLabel = packLabel,
                        reportedPack = reportedPack,
                        index = index,
                        obj = providerObj,
                        seenCodepoints = seenCodepoints,
                        firstCodepointOut = firstCodepointOut,
                        findings = findings,
                    )
                    "reference" -> auditReferenceProvider(
                        fontId = fontId,
                        displayPath = displayPath,
                        reportedPack = reportedPack,
                        index = index,
                        obj = providerObj,
                        referenceEdgesOut = referenceEdgesOut,
                        findings = findings,
                    )
                    "ttf" -> auditTtfProvider(
                        pack = pack,
                        fontId = fontId,
                        displayPath = displayPath,
                        reportedPack = reportedPack,
                        index = index,
                        obj = providerObj,
                        findings = findings,
                    )
                    "unihex" -> auditUnihexProvider(
                        pack = pack,
                        fontId = fontId,
                        displayPath = displayPath,
                        reportedPack = reportedPack,
                        index = index,
                        obj = providerObj,
                        findings = findings,
                    )
                    null -> findings += AuditFinding(
                        rule = "PROVIDER_MISSING_TYPE",
                        severity = FindingSeverity.ERROR,
                        fontId = fontId,
                        providerIndex = index,
                        message = "Provider #$index is missing 'type'.",
                        pack = reportedPack,
                        filePath = displayPath,
                    )
                    else -> findings += AuditFinding(
                        rule = "PROVIDER_UNKNOWN_TYPE",
                        severity = FindingSeverity.WARNING,
                        fontId = fontId,
                        providerIndex = index,
                        message = "Provider #$index uses unrecognized provider type '$type'.",
                        pack = reportedPack,
                        filePath = displayPath,
                    )
                }
            }
        }
        return providerCount
    }

    private fun auditBitmapProvider(
        pack: PackSource,
        fontId: String,
        displayPath: String,
        packLabel: String,
        reportedPack: String?,
        index: Int,
        obj: JsonObject,
        seenCodepoints: MutableMap<Int, Int>,
        firstCodepointOut: MutableMap<Pair<String, Int>, CodepointOrigin>?,
        onBmpPua: () -> Boolean,
        findings: MutableList<AuditFinding>,
    ) {
        val height = obj.intField("height") ?: 8
        if (height <= 0) {
            findings += AuditFinding(
                rule = "BITMAP_INVALID_HEIGHT",
                severity = FindingSeverity.ERROR,
                fontId = fontId,
                providerIndex = index,
                message = "Bitmap provider #$index declares non-positive height $height; render height must be >= 1.",
                pack = reportedPack,
                filePath = displayPath,
            )
        }
        val ascent = obj.intField("ascent")
        if (ascent == null) {
            findings += AuditFinding(
                rule = "BITMAP_MISSING_ASCENT",
                severity = FindingSeverity.ERROR,
                fontId = fontId,
                providerIndex = index,
                message = "Bitmap provider #$index is missing required integer 'ascent'.",
                pack = reportedPack,
                filePath = displayPath,
            )
        } else if (height > 0 && ascent > height) {
            findings += AuditFinding(
                rule = "BITMAP_ASCENT_EXCEEDS_HEIGHT",
                severity = FindingSeverity.ERROR,
                fontId = fontId,
                providerIndex = index,
                message = "Bitmap provider #$index declares ascent $ascent > height $height; the client rejects the entire font file.",
                pack = reportedPack,
                filePath = displayPath,
            )
        }

        val charsArr = obj["chars"] as? JsonArray
        if (charsArr == null || charsArr.isEmpty()) {
            findings += AuditFinding(
                rule = "BITMAP_IRREGULAR_GRID",
                severity = FindingSeverity.ERROR,
                fontId = fontId,
                providerIndex = index,
                message = "Bitmap provider #$index must declare a non-empty 'chars' array.",
                pack = reportedPack,
                filePath = displayPath,
            )
            return
        }

        val rows = mutableListOf<List<Int>>()
        for (rowElem in charsArr) {
            val rowStr = (rowElem as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (rowStr == null) {
                findings += AuditFinding(
                    rule = "BITMAP_IRREGULAR_GRID",
                    severity = FindingSeverity.ERROR,
                    fontId = fontId,
                    providerIndex = index,
                    message = "Bitmap provider #$index contains a non-string row in 'chars'.",
                    pack = reportedPack,
                    filePath = displayPath,
                )
                return
            }
            rows += rowStr.codePoints().toArray().toList()
        }

        val cols = rows.first().size
        if (cols == 0 || rows.any { it.size != cols }) {
            findings += AuditFinding(
                rule = "BITMAP_IRREGULAR_GRID",
                severity = FindingSeverity.ERROR,
                fontId = fontId,
                providerIndex = index,
                message = "Bitmap provider #$index has unequal codepoint row lengths in 'chars'.",
                pack = reportedPack,
                filePath = displayPath,
            )
            return
        }

        var bmpPuaSample: Int? = null
        for (row in rows) {
            for (cp in row) {
                if (cp == GlyphMath.PADDING_CODEPOINT) continue
                firstCodepointOut?.putIfAbsent(
                    fontId to cp,
                    CodepointOrigin(packLabel = packLabel, filePath = displayPath, providerIndex = index),
                )
                val prev = seenCodepoints.put(cp, index)
                if (prev != null) {
                    findings += AuditFinding(
                        rule = "CODEPOINT_SHADOWED",
                        severity = FindingSeverity.WARNING,
                        fontId = fontId,
                        providerIndex = index,
                        message = "Codepoint ${Codepoints.describe(cp)} in provider #$index overwrites earlier provider #$prev.",
                        pack = reportedPack,
                        filePath = displayPath,
                    )
                }
                if (bmpPuaSample == null && cp in Codepoints.BMP_PRIVATE_USE_FIRST..Codepoints.BMP_PRIVATE_USE_LAST) {
                    bmpPuaSample = cp
                }
            }
        }
        if (bmpPuaSample != null && onBmpPua()) {
            findings += AuditFinding(
                rule = "CODEPOINT_BMP_PUA_HAZARD",
                severity = FindingSeverity.WARNING,
                fontId = fontId,
                providerIndex = index,
                message = "Font allocates in BMP Private Use Area (e.g. ${Codepoints.describe(bmpPuaSample)}), which collides with common custom-item plugins when merged.",
                pack = reportedPack,
                filePath = displayPath,
            )
        }

        val providerCodepoints = rows.flatten().filter { it != GlyphMath.PADDING_CODEPOINT }
        findings += UnicodeCoverageValidator.checkPlaneSupport(
            providerType = "bitmap",
            codepoints = providerCodepoints,
            fontId = fontId,
            providerIndex = index,
            format = null,
            packLabel = reportedPack,
            filePath = displayPath,
        )

        val fileRef = obj.stringField("file")
        if (fileRef.isNullOrBlank()) {
            findings += AuditFinding(
                rule = "BITMAP_MISSING_TEXTURE",
                severity = FindingSeverity.ERROR,
                fontId = fontId,
                providerIndex = index,
                message = "Bitmap provider #$index is missing 'file'.",
                pack = reportedPack,
                filePath = displayPath,
            )
            return
        }

        val textureEntry = resolveTextureEntry(fileRef)
        if (!pack.hasEntry(textureEntry)) {
            findings += AuditFinding(
                rule = "BITMAP_MISSING_TEXTURE",
                severity = FindingSeverity.ERROR,
                fontId = fontId,
                providerIndex = index,
                message = "Bitmap provider #$index references '$fileRef', but '$textureEntry' does not exist in the pack.",
                pack = reportedPack,
                filePath = displayPath,
            )
            return
        }

        val decoded = try {
            ByteArrayInputStream(pack.readBytes(textureEntry)).use { ImageIO.read(it) }
        } catch (_: Exception) {
            null
        }
        if (decoded == null) {
            findings += AuditFinding(
                rule = "BITMAP_MISSING_TEXTURE",
                severity = FindingSeverity.ERROR,
                fontId = fontId,
                providerIndex = index,
                message = "Texture '$textureEntry' referenced by provider #$index is not a readable PNG image.",
                pack = reportedPack,
                filePath = displayPath,
            )
            return
        }

        val rowCount = rows.size
        if (decoded.width % cols != 0 || decoded.height % rowCount != 0) {
            findings += AuditFinding(
                rule = "BITMAP_GRID_MISMATCH",
                severity = FindingSeverity.WARNING,
                fontId = fontId,
                providerIndex = index,
                message = "Texture '$textureEntry' is ${decoded.width}x${decoded.height}px, which does not divide evenly into ${cols}x${rowCount} grid cells; right/bottom remainder pixels are truncated.",
                pack = reportedPack,
                filePath = displayPath,
            )
        }

        val cellW = decoded.width / cols
        val cellH = decoded.height / rowCount
        if (cellW > GlyphMath.MAX_CELL || cellH > GlyphMath.MAX_CELL) {
            findings += AuditFinding(
                rule = "BITMAP_CELL_EXCEEDS_ATLAS",
                severity = FindingSeverity.ERROR,
                fontId = fontId,
                providerIndex = index,
                message = "Texture '$textureEntry' resolves to ${cellW}x${cellH}px cells, exceeding the ${GlyphMath.MAX_CELL}px vanilla FontTexture page limit.",
                pack = reportedPack,
                filePath = displayPath,
            )
        }

        if (!decoded.colorModel.hasAlpha()) {
            findings += AuditFinding(
                rule = "BITMAP_OPAQUE_BACKGROUND_BLEED",
                severity = FindingSeverity.WARNING,
                fontId = fontId,
                providerIndex = index,
                message = "Texture '$textureEntry' has no alpha channel; Minecraft treats every pixel as 100% opaque, drawing solid background boxes and inflating advance to cellWidth + 1.",
                pack = reportedPack,
                filePath = displayPath,
            )
        } else if ((cols > 1 || rowCount > 1) && cellW in 1..GlyphMath.MAX_CELL && cellH in 1..GlyphMath.MAX_CELL) {
            var inkedCellCount = 0
            var firstCollapsedCp: Int? = null
            for (r in 0 until rowCount) {
                val rowCps = rows[r]
                for (c in 0 until cols) {
                    val cp = rowCps[c]
                    val startX = c * cellW
                    val startY = r * cellH
                    var hasInk = false
                    outer@ for (py in startY until (startY + cellH)) {
                        for (px in startX until (startX + cellW)) {
                            if ((decoded.getRGB(px, py) ushr 24) != 0) {
                                hasInk = true
                                break@outer
                            }
                        }
                    }
                    if (hasInk) {
                        inkedCellCount++
                    } else if (cp != GlyphMath.PADDING_CODEPOINT && cp != 0x20 && cp != 0xA0 && firstCollapsedCp == null) {
                        firstCollapsedCp = cp
                    }
                }
            }
            if (inkedCellCount > 0 && firstCollapsedCp != null) {
                findings += AuditFinding(
                    rule = "BITMAP_TRANSPARENT_CELL_COLLAPSE",
                    severity = FindingSeverity.WARNING,
                    fontId = fontId,
                    providerIndex = index,
                    message = "Sprite sheet '$textureEntry' maps ${Codepoints.describe(firstCollapsedCp)} to a completely transparent ${cellW}x${cellH}px cell; BitmapProvider collapses empty cells to 1px advance and desynchronizes composite HUD layouts.",
                    pack = reportedPack,
                    filePath = displayPath,
                )
            }
        }
    }

    private fun auditSpaceProvider(
        fontId: String,
        displayPath: String,
        packLabel: String,
        reportedPack: String?,
        index: Int,
        obj: JsonObject,
        seenCodepoints: MutableMap<Int, Int>,
        firstCodepointOut: MutableMap<Pair<String, Int>, CodepointOrigin>?,
        findings: MutableList<AuditFinding>,
    ) {
        val advances = obj["advances"] as? JsonObject
        if (advances == null) {
            findings += AuditFinding(
                rule = "SPACE_MISSING_ADVANCES",
                severity = FindingSeverity.ERROR,
                fontId = fontId,
                providerIndex = index,
                message = "Space provider #$index is missing 'advances' object.",
                pack = reportedPack,
                filePath = displayPath,
            )
            return
        }
        val negPowerSteps = HashSet<Int>()
        val posPowerSteps = HashSet<Int>()

        for ((key, valueElem) in advances) {
            val cps = key.codePoints().toArray()
            if (cps.size != 1) {
                findings += AuditFinding(
                    rule = "SPACE_INVALID_CODEPOINT_KEY",
                    severity = FindingSeverity.ERROR,
                    fontId = fontId,
                    providerIndex = index,
                    message = "Space provider #$index has an advances key of ${cps.size} codepoints (expected exactly 1).",
                    pack = reportedPack,
                    filePath = displayPath,
                )
                continue
            }
            val cp = cps[0]
            val prim = valueElem as? JsonPrimitive
            val numericAdvance = if (prim != null && !prim.isString) prim.doubleOrNull else null
            if (numericAdvance == null || !numericAdvance.isFinite() || numericAdvance < MIN_SPACE_ADVANCE || numericAdvance > MAX_SPACE_ADVANCE) {
                findings += AuditFinding(
                    rule = "SPACE_INVALID_ADVANCE",
                    severity = FindingSeverity.ERROR,
                    fontId = fontId,
                    providerIndex = index,
                    message = "Space provider #$index advance for ${Codepoints.describe(cp)} must be a finite number in [$MIN_SPACE_ADVANCE, $MAX_SPACE_ADVANCE], got $valueElem.",
                    pack = reportedPack,
                    filePath = displayPath,
                )
            } else if (numericAdvance % 1.0 == 0.0) {
                val intVal = numericAdvance.toInt()
                val mag = kotlin.math.abs(intVal)
                if (mag >= 1 && (mag and (mag - 1)) == 0) {
                    if (intVal < 0) negPowerSteps += mag else if (intVal > 0) posPowerSteps += mag
                }
            }
            if (cp != GlyphMath.PADDING_CODEPOINT) {
                firstCodepointOut?.putIfAbsent(
                    fontId to cp,
                    CodepointOrigin(packLabel = packLabel, filePath = displayPath, providerIndex = index),
                )
            }
            val prev = seenCodepoints.put(cp, index)
            if (prev != null) {
                findings += AuditFinding(
                    rule = "CODEPOINT_SHADOWED",
                    severity = FindingSeverity.WARNING,
                    fontId = fontId,
                    providerIndex = index,
                    message = "Codepoint ${Codepoints.describe(cp)} in space provider #$index overwrites earlier provider #$prev.",
                    pack = reportedPack,
                    filePath = displayPath,
                )
            }
        }

        if (negPowerSteps.size >= 3) {
            val maxNeg = negPowerSteps.maxOrNull() ?: 0
            if (maxNeg >= 8) {
                val missingNeg = mutableListOf<Int>()
                var step = 1
                while (step <= maxNeg) {
                    if (step !in negPowerSteps) missingNeg += -step
                    step *= 2
                }
                if (missingNeg.isNotEmpty()) {
                    findings += AuditFinding(
                        rule = "HUD_SHIFT_TABLE_GAP",
                        severity = FindingSeverity.WARNING,
                        fontId = fontId,
                        providerIndex = index,
                        message = "Space provider #$index defines negative-space shifts up to -${maxNeg}px but is missing intermediate power-of-two steps (${missingNeg.joinToString(", ")}px).",
                        pack = reportedPack,
                        filePath = displayPath,
                    )
                } else if (posPowerSteps.size >= 2 && posPowerSteps != negPowerSteps) {
                    val missingPos = (negPowerSteps - posPowerSteps).sorted()
                    if (missingPos.isNotEmpty()) {
                        findings += AuditFinding(
                            rule = "HUD_SHIFT_TABLE_GAP",
                            severity = FindingSeverity.WARNING,
                            fontId = fontId,
                            providerIndex = index,
                            message = "Space provider #$index defines negative shifts up to -${maxNeg}px without matching positive restore steps (+${missingPos.joinToString(", +")}px), preventing NAF encoding and positive cursor restoration.",
                            pack = reportedPack,
                            filePath = displayPath,
                        )
                    }
                }
            }
        }
    }

    private fun auditReferenceProvider(
        fontId: String,
        displayPath: String,
        reportedPack: String?,
        index: Int,
        obj: JsonObject,
        referenceEdgesOut: MutableList<ReferenceEdge>,
        findings: MutableList<AuditFinding>,
    ) {
        val rawId = obj.stringField("id")
        val normalized = rawId?.let(::normalizeFontIdentifier)
        if (normalized == null) {
            findings += AuditFinding(
                rule = "REFERENCE_MISSING_ID",
                severity = FindingSeverity.ERROR,
                fontId = fontId,
                providerIndex = index,
                message = "Reference provider #$index must declare a valid 'id' resource location (got '${rawId ?: "<missing>"}').",
                pack = reportedPack,
                filePath = displayPath,
            )
            return
        }
        referenceEdgesOut += ReferenceEdge(
            fromFont = fontId,
            toFont = normalized,
            packLabel = reportedPack,
            filePath = displayPath,
            providerIndex = index,
        )
    }

    private fun auditProviderFilter(
        fontId: String,
        displayPath: String,
        reportedPack: String?,
        index: Int,
        filterElem: JsonElement?,
        findings: MutableList<AuditFinding>,
    ) {
        val filterObj = filterElem as? JsonObject
        if (filterObj == null) {
            findings += AuditFinding(
                rule = "PROVIDER_INVALID_FILTER",
                severity = FindingSeverity.ERROR,
                fontId = fontId,
                providerIndex = index,
                message = "Provider #$index 'filter' must be a JSON object of FontOption booleans (uniform, jp), got $filterElem.",
                pack = reportedPack,
                filePath = displayPath,
            )
            return
        }
        for ((k, v) in filterObj) {
            if (k != "uniform" && k != "jp") {
                findings += AuditFinding(
                    rule = "PROVIDER_INVALID_FILTER",
                    severity = FindingSeverity.ERROR,
                    fontId = fontId,
                    providerIndex = index,
                    message = "Provider #$index 'filter' declares unsupported option '$k'; vanilla FontOption.CODEC accepts only 'uniform' and 'jp'.",
                    pack = reportedPack,
                    filePath = displayPath,
                )
            } else {
                val prim = v as? JsonPrimitive
                val boolVal = if (prim != null && !prim.isString) prim.booleanOrNull else null
                if (boolVal == null) {
                    findings += AuditFinding(
                        rule = "PROVIDER_INVALID_FILTER",
                        severity = FindingSeverity.ERROR,
                        fontId = fontId,
                        providerIndex = index,
                        message = "Provider #$index 'filter.$k' must be a JSON boolean (true or false), got $v.",
                        pack = reportedPack,
                        filePath = displayPath,
                    )
                }
            }
        }
    }

    private fun auditTtfProvider(
        pack: PackSource,
        fontId: String,
        displayPath: String,
        reportedPack: String?,
        index: Int,
        obj: JsonObject,
        findings: MutableList<AuditFinding>,
    ) {
        if (obj.containsKey("size")) {
            val prim = obj["size"] as? JsonPrimitive
            val sizeVal = if (prim != null && !prim.isString) prim.doubleOrNull else null
            if (sizeVal == null || !sizeVal.isFinite() || sizeVal <= 0.0) {
                findings += AuditFinding(
                    rule = "TTF_INVALID_METRICS",
                    severity = FindingSeverity.ERROR,
                    fontId = fontId,
                    providerIndex = index,
                    message = "TTF provider #$index 'size' must be a positive number, got ${obj["size"]}.",
                    pack = reportedPack,
                    filePath = displayPath,
                )
            }
        }
        if (obj.containsKey("oversample")) {
            val prim = obj["oversample"] as? JsonPrimitive
            val overVal = if (prim != null && !prim.isString) prim.doubleOrNull else null
            if (overVal == null || !overVal.isFinite() || overVal <= 0.0) {
                findings += AuditFinding(
                    rule = "TTF_INVALID_METRICS",
                    severity = FindingSeverity.ERROR,
                    fontId = fontId,
                    providerIndex = index,
                    message = "TTF provider #$index 'oversample' must be a positive number, got ${obj["oversample"]}.",
                    pack = reportedPack,
                    filePath = displayPath,
                )
            }
        }
        if (obj.containsKey("shift")) {
            val shiftArr = obj["shift"] as? JsonArray
            val validShift = shiftArr != null && shiftArr.size == 2 && shiftArr.all { elem ->
                val p = elem as? JsonPrimitive
                val d = if (p != null && !p.isString) p.doubleOrNull else null
                d != null && d.isFinite()
            }
            if (!validShift) {
                findings += AuditFinding(
                    rule = "TTF_INVALID_METRICS",
                    severity = FindingSeverity.ERROR,
                    fontId = fontId,
                    providerIndex = index,
                    message = "TTF provider #$index 'shift' must be a 2-element numeric array [x, y], got ${obj["shift"]}.",
                    pack = reportedPack,
                    filePath = displayPath,
                )
            }
        }

        val skippedCodepoints = HashSet<Int>()
        if (obj.containsKey("skip")) {
            when (val skipElem = obj["skip"]) {
                is JsonPrimitive -> {
                    if (!skipElem.isString) {
                        findings += AuditFinding(
                            rule = "TTF_INVALID_METRICS",
                            severity = FindingSeverity.ERROR,
                            fontId = fontId,
                            providerIndex = index,
                            message = "TTF provider #$index 'skip' must be a string or array of strings, got $skipElem.",
                            pack = reportedPack,
                            filePath = displayPath,
                        )
                    } else {
                        skipElem.content.codePoints().forEach { skippedCodepoints.add(it) }
                    }
                }
                is JsonArray -> {
                    var badSkip = false
                    for (item in skipElem) {
                        val s = (item as? JsonPrimitive)?.takeIf { it.isString }?.content
                        if (s == null) {
                            badSkip = true
                        } else {
                            s.codePoints().forEach { skippedCodepoints.add(it) }
                        }
                    }
                    if (badSkip) {
                        findings += AuditFinding(
                            rule = "TTF_INVALID_METRICS",
                            severity = FindingSeverity.ERROR,
                            fontId = fontId,
                            providerIndex = index,
                            message = "TTF provider #$index 'skip' array contains a non-string element.",
                            pack = reportedPack,
                            filePath = displayPath,
                        )
                    }
                }
                else -> findings += AuditFinding(
                    rule = "TTF_INVALID_METRICS",
                    severity = FindingSeverity.ERROR,
                    fontId = fontId,
                    providerIndex = index,
                    message = "TTF provider #$index 'skip' must be a string or array of strings.",
                    pack = reportedPack,
                    filePath = displayPath,
                )
            }
        }

        val fileRef = obj.stringField("file")
        if (fileRef.isNullOrBlank()) {
            findings += AuditFinding(
                rule = "TTF_MISSING_FILE",
                severity = FindingSeverity.ERROR,
                fontId = fontId,
                providerIndex = index,
                message = "TTF provider #$index is missing 'file'.",
                pack = reportedPack,
                filePath = displayPath,
            )
            return
        }

        val fontBinaryEntry = resolveFontBinaryEntry(fileRef)
        if (!pack.hasEntry(fontBinaryEntry)) {
            val misplacedTex = resolveAssetEntry(fileRef, "textures")
            val hint = if (pack.hasEntry(misplacedTex)) {
                " Found at '$misplacedTex' instead; move TrueType/OpenType files under 'assets/<ns>/font/'."
            } else {
                ""
            }
            findings += AuditFinding(
                rule = "TTF_MISSING_FILE",
                severity = FindingSeverity.ERROR,
                fontId = fontId,
                providerIndex = index,
                message = "TTF provider #$index references '$fileRef', but '$fontBinaryEntry' does not exist in the pack.$hint",
                pack = reportedPack,
                filePath = displayPath,
            )
            return
        }

        val parsed = try {
            OpenTypeFont.parse(pack.readBytes(fontBinaryEntry))
        } catch (failure: OpenTypeException) {
            findings += AuditFinding(
                rule = failure.errorCode,
                severity = FindingSeverity.ERROR,
                fontId = fontId,
                providerIndex = index,
                message = "TTF provider #$index ('$fontBinaryEntry'): ${failure.message}",
                pack = reportedPack,
                filePath = displayPath,
            )
            return
        } catch (failure: Exception) {
            findings += AuditFinding(
                rule = "TTF_MISSING_FILE",
                severity = FindingSeverity.ERROR,
                fontId = fontId,
                providerIndex = index,
                message = "Could not read TTF binary '$fontBinaryEntry': ${failure.message}",
                pack = reportedPack,
                filePath = displayPath,
            )
            return
        }

        val usableCodepoints = parsed.codepointToGlyph.keys.count { it !in skippedCodepoints }
        if (usableCodepoints == 0) {
            findings += AuditFinding(
                rule = "TTF_EMPTY_CMAP",
                severity = FindingSeverity.WARNING,
                fontId = fontId,
                providerIndex = index,
                message = "TTF provider #$index ('$fontBinaryEntry') exposes 0 usable glyphs after cmap and skip filtering.",
                pack = reportedPack,
                filePath = displayPath,
            )
        }

        if (parsed.info.isVariableFont) {
            val axesSummary = parsed.info.variationAxes.joinToString(", ") { ax ->
                "${ax.tag}[${ax.minValue}..${ax.maxValue}, default=${ax.defaultValue}]"
            }
            findings += AuditFinding(
                rule = "TTF_VARIABLE_FONT_UNPINNED_AXES",
                severity = FindingSeverity.WARNING,
                fontId = fontId,
                providerIndex = index,
                message = "TTF provider #$index ('$fontBinaryEntry') is an OpenType variable font ($axesSummary; ${parsed.info.variationPayloadBytes} variation bytes); Minecraft FreeType pins all variation axes to default coordinates.",
                pack = reportedPack,
                filePath = displayPath,
            )

            val opszAxis = parsed.info.variationAxes.find { it.tag.equals("opsz", ignoreCase = true) }
            if (opszAxis != null) {
                val prim = obj["size"] as? JsonPrimitive
                val sizeVal = if (prim != null && !prim.isString) prim.doubleOrNull else null
                val opszFinding = OpticalSizeCompiler.checkOpszMismatch(
                    fontId = fontId,
                    providerIndex = index,
                    providerSize = sizeVal,
                    opszAxis = opszAxis,
                    fontFile = fontBinaryEntry,
                    packLabel = reportedPack,
                    filePath = displayPath,
                )
                if (opszFinding != null) {
                    findings += opszFinding
                }
            }
        }
    }

    private fun auditUnihexProvider(
        pack: PackSource,
        fontId: String,
        displayPath: String,
        reportedPack: String?,
        index: Int,
        obj: JsonObject,
        findings: MutableList<AuditFinding>,
    ) {
        val hexRef = obj.stringField("hex_file")
        if (hexRef.isNullOrBlank()) {
            findings += AuditFinding(
                rule = "UNIHEX_INVALID_PROVIDER",
                severity = FindingSeverity.ERROR,
                fontId = fontId,
                providerIndex = index,
                message = "Unihex provider #$index is missing required 'hex_file' resource location.",
                pack = reportedPack,
                filePath = displayPath,
            )
            return
        }
        val entry = resolveDirectAssetEntry(hexRef)
        if (!pack.hasEntry(entry)) {
            findings += AuditFinding(
                rule = "UNIHEX_INVALID_PROVIDER",
                severity = FindingSeverity.ERROR,
                fontId = fontId,
                providerIndex = index,
                message = "Unihex provider #$index references '$hexRef', but '$entry' is missing from the pack.",
                pack = reportedPack,
                filePath = displayPath,
            )
        }
        if (obj.containsKey("size_overrides")) {
            val arr = obj["size_overrides"] as? JsonArray
            if (arr == null) {
                findings += AuditFinding(
                    rule = "UNIHEX_INVALID_PROVIDER",
                    severity = FindingSeverity.ERROR,
                    fontId = fontId,
                    providerIndex = index,
                    message = "Unihex provider #$index 'size_overrides' must be a JSON array.",
                    pack = reportedPack,
                    filePath = displayPath,
                )
            } else {
                arr.forEachIndexed { ovIdx, item ->
                    val ovObj = item as? JsonObject
                    val fromStr = ovObj?.stringField("from")
                    val toStr = ovObj?.stringField("to")
                    val left = ovObj?.intField("left")
                    val right = ovObj?.intField("right")
                    val valid = fromStr != null && fromStr.codePointCount(0, fromStr.length) == 1 &&
                        toStr != null && toStr.codePointCount(0, toStr.length) == 1 &&
                        left != null && right != null
                    if (!valid) {
                        findings += AuditFinding(
                            rule = "UNIHEX_INVALID_PROVIDER",
                            severity = FindingSeverity.ERROR,
                            fontId = fontId,
                            providerIndex = index,
                            message = "Unihex provider #$index size_overrides[$ovIdx] must declare 1-codepoint 'from'/'to' and integer 'left'/'right'.",
                            pack = reportedPack,
                            filePath = displayPath,
                        )
                    }
                }
            }
        }
    }

    private fun resolveFontBinaryEntry(fileRef: String): String = resolveAssetEntry(fileRef, "font")

    private fun resolveAssetEntry(fileRef: String, folder: String): String {
        val parts = fileRef.split(":", limit = 2)
        val namespace = if (parts.size == 2) parts[0] else "minecraft"
        val rawPath = if (parts.size == 2) parts[1] else parts[0]
        return "assets/$namespace/$folder/$rawPath"
    }

    private fun resolveDirectAssetEntry(fileRef: String): String {
        val parts = fileRef.split(":", limit = 2)
        val namespace = if (parts.size == 2) parts[0] else "minecraft"
        val rawPath = if (parts.size == 2) parts[1] else parts[0]
        return "assets/$namespace/$rawPath"
    }

    private fun detectReferenceCycles(
        edges: List<ReferenceEdge>,
        findings: MutableList<AuditFinding>,
    ) {
        if (edges.isEmpty()) return
        val adj = LinkedHashMap<String, MutableList<ReferenceEdge>>()
        for (edge in edges) {
            adj.getOrPut(edge.fromFont) { mutableListOf() }.add(edge)
        }
        val state = HashMap<String, Int>() // 0 = unvisited, 1 = visiting, 2 = done
        val stack = ArrayList<String>()
        val reportedCycleLocations = HashSet<String>()

        fun dfs(node: String) {
            state[node] = 1
            stack.add(node)
            for (edge in adj[node].orEmpty()) {
                val targetState = state[edge.toFont] ?: 0
                if (targetState == 1) {
                    val cycleStart = stack.indexOf(edge.toFont).coerceAtLeast(0)
                    val chain = (stack.subList(cycleStart, stack.size) + edge.toFont).joinToString(" -> ")
                    val dedupKey = "${edge.filePath}#${edge.providerIndex}->$chain"
                    if (reportedCycleLocations.add(dedupKey)) {
                        findings += AuditFinding(
                            rule = "REFERENCE_CYCLE",
                            severity = FindingSeverity.ERROR,
                            fontId = edge.fromFont,
                            providerIndex = edge.providerIndex,
                            message = "Circular font reference chain detected ($chain); the client cannot resolve recursive font providers.",
                            pack = edge.packLabel,
                            filePath = edge.filePath,
                        )
                    }
                } else if (targetState == 0 && adj.containsKey(edge.toFont)) {
                    dfs(edge.toFont)
                }
            }
            stack.removeAt(stack.lastIndex)
            state[node] = 2
        }

        for (font in adj.keys) {
            if ((state[font] ?: 0) == 0) {
                dfs(font)
            }
        }
    }

    private fun normalizeFontIdentifier(raw: String): String? {
        if (raw.isBlank()) return null
        val parts = raw.split(":", limit = 2)
        val namespace = if (parts.size == 2) parts[0] else "minecraft"
        val relPath = if (parts.size == 2) parts[1] else parts[0]
        if (!NAMESPACE_REGEX.matches(namespace) || !FONT_REL_REGEX.matches(relPath)) {
            return null
        }
        return "$namespace:$relPath"
    }

    private fun resolveTextureEntry(fileRef: String): String {
        val parts = fileRef.split(":", limit = 2)
        val namespace = if (parts.size == 2) parts[0] else "minecraft"
        val rawPath = if (parts.size == 2) parts[1] else parts[0]
        val withExt = if (rawPath.endsWith(".png")) rawPath else "$rawPath.png"
        return "assets/$namespace/textures/$withExt"
    }

    private fun JsonObject.stringField(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.intField(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private interface PackSource : AutoCloseable {
        fun listEntries(): List<String>
        fun hasEntry(entryPath: String): Boolean
        fun readBytes(entryPath: String): ByteArray
    }

    private class OverlayPackSource(
        private val overlayDir: String,
        private val base: PackSource,
    ) : PackSource {
        override fun listEntries(): List<String> = base.listEntries()

        override fun hasEntry(entryPath: String): Boolean =
            base.hasEntry("$overlayDir/$entryPath") || base.hasEntry(entryPath)

        override fun readBytes(entryPath: String): ByteArray {
            val overlaid = "$overlayDir/$entryPath"
            return if (base.hasEntry(overlaid)) base.readBytes(overlaid) else base.readBytes(entryPath)
        }

        override fun close() = Unit
    }

    private class DirectoryPackSource(private val root: Path) : PackSource {
        override fun listEntries(): List<String> {
            if (!root.isDirectory()) return emptyList()
            Files.walk(root).use { stream ->
                return stream
                    .filter { Files.isRegularFile(it) }
                    .map { root.relativize(it).invariantSeparatorsPathString }
                    .toList()
            }
        }

        override fun hasEntry(entryPath: String): Boolean {
            val resolved = safeResolve(entryPath) ?: return false
            return Files.isRegularFile(resolved)
        }

        override fun readBytes(entryPath: String): ByteArray {
            val resolved = safeResolve(entryPath) ?: throw IOException("Invalid entry path: $entryPath")
            val size = Files.size(resolved)
            if (size > MAX_ENTRY_BYTES) throw IOException("Entry $entryPath exceeds ${MAX_ENTRY_BYTES}B limit")
            return Files.readAllBytes(resolved)
        }

        private fun safeResolve(entryPath: String): Path? {
            val normalizedRoot = root.toAbsolutePath().normalize()
            val candidate = normalizedRoot.resolve(entryPath).normalize()
            return if (candidate.startsWith(normalizedRoot)) candidate else null
        }

        override fun close() = Unit
    }

    private class ZipPackSource(path: Path) : PackSource {
        private val zip = try {
            ZipFile(path.toFile())
        } catch (failure: Exception) {
            throw IOException("Could not open zip archive $path: ${failure.message}", failure)
        }

        private val index: Map<String, java.util.zip.ZipEntry> = buildMap {
            val entries = zip.entries()
            var count = 0
            while (entries.hasMoreElements()) {
                count++
                if (count > MAX_ZIP_ENTRIES) {
                    throw IOException("Zip archive exceeds maximum entry count ($MAX_ZIP_ENTRIES)")
                }
                val entry = entries.nextElement()
                if (!entry.isDirectory) {
                    val clean = entry.name.trimStart('/').replace('\\', '/')
                    if (!clean.contains("..")) {
                        put(clean, entry)
                    }
                }
            }
        }

        override fun listEntries(): List<String> = index.keys.toList()

        override fun hasEntry(entryPath: String): Boolean = index.containsKey(entryPath)

        override fun readBytes(entryPath: String): ByteArray {
            val entry = index[entryPath] ?: throw IOException("Missing zip entry: $entryPath")
            if (entry.size > MAX_ENTRY_BYTES) {
                throw IOException("Zip entry $entryPath exceeds ${MAX_ENTRY_BYTES}B limit")
            }
            zip.getInputStream(entry).use { input ->
                val out = input.readNBytes((MAX_ENTRY_BYTES + 1).toInt())
                if (out.size > MAX_ENTRY_BYTES) {
                    throw IOException("Zip entry $entryPath exceeds ${MAX_ENTRY_BYTES}B decompressed limit")
                }
                return out
            }
        }

        override fun close() = zip.close()
    }
}
