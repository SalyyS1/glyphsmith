package com.salyvn.glyphsmith.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.glyphsmith.audit.FindingSeverity
import com.salyvn.glyphsmith.audit.PackAuditor

/**
 * Inspects any resource pack directory or `.zip` archive (built by glyphsmith, a server plugin, or by hand)
 * and checks every font file against vanilla client invariants.
 */
class AuditCommand : GlyphsmithCommand(name = "audit") {

    override fun help(context: Context) =
        "Audit a resource pack directory or zip for broken font providers, missing textures, and codepoint collisions."

    private val target by argument("pack", help = "Pack directory or .zip archive to inspect").path()

    private val json by option("--json", help = "Emit machine-readable JSON report to stdout").flag()

    private val strict by option("--strict", help = "Exit non-zero on warnings as well as errors").flag()

    override fun execute() {
        val report = PackAuditor.audit(target)
        if (json) {
            echo(report.toJsonString())
        } else {
            echo("== audit ${report.target}")
            echo("   fonts      ${report.fontCount}")
            echo("   providers  ${report.providerCount}")
            echo("   errors     ${report.errorCount}")
            echo("   warnings   ${report.warningCount}")
            if (report.findings.isNotEmpty()) {
                echo("")
                report.findings.forEach { finding ->
                    val tag = if (finding.severity == FindingSeverity.ERROR) "error" else "warning"
                    val loc = finding.providerIndex?.let { "${finding.fontId}#$it" } ?: finding.fontId
                    echo("   [$tag] ${finding.rule} ($loc): ${finding.message}")
                }
            }
        }

        val failed = report.errorCount > 0 || (strict && report.warningCount > 0)
        if (failed) {
            throw ProgramResult(USAGE_FAILED)
        }
    }
}
