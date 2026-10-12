package app.areada.data.private

import java.security.MessageDigest
import java.security.SecureRandom

internal const val PrivatePasswordMinimumLength = 4

object PrivateModePassword {
    private const val SaltByteCount = 16
    private const val HashIterations = 10_000
    private const val HexDigits = "0123456789abcdef"
    private val random = SecureRandom()

    fun isAcceptable(password: String): Boolean =
        password.trim().length >= PrivatePasswordMinimumLength

    fun newSalt(): String {
        val bytes = ByteArray(SaltByteCount)
        random.nextBytes(bytes)
        return bytes.toHexString()
    }

    fun hash(salt: String, password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var current = digest.digest("$salt:$password".toByteArray(Charsets.UTF_8))
        repeat(HashIterations - 1) {
            current = digest.digest(current)
        }
        return current.toHexString()
    }

    fun matches(salt: String, expectedHash: String, password: String): Boolean {
        if (expectedHash.isBlank()) {
            return false
        }
        return constantTimeEquals(hash(salt, password), expectedHash)
    }

    private fun constantTimeEquals(left: String, right: String): Boolean {
        if (left.length != right.length) {
            return false
        }
        var difference = 0
        for (index in left.indices) {
            difference = difference or (left[index].code xor right[index].code)
        }
        return difference == 0
    }

    private fun ByteArray.toHexString(): String {
        val builder = StringBuilder(size * 2)
        for (byte in this) {
            val value = byte.toInt() and 0xFF
            builder.append(HexDigits[value ushr 4])
            builder.append(HexDigits[value and 0x0F])
        }
        return builder.toString()
    }
}