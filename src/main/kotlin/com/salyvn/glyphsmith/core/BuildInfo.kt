package com.salyvn.glyphsmith.core

import java.util.Properties

/** The version, read back from the resource the build stamps so `--version` cannot drift. */
object BuildInfo {
    val version: String by lazy {
        val properties = Properties()
        BuildInfo::class.java.getResourceAsStream("/glyphsmith-version.properties")?.use { stream ->
            properties.load(stream)
        }
        properties.getProperty("version") ?: "unknown"
    }
}
