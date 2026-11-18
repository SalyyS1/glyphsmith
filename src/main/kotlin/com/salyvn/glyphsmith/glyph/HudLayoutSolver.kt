package com.salyvn.glyphsmith.glyph

import com.salyvn.glyphsmith.codepoint.CodepointAllocator
import com.salyvn.glyphsmith.codepoint.Codepoints
import com.salyvn.glyphsmith.image.Texture
import com.salyvn.glyphsmith.lock.LockFile
import com.salyvn.glyphsmith.pack.Shifts
import com.salyvn.glyphsmith.spec.SpecException
import com.salyvn.glyphsmith.text.Snippet
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.max

enum class HudContext(
    val id: String,
    val centered: Boolean,
    val originX: Int,
    val originY: Int,
    val safeMinX: Int,
    val safeMaxX: Int,
    val safeMinY: Int,
    val safeMaxY: Int,
    val note: String,
) {
    ACTIONBAR(
        id = "actionbar",
        centered = true,
        originX = 0,
        originY = 0,
        safeMinX = -160,
        safeMaxX = 160,
        safeMinY = -32,
        safeMaxY = 22,
        note = "Centered actionbar overlay above hotbar (Gui.renderOverlayMessage)",
    ),
    BOSSBAR(
        id = "bossbar",
        centered = true,
        originX = 0,
        originY = -9,
        safeMinX = -160,
        safeMaxX = 160,
        safeMinY = -12,
        safeMaxY = 28,
        note = "Centered bossbar overlay relative to 182x5 bar center-top (title drawn 9px above bar)",
    ),
    CONTAINER_TITLE(
        id = "container-title",
        centered = false,
        originX = 8,
        originY = 6,
        safeMinX = -32,
        safeMaxX = 208,
        safeMinY = -20,
        safeMaxY = 222,
        note = "Container GUI window top-left corner (titleLabel at 8, 6)",
    ),
    TITLE(
        id = "title",
        centered = true,
        originX = 0,
        originY = 0,
        safeMinX = -160,
        safeMaxX = 160,
        safeMinY = -64,
        safeMaxY = 64,
        note = "Centered screen title overlay (4.0x matrix scale)",
    ),
    SUBTITLE(
        id = "subtitle",
        centered = true,
        originX = 0,
        originY = 0,
        safeMinX = -160,
        safeMaxX = 160,
        safeMinY = -64,
        safeMaxY = 64,
        note = "Centered screen subtitle overlay (2.0x matrix scale)",
    ),
    CHAT(
        id = "chat",
        centered = false,
        originX = 0,
        originY = 0,
        safeMinX = 0,
        safeMaxX = 320,
        safeMinY = -16,
        safeMaxY = 16,
        note = "Left-aligned chat line origin",
    ),
    SCOREBOARD(
        id = "scoreboard",
        centered = false,
        originX = 0,
        originY = 0,
        safeMinX = -200,
        safeMaxX = 200,
        safeMinY = -16,
        safeMaxY = 16,
        note = "Sidebar scoreboard entry origin",
    ),
    ;

    companion object {
        fun of(id: String): HudContext = entries.firstOrNull { it.id == id.trim().lowercase() }
            ?: throw IllegalArgumentException(
                "Unknown HUD context '$id'. Known contexts: ${entries.joinToString(", ") { it.id }}.",
            )
    }
}

data class HudLayerInput(
    val id: String,
    val x: Int,
    val y: Int,
    val widthPx: Int,
    val heightPx: Int,
    val advancePx: Int? = null,
    val codepoints: List<Int>? = null,
    val zIndex: Int = 0,
)

data class HudLayoutSpec(
    val namespace: String = "myserver",
    val font: String = "myserver:hud",
    val context: HudContext = HudContext.ACTIONBAR,
    val shiftRange: Int = 1024,
    val startCodepoint: Int = 0xF0000,
    val compactShifts: Boolean = true,
    val balanceCursor: Boolean = true,
    val targetNetAdvancePx: Int = 0,
    val optimizeOrder: Boolean = true,
    val layers: List<HudLayerInput>,
)

