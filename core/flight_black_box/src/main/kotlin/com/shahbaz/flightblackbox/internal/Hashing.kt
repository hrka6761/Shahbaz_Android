package com.shahbaz.flightblackbox.internal

import java.security.MessageDigest

/**
 * Runs the String operation.
 */
internal fun String.sha256Short(): String =
    encodeToByteArray().sha256Short()

/**
 * Runs the ByteArray operation.
 */
internal fun ByteArray.sha256Short(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }
        .take(12)
