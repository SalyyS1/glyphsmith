package com.salyvn.glyphsmith.spec

import com.salyvn.glyphsmith.codepoint.Codepoints
import com.salyvn.glyphsmith.core.PackFormats
import com.salyvn.glyphsmith.glyph.Anchor
import com.salyvn.glyphsmith.pack.PackFormat
import com.salyvn.glyphsmith.pack.ProviderFilter
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.file.Files
import java.nio.file.Path

/** Raised for anything wrong with the spec file, quoting the key at fault. */
class SpecException(message: String) : Exception(message)

/**
 * Reads a spec file.
 *
 * A mistyped key is an error rather than a silent default. Getting `ascnet` accepted and ignored, then
 * spending an evening wondering why the glyph did not move, is a worse outcome than being told the file
 * has a key nobody asked for.
 */
object SpecReader {
    private val TOP_LEVEL = setOf(
        "namespace",
        "font",
        "description",
        "versions",
        "codepoints",
        "shifts",
        "compact_shifts",
        "glyphs",
        "ttf",
    )
    private val GLYPH_KEYS = setOf(
        "image",
        "at",
        "anchor",
        "scale",
        "ascent",
        "height",
        "restore",
        "filter",
        "versions",
    )
    private val TTF_KEYS = setOf(
        "file",
        "size",
        "oversample",
        "shift",
        "skip",
        "filter",
        "versions",
    )
    private val FILTER_KEYS = setOf("uniform", "jp")
    private val ID = Regex("[a-z0-9._-]+")
    private val FONT_ID = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")

    fun read(file: Path): Spec {
        if (!Files.isRegularFile(file)) throw SpecException("No such spec file: $file")
        val root = load(file)
        unknownKeys(root.keys, TOP_LEVEL)?.let { key ->
            throw SpecException("$file has a key this tool does not know: '$key'. Known keys: ${TOP_LEVEL.sorted().joinToString(", ")}.")
        }

        val namespace = string(root, "namespace")
            ?: throw SpecException("$file is missing 'namespace', which decides where the textures live")
        if (!ID.matches(namespace)) {
            throw SpecException("namespace '$namespace' is not a valid resource namespace (lowercase letters, digits, . _ -)")
        }

        val font = string(root, "font") ?: "$namespace:ui"
        if (!FONT_ID.matches(font)) throw SpecException("font '$font' must look like 'namespace:path'")

        val versions = string(root, "versions") ?: PackFormats.newestRelease
        val (min, max) = try {
            PackFormats.parseRange(versions)
        } catch (failure: IllegalArgumentException) {
            throw SpecException("versions: ${failure.message}")
        }

        val startCodepoint = string(root, "codepoints")?.let { text ->
            try {
                Codepoints.parse(text)
            } catch (failure: IllegalArgumentException) {
                throw SpecException("codepoints: ${failure.message}")
            }
        } ?: Codepoints.SUPPLEMENTARY_PRIVATE_USE_FIRST

        val shifts = int(root, "shifts") ?: 1024
        if (shifts < 0) throw SpecException("shifts cannot be negative; use 0 to leave the shift glyphs out")
        val compactShifts = boolean(root, "compact_shifts") ?: false

        val directory = file.toAbsolutePath().normalize().parent

        val glyphsNode = root["glyphs"]
        val ttfNode = root["ttf"]
        if (glyphsNode == null && ttfNode == null) {
            throw SpecException("$file has no 'glyphs' or 'ttf' section, so there is nothing to build")
        }

        val glyphs = when (glyphsNode) {
            null -> emptyList()
            is Map<*, *> -> {
                if (glyphsNode.isEmpty()) {
                    throw SpecException("'glyphs' must be a mapping of glyph id to its settings, and cannot be empty")
                }
                glyphsNode.entries.map { (rawId, rawGlyph) -> glyph(rawId.toString(), rawGlyph, directory, min, max) }
            }
            else -> throw SpecException("'glyphs' must be a mapping of glyph id to its settings, and cannot be empty")
        }
        glyphs.groupBy { it.id }.forEach { (id, entries) ->
            if (entries.size > 1) throw SpecException("glyph id '$id' is declared ${entries.size} times")
        }

        val ttfProviders = when (ttfNode) {
            null -> emptyList()
            is Map<*, *> -> {
                if (ttfNode.isEmpty()) {
                    throw SpecException("'ttf' must be a non-empty mapping of provider id to its settings")
                }
                ttfNode.entries.map { (rawId, rawTtf) -> ttfProvider(rawId.toString(), rawTtf, directory, min, max) }
            }
            else -> throw SpecException("'ttf' must be a mapping of provider id to its settings")
        }
        ttfProviders.groupBy { it.id }.forEach { (id, entries) ->
            if (entries.size > 1) throw SpecException("ttf provider id '$id' is declared ${entries.size} times")
        }

        return Spec(
            namespace = namespace,
            font = font,
            description = string(root, "description") ?: "$namespace glyphs",
            minFormat = min,
            maxFormat = max,
            startCodepoint = startCodepoint,
            shiftRange = shifts,
            compactShifts = compactShifts,
            glyphs = glyphs,
            ttfProviders = ttfProviders,
            source = file.toAbsolutePath().normalize(),
        )
    }

