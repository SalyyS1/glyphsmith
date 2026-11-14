package com.salyvn.glyphsmith.audit

import com.salyvn.glyphsmith.codepoint.Codepoints
import com.salyvn.glyphsmith.glyph.GlyphMath
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
)

@Serializable
data class AuditReport(
    val target: String,
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
 * Audits an existing resource pack directory or `.zip` archive for font provider bugs that cause the
 * Minecraft client to reject a font file, truncate glyph pixels, or silently overwrite codepoints across
 * merged packs.
 */
object PackAuditor {
    private const val MAX_ZIP_ENTRIES = 20_000
    private const val MAX_ENTRY_BYTES = 32 * 1024 * 1024L

    private val FONT_PATH_REGEX = Regex("^assets/([a-z0-9_.-]+)/font/([a-z0-9_./-]+)\\.json$")

    fun audit(target: Path): AuditReport {
        val source: PackSource = when {
            target.isDirectory() -> DirectoryPackSource(target)
            target.isRegularFile() && target.extension.lowercase() == "zip" -> ZipPackSource(target)
            target.isRegularFile() -> ZipPackSource(target)
            else -> throw IOException("No resource pack directory or zip archive found at $target")
        }
        source.use { pack ->
            val fontEntries = pack.listEntries()
                .mapNotNull { path ->
                    val match = FONT_PATH_REGEX.matchEntire(path) ?: return@mapNotNull null
                    Triple(path, match.groupValues[1], match.groupValues[2])
                }
                .sortedBy { it.first }

            val findings = mutableListOf<AuditFinding>()
            var totalProviders = 0

            for ((entryPath, namespace, fontRelPath) in fontEntries) {
                val fontId = "$namespace:$fontRelPath"
                val text = try {
                    String(pack.readBytes(entryPath), StandardCharsets.UTF_8)
                } catch (failure: Exception) {
                    findings += AuditFinding(
                        rule = "FONT_UNREADABLE",
                        severity = FindingSeverity.ERROR,
                        fontId = fontId,
                        message = "Could not read $entryPath: ${failure.message}",
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
                        message = "Malformed JSON in $entryPath: ${failure.message}",
                    )
                    continue
                }

                val providersElem = rootObj["providers"]
                if (providersElem !is JsonArray) {
                    findings += AuditFinding(
                        rule = "FONT_MISSING_PROVIDERS",
                        severity = FindingSeverity.ERROR,
                        fontId = fontId,
                        message = "$entryPath does not declare a 'providers' array.",
                    )
                    continue
                }

                val seenCodepoints = mutableMapOf<Int, Int>()
                var reportedBmpPuaForFont = false

                providersElem.forEachIndexed { index, item ->
                    totalProviders++
                    val providerObj = item as? JsonObject
                    if (providerObj == null) {
                        findings += AuditFinding(
                            rule = "PROVIDER_NOT_OBJECT",
                            severity = FindingSeverity.ERROR,
                            fontId = fontId,
                            providerIndex = index,
                            message = "Provider #$index is not a JSON object.",
                        )
                        return@forEachIndexed
                    }

                    val type = providerObj.stringField("type")
                    when (type) {
                        "bitmap" -> auditBitmapProvider(
                            pack = pack,
                            fontId = fontId,
                            index = index,
                            obj = providerObj,
                            seenCodepoints = seenCodepoints,
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
                            index = index,
                            obj = providerObj,
                            seenCodepoints = seenCodepoints,
                            findings = findings,
                        )
                        "reference", "ttf", "unihex" -> Unit
                        null -> findings += AuditFinding(
                            rule = "PROVIDER_MISSING_TYPE",
                            severity = FindingSeverity.ERROR,
                            fontId = fontId,
                            providerIndex = index,
                            message = "Provider #$index is missing 'type'.",
                        )
                        else -> findings += AuditFinding(
                            rule = "PROVIDER_UNKNOWN_TYPE",
                            severity = FindingSeverity.WARNING,
                            fontId = fontId,
                            providerIndex = index,
                            message = "Provider #$index uses unrecognized provider type '$type'.",
                        )
                    }
                }
            }

            val errors = findings.count { it.severity == FindingSeverity.ERROR }
            val warnings = findings.count { it.severity == FindingSeverity.WARNING }
            return AuditReport(
                target = target.toString(),
                fontCount = fontEntries.size,
                providerCount = totalProviders,
                errorCount = errors,
                warningCount = warnings,
                findings = findings,
            )
        }
    }

    private fun auditBitmapProvider(
        pack: PackSource,
        fontId: String,
        index: Int,
        obj: JsonObject,
        seenCodepoints: MutableMap<Int, Int>,
        onBmpPua: () -> Boolean,
        findings: MutableList<AuditFinding>,
    ) {
        val height = obj.intField("height") ?: 8
        val ascent = obj.intField("ascent")
        if (ascent == null) {
            findings += AuditFinding(
                rule = "BITMAP_MISSING_ASCENT",
                severity = FindingSeverity.ERROR,
                fontId = fontId,
                providerIndex = index,
                message = "Bitmap provider #$index is missing required integer 'ascent'.",
            )
        } else if (ascent > height) {
            findings += AuditFinding(
                rule = "BITMAP_ASCENT_EXCEEDS_HEIGHT",
                severity = FindingSeverity.ERROR,
                fontId = fontId,
                providerIndex = index,
                message = "Bitmap provider #$index declares ascent $ascent > height $height; the client rejects the entire font file.",
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
            )
            return
        }

        var bmpPuaSample: Int? = null
        for (row in rows) {
            for (cp in row) {
                if (cp == GlyphMath.PADDING_CODEPOINT) continue
                val prev = seenCodepoints.put(cp, index)
                if (prev != null) {
                    findings += AuditFinding(
                        rule = "CODEPOINT_SHADOWED",
                        severity = FindingSeverity.WARNING,
                        fontId = fontId,
                        providerIndex = index,
                        message = "Codepoint ${Codepoints.describe(cp)} in provider #$index overwrites earlier provider #$prev.",
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
            )
        }
    }

    private fun auditSpaceProvider(
        fontId: String,
        index: Int,
        obj: JsonObject,
        seenCodepoints: MutableMap<Int, Int>,
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
            )
            return
        }
        for (key in advances.keys) {
            val cps = key.codePoints().toArray()
            if (cps.size != 1) {
                findings += AuditFinding(
                    rule = "SPACE_INVALID_CODEPOINT_KEY",
                    severity = FindingSeverity.ERROR,
                    fontId = fontId,
                    providerIndex = index,
                    message = "Space provider #$index has an advances key of ${cps.size} codepoints (expected exactly 1).",
                )
                continue
            }
            val cp = cps[0]
            val prev = seenCodepoints.put(cp, index)
            if (prev != null) {
                findings += AuditFinding(
                    rule = "CODEPOINT_SHADOWED",
                    severity = FindingSeverity.WARNING,
                    fontId = fontId,
                    providerIndex = index,
                    message = "Codepoint ${Codepoints.describe(cp)} in space provider #$index overwrites earlier provider #$prev.",
                )
            }
        }
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

    private class DirectoryPackSource(private val root: Path) : PackSource {
        override fun listEntries(): List<String> {
            val assetsDir = root.resolve("assets")
            if (!assetsDir.isDirectory()) return emptyList()
            Files.walk(assetsDir).use { stream ->
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
