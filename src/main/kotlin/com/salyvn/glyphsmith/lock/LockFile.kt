package com.salyvn.glyphsmith.lock

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/** What a glyph resolved to last time, kept so a rebuild can be compared against it. */
@Serializable
data class LockedGlyph(
    val codepoints: List<Int>,
    val sha256: String,
    val height: Int,
    val ascent: Int,
    val advances: List<Int>,
)

/**
 * The record of which codepoint belongs to which id.
 *
 * This file is the reason a rebuild is safe. A glyph's codepoint ends up in item lore, on signs, in other
 * plugins' configs and in screenshots; renumbering it because a spec entry moved would silently replace
 * every existing use with a different picture. So the lock is committed alongside the spec, read before
 * anything is allocated, and only ever grows.
 */
@Serializable
data class LockFile(
    val lockVersion: Int = 1,
    val font: String,
    val codepoints: Map<String, Int> = emptyMap(),
    val glyphs: Map<String, LockedGlyph> = emptyMap(),
) {
    fun write(target: Path) {
        Files.createDirectories(target.toAbsolutePath().parent)
        Files.writeString(target, FORMAT.encodeToString(this) + "\n")
    }

    companion object {
        private val FORMAT = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            encodeDefaults = true
        }

        fun read(source: Path): LockFile? {
            if (!Files.isRegularFile(source)) return null
            val text = Files.readString(source)
            return try {
                FORMAT.decodeFromString<LockFile>(text)
            } catch (failure: Exception) {
                throw IllegalStateException(
                    "$source is not a lock file this version understands (${failure.message}). " +
                        "Delete it only if no server is using the codepoints it recorded.",
                )
            }
        }
    }
}