    private fun glyph(
        id: String,
        node: Any?,
        directory: Path,
        packMin: PackFormat,
        packMax: PackFormat,
    ): GlyphSpec {
        if (!ID.matches(id)) {
            throw SpecException("glyph id '$id' is invalid (use lowercase letters, digits, . _ -)")
        }
        if (node !is Map<*, *>) throw SpecException("glyph '$id' must be a mapping with at least an 'image'")
        val keys = node.keys.map { it.toString() }.toSet()
        unknownKeys(keys, GLYPH_KEYS)?.let { key ->
            throw SpecException("glyph '$id' has a key this tool does not know: '$key'. Known keys: ${GLYPH_KEYS.sorted().joinToString(", ")}.")
        }
        val map = node.entries.associate { (key, value) -> key.toString() to value }

        val image = string(map, "image")
            ?: throw SpecException("glyph '$id' is missing 'image'")
        val anchor = string(map, "anchor")?.let { name ->
            try {
                Anchor.of(name)
            } catch (failure: IllegalArgumentException) {
                throw SpecException("glyph '$id': ${failure.message}")
            }
        } ?: Anchor.TEXT

        val at = map["at"]
        val placement = when (at) {
            null -> null
            is List<*> -> {
                if (at.size != 2) throw SpecException("glyph '$id': 'at' takes two numbers, [x, y]")
                val x = (at[0] as? Number)?.toInt() ?: throw SpecException("glyph '$id': 'at' x is not a number")
                val y = (at[1] as? Number)?.toInt() ?: throw SpecException("glyph '$id': 'at' y is not a number")
                anchor.toPlacement(x, y)
            }
            else -> throw SpecException("glyph '$id': 'at' takes two numbers, [x, y]")
        }

        val scale = int(map, "scale") ?: 1
        if (scale < 1) throw SpecException("glyph '$id': scale must be at least 1")
        val ascent = int(map, "ascent")
        val height = int(map, "height")
        if (placement != null && ascent != null) {
            throw SpecException("glyph '$id' sets both 'at' and 'ascent'; 'at' is what derives the ascent, so pick one")
        }
        if (ascent != null && height == null) {
            throw SpecException("glyph '$id' sets 'ascent' without 'height'; the client rejects a font whose ascent is higher than its height, so state both")
        }

        val filter = parseFilter("glyph '$id'", map["filter"])
        val targetRange = parseTargetRange("glyph '$id'", string(map, "versions"), packMin, packMax)

        return GlyphSpec(
            id = id,
            image = directory.resolve(image).normalize(),
            anchor = anchor,
            placement = placement,
            scale = scale,
            ascent = ascent,
            height = height,
            restoreCursor = boolean(map, "restore") ?: (placement != null),
            filter = filter,
            targetRange = targetRange,
        )
    }

