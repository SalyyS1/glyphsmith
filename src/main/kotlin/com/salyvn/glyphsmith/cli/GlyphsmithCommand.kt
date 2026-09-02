package com.salyvn.glyphsmith.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.salyvn.glyphsmith.glyph.GlyphSolveException
import com.salyvn.glyphsmith.pack.PackMetaException
import com.salyvn.glyphsmith.spec.SpecException
import java.io.IOException

/**
 * What every subcommand shares: the four failures that are the user's problem rather than a bug become one
 * sentence on stderr and exit code 2.
 *
 * A stack trace tells someone about this tool. A sentence tells them about their spec file, and every
 * message here is written to name the glyph, the key or the number that has to change.
 */
abstract class GlyphsmithCommand(name: String) : CliktCommand(name = name) {

    final override fun run() {
        try {
            execute()
        } catch (failure: SpecException) {
            fail(failure.message)
        } catch (failure: GlyphSolveException) {
            fail(failure.message)
        } catch (failure: PackMetaException) {
            fail(failure.message)
        } catch (failure: IllegalArgumentException) {
            fail(failure.message)
        } catch (failure: IllegalStateException) {
            fail(failure.message)
        } catch (failure: IOException) {
            fail(failure.message)
        }
    }

    protected abstract fun execute()

    protected fun fail(message: String?): Nothing {
        echo(message ?: "The command could not be carried out.", err = true)
        throw ProgramResult(USAGE_FAILED)
    }

    protected companion object {
        /** Kept distinct from 1 so a script can tell "you asked for the impossible" from "it broke". */
        const val USAGE_FAILED = 2
    }
}
