package com.salyvn.glyphsmith.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.glyphsmith.core.PackFormats
import com.salyvn.glyphsmith.image.DemoArt
import com.salyvn.glyphsmith.spec.SpecException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Writes a spec that already builds, along with the two images it refers to.
 *
 * Starting from a file that works is a better introduction than a reference table, and the artwork is
 * drawn rather than downloaded so this works offline and adds no binaries to your repository.
 */
class InitCommand : GlyphsmithCommand(name = "init") {

    override fun help(context: Context) = "Write an example spec, with artwork, that builds as it stands."

    private val file by argument("file", help = "Where to write the spec").path().default(Path.of("glyphs.yml"))

    private val force by option("--force", help = "Overwrite files that are already there").flag()

    override fun execute() {
        val directory = file.toAbsolutePath().normalize().parent
        val art = directory.resolve("art")
        val panel = art.resolve("ui.panel.png")
        val coin = art.resolve("hud.coin.png")

        listOf(file, panel, coin).firstOrNull { path -> path.exists() && !force }?.let { existing ->
            throw SpecException("$existing already exists. Pass --force to overwrite it.")
        }

        Files.createDirectories(art)
        DemoArt.panel().write(panel)
        DemoArt.coin().write(coin)
        Files.createDirectories(directory)
        Files.writeString(file, TEMPLATE)

        echo("Wrote $file, ${directory.relativize(panel)} and ${directory.relativize(coin)}")
        echo("Build it with: glyphsmith build ${file.fileName}")
    }

    private companion object {
        val TEMPLATE = """
            # A spec says where the pixels should land. glyphsmith works out the numbers the game needs:
            # the provider height, the ascent, how much transparent padding the artwork needs so that
            # ascent is legal, the advance the client will derive, and the shifts that place it.
            #
            # Build it with: glyphsmith build glyphs.yml
            namespace: myserver
            font: myserver:ui
            description: myserver interface glyphs

            # The pack.mcmeta is written to the rules each of these versions enforces.
            versions: 1.20.1..${PackFormats.newestRelease}

            # Where codepoints start. Plane 15 is a private use area that no custom-item plugin allocates
            # from, so a pack built here can be merged with one of theirs without either overwriting the
            # other.
            codepoints: U+F0000

            # Largest single shift step. Powers of two up to this are generated in both directions, which
            # is enough to express any whole-pixel offset in a handful of characters.
            shifts: 1024

            glyphs:
              # A panel drawn behind a container's item grid. The coordinates are the window's own
              # top-left corner, so this sits 8 pixels left and 6 above where the title is drawn — the
              # conversion nobody should be doing by restarting the client.
              ui.panel:
                image: art/ui.panel.png
                anchor: container-title
                at: [0, 0]

              # An icon that sits in a line of text and pushes the text along, so no shifts and no
              # placement: state the two numbers directly.
              hud.coin:
                image: art/hud.coin.png
                height: 9
                ascent: 8

        """.trimIndent()
    }
}
