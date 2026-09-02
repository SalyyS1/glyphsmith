package com.salyvn.glyphsmith.image

import java.awt.image.BufferedImage
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** A source texture, normalised to ARGB so alpha can be read the way the client reads it. */
class Texture(val image: BufferedImage, val hadAlphaChannel: Boolean) {
    val width: Int get() = image.width
    val height: Int get() = image.height

    /**
     * One past the rightmost column with any ink, or 0 for a cell with none — the client's
     * `getActualGlyphWidth`. It tests `getLuminanceOrAlpha`, and since the image is read as RGBA that
     * is the alpha channel, so a texture saved without one is opaque everywhere and every glyph
     * advances the full cell width.
     */
    fun inkWidth(x: Int, y: Int, cellWidth: Int, cellHeight: Int): Int {
        for (column in cellWidth - 1 downTo 0) {
            for (row in 0 until cellHeight) {
                if (image.getRGB(x + column, y + row) ushr 24 != 0) return column + 1
            }
        }
        return 0
    }

    /** The lowest row with ink, used to report how much of a padded cell is actually visible. */
    fun inkHeight(): Int {
        for (row in height - 1 downTo 0) {
            for (column in 0 until width) {
                if (image.getRGB(column, row) ushr 24 != 0) return row + 1
            }
        }
        return 0
    }

    /** A copy with transparent rows appended below and columns appended to the right. */
    fun grown(extraRows: Int, extraColumns: Int): Texture {
        if (extraRows == 0 && extraColumns == 0) return this
        val grown = BufferedImage(width + extraColumns, height + extraRows, BufferedImage.TYPE_INT_ARGB)
        val graphics = grown.createGraphics()
        try {
            graphics.drawImage(image, 0, 0, null)
        } finally {
            graphics.dispose()
        }
        return Texture(grown, hadAlphaChannel)
    }

    /** A single column band, for splitting one wide source into several cells. */
    fun column(x: Int, columnWidth: Int): Texture =
        Texture(image.getSubimage(x, 0, columnWidth, height).let { region ->
            val copy = BufferedImage(columnWidth, height, BufferedImage.TYPE_INT_ARGB)
            val graphics = copy.createGraphics()
            try {
                graphics.drawImage(region, 0, 0, null)
            } finally {
                graphics.dispose()
            }
            copy
        }, hadAlphaChannel)

    fun write(target: Path) {
        Files.createDirectories(target.parent)
        Files.newOutputStream(target).use { out ->
            if (!ImageIO.write(image, "png", out)) throw IOException("No PNG writer is available in this JVM")
        }
    }

    companion object {
        fun read(source: Path): Texture {
            if (!Files.isRegularFile(source)) throw IOException("No such image: $source")
            val decoded = Files.newInputStream(source).use { ImageIO.read(it) }
                ?: throw IOException("$source is not an image this JVM can read (PNG is expected)")
            val hadAlpha = decoded.colorModel.hasAlpha()
            if (decoded.type == BufferedImage.TYPE_INT_ARGB) return Texture(decoded, hadAlpha)
            val converted = BufferedImage(decoded.width, decoded.height, BufferedImage.TYPE_INT_ARGB)
            val graphics = converted.createGraphics()
            try {
                graphics.drawImage(decoded, 0, 0, null)
            } finally {
                graphics.dispose()
            }
            return Texture(converted, hadAlpha)
        }

        /** An in-memory texture, used by `init` to write a runnable example without shipping a binary. */
        fun blank(width: Int, height: Int): Texture =
            Texture(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), true)
    }
}
