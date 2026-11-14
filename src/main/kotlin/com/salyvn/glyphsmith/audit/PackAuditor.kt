package com.salyvn.glyphsmith.audit

import com.salyvn.glyphsmith.codepoint.Codepoints
import com.salyvn.glyphsmith.glyph.GlyphMath
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
        val overlaysObj = rootObj["overlays"] as? JsonObject ?: return emptyList()
        val entriesArr = overlaysObj["entries"] as? JsonArray ?: return emptyList()

        val validDirs = LinkedHashSet<String>()
        entriesArr.forEachIndexed { idx, elem ->
            val entryObj = elem as? JsonObject
            val dir = entryObj?.stringField("directory")
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
                    "ttf", "unihex" -> Unit
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
