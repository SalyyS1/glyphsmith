package com.salyvn.glyphsmith.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.glyphsmith.audit.FindingSeverity
import com.salyvn.glyphsmith.audit.PackAuditor
import com.salyvn.glyphsmith.report.SarifReport
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * Inspects one resource pack or an ordered stack of resource packs (directories or `.zip` archives) and
 * checks every font file, overlay directory, reference graph, and cross-pack codepoint allocation against
 * vanilla client invariants.
 */
class AuditCommand : GlyphsmithCommand(name = "audit") {

    override fun help(context: Context) =
        "Audit one or more resource packs (directory or zip) for broken font providers, reference cycles, overlay bugs, and cross-pack codepoint collisions."

    private val targets by argument(
        "pack",
        help = "One or more pack directories or .zip archives in stack load order (bottom to top)",
    ).path().multiple(required = true)

    private val json by option("--json", help = "Emit machine-readable JSON report to stdout").flag()

    private val format by option("--format", help = "Stdout output format: text, json, or sarif")
        .choice("text", "json", "sarif")

    private val sarifOut by option(
        "--sarif",
        help = "Write OASIS SARIF v2.1.0 report to this file path (for GitHub Code Scanning)",
    ).path()

    private val strict by option("--strict", help = "Exit non-zero on warnings as well as errors").flag()

    override fun execute() {
        val report = PackAuditor.auditStack(targets)

        sarifOut?.let { outPath ->
            outPath.parent?.let { Files.createDirectories(it) }
            Files.writeString(outPath, SarifReport.render(report) + "\n", StandardCharsets.UTF_8)
        }

        val effectiveFormat = when {
            format != null -> format!!
            json -> "json"
            else -> "text"
        }

        when (effectiveFormat) {
            "json" -> echo(report.toJsonString())
            "sarif" -> echo(SarifReport.render(report))
            else -> {
                echo("== audit ${report.target}")
                if (report.packCount > 1) {
                    echo("   packs      ${report.packCount}")
                }
                echo("   fonts      ${report.fontCount}")
                echo("   providers  ${report.providerCount}")
                echo("   errors     ${report.errorCount}")
                echo("   warnings   ${report.warningCount}")
                if (report.findings.isNotEmpty()) {
                    echo("")
                    report.findings.forEach { finding ->
                        val tag = if (finding.severity == FindingSeverity.ERROR) "error" else "warning"
                        val baseLoc = finding.providerIndex?.let { "${finding.fontId}#$it" } ?: finding.fontId
                        val loc = finding.pack?.let { "$it::$baseLoc" } ?: baseLoc
                        echo("   [$tag] ${finding.rule} ($loc): ${finding.message}")
                    }
                }
            }
        }

        val failed = report.errorCount > 0 || (strict && report.warningCount > 0)
        if (failed) {
            throw ProgramResult(USAGE_FAILED)
        }
    }
}