    private fun ttfProvider(
        id: String,
        node: Any?,
        directory: Path,
        packMin: PackFormat,
        packMax: PackFormat,
    ): TtfSpec {
        if (!ID.matches(id)) {
            throw SpecException("ttf provider id '$id' is invalid (use lowercase letters, digits, . _ -)")
        }
        if (node !is Map<*, *>) throw SpecException("ttf '$id' must be a mapping with at least 'file'")
        val keys = node.keys.map { it.toString() }.toSet()
        unknownKeys(keys, TTF_KEYS)?.let { key ->
            throw SpecException("ttf '$id' has a key this tool does not know: '$key'. Known keys: ${TTF_KEYS.sorted().joinToString(", ")}.")
        }
        val map = node.entries.associate { (key, value) -> key.toString() to value }

        val file = string(map, "file")
            ?: throw SpecException("ttf '$id' is missing 'file'")
        val size = double(map, "size") ?: 11.0
        if (size <= 0.0 || !size.isFinite()) {
            throw SpecException("ttf '$id': 'size' must be greater than 0, got $size")
        }
        val oversample = double(map, "oversample") ?: 1.0
        if (oversample <= 0.0 || !oversample.isFinite()) {
            throw SpecException("ttf '$id': 'oversample' must be greater than 0, got $oversample")
        }

        val shift = when (val rawShift = map["shift"]) {
            null -> 0.0 to 0.0
            is List<*> -> {
                if (rawShift.size != 2) throw SpecException("ttf '$id': 'shift' takes two numbers, [x, y]")
                val sx = (rawShift[0] as? Number)?.toDouble() ?: throw SpecException("ttf '$id': 'shift' x is not a number")
                val sy = (rawShift[1] as? Number)?.toDouble() ?: throw SpecException("ttf '$id': 'shift' y is not a number")
                sx to sy
            }
            else -> throw SpecException("ttf '$id': 'shift' takes two numbers, [x, y]")
        }

        val skip = when (val rawSkip = map["skip"]) {
            null -> emptyList()
            is String -> listOf(rawSkip)
            is List<*> -> rawSkip.mapIndexed { idx, item ->
                item?.toString() ?: throw SpecException("ttf '$id': 'skip[$idx]' cannot be null")
            }
            else -> throw SpecException("ttf '$id': 'skip' must be a string or list of strings")
        }

        val filter = parseFilter("ttf '$id'", map["filter"])
        val targetRange = parseTargetRange("ttf '$id'", string(map, "versions"), packMin, packMax)

        return TtfSpec(
            id = id,
            file = directory.resolve(file).normalize(),
            size = size,
            oversample = oversample,
            shift = shift,
            skip = skip,
            filter = filter,
            targetRange = targetRange,
        )
    }

    private fun parseFilter(owner: String, raw: Any?): ProviderFilter? {
        if (raw == null) return null
        if (raw !is Map<*, *>) {
            throw SpecException("$owner: 'filter' must be a mapping of vanilla FontOption booleans (uniform, jp)")
        }
        val keys = raw.keys.map { it.toString() }.toSet()
        unknownKeys(keys, FILTER_KEYS)?.let { badKey ->
            throw SpecException("$owner: unknown filter option '$badKey'. Vanilla accepts only: jp, uniform.")
        }
        val map = raw.entries.associate { (k, v) -> k.toString() to v }
        val uniform = boolean(map, "uniform")
        val jp = boolean(map, "jp")
        return ProviderFilter(uniform = uniform, jp = jp).takeUnless { it.isEmpty }
    }

    private fun parseTargetRange(
        owner: String,
        rawVersions: String?,
        packMin: PackFormat,
        packMax: PackFormat,
    ): Pair<PackFormat, PackFormat>? {
        if (rawVersions == null) return null
        val (min, max) = try {
            PackFormats.parseRange(rawVersions)
        } catch (failure: IllegalArgumentException) {
            throw SpecException("$owner versions: ${failure.message}")
        }
        if (PackFormat.exceedsRange(min, max)) {
            throw SpecException("$owner versions: min_format ($min) is greater than max_format ($max)")
        }
        if (min.major < packMin.major || max.major > packMax.major) {
            throw SpecException(
                "$owner versions ($min..$max) falls outside the pack's root versions ($packMin..$packMax)",
            )
        }
        return if (min == packMin && max == packMax) null else (min to max)
    }

    private fun load(file: Path): Map<String, Any?> {
        val options = LoaderOptions().apply {
            isAllowDuplicateKeys = false
            codePointLimit = 4 * 1024 * 1024
        }
        val parsed = try {
            Files.newBufferedReader(file).use { reader -> Yaml(SafeConstructor(options)).load<Any?>(reader) }
        } catch (failure: Exception) {
            throw SpecException("$file is not valid YAML: ${failure.message?.lineSequence()?.firstOrNull() ?: failure}")
        }
        if (parsed == null) throw SpecException("$file is empty")
        if (parsed !is Map<*, *>) throw SpecException("$file must be a mapping at the top level")
        return parsed.entries.associate { (key, value) -> key.toString() to value }
    }

    private fun unknownKeys(present: Collection<*>, known: Set<String>): String? =
        present.map { it.toString() }.firstOrNull { key -> key !in known }

    private fun string(map: Map<String, Any?>, key: String): String? = map[key]?.toString()?.trim()?.ifEmpty { null }

    private fun int(map: Map<String, Any?>, key: String): Int? = when (val value = map[key]) {
        null -> null
        is Number -> value.toInt()
        else -> throw SpecException("'$key' must be a number, got '$value'")
    }

    private fun double(map: Map<String, Any?>, key: String): Double? = when (val value = map[key]) {
        null -> null
        is Number -> value.toDouble()
        else -> throw SpecException("'$key' must be a number, got '$value'")
    }

    private fun boolean(map: Map<String, Any?>, key: String): Boolean? = when (val value = map[key]) {
        null -> null
        is Boolean -> value
        else -> throw SpecException("'$key' must be true or false, got '$value'")
    }
}
