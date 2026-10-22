package io.knura.android

private const val DIGITS = "0123456789ABCDEF"

fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (b in this) {
        out.append(DIGITS[(b.toInt() shr 4) and 0xF]).append(DIGITS[b.toInt() and 0xF])
    }
    return out.toString()
}

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd number of hex digits" }
    return ByteArray(length / 2) { i -> substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}