@Serializable
data class SolvedHudLayer(
    val id: String,
    val zIndex: Int,
    val targetX: Int,
    val targetY: Int,
    val textOriginX: Int,
    val textOriginY: Int,
    val widthPx: Int,
    val heightPx: Int,
    val solvedHeight: Int,
    val solvedAscent: Int,
    val padBottomPx: Int,
    val vanillaAdvancePx: Int,
    val codepoints: List<String>,
    val preShiftPx: Int,
    val preShiftSteps: List<Int>,
    val greedyShiftGlyphs: Int,
    val nafShiftGlyphs: Int,
    val cursorBeforePx: Int,
    val cursorAfterPx: Int,
)

@Serializable
data class HudLayoutWarning(
    val code: String,
    val layerId: String? = null,
    val message: String,
)

@Serializable
data class SolvedHudLayout(
    val font: String,
    val context: String,
    val centeredContext: Boolean,
    val compactShifts: Boolean,
    val balanceCursor: Boolean,
    val targetNetAdvancePx: Int,
    val rawUnbalancedAdvancePx: Int,
    val unbalancedCenterDriftPx: Int,
    val tailShiftPx: Int,
    val tailShiftSteps: List<Int>,
    val finalNetAdvancePx: Int,
    val reorderedLayers: Boolean,
    val totalGlyphCodepoints: Int,
    val totalShiftGlyphsGreedy: Int,
    val totalShiftGlyphsNaf: Int,
    val utf16Length: Int,
    val utf8Bytes: Int,
    val layers: List<SolvedHudLayer>,
    val warnings: List<HudLayoutWarning>,
    val snippets: Map<String, String>,
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
 * Multi-layer Minecraft 26.3 composite HUD, bossbar, actionbar, and container negative-space layout solver.
 *
 * Solves exact inter-layer cursor deltas, enforces zero-width cursor balancing on centered overlays so
 * `Gui.renderOverlayMessage` and `BossHealthOverlay` never drift horizontally when text changes, and uses
 * precedence-constrained Held-Karp dynamic programming (respecting z-index and 2D painter's occlusion)
 * paired with signed-digit NAF shift encoding to minimize packet character count.
 */
object HudLayoutSolver {
    private const val MAX_HELD_KARP_LAYERS = 14
    private const val PACKET_SAFE_UTF16_LIMIT = 32_767

    private data class PreparedLayer(
        val declaredIndex: Int,
        val input: HudLayerInput,
        val relX: Int,
        val relY: Int,
        val solvedHeight: Int,
        val solvedAscent: Int,
        val padBottom: Int,
        val advancePx: Int,
        val codepoints: List<Int>,
    )

    fun solve(spec: HudLayoutSpec, lockFile: LockFile? = null): SolvedHudLayout {
        require(spec.layers.isNotEmpty()) { "At least one HUD layer is required." }
        require(spec.shiftRange >= 1) { "shiftRange must be at least 1, got ${spec.shiftRange}." }

        val allocator = CodepointAllocator(spec.startCodepoint, lockFile?.codepoints ?: emptyMap())
        val shifts = Shifts.build(spec.shiftRange, allocator::allocate)
        val availablePowers = Shifts.powersOfTwo(spec.shiftRange)

        val warnings = mutableListOf<HudLayoutWarning>()
        val seenIds = HashSet<String>()

        val prepared = spec.layers.mapIndexed { idx, layer ->
            if (!seenIds.add(layer.id)) {
                throw SpecException("Duplicate HUD layer id '${layer.id}'.")
            }
            if (layer.widthPx <= 0 || layer.heightPx <= 0) {
                throw GlyphSolveException(
                    "Layer '${layer.id}' must have positive dimensions, got ${layer.widthPx}x${layer.heightPx}px.",
                )
            }
            if (layer.heightPx > GlyphMath.MAX_CELL) {
                throw GlyphSolveException(
                    "Layer '${layer.id}' is ${layer.heightPx}px tall, exceeding the ${GlyphMath.MAX_CELL}px vanilla FontTexture atlas limit.",
                )
            }

            val relX = layer.x - spec.context.originX
            val relY = layer.y - spec.context.originY
            val solvedAscent = GlyphMath.ascentFor(relY)
            if (solvedAscent > GlyphMath.MAX_CELL) {
                throw GlyphSolveException(
                    "Layer '${layer.id}' at y=${layer.y} requires ascent=$solvedAscent, exceeding the ${GlyphMath.MAX_CELL}px vanilla FontTexture atlas limit.",
                )
            }

            val solvedHeight = max(layer.heightPx, solvedAscent)
            val padBottom = max(0, solvedAscent - layer.heightPx)
            val cols = ((layer.widthPx + GlyphMath.MAX_CELL - 1) / GlyphMath.MAX_CELL).coerceAtLeast(1)

            val resolvedCodepoints = layer.codepoints
                ?: lockFile?.glyphs?.get(layer.id)?.codepoints
                ?: allocator.allocateRun(layer.id, cols)

            val resolvedAdvance = layer.advancePx
                ?: lockFile?.glyphs?.get(layer.id)?.advances?.sum()
                ?: (layer.widthPx + cols)

            if (layer.x < spec.context.safeMinX || (layer.x + layer.widthPx) > spec.context.safeMaxX) {
                warnings += HudLayoutWarning(
                    code = "HUD_VIEWPORT_HORIZONTAL_CLIP",
                    layerId = layer.id,
                    message = "Layer '${layer.id}' spans x=[${layer.x}, ${layer.x + layer.widthPx}]px outside safe ${spec.context.id} horizontal bounds [${spec.context.safeMinX}, ${spec.context.safeMaxX}]px at minimum 320px GUI width.",
                )
            }
            if (layer.y < spec.context.safeMinY || (layer.y + layer.heightPx) > spec.context.safeMaxY) {
                warnings += HudLayoutWarning(
                    code = "HUD_VIEWPORT_VERTICAL_BAND",
                    layerId = layer.id,
                    message = "Layer '${layer.id}' spans y=[${layer.y}, ${layer.y + layer.heightPx}]px outside standard ${spec.context.id} vertical band [${spec.context.safeMinY}, ${spec.context.safeMaxY}]px.",
                )
            }

            PreparedLayer(
                declaredIndex = idx,
                input = layer,
                relX = relX,
                relY = relY,
                solvedHeight = solvedHeight,
                solvedAscent = solvedAscent,
                padBottom = padBottom,
                advancePx = resolvedAdvance,
                codepoints = resolvedCodepoints,
            )
        }

        val ordered = if (spec.optimizeOrder && prepared.size > 1) {
            scheduleOptimalOrder(
                layers = prepared,
                availablePowers = availablePowers,
                compactShifts = spec.compactShifts,
                balanceCursor = spec.balanceCursor,
                targetNetAdvance = spec.targetNetAdvancePx,
            )
        } else {
            prepared.sortedWith(compareBy<PreparedLayer> { it.input.zIndex }.thenBy { it.declaredIndex })
        }

        val reordered = ordered.map { it.declaredIndex } != prepared.indices.toList()

        val rawBuilder = StringBuilder()
        var cursorX = 0
        var totalGreedyShifts = 0
        var totalNafShifts = 0
        var totalGlyphCount = 0

        val solvedLayers = ordered.map { p ->
            val cursorBefore = cursorX
            val preShift = p.relX - cursorBefore
            val nafSteps = if (preShift == 0) emptyList() else KerningOptimizer.minimalShiftSteps(preShift, availablePowers)
            val greedyCount = shifts.cost(preShift)
            val nafCount = nafSteps.size

            totalGreedyShifts += greedyCount
            totalNafShifts += nafCount
            totalGlyphCount += p.codepoints.size

            rawBuilder.append(shifts.string(preShift, compact = spec.compactShifts))
            p.codepoints.forEach { cp -> rawBuilder.append(Codepoints.raw(cp)) }

            val cursorAfter = p.relX + p.advancePx
            cursorX = cursorAfter

            SolvedHudLayer(
                id = p.input.id,
                zIndex = p.input.zIndex,
                targetX = p.input.x,
                targetY = p.input.y,
                textOriginX = p.relX,
                textOriginY = p.relY,
                widthPx = p.input.widthPx,
                heightPx = p.input.heightPx,
                solvedHeight = p.solvedHeight,
                solvedAscent = p.solvedAscent,
                padBottomPx = p.padBottom,
                vanillaAdvancePx = p.advancePx,
                codepoints = p.codepoints.map { Codepoints.describe(it) },
                preShiftPx = preShift,
                preShiftSteps = nafSteps,
                greedyShiftGlyphs = greedyCount,
                nafShiftGlyphs = nafCount,
                cursorBeforePx = cursorBefore,
                cursorAfterPx = cursorAfter,
            )
        }

        val rawUnbalanced = cursorX
        val unbalancedCenterDrift = if (spec.context.centered) -(rawUnbalanced / 2) else 0
        val tailShift = if (spec.balanceCursor) (spec.targetNetAdvancePx - cursorX) else 0
        val tailSteps = if (tailShift == 0) emptyList() else KerningOptimizer.minimalShiftSteps(tailShift, availablePowers)

        if (tailShift != 0) {
            totalGreedyShifts += shifts.cost(tailShift)
            totalNafShifts += tailSteps.size
            rawBuilder.append(shifts.string(tailShift, compact = spec.compactShifts))
        } else if (spec.context.centered && !spec.balanceCursor && rawUnbalanced != 0) {
            warnings += HudLayoutWarning(
                code = "HUD_CENTERED_UNBALANCED_DRIFT",
                layerId = null,
                message = "Context '${spec.context.id}' is center-aligned and cursor balancing is disabled; net advance ${rawUnbalanced}px shifts the entire HUD by ${unbalancedCenterDrift}px horizontally.",
            )
        }

        val finalNetAdvance = cursorX + tailShift
        val rawText = rawBuilder.toString()
        val utf16Len = rawText.length
        val utf8Bytes = rawText.toByteArray(StandardCharsets.UTF_8).size

        if (utf16Len > PACKET_SAFE_UTF16_LIMIT) {
            warnings += HudLayoutWarning(
                code = "HUD_PACKET_UTF16_EXCEEDED",
                layerId = null,
                message = "Composite HUD string uses $utf16Len UTF-16 code units, exceeding the $PACKET_SAFE_UTF16_LIMIT network component string limit.",
            )
        }

        val snippet = Snippet(rawText, spec.font)
        val snippets = linkedMapOf(
            "raw" to snippet.raw,
            "escape" to snippet.escaped,
            "minimessage" to snippet.miniMessage,
            "json" to snippet.component,
        )

        return SolvedHudLayout(
            font = spec.font,
            context = spec.context.id,
            centeredContext = spec.context.centered,
            compactShifts = spec.compactShifts,
            balanceCursor = spec.balanceCursor,
            targetNetAdvancePx = spec.targetNetAdvancePx,
            rawUnbalancedAdvancePx = rawUnbalanced,
            unbalancedCenterDriftPx = unbalancedCenterDrift,
            tailShiftPx = tailShift,
            tailShiftSteps = tailSteps,
            finalNetAdvancePx = finalNetAdvance,
            reorderedLayers = reordered,
            totalGlyphCodepoints = totalGlyphCount,
            totalShiftGlyphsGreedy = totalGreedyShifts,
            totalShiftGlyphsNaf = totalNafShifts,
            utf16Length = utf16Len,
            utf8Bytes = utf8Bytes,
            layers = solvedLayers,
            warnings = warnings,
            snippets = snippets,
        )
    }

    fun parseYamlSpec(specPath: Path): HudLayoutSpec {
        if (!Files.isRegularFile(specPath)) {
            throw SpecException("No such HUD layout file: $specPath")
        }
        val baseDir = specPath.toAbsolutePath().normalize().parent
        val loaderOptions = LoaderOptions().apply {
            isAllowDuplicateKeys = false
            maxAliasesForCollections = 32
        }
        val yaml = Yaml(SafeConstructor(loaderOptions))
        val root = yaml.load<Any?>(Files.readString(specPath)) as? Map<*, *>
            ?: throw SpecException("$specPath must contain a top-level YAML mapping.")

        val allowedRoot = setOf(
            "namespace",
            "font",
            "context",
            "shifts",
            "codepoints",
            "compact_shifts",
            "balance_cursor",
            "target_advance",
            "optimize_order",
            "layers",
        )
        for (k in root.keys) {
            val key = k.toString()
            if (key !in allowedRoot) {
                throw SpecException("Unknown key '$key' in $specPath. Allowed keys: ${allowedRoot.joinToString(", ")}.")
            }
        }

        val namespace = (root["namespace"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: "myserver"
        val font = (root["font"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: "$namespace:hud"
        val context = root["context"]?.toString()?.let { HudContext.of(it) } ?: HudContext.ACTIONBAR
        val shifts = (root["shifts"] as? Number)?.toInt() ?: 1024
        val startCp = root["codepoints"]?.toString()?.let { Codepoints.parse(it) } ?: 0xF0000
        val compactShifts = (root["compact_shifts"] as? Boolean) ?: true
        val balanceCursor = (root["balance_cursor"] as? Boolean) ?: true
        val targetAdvance = (root["target_advance"] as? Number)?.toInt() ?: 0
        val optimizeOrder = (root["optimize_order"] as? Boolean) ?: true

        val rawLayers = root["layers"] as? Map<*, *>
            ?: throw SpecException("$specPath must declare a non-empty 'layers:' mapping.")
        if (rawLayers.isEmpty()) {
            throw SpecException("$specPath declares an empty 'layers:' mapping.")
        }

        val allowedLayer = setOf("x", "y", "at", "width", "height", "size", "image", "advance", "z", "codepoint")
        val layers = rawLayers.entries.map { (rawId, rawVal) ->
            val id = rawId.toString().trim()
            val map = rawVal as? Map<*, *>
                ?: throw SpecException("Layer '$id' in $specPath must be a mapping.")
            for (lk in map.keys) {
                val key = lk.toString()
                if (key !in allowedLayer) {
                    throw SpecException("Unknown key '$key' in layer '$id'. Allowed: ${allowedLayer.joinToString(", ")}.")
                }
            }

            val atList = map["at"] as? List<*>
            val x = (map["x"] as? Number)?.toInt()
                ?: (atList?.getOrNull(0) as? Number)?.toInt()
                ?: throw SpecException("Layer '$id' must specify integer 'x' or 'at: [x, y]'.")
            val y = (map["y"] as? Number)?.toInt()
                ?: (atList?.getOrNull(1) as? Number)?.toInt()
                ?: throw SpecException("Layer '$id' must specify integer 'y' or 'at: [x, y]'.")

            val imageRel = (map["image"] as? String)?.trim()
            val sizeList = map["size"] as? List<*>
            val widthPx: Int
            val heightPx: Int
            val imageAdvance: Int?

            if (imageRel != null) {
                val imgPath = baseDir.resolve(imageRel).normalize()
                val tex = Texture.read(imgPath)
                val solved = Solver.solve(tex, Placement(x - context.originX, y - context.originY))
                widthPx = tex.width
                heightPx = tex.height
                imageAdvance = solved.totalAdvance
            } else {
                widthPx = (map["width"] as? Number)?.toInt()
                    ?: (sizeList?.getOrNull(0) as? Number)?.toInt()
                    ?: throw SpecException("Layer '$id' must specify 'width' (or 'size: [w, h]' or 'image').")
                heightPx = (map["height"] as? Number)?.toInt()
                    ?: (sizeList?.getOrNull(1) as? Number)?.toInt()
                    ?: throw SpecException("Layer '$id' must specify 'height' (or 'size: [w, h]' or 'image').")
                imageAdvance = null
            }

            val explicitAdvance = (map["advance"] as? Number)?.toInt() ?: imageAdvance
            val zIndex = (map["z"] as? Number)?.toInt() ?: 0
            val explicitCp = map["codepoint"]?.toString()?.let { listOf(Codepoints.parse(it)) }

            HudLayerInput(
                id = id,
                x = x,
                y = y,
                widthPx = widthPx,
                heightPx = heightPx,
                advancePx = explicitAdvance,
                codepoints = explicitCp,
                zIndex = zIndex,
            )
        }

        return HudLayoutSpec(
            namespace = namespace,
            font = font,
            context = context,
            shiftRange = shifts,
            startCodepoint = startCp,
            compactShifts = compactShifts,
            balanceCursor = balanceCursor,
            targetNetAdvancePx = targetAdvance,
            optimizeOrder = optimizeOrder,
            layers = layers,
        )
    }

    /**
     * Parses compact CLI inline layer syntax: `<id>:<width>x<height>@<x>,<y>[:z=<z>][:adv=<advance>]`
     * Example: `bar_bg:182x10@-91,-5` or `hp_fill:84x6@-89,-3:z=1`
     */
    fun parseInlineLayer(specText: String): HudLayerInput {
        val parts = specText.trim().split(':')
        if (parts.size < 2) {
            throw IllegalArgumentException(
                "Invalid --layer '$specText'. Expected '<id>:<W>x<H>@<X>,<Y>[:z=<Z>][:adv=<A>]' (e.g. bg:182x10@-91,-5).",
            )
        }
        val id = parts[0].trim()
        require(id.isNotEmpty()) { "Layer id in '$specText' must not be empty." }

        val geom = parts[1].trim()
        val atIdx = geom.indexOf('@')
        if (atIdx <= 0) {
            throw IllegalArgumentException(
                "Invalid geometry '${parts[1]}' in --layer '$specText'. Expected '<W>x<H>@<X>,<Y>'.",
            )
        }
        val sizePart = geom.substring(0, atIdx)
        val posPart = geom.substring(atIdx + 1)
        val wh = sizePart.lowercase().split('x')
        val xy = posPart.split(',')
        if (wh.size != 2 || xy.size != 2) {
            throw IllegalArgumentException("Invalid dimensions or coordinates in --layer '$specText'.")
        }
        val w = wh[0].toIntOrNull() ?: throw IllegalArgumentException("Invalid width '${wh[0]}' in '$specText'.")
        val h = wh[1].toIntOrNull() ?: throw IllegalArgumentException("Invalid height '${wh[1]}' in '$specText'.")
        val x = xy[0].toIntOrNull() ?: throw IllegalArgumentException("Invalid x '${xy[0]}' in '$specText'.")
        val y = xy[1].toIntOrNull() ?: throw IllegalArgumentException("Invalid y '${xy[1]}' in '$specText'.")

        var z = 0
        var adv: Int? = null
        for (extra in parts.drop(2)) {
            val kv = extra.split('=', limit = 2)
            if (kv.size != 2) throw IllegalArgumentException("Invalid modifier '$extra' in --layer '$specText'.")
            when (kv[0].trim().lowercase()) {
                "z" -> z = kv[1].toIntOrNull() ?: throw IllegalArgumentException("Invalid z '${kv[1]}' in '$specText'.")
                "adv", "advance" -> adv = kv[1].toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid advance '${kv[1]}' in '$specText'.")
                else -> throw IllegalArgumentException("Unknown modifier '${kv[0]}' in --layer '$specText'.")
            }
        }

        return HudLayerInput(id = id, x = x, y = y, widthPx = w, heightPx = h, advancePx = adv, zIndex = z)
    }

    private fun boundsOverlap(a: PreparedLayer, b: PreparedLayer): Boolean {
        val ax2 = a.relX + a.input.widthPx
        val bx2 = b.relX + b.input.widthPx
        val ay2 = a.relY + a.input.heightPx
        val by2 = b.relY + b.input.heightPx
        return a.relX < bx2 && ax2 > b.relX && a.relY < by2 && ay2 > b.relY
    }

    private fun shiftCost(
        deltaPx: Int,
        availablePowers: List<Int>,
        compactShifts: Boolean,
        costCache: HashMap<Int, Int>,
    ): Int {
        if (deltaPx == 0) return 0
        return costCache.getOrPut(deltaPx) {
            if (compactShifts) {
                KerningOptimizer.minimalShiftSteps(deltaPx, availablePowers).size
            } else {
                var rem = abs(deltaPx)
                var count = 0
                val desc = availablePowers.sortedDescending()
                for (s in desc) {
                    if (rem >= s) {
                        count += rem / s
                        rem %= s
                    }
                }
                count
            }
        }
    }

    private fun scheduleOptimalOrder(
        layers: List<PreparedLayer>,
        availablePowers: List<Int>,
        compactShifts: Boolean,
        balanceCursor: Boolean,
        targetNetAdvance: Int,
    ): List<PreparedLayer> {
        val n = layers.size
        val costCache = HashMap<Int, Int>()

        if (n <= MAX_HELD_KARP_LAYERS) {
            val prereq = IntArray(n)
            for (j in 0 until n) {
                var mask = 0
                for (i in 0 until n) {
                    if (i == j) continue
                    val zi = layers[i].input.zIndex
                    val zj = layers[j].input.zIndex
                    if (zi < zj || (zi == zj && i < j && boundsOverlap(layers[i], layers[j]))) {
                        mask = mask or (1 shl i)
                    }
                }
                prereq[j] = mask
            }

            val states = 1 shl n
            val infCost = 1_000_000_000L
            // Pack (shiftGlyphCount << 30) + totalAbsShiftPx into a single Long for exact lexicographical DP.
            val dp = LongArray(states * n) { infCost shl 30 }
            val parent = IntArray(states * n) { -1 }

            for (first in 0 until n) {
                if (prereq[first] == 0) {
                    val delta = layers[first].relX
                    val glyphs = shiftCost(delta, availablePowers, compactShifts, costCache).toLong()
                    val packed = (glyphs shl 30) + abs(delta).toLong()
                    dp[(1 shl first) * n + first] = packed
                }
            }

            for (mask in 1 until states) {
                for (last in 0 until n) {
                    val curVal = dp[mask * n + last]
                    if ((curVal ushr 30) >= infCost) continue
                    val cursor = layers[last].relX + layers[last].advancePx

                    for (next in 0 until n) {
                        if ((mask and (1 shl next)) != 0) continue
                        if ((mask and prereq[next]) != prereq[next]) continue

                        val nextMask = mask or (1 shl next)
                        val delta = layers[next].relX - cursor
                        val stepGlyphs = shiftCost(delta, availablePowers, compactShifts, costCache).toLong()
                        val cand = curVal + (stepGlyphs shl 30) + abs(delta).toLong()
                        val idx = nextMask * n + next
                        if (cand < dp[idx]) {
                            dp[idx] = cand
                            parent[idx] = last
                        }
                    }
                }
            }

            val fullMask = states - 1
            var bestLast = -1
            var bestTotal = infCost shl 30
            for (last in 0 until n) {
                val baseVal = dp[fullMask * n + last]
                if ((baseVal ushr 30) >= infCost) continue
                val cursor = layers[last].relX + layers[last].advancePx
                val tailDelta = if (balanceCursor) (targetNetAdvance - cursor) else 0
                val tailGlyphs = shiftCost(tailDelta, availablePowers, compactShifts, costCache).toLong()
                val totalVal = baseVal + (tailGlyphs shl 30) + abs(tailDelta).toLong()
                if (totalVal < bestTotal) {
                    bestTotal = totalVal
                    bestLast = last
                }
            }

            if (bestLast >= 0) {
                val seq = IntArray(n)
                var curMask = fullMask
                var curLast = bestLast
                for (pos in n - 1 downTo 0) {
                    seq[pos] = curLast
                    val prev = parent[curMask * n + curLast]
                    curMask = curMask xor (1 shl curLast)
                    curLast = prev
                }
                return seq.map { layers[it] }
            }
        }

        // Greedy topological fallback for large (>14) layer counts.
        val remaining = layers.toMutableList()
        val placed = HashSet<Int>()
        val result = ArrayList<PreparedLayer>(n)
        var cursor = 0

        while (remaining.isNotEmpty()) {
            val minZ = remaining.minOf { it.input.zIndex }
            val candidates = remaining.filter { cand ->
                cand.input.zIndex == minZ && remaining.none { other ->
                    other !== cand &&
                        other.input.zIndex == minZ &&
                        other.declaredIndex < cand.declaredIndex &&
                        boundsOverlap(other, cand)
                }
            }
            val chosen = candidates.minWith(
                compareBy<PreparedLayer> {
                    shiftCost(it.relX - cursor, availablePowers, compactShifts, costCache)
                }.thenBy { abs(it.relX - cursor) }
                    .thenBy { it.relX }
                    .thenBy { it.declaredIndex },
            )
            remaining.remove(chosen)
            placed.add(chosen.declaredIndex)
            result.add(chosen)
            cursor = chosen.relX + chosen.advancePx
        }
        return result
    }
}
