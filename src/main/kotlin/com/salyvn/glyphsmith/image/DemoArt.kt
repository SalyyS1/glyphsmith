package com.salyvn.glyphsmith.image

import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/**
 * The artwork `init` writes so that the example spec builds on a clean checkout.
 *
 * Drawing it rather than shipping two PNGs keeps the repository free of binaries and makes the example
 * honest: these are the shapes the numbers in the spec were chosen for.
 */
object DemoArt {
    /** A container-sized panel, 176px wide like a chest window, with a border and a soft inner fill. */
    fun panel(width: Int = 176, height: Int = 64): Texture {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
            graphics.color = Color(24, 24, 32, 220)
            graphics.fillRect(0, 0, width, height)
            graphics.color = Color(92, 92, 120, 255)
            graphics.stroke = BasicStroke(1f)
            graphics.drawRect(0, 0, width - 1, height - 1)
            graphics.color = Color(52, 52, 70, 255)
            graphics.drawRect(2, 2, width - 5, height - 5)
            graphics.color = Color(140, 140, 180, 90)
            graphics.fillRect(3, 3, width - 6, 6)
        } finally {
            graphics.dispose()
        }
        return Texture(image, true)
    }

    /** A nine pixel coin, the size of an icon that sits inline in a line of text. */
    fun coin(size: Int = 9): Texture {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
            graphics.color = Color(214, 168, 46, 255)
            graphics.fillOval(0, 0, size - 1, size - 1)
            graphics.color = Color(255, 226, 138, 255)
            graphics.fillOval(2, 2, size - 6, size - 6)
        } finally {
            graphics.dispose()
        }
        return Texture(image, true)
    }
}
