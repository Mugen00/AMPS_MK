package dev.amps.app.util

import java.security.MessageDigest

/** Lowercase hex SHA-256 of a byte array. */
fun ByteArray.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(this)
    return buildString(digest.size * 2) {
        digest.forEach { byte -> append("%02x".format(byte)) }
    }
}

/** Lowercase hex of any digest output, in the shape the music clients expect. */
fun ByteArray.hex(): String = buildString(size * 2) {
    this@hex.forEach { byte -> append("%02x".format(byte)) }
}
