package app.areada.data.private

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateModePasswordTest {
    @Test
    fun acceptsPasswordsAtLeastMinimumLength() {
        assertFalse(PrivateModePassword.isAcceptable(""))
        assertFalse(PrivateModePassword.isAcceptable("abc"))
        assertTrue(PrivateModePassword.isAcceptable("abcd"))
        assertTrue(PrivateModePassword.isAcceptable(" correct horse "))
    }

    @Test
    fun saltsAreUniqueAndHexEncoded() {
        val first = PrivateModePassword.newSalt()
        val second = PrivateModePassword.newSalt()

        assertEquals(32, first.length)
        assertEquals(32, second.length)
        assertNotEquals(first, second)
        assertTrue(first.all { character -> character in "0123456789abcdef" })
    }

    @Test
    fun hashIsStableForSameSaltAndPassword() {
        val salt = "0123456789abcdef"

        assertEquals(
            PrivateModePassword.hash(salt, "areada"),
            PrivateModePassword.hash(salt, "areada"),
        )
    }

    @Test
    fun hashChangesWithSaltAndPassword() {
        val salt = "0123456789abcdef"
        val otherSalt = "fedcba9876543210"

        assertNotEquals(PrivateModePassword.hash(salt, "areada"), PrivateModePassword.hash(otherSalt, "areada"))
        assertNotEquals(PrivateModePassword.hash(salt, "areada"), PrivateModePassword.hash(salt, "areadb"))
    }

    @Test
    fun matchesOnlyAcceptsTheOriginalPassword() {
        val salt = PrivateModePassword.newSalt()
        val hash = PrivateModePassword.hash(salt, "correct horse")

        assertTrue(PrivateModePassword.matches(salt, hash, "correct horse"))
        assertFalse(PrivateModePassword.matches(salt, hash, "Correct horse"))
        assertFalse(PrivateModePassword.matches(salt, hash, "correct horse "))
        assertFalse(PrivateModePassword.matches(salt, hash, ""))
    }

    @Test
    fun matchesRejectsBlankStoredHash() {
        assertFalse(PrivateModePassword.matches("salt", "", "areada"))
    }
}