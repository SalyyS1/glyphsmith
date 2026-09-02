package com.salyvn.glyphsmith.pack

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a pack directory, and optionally zips it.
 *
 * Output is byte-stable for the same input: files are visited in sorted order and zip entries are given a
 * fixed timestamp, because a pack that changes on every build defeats every CDN and hash check between
 * here and the player's client.
 */
object PackWriter {
    /** A DOS timestamp of 1980-01-01, the earliest a zip entry can carry, chosen because it never moves. */
    private const val FIXED_TIME = 315532800000L

    fun writeText(root: Path, relative: String, content: String): Path {
        val target = root.resolve(relative)
        Files.createDirectories(target.parent)
        Files.writeString(target, content)
        return target
    }

    fun zip(root: Path, target: Path) {
        Files.createDirectories(target.toAbsolutePath().parent)
        val entries = Files.walk(root).use { stream ->
            stream.filter { path -> Files.isRegularFile(path) }
                .map { path -> root.relativize(path) }
                .sorted()
                .toList()
        }
        ZipOutputStream(Files.newOutputStream(target)).use { out ->
            entries.forEach { relative ->
                val entry = ZipEntry(relative.joinToString("/") { part -> part.toString() })
                entry.time = FIXED_TIME
                out.putNextEntry(entry)
                Files.newInputStream(root.resolve(relative)).use { input -> input.copyTo(out) }
                out.closeEntry()
            }
        }
    }
}
