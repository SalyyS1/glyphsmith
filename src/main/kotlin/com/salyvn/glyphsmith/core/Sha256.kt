package com.salyvn.glyphsmith.core

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Content hashes, so the lock file can tell "the spec changed" from "the artwork changed". */
object Sha256 {
    fun of(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = StringBuilder(digest.size * 2)
        digest.forEach { byte -> out.append("%02x".format(byte)) }
        return out.toString()
    }

    fun ofFile(file: Path): String = of(Files.readAllBytes(file))
}
