package com.shahbaz.flightblackbox.internal

import java.security.MessageDigest

internal fun String.sha256Short(): String =
    encodeToByteArray().sha256Short()

internal fun ByteArray.sha256Short(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }
        .take(12)
